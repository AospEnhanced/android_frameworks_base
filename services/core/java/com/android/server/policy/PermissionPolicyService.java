/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.policy;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.AndroidPackage;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManagerInternal;
import android.permission.PermissionControllerManager;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseLongArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.IntPair;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.policy.PermissionPolicyInternal.OnInitializedCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * This is a permission policy that governs over all permission mechanism
 * such as permissions, app ops, etc. For example, the policy ensures that
 * permission state and app ops is synchronized for cases where there is a
 * dependency between permission state (permissions or permission flags)
 * and app ops - and vise versa.
 */
public final class PermissionPolicyService extends SystemService {
    private static final String LOG_TAG = PermissionPolicyService.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    /** Whether the user is started but not yet stopped */
    @GuardedBy("mLock")
    private final SparseBooleanArray mIsStarted = new SparseBooleanArray();

    /** Callbacks for when a user is initialized */
    @GuardedBy("mLock")
    private OnInitializedCallback mOnInitializedCallback;

    /**
     * Whether an async {@link #synchronizePackagePermissionsAndAppOpsForUser} is currently
     * scheduled for a package/user.
     */
    @GuardedBy("mLock")
    private final ArraySet<Pair<String, Integer>> mIsPackageSyncsScheduled = new ArraySet<>();

    public PermissionPolicyService(@NonNull Context context) {
        super(context);

        LocalServices.addService(PermissionPolicyInternal.class, new Internal());
    }

    @Override
    public void onStart() {
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PermissionManagerServiceInternal permManagerInternal = LocalServices.getService(
                PermissionManagerServiceInternal.class);
        final IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        packageManagerInternal.getPackageList(new PackageListObserver() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                onPackageChanged(packageName, uid);
            }

            @Override
            public void onPackageChanged(String packageName, int uid) {
                final int userId = UserHandle.getUserId(uid);

                if (isStarted(userId)) {
                    synchronizePackagePermissionsAndAppOpsForUser(packageName, userId);
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                /* do nothing */
            }
        });

        permManagerInternal.addOnRuntimePermissionStateChangedListener(
                this::synchronizePackagePermissionsAndAppOpsAsyncForUser);

        IAppOpsCallback appOpsListener = new IAppOpsCallback.Stub() {
            public void opChanged(int op, int uid, String packageName) {
                synchronizePackagePermissionsAndAppOpsAsyncForUser(packageName,
                        UserHandle.getUserId(uid));
            }
        };

        final ArrayList<PermissionInfo> dangerousPerms =
                permManagerInternal.getAllPermissionWithProtection(
                        PermissionInfo.PROTECTION_DANGEROUS);

