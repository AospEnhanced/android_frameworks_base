/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.SELinuxUtil;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.TimingsTraceLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;

import dalvik.system.VMRuntime;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Prepares app data for users
 */
final class AppDataHelper {
    private static final boolean DEBUG_APP_DATA = false;

    private final PackageManagerService mPm;
    private final Installer mInstaller;
    private final ArtManagerService mArtManagerService;

    // TODO(b/198166813): remove PMS dependency
    AppDataHelper(PackageManagerService pm) {
        mPm = pm;
        mInstaller = mPm.mInjector.getInstaller();
        mArtManagerService = mPm.mInjector.getArtManagerService();
    }

    /**
     * Prepare app data for the given app just after it was installed or
     * upgraded. This method carefully only touches users that it's installed
     * for, and it forces a restorecon to handle any seinfo changes.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch, it
     * will try recovering system apps by wiping data; third-party app data is
     * left intact.
     * <p>
     * <em>Note: To avoid a deadlock, do not call this method with {@code mLock} lock held</em>
     */
    public void prepareAppDataAfterInstallLIF(AndroidPackage pkg) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
            mPm.mSettings.writeKernelMappingLPr(ps);
        }

        Installer.Batch batch = new Installer.Batch();
        UserManagerInternal umInternal = mPm.mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mPm.mInjector.getLocalService(
                StorageManagerInternal.class);
        for (UserInfo user : umInternal.getUsers(false /*excludeDying*/)) {
            final int flags;
            if (StorageManager.isUserKeyUnlocked(user.id)
                    && smInternal.isCeStoragePrepared(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            } else if (umInternal.isUserRunning(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE;
            } else {
                continue;
            }

            // TODO@ashfall check ScanResult.mNeedsNewAppId, and if true instead
            // of creating app data, migrate / change ownership of existing
            // data.

            if (ps.getInstalled(user.id)) {
                // TODO: when user data is locked, mark that we're still dirty
                prepareAppData(batch, pkg, user.id, flags).thenRun(() -> {
                    // Note: this code block is executed with the Installer lock
                    // already held, since it's invoked as a side-effect of
                    // executeBatchLI()
                    if (umInternal.isUserUnlockingOrUnlocked(user.id)) {
                        // Prepare app data on external storage; currently this is used to
                        // setup any OBB dirs that were created by the installer correctly.
                        int uid = UserHandle.getUid(user.id, UserHandle.getAppId(pkg.getUid()));
                        smInternal.prepareAppDataAfterInstall(pkg.getPackageName(), uid);
                    }
                });
            }
        }
        executeBatchLI(batch);
    }

    private void executeBatchLI(@NonNull Installer.Batch batch) {
        try {
            batch.execute(mInstaller);
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, "Failed to execute pending operations", e);
        }
    }

    /**
     * Prepare app data for the given app.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch, this
     * will try recovering system apps by wiping data; third-party app data is
     * left intact.
     */
    private @NonNull CompletableFuture<?> prepareAppData(@NonNull Installer.Batch batch,
            @Nullable AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return CompletableFuture.completedFuture(null);
        }
        return prepareAppDataLeaf(batch, pkg, userId, flags);
    }

    private void prepareAppDataAndMigrate(@NonNull Installer.Batch batch,
            @NonNull AndroidPackage pkg, int userId, int flags, boolean maybeMigrateAppData) {
        prepareAppData(batch, pkg, userId, flags).thenRun(() -> {
            // Note: this code block is executed with the Installer lock
            // already held, since it's invoked as a side-effect of
            // executeBatchLI()
            if (maybeMigrateAppData && maybeMigrateAppDataLIF(pkg, userId)) {
                // We may have just shuffled around app data directories, so
                // prepare them one more time
                final Installer.Batch batchInner = new Installer.Batch();
                prepareAppData(batchInner, pkg, userId, flags);
                executeBatchLI(batchInner);
            }
        });
    }

    private @NonNull CompletableFuture<?> prepareAppDataLeaf(@NonNull Installer.Batch batch,
            @NonNull AndroidPackage pkg, int userId, int flags) {
        if (DEBUG_APP_DATA) {
            Slog.v(TAG, "prepareAppData for " + pkg.getPackageName() + " u" + userId + " 0x"
                    + Integer.toHexString(flags));
        }

        final PackageSetting ps;
        final String seInfoUser;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
            seInfoUser = SELinuxUtil.getSeinfoUser(ps.readUserState(userId));
        }
        final String volumeUuid = pkg.getVolumeUuid();
        final String packageName = pkg.getPackageName();

        final int appId = UserHandle.getAppId(pkg.getUid());

        String pkgSeInfo = AndroidPackageUtils.getSeInfo(pkg, ps);

        Preconditions.checkNotNull(pkgSeInfo);

        final String seInfo = pkgSeInfo + seInfoUser;
        final int targetSdkVersion = pkg.getTargetSdkVersion();

        return batch.createAppData(volumeUuid, packageName, userId, flags, appId, seInfo,
                targetSdkVersion).whenComplete((ceDataInode, e) -> {
                    // Note: this code block is executed with the Installer lock
                    // already held, since it's invoked as a side-effect of
                    // executeBatchLI()
                    if (e != null) {
                        logCriticalInfo(Log.WARN, "Failed to create app data for " + packageName
                                + ", but trying to recover: " + e);
                        destroyAppDataLeafLIF(pkg, userId, flags);
                        try {
                            ceDataInode = mInstaller.createAppData(volumeUuid, packageName, userId,
                                    flags, appId, seInfo, pkg.getTargetSdkVersion());
                            logCriticalInfo(Log.DEBUG, "Recovery succeeded!");
                        } catch (Installer.InstallerException e2) {
                            logCriticalInfo(Log.DEBUG, "Recovery failed!");
                        }
                    }

                    // Prepare the application profiles only for upgrades and
                    // first boot (so that we don't repeat the same operation at
                    // each boot).
                    //
                    // We only have to cover the upgrade and first boot here
                    // because for app installs we prepare the profiles before
                    // invoking dexopt (in installPackageLI).
                    //
                    // We also have to cover non system users because we do not
                    // call the usual install package methods for them.
                    //
                    // NOTE: in order to speed up first boot time we only create
                    // the current profile and do not update the content of the
                    // reference profile. A system image should already be
                    // configured with the right profile keys and the profiles
                    // for the speed-profile prebuilds should already be copied.
                    // That's done in #performDexOptUpgrade.
                    //
                    // TODO(calin, mathieuc): We should use .dm files for
                    // prebuilds profiles instead of manually copying them in
                    // #performDexOptUpgrade. When we do that we should have a
                    // more granular check here and only update the existing
                    // profiles.
                    if (mPm.mIsUpgrade || mPm.mFirstBoot || (userId != UserHandle.USER_SYSTEM)) {
                        mArtManagerService.prepareAppProfiles(pkg, userId,
                                /* updateReferenceProfileContent= */ false);
                    }

                    if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 && ceDataInode != -1) {
                        // TODO: mark this structure as dirty so we persist it!
                        synchronized (mPm.mLock) {
                            if (ps != null) {
                                ps.setCeDataInode(ceDataInode, userId);
                            }
                        }
                    }

                    prepareAppDataContentsLeafLIF(pkg, ps, userId, flags);
                });
    }

    public void prepareAppDataContentsLIF(AndroidPackage pkg, @Nullable PackageSetting pkgSetting,
            int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        prepareAppDataContentsLeafLIF(pkg, pkgSetting, userId, flags);
    }

    private void prepareAppDataContentsLeafLIF(AndroidPackage pkg,
            @Nullable PackageSetting pkgSetting, int userId, int flags) {
        final String volumeUuid = pkg.getVolumeUuid();
        final String packageName = pkg.getPackageName();

        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            // Create a native library symlink only if we have native libraries
            // and if the native libraries are 32 bit libraries. We do not provide
            // this symlink for 64 bit libraries.
            String primaryCpuAbi = AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting);
            if (primaryCpuAbi != null && !VMRuntime.is64BitAbi(primaryCpuAbi)) {
                final String nativeLibPath = pkg.getNativeLibraryDir();
                try {
                    mInstaller.linkNativeLibraryDirectory(volumeUuid, packageName,
                            nativeLibPath, userId);
                } catch (Installer.InstallerException e) {
                    Slog.e(TAG, "Failed to link native for " + packageName + ": " + e);
                }
            }
        }
    }

    /**
     * For system apps on non-FBE devices, this method migrates any existing
     * CE/DE data to match the {@code defaultToDeviceProtectedStorage} flag
     * requested by the app.
     */
    private boolean maybeMigrateAppDataLIF(AndroidPackage pkg, int userId) {
        if (pkg.isSystem() && !StorageManager.isFileEncryptedNativeOrEmulated()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            final int storageTarget = pkg.isDefaultToDeviceProtectedStorage()
                    ? StorageManager.FLAG_STORAGE_DE : StorageManager.FLAG_STORAGE_CE;
            try {
                mInstaller.migrateAppData(pkg.getVolumeUuid(), pkg.getPackageName(), userId,
                        storageTarget);
            } catch (Installer.InstallerException e) {
                logCriticalInfo(Log.WARN,
                        "Failed to migrate " + pkg.getPackageName() + ": " + e.getMessage());
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Reconcile all app data for the given user.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps on all mounted volumes.
     */
    @NonNull
    public void reconcileAppsData(int userId, int flags, boolean migrateAppsData) {
        final StorageManager storage = mPm.mInjector.getSystemService(StorageManager.class);
        for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
            final String volumeUuid = vol.getFsUuid();
            synchronized (mPm.mInstallLock) {
                reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppsData);
            }
        }
    }

    @GuardedBy("mPm.mInstallLock")
    void reconcileAppsDataLI(String volumeUuid, int userId, int flags,
            boolean migrateAppData) {
        reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppData, false /* onlyCoreApps */);
    }

    /**
     * Reconcile all app data on given mounted volume.
     * <p>
     * Destroys app data that isn't expected, either due to uninstallation or
     * reinstallation on another volume.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps.
     *
     * @return list of skipped non-core packages (if {@code onlyCoreApps} is true)
     */
    @GuardedBy("mPm.mInstallLock")
    private List<String> reconcileAppsDataLI(String volumeUuid, int userId, int flags,
            boolean migrateAppData, boolean onlyCoreApps) {
        Slog.v(TAG, "reconcileAppsData for " + volumeUuid + " u" + userId + " 0x"
                + Integer.toHexString(flags) + " migrateAppData=" + migrateAppData);
        List<String> result = onlyCoreApps ? new ArrayList<>() : null;

        final File ceDir = Environment.getDataUserCeDirectory(volumeUuid, userId);
        final File deDir = Environment.getDataUserDeDirectory(volumeUuid, userId);

        // First look for stale data that doesn't belong, and check if things
        // have changed since we did our last restorecon
        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            if (StorageManager.isFileEncryptedNativeOrEmulated()
                    && !StorageManager.isUserKeyUnlocked(userId)) {
                throw new RuntimeException(
                        "Yikes, someone asked us to reconcile CE storage while " + userId
                                + " was still locked; this would have caused massive data loss!");
            }

            final File[] files = FileUtils.listFilesOrEmpty(ceDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_CE, 0);
                    } catch (Installer.InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }
        if ((flags & StorageManager.FLAG_STORAGE_DE) != 0) {
            final File[] files = FileUtils.listFilesOrEmpty(deDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_DE, 0);
                    } catch (Installer.InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }

        // Ensure that data directories are ready to roll for all packages
        // installed for this volume and user
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "prepareAppDataAndMigrate");
        Installer.Batch batch = new Installer.Batch();
        final List<PackageSetting> packages;
        synchronized (mPm.mLock) {
            packages = mPm.mSettings.getVolumePackagesLPr(volumeUuid);
        }
        int preparedCount = 0;
        for (PackageSetting ps : packages) {
            final String packageName = ps.getPackageName();
            if (ps.getPkg() == null) {
                Slog.w(TAG, "Odd, missing scanned package " + packageName);
                // TODO: might be due to legacy ASEC apps; we should circle back
                // and reconcile again once they're scanned
                continue;
            }
            // Skip non-core apps if requested
            if (onlyCoreApps && !ps.getPkg().isCoreApp()) {
                result.add(packageName);
                continue;
            }

            if (ps.getInstalled(userId)) {
                prepareAppDataAndMigrate(batch, ps.getPkg(), userId, flags, migrateAppData);
                preparedCount++;
            }
        }
        executeBatchLI(batch);
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

        Slog.v(TAG, "reconcileAppsData finished " + preparedCount + " packages");
        return result;
    }

    private void assertPackageKnownAndInstalled(String volumeUuid, String packageName, int userId)
            throws PackageManagerException {
        synchronized (mPm.mLock) {
            // Normalize package name to handle renamed packages
            packageName = normalizePackageNameLPr(packageName);

            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps == null) {
                throw new PackageManagerException("Package " + packageName + " is unknown");
            } else if (!TextUtils.equals(volumeUuid, ps.getVolumeUuid())) {
                throw new PackageManagerException(
                        "Package " + packageName + " found on unknown volume " + volumeUuid
                                + "; expected volume " + ps.getVolumeUuid());
            } else if (!ps.getInstalled(userId)) {
                throw new PackageManagerException(
                        "Package " + packageName + " not installed for user " + userId);
            }
        }
    }

    @GuardedBy("mPm.mLock")
    private String normalizePackageNameLPr(String packageName) {
        String normalizedPackageName = mPm.mSettings.getRenamedPackageLPr(packageName);
        return normalizedPackageName != null ? normalizedPackageName : packageName;
    }

    /**
     * Prepare storage for system user really early during boot,
     * since core system apps like SettingsProvider and SystemUI
     * can't wait for user to start
     */
    public Future<?> fixAppsDataOnBoot() {
        final int storageFlags;
        if (StorageManager.isFileEncryptedNativeOrEmulated()) {
            storageFlags = StorageManager.FLAG_STORAGE_DE;
        } else {
            storageFlags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
        }
        List<String> deferPackages = reconcileAppsDataLI(StorageManager.UUID_PRIVATE_INTERNAL,
                UserHandle.USER_SYSTEM, storageFlags, true /* migrateAppData */,
                true /* onlyCoreApps */);
        Future<?> prepareAppDataFuture = SystemServerInitThreadPool.submit(() -> {
            TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTimingAsync",
                    Trace.TRACE_TAG_PACKAGE_MANAGER);
            traceLog.traceBegin("AppDataFixup");
            try {
                mInstaller.fixupAppData(StorageManager.UUID_PRIVATE_INTERNAL,
                        StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Trouble fixing GIDs", e);
            }
            traceLog.traceEnd();

            traceLog.traceBegin("AppDataPrepare");
            if (deferPackages == null || deferPackages.isEmpty()) {
                return;
            }
            int count = 0;
            final Installer.Batch batch = new Installer.Batch();
            for (String pkgName : deferPackages) {
                AndroidPackage pkg = null;
                synchronized (mPm.mLock) {
                    PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
                    if (ps != null && ps.getInstalled(UserHandle.USER_SYSTEM)) {
                        pkg = ps.getPkg();
                    }
                }
                if (pkg != null) {
                    prepareAppDataAndMigrate(batch, pkg, UserHandle.USER_SYSTEM, storageFlags,
                            true /* maybeMigrateAppData */);
                    count++;
                }
            }
            synchronized (mPm.mInstallLock) {
                executeBatchLI(batch);
            }
            traceLog.traceEnd();
            Slog.i(TAG, "Deferred reconcileAppsData finished " + count + " packages");
        }, "prepareAppData");
        return prepareAppDataFuture;
    }

    void clearAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            return;
        }
        clearAppDataLeafLIF(pkg, userId, flags);

        if ((flags & Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES) == 0) {
            clearAppProfilesLIF(pkg);
        }
    }

    private void clearAppDataLeafLIF(AndroidPackage pkg, int userId, int flags) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
        }
        for (int realUserId : mPm.resolveUserIds(userId)) {
            final long ceDataInode = (ps != null) ? ps.getCeDataInode(realUserId) : 0;
            try {
                mInstaller.clearAppData(pkg.getVolumeUuid(), pkg.getPackageName(), realUserId,
                        flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
        }
    }

    void clearAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        mArtManagerService.clearAppProfiles(pkg);
    }

    public void destroyAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppDataLeafLIF(pkg, userId, flags);
    }

    public void destroyAppDataLeafLIF(AndroidPackage pkg, int userId, int flags) {
        final PackageSetting ps;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());
        }
        for (int realUserId : mPm.resolveUserIds(userId)) {
            final long ceDataInode = (ps != null) ? ps.getCeDataInode(realUserId) : 0;
            try {
                mInstaller.destroyAppData(pkg.getVolumeUuid(), pkg.getPackageName(), realUserId,
                        flags, ceDataInode);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
            mPm.getDexManager().notifyPackageDataDestroyed(pkg.getPackageName(), userId);
        }
    }

    public void destroyAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppProfilesLeafLIF(pkg);
    }

    private void destroyAppProfilesLeafLIF(AndroidPackage pkg) {
        try {
            mInstaller.destroyAppProfiles(pkg.getPackageName());
        } catch (Installer.InstallerException e) {
            Slog.w(TAG, String.valueOf(e));
        }
    }
}