        try {
            int numDangerousPerms = dangerousPerms.size();
            for (int i = 0; i < numDangerousPerms; i++) {
                PermissionInfo perm = dangerousPerms.get(i);

                if (perm.isRuntime()) {
                    appOpsService.startWatchingMode(getSwitchOp(perm.name), null, appOpsListener);
                }
                if (perm.isSoftRestricted()) {
                    SoftRestrictedPermissionPolicy policy =
                            SoftRestrictedPermissionPolicy.forPermission(null, null, null,
                                    perm.name);
                    int extraAppOp = policy.getExtraAppOpCode();
                    if (extraAppOp != OP_NONE) {
                        appOpsService.startWatchingMode(extraAppOp, null, appOpsListener);
                    }
                }
            }
        } catch (RemoteException doesNotHappen) {
            Slog.wtf(LOG_TAG, "Cannot set up app-ops listener");
        }
    }

    /**
     * Get op that controls the access related to the permission.
     *
     * <p>Usually the permission-op relationship is 1:1 but some permissions (e.g. fine location)
     * {@link AppOpsManager#sOpToSwitch share an op} to control the access.
     *
     * @param permission The permission
     *
     * @return The op that controls the access of the permission
     */
    private static int getSwitchOp(@NonNull String permission) {
        int op = AppOpsManager.permissionToOpCode(permission);
        if (op == OP_NONE) {
            return OP_NONE;
        }

        return AppOpsManager.opToSwitch(op);
    }

    private void synchronizePackagePermissionsAndAppOpsAsyncForUser(@NonNull String packageName,
            @UserIdInt int changedUserId) {
        if (isStarted(changedUserId)) {
            synchronized (mLock) {
                if (mIsPackageSyncsScheduled.add(new Pair<>(packageName, changedUserId))) {
                    FgThread.getHandler().sendMessage(PooledLambda.obtainMessage(
                            PermissionPolicyService
                                    ::synchronizePackagePermissionsAndAppOpsForUser,
                            this, packageName, changedUserId));
                } else {
                    if (DEBUG) {
                        Slog.v(LOG_TAG, "sync for " + packageName + "/" + changedUserId
                                + " already scheduled");
                    }
                }
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (DEBUG) Slog.i(LOG_TAG, "onBootPhase(" + phase + ")");

        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            final UserManagerInternal um = LocalServices.getService(UserManagerInternal.class);

            // For some users we might not receive a onStartUser, hence force one here
            for (int userId : um.getUserIds()) {
                if (um.isUserRunning(userId)) {
                    onStartUser(userId);
                }
            }
        }
    }

    /**
     * @return Whether the user is started but not yet stopped
     */
    private boolean isStarted(@UserIdInt int userId) {
        synchronized (mLock) {
            return mIsStarted.get(userId);
        }
    }

    @Override
    public void onStartUser(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "onStartUser(" + userId + ")");

        if (isStarted(userId)) {
            return;
        }

        grantOrUpgradeDefaultRuntimePermissionsIfNeeded(userId);

        final OnInitializedCallback callback;

        synchronized (mLock) {
            mIsStarted.put(userId, true);
            callback = mOnInitializedCallback;
        }

        // Force synchronization as permissions might have changed
        synchronizePermissionsAndAppOpsForUser(userId);

        // Tell observers we are initialized for this user.
        if (callback != null) {
            callback.onInitialized(userId);
        }
    }

    @Override
    public void onStopUser(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "onStopUser(" + userId + ")");

        synchronized (mLock) {
            mIsStarted.delete(userId);
        }
    }

    private void grantOrUpgradeDefaultRuntimePermissionsIfNeeded(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "grantOrUpgradeDefaultPermsIfNeeded(" + userId + ")");

        final PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        final PermissionManagerServiceInternal permissionManagerInternal =
                LocalServices.getService(PermissionManagerServiceInternal.class);
        if (permissionManagerInternal.wereDefaultPermissionsGrantedSinceBoot(userId)) {
            if (DEBUG) Slog.i(LOG_TAG, "defaultPermsWereGrantedSinceBoot(" + userId + ")");

            // Now call into the permission controller to apply policy around permissions
            final CountDownLatch latch = new CountDownLatch(1);

            // We need to create a local manager that does not schedule work on the main
            // there as we are on the main thread and want to block until the work is
            // completed or we time out.
            final PermissionControllerManager permissionControllerManager =
                    new PermissionControllerManager(
                            getUserContext(getContext(), UserHandle.of(userId)),
                            FgThread.getHandler());
            permissionControllerManager.grantOrUpgradeDefaultRuntimePermissions(
                    FgThread.getExecutor(),
                    (Boolean success) -> {
                        if (!success) {
                            // We are in an undefined state now, let us crash and have
                            // rescue party suggest a wipe to recover to a good one.
                            final String message = "Error granting/upgrading runtime permissions";
                            Slog.wtf(LOG_TAG, message);
                            throw new IllegalStateException(message);
                        }
                        latch.countDown();
                    }
            );
            try {
                latch.await();
            } catch (InterruptedException e) {
                /* ignore */
            }

            permissionControllerManager.updateUserSensitive();

            packageManagerInternal.setRuntimePermissionsFingerPrint(Build.FINGERPRINT, userId);
        }
    }

    private static @Nullable Context getUserContext(@NonNull Context context,
            @Nullable UserHandle user) {
        if (context.getUser().equals(user)) {
            return context;
        } else {
            try {
                return context.createPackageContextAsUser(context.getPackageName(), 0, user);
            } catch (NameNotFoundException e) {
                Slog.e(LOG_TAG, "Cannot create context for user " + user, e);
                return null;
            }
        }
    }

    /**
     * Synchronize a single package.
     */
    private void synchronizePackagePermissionsAndAppOpsForUser(@NonNull String packageName,
            @UserIdInt int userId) {
        synchronized (mLock) {
            mIsPackageSyncsScheduled.remove(new Pair<>(packageName, userId));
        }

        if (DEBUG) {
            Slog.v(LOG_TAG,
                    "synchronizePackagePermissionsAndAppOpsForUser(" + packageName + ", "
                            + userId + ")");
        }

        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PackageInfo pkg = packageManagerInternal.getPackageInfo(packageName, 0,
                Process.SYSTEM_UID, userId);
        if (pkg == null) {
            return;
        }
        final PermissionToOpSynchroniser synchroniser = new PermissionToOpSynchroniser(
                getUserContext(getContext(), UserHandle.of(userId)));
        synchroniser.addPackage(pkg.packageName);
        final String[] sharedPkgNames = packageManagerInternal.getPackagesForSharedUserId(
                pkg.sharedUserId, userId);
        if (sharedPkgNames != null) {
            for (String sharedPkgName : sharedPkgNames) {
                final AndroidPackage sharedPkg = packageManagerInternal
                        .getPackage(sharedPkgName);
                if (sharedPkg != null) {
                    synchroniser.addPackage(sharedPkg.getPackageName());
                }
            }
        }
        synchroniser.syncPackages();
    }

    /**
     * Synchronize all packages
     */
    private void synchronizePermissionsAndAppOpsForUser(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "synchronizePermissionsAndAppOpsForUser(" + userId + ")");

        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PermissionToOpSynchroniser synchronizer = new PermissionToOpSynchroniser(
                getUserContext(getContext(), UserHandle.of(userId)));
        packageManagerInternal.forEachPackage(
                (pkg) -> synchronizer.addPackage(pkg.getPackageName()));
        synchronizer.syncPackages();
    }

    /**
     * Synchronizes permission to app ops. You *must* always sync all packages
     * in a shared UID at the same time to ensure proper synchronization.
     */
    private static class PermissionToOpSynchroniser {
        private final @NonNull Context mContext;
        private final @NonNull PackageManager mPackageManager;
        private final @NonNull AppOpsManager mAppOpsManager;

        private final @NonNull ArrayMap<String, PermissionInfo> mRuntimePermissionInfos;

        /**
         * All ops that need to be flipped to allow.
         *
         * @see #syncPackages
         */
        private final @NonNull ArrayList<OpToChange> mOpsToAllow = new ArrayList<>();

        /**
         * All ops that need to be flipped to ignore.
         *
         * @see #syncPackages
         */
        private final @NonNull ArrayList<OpToChange> mOpsToIgnore = new ArrayList<>();

        /**
         * All ops that need to be flipped to ignore if not allowed.
         *
         * Currently, only used by soft restricted permissions logic.
         *
         * @see #syncPackages
         */
        private final @NonNull ArrayList<OpToChange> mOpsToIgnoreIfNotAllowed = new ArrayList<>();

        /**
         * All ops that need to be flipped to foreground.
         *
         * Currently, only used by the foreground/background permissions logic.
         *
         * @see #syncPackages
         */
        private final @NonNull ArrayList<OpToChange> mOpsToForeground = new ArrayList<>();

        PermissionToOpSynchroniser(@NonNull Context context) {
            mContext = context;
            mPackageManager = context.getPackageManager();
            mAppOpsManager = context.getSystemService(AppOpsManager.class);

            mRuntimePermissionInfos = new ArrayMap<>();
            PermissionManagerServiceInternal permissionManagerInternal = LocalServices.getService(
                    PermissionManagerServiceInternal.class);
            List<PermissionInfo> permissionInfos =
                    permissionManagerInternal.getAllPermissionWithProtection(
                            PermissionInfo.PROTECTION_DANGEROUS);
            int permissionInfosSize = permissionInfos.size();
            for (int i = 0; i < permissionInfosSize; i++) {
                PermissionInfo permissionInfo = permissionInfos.get(i);
                mRuntimePermissionInfos.put(permissionInfo.name, permissionInfo);
            }
        }

        /**
         * Set app ops that were added in {@link #addPackage}.
         *
         * <p>This processes ops previously added by {@link #addAppOps(PackageInfo, String)}
         */
        private void syncPackages() {
            // Remember which ops were already set. This makes sure that we always set the most
            // permissive mode if two OpChanges are scheduled. This can e.g. happen if two
            // permissions change the same op. See {@link #getSwitchOp}.
            LongSparseLongArray alreadySetAppOps = new LongSparseLongArray();

            final int allowCount = mOpsToAllow.size();
            for (int i = 0; i < allowCount; i++) {
                final OpToChange op = mOpsToAllow.get(i);

                setUidModeAllowed(op.code, op.uid, op.packageName);
                alreadySetAppOps.put(IntPair.of(op.uid, op.code), 1);
            }

            final int foregroundCount = mOpsToForeground.size();
            for (int i = 0; i < foregroundCount; i++) {
                final OpToChange op = mOpsToForeground.get(i);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op.uid, op.code)) >= 0) {
                    continue;
                }

                setUidModeForeground(op.code, op.uid, op.packageName);
                alreadySetAppOps.put(IntPair.of(op.uid, op.code), 1);
            }

            final int ignoreCount = mOpsToIgnore.size();
            for (int i = 0; i < ignoreCount; i++) {
                final OpToChange op = mOpsToIgnore.get(i);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op.uid, op.code)) >= 0) {
                    continue;
                }

                setUidModeIgnored(op.code, op.uid, op.packageName);
                alreadySetAppOps.put(IntPair.of(op.uid, op.code), 1);
            }

            final int ignoreIfNotAllowedCount = mOpsToIgnoreIfNotAllowed.size();
            for (int i = 0; i < ignoreIfNotAllowedCount; i++) {
                final OpToChange op = mOpsToIgnoreIfNotAllowed.get(i);
                if (alreadySetAppOps.indexOfKey(IntPair.of(op.uid, op.code)) >= 0) {
                    continue;
                }

                boolean wasSet = setUidModeIgnoredIfNotAllowed(op.code, op.uid, op.packageName);
                if (wasSet) {
                    alreadySetAppOps.put(IntPair.of(op.uid, op.code), 1);
                }
            }
        }

        /**
         * Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         */
        private void addAppOps(@NonNull PackageInfo packageInfo, @NonNull String permissionName) {
            PermissionInfo permissionInfo = mRuntimePermissionInfos.get(permissionName);
            if (permissionInfo == null) {
                return;
            }
            addPermissionAppOp(packageInfo, permissionInfo);
            addExtraAppOp(packageInfo, permissionInfo);
        }

        private void addPermissionAppOp(@NonNull PackageInfo packageInfo,
                @NonNull PermissionInfo permissionInfo) {
            if (!permissionInfo.isRuntime()) {
                return;
            }

            String permissionName = permissionInfo.name;
            String packageName = packageInfo.packageName;
            int permissionFlags = mPackageManager.getPermissionFlags(permissionName,
                    packageName, mContext.getUser());
            boolean isReviewRequired = (permissionFlags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0;
            if (isReviewRequired) {
                return;
            }

            // TODO: COARSE_LOCATION and FINE_LOCATION shares the same app op. We are solving this
            //  with switch op but once we start syncing single permission this won't work.
            int appOpCode = getSwitchOp(permissionName);
            if (appOpCode == OP_NONE) {
                // Note that background permissions don't have an associated app op.
                return;
            }

            int appOpMode;
            boolean shouldGrantAppOp = shouldGrantAppOp(packageInfo, permissionInfo);
            if (shouldGrantAppOp) {
                if (permissionInfo.backgroundPermission != null) {
                    PermissionInfo backgroundPermissionInfo = mRuntimePermissionInfos.get(
                            permissionInfo.backgroundPermission);
                    boolean shouldGrantBackgroundAppOp = backgroundPermissionInfo != null
                            && shouldGrantAppOp(packageInfo, backgroundPermissionInfo);
                    appOpMode = shouldGrantBackgroundAppOp ? MODE_ALLOWED : MODE_FOREGROUND;
                } else {
                    appOpMode = MODE_ALLOWED;
                }
            } else {
                appOpMode = MODE_IGNORED;
            }

            int uid = packageInfo.applicationInfo.uid;
            OpToChange opToChange = new OpToChange(uid, packageName, appOpCode);
            switch (appOpMode) {
                case MODE_ALLOWED:
                    mOpsToAllow.add(opToChange);
                    break;
                case MODE_FOREGROUND:
                    mOpsToForeground.add(opToChange);
                    break;
                case MODE_IGNORED:
                    mOpsToIgnore.add(opToChange);
                    break;
            }
        }

        private boolean shouldGrantAppOp(@NonNull PackageInfo packageInfo,
                @NonNull PermissionInfo permissionInfo) {
            String permissionName = permissionInfo.name;
            String packageName = packageInfo.packageName;
            boolean isGranted = mPackageManager.checkPermission(permissionName, packageName)
                    == PackageManager.PERMISSION_GRANTED;
            if (!isGranted) {
                return false;
            }

            int permissionFlags = mPackageManager.getPermissionFlags(permissionName, packageName,
                    mContext.getUser());
            boolean isRevokedCompat = (permissionFlags & FLAG_PERMISSION_REVOKED_COMPAT)
                    == FLAG_PERMISSION_REVOKED_COMPAT;
            if (isRevokedCompat) {
                return false;
            }

            if (permissionInfo.isHardRestricted()) {
                boolean shouldApplyRestriction =
                        (permissionFlags & FLAG_PERMISSION_APPLY_RESTRICTION)
                                == FLAG_PERMISSION_APPLY_RESTRICTION;
                return !shouldApplyRestriction;
            } else if (permissionInfo.isSoftRestricted()) {
                SoftRestrictedPermissionPolicy policy =
                        SoftRestrictedPermissionPolicy.forPermission(mContext,
                                packageInfo.applicationInfo, mContext.getUser(), permissionName);
                return policy.mayGrantPermission();
            } else {
                return true;
            }
        }

        private void addExtraAppOp(@NonNull PackageInfo packageInfo,
                @NonNull PermissionInfo permissionInfo) {
            if (!permissionInfo.isSoftRestricted()) {
                return;
            }

            String permissionName = permissionInfo.name;
            SoftRestrictedPermissionPolicy policy =
                    SoftRestrictedPermissionPolicy.forPermission(mContext,
                            packageInfo.applicationInfo, mContext.getUser(), permissionName);
            int extraOpCode = policy.getExtraAppOpCode();
            if (extraOpCode == OP_NONE) {
                return;
            }

            int uid = packageInfo.applicationInfo.uid;
            String packageName = packageInfo.packageName;
            OpToChange extraOpToChange = new OpToChange(uid, packageName, extraOpCode);
            if (policy.mayAllowExtraAppOp()) {
                mOpsToAllow.add(extraOpToChange);
            } else {
                if (policy.mayDenyExtraAppOpIfGranted()) {
                    mOpsToIgnore.add(extraOpToChange);
                } else {
                    mOpsToIgnoreIfNotAllowed.add(extraOpToChange);
                }
            }
        }

        /**
         * Add a package for {@link #syncPackages() processing} later.
         *
         * <p>Note: Called with the package lock held. Do <u>not</u> call into app-op manager.
         *
         * @param pkgName The package to add for later processing.
         */
        void addPackage(@NonNull String pkgName) {
            final PackageInfo pkg;
            try {
                pkg = mPackageManager.getPackageInfo(pkgName, GET_PERMISSIONS);
            } catch (NameNotFoundException e) {
                return;
            }

            if (pkg.requestedPermissions == null) {
                return;
            }

            for (String permission : pkg.requestedPermissions) {
                addAppOps(pkg, permission);
            }
        }

        private void setUidModeAllowed(int opCode, int uid, @NonNull String packageName) {
            setUidMode(opCode, uid, MODE_ALLOWED, packageName);
        }

        private void setUidModeForeground(int opCode, int uid, @NonNull String packageName) {
            setUidMode(opCode, uid, MODE_FOREGROUND, packageName);
        }

        private void setUidModeIgnored(int opCode, int uid, @NonNull String packageName) {
            setUidMode(opCode, uid, MODE_IGNORED, packageName);
        }

        private boolean setUidModeIgnoredIfNotAllowed(int opCode, int uid,
                @NonNull String packageName) {
            final int currentMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager.opToPublicName(
                    opCode), uid, packageName);
            if (currentMode != MODE_ALLOWED) {
                if (currentMode != MODE_IGNORED) {
                    mAppOpsManager.setUidMode(opCode, uid, MODE_IGNORED);
                }
                return true;
            }
            return false;
        }

        private void setUidMode(int opCode, int uid, int mode,
                @NonNull String packageName) {
            final int currentMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager
                    .opToPublicName(opCode), uid, packageName);
            if (currentMode != mode) {
                mAppOpsManager.setUidMode(opCode, uid, mode);
            }
        }

        private class OpToChange {
            final int uid;
            final @NonNull String packageName;
            final int code;

            OpToChange(int uid, @NonNull String packageName, int code) {
                this.uid = uid;
                this.packageName = packageName;
                this.code = code;
            }
        }
    }

    private class Internal extends PermissionPolicyInternal {

        @Override
        public boolean checkStartActivity(@NonNull Intent intent, int callingUid,
                @Nullable String callingPackage) {
            if (callingPackage != null && isActionRemovedForCallingPackage(intent, callingUid,
                    callingPackage)) {
                Slog.w(LOG_TAG, "Action Removed: starting " + intent.toString() + " from "
                        + callingPackage + " (uid=" + callingUid + ")");
                return false;
            }
            return true;
        }

        @Override
        public boolean isInitialized(int userId) {
            return isStarted(userId);
        }

        @Override
        public void setOnInitializedCallback(@NonNull OnInitializedCallback callback) {
            synchronized (mLock) {
                mOnInitializedCallback = callback;
            }
        }

        /**
         * Check if the intent action is removed for the calling package (often based on target SDK
         * version). If the action is removed, we'll silently cancel the activity launch.
         */
        private boolean isActionRemovedForCallingPackage(@NonNull Intent intent, int callingUid,
                @NonNull String callingPackage) {
            String action = intent.getAction();
            if (action == null) {
                return false;
            }
            switch (action) {
                case TelecomManager.ACTION_CHANGE_DEFAULT_DIALER:
                case Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT: {
                    ApplicationInfo applicationInfo;
                    try {
                        applicationInfo = getContext().getPackageManager().getApplicationInfoAsUser(
                                callingPackage, 0, UserHandle.getUserId(callingUid));
                        if (applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q) {
                            // Applications targeting Q or higher should use
                            // RoleManager.createRequestRoleIntent() instead.
                            return true;
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        Slog.i(LOG_TAG, "Cannot find application info for " + callingPackage);
                    }
                    // Make sure RequestRoleActivity can know the calling package if we allow it.
                    intent.putExtra(Intent.EXTRA_CALLING_PACKAGE, callingPackage);
                    return false;
                }
                default:
                    return false;
            }
        }
    }
}
