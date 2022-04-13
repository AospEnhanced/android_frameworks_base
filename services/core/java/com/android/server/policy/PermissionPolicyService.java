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

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.app.ActivityOptions.ANIM_REMOTE_ANIMATION;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS;
import static android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS_FOR_OTHER;
import static android.content.pm.PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.app.KeyguardManager;
import android.app.TaskInfo;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseLongArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.IntPair;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.policy.PermissionPolicyInternal.OnInitializedCallback;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptorInfo;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * This is a permission policy that governs over all permission mechanism
 * such as permissions, app ops, etc. For example, the policy ensures that
 * permission state and app ops is synchronized for cases where there is a
 * dependency between permission state (permissions or permission flags)
 * and app ops - and vise versa.
 */
public final class PermissionPolicyService extends SystemService {
    private static final String LOG_TAG = PermissionPolicyService.class.getSimpleName();
    private static final String SYSTEM_PKG = "android";
    private static final boolean DEBUG = false;
    private static final long USER_SENSITIVE_UPDATE_DELAY_MS = 60000;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mBootCompleted = false;

    private IAppOpsCallback mAppOpsCallback;

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

    /**
     * Whether an async {@link #resetAppOpPermissionsIfNotRequestedForUid} is currently
     * scheduled for a uid.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mIsUidSyncScheduled = new SparseBooleanArray();

    /**
     * This change reflects the presence of the new Notification Permission
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S_V2)
    private static final long NOTIFICATION_PERM_CHANGE_ID = 194833441L;

    private List<String> mAppOpPermissions;

    private Context mContext;
    private PackageManagerInternal mPackageManagerInternal;
    private PermissionManagerServiceInternal mPermissionManagerInternal;
    private NotificationManagerInternal mNotificationManager;
    private final KeyguardManager mKeyguardManager;
    private final PackageManager mPackageManager;
    private final Handler mHandler;

    public PermissionPolicyService(@NonNull Context context) {
        super(context);

        mContext = context;
        mHandler = new Handler(Looper.getMainLooper());
        mPackageManager = context.getPackageManager();
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        LocalServices.addService(PermissionPolicyInternal.class, new Internal());
    }

    @Override
    public void onStart() {
        mPackageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        mPermissionManagerInternal = LocalServices.getService(
                PermissionManagerServiceInternal.class);
        final IAppOpsService appOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        mPackageManagerInternal.getPackageList(new PackageListObserver() {
            @Override
            public void onPackageAdded(String packageName, int uid) {
                final int userId = UserHandle.getUserId(uid);
                if (isStarted(userId)) {
                    synchronizePackagePermissionsAndAppOpsForUser(packageName, userId);
                }
            }

            @Override
            public void onPackageChanged(String packageName, int uid) {
                final int userId = UserHandle.getUserId(uid);
                if (isStarted(userId)) {
                    synchronizePackagePermissionsAndAppOpsForUser(packageName, userId);
                    resetAppOpPermissionsIfNotRequestedForUid(uid);
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                final int userId = UserHandle.getUserId(uid);
                if (isStarted(userId)) {
                    resetAppOpPermissionsIfNotRequestedForUid(uid);
                }
            }
        });

        mPermissionManagerInternal.addOnRuntimePermissionStateChangedListener(
                this::synchronizePackagePermissionsAndAppOpsAsyncForUser);

        mAppOpsCallback = new IAppOpsCallback.Stub() {
            public void opChanged(int op, int uid, String packageName) {
                synchronizePackagePermissionsAndAppOpsAsyncForUser(packageName,
                        UserHandle.getUserId(uid));
                resetAppOpPermissionsIfNotRequestedForUidAsync(uid);
            }
        };

        final ArrayList<PermissionInfo> dangerousPerms =
                mPermissionManagerInternal.getAllPermissionsWithProtection(
                        PermissionInfo.PROTECTION_DANGEROUS);
        try {
            int numDangerousPerms = dangerousPerms.size();
            for (int i = 0; i < numDangerousPerms; i++) {
                PermissionInfo perm = dangerousPerms.get(i);

                if (perm.isRuntime()) {
                    appOpsService.startWatchingMode(getSwitchOp(perm.name), null, mAppOpsCallback);
                }
                if (perm.isSoftRestricted()) {
                    SoftRestrictedPermissionPolicy policy =
                            SoftRestrictedPermissionPolicy.forPermission(null, null, null,
                                    null, perm.name);
                    int extraAppOp = policy.getExtraAppOpCode();
                    if (extraAppOp != OP_NONE) {
                        appOpsService.startWatchingMode(extraAppOp, null, mAppOpsCallback);
                    }
                }
            }
        } catch (RemoteException doesNotHappen) {
            Slog.wtf(LOG_TAG, "Cannot set up app-ops listener");
        }

        final List<PermissionInfo> appOpPermissionInfos =
                mPermissionManagerInternal.getAllPermissionsWithProtectionFlags(
                        PermissionInfo.PROTECTION_FLAG_APPOP);
        mAppOpPermissions = new ArrayList<>();
        final int appOpPermissionInfosSize = appOpPermissionInfos.size();
        for (int i = 0; i < appOpPermissionInfosSize; i++) {
            final PermissionInfo appOpPermissionInfo = appOpPermissionInfos.get(i);

            switch (appOpPermissionInfo.name) {
                case Manifest.permission.ACCESS_NOTIFICATIONS:
                case Manifest.permission.MANAGE_IPSEC_TUNNELS:
                    continue;
                case Manifest.permission.REQUEST_INSTALL_PACKAGES:
                    // Settings allows the user to control the app op if it's not in the default
                    // mode, regardless of whether the app has requested the permission, so we
                    // should not reset it.
                    continue;
                default:
                    final int appOpCode = AppOpsManager.permissionToOpCode(
                            appOpPermissionInfo.name);
                    if (appOpCode != OP_NONE) {
                        mAppOpPermissions.add(appOpPermissionInfo.name);
                        try {
                            appOpsService.startWatchingMode(appOpCode, null, mAppOpsCallback);
                        } catch (RemoteException e) {
                            Slog.wtf(LOG_TAG, "Cannot set up app-ops listener", e);
                        }
                    }
            }
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addDataScheme("package");

        getContext().registerReceiverAsUser(new BroadcastReceiver() {
            final List<Integer> mUserSetupUids = new ArrayList<>(200);
            final Map<UserHandle, PermissionControllerManager> mPermControllerManagers =
                    new HashMap<>();

            @Override
            public void onReceive(Context context, Intent intent) {
                boolean hasSetupRun = true;
                try {
                    final ContentResolver cr = getContext().getContentResolver();
                    hasSetupRun = Settings.Secure.getIntForUser(cr,
                            Settings.Secure.USER_SETUP_COMPLETE, cr.getUserId()) != 0;
                } catch (Settings.SettingNotFoundException e) {
                    // Ignore error, assume setup has run
                }
                int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                // If there is no valid package for the given UID, return immediately
                if (mPackageManagerInternal.getPackage(uid) == null) {
                    return;
                }

                if (hasSetupRun) {
                    if (!mUserSetupUids.isEmpty()) {
                        synchronized (mUserSetupUids) {
                            for (int i = mUserSetupUids.size() - 1; i >= 0; i--) {
                                updateUid(mUserSetupUids.get(i));
                            }
                            mUserSetupUids.clear();
                        }
                    }
                    updateUid(uid);
                } else {
                    synchronized (mUserSetupUids) {
                        if (!mUserSetupUids.contains(uid)) {
                            mUserSetupUids.add(uid);
                        }
                    }
                }
            }

            private void updateUid(int uid) {
                UserHandle user = UserHandle.getUserHandleForUid(uid);
                PermissionControllerManager manager = mPermControllerManagers.get(user);
                if (manager == null) {
                    manager = new PermissionControllerManager(
                            getUserContext(getContext(), user), FgThread.getHandler());
                    mPermControllerManagers.put(user, manager);
                }
                manager.updateUserSensitiveForApp(uid);
            }
        }, UserHandle.ALL, intentFilter, null, null);

        PermissionControllerManager manager = new PermissionControllerManager(
                getUserContext(getContext(), Process.myUserHandle()), FgThread.getHandler());
        FgThread.getHandler().postDelayed(manager::updateUserSensitive,
                USER_SENSITIVE_UPDATE_DELAY_MS);
    }

    /**
     * Get op that controls the access related to the permission.
     *
     * <p>Usually the permission-op relationship is 1:1 but some permissions (e.g. fine location)
     * {@link AppOpsManager#sOpToSwitch share an op} to control the access.
     *
     * @param permission The permission
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

        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            ((Internal) LocalServices.getService(PermissionPolicyInternal.class))
                    .onActivityManagerReady();
        }

        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            synchronized (mLock) {
                mBootCompleted = true;
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
    public void onUserStarting(@NonNull TargetUser user) {
        onStartUser(user.getUserIdentifier());
    }

    private void onStartUser(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "onStartUser(" + userId + ")");

        if (isStarted(userId)) {
            return;
        }


        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("Permission_grant_default_permissions-" + userId);
        grantOrUpgradeDefaultRuntimePermissionsIfNeeded(userId);
        t.traceEnd();

        final OnInitializedCallback callback;

        synchronized (mLock) {
            mIsStarted.put(userId, true);
            callback = mOnInitializedCallback;
        }

        // Force synchronization as permissions might have changed
        t.traceBegin("Permission_synchronize_permissions-" + userId);
        synchronizePermissionsAndAppOpsForUser(userId);
        t.traceEnd();

        // Tell observers we are initialized for this user.
        if (callback != null) {
            t.traceBegin("Permission_onInitialized-" + userId);
            callback.onInitialized(userId);
            t.traceEnd();
        }
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        if (DEBUG) Slog.i(LOG_TAG, "onStopUser(" + user + ")");

        synchronized (mLock) {
            mIsStarted.delete(user.getUserIdentifier());
        }
    }

    private void grantOrUpgradeDefaultRuntimePermissionsIfNeeded(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "grantOrUpgradeDefaultPermsIfNeeded(" + userId + ")");
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();

        final PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        final PermissionManagerServiceInternal permissionManagerInternal =
                LocalServices.getService(PermissionManagerServiceInternal.class);
        if (packageManagerInternal.isPermissionUpgradeNeeded(userId)) {
            if (DEBUG) Slog.i(LOG_TAG, "defaultPermsWereGrantedSinceBoot(" + userId + ")");

            // Now call into the permission controller to apply policy around permissions
            final AndroidFuture<Boolean> future = new AndroidFuture<>();

            // We need to create a local manager that does not schedule work on the main
            // there as we are on the main thread and want to block until the work is
            // completed or we time out.
            final PermissionControllerManager permissionControllerManager =
                    new PermissionControllerManager(
                            getUserContext(getContext(), UserHandle.of(userId)),
                            FgThread.getHandler());
            permissionControllerManager.grantOrUpgradeDefaultRuntimePermissions(
                    FgThread.getExecutor(), successful -> {
                        if (successful) {
                            future.complete(null);
                        } else {
                            // We are in an undefined state now, let us crash and have
                            // rescue party suggest a wipe to recover to a good one.
                            final String message = "Error granting/upgrading runtime permissions"
                                    + " for user " + userId;
                            Slog.wtf(LOG_TAG, message);
                            future.completeExceptionally(new IllegalStateException(message));
                        }
                    });
            try {
                t.traceBegin("Permission_callback_waiting-" + userId);
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            } finally {
                t.traceEnd();
            }

            permissionControllerManager.updateUserSensitive();

            packageManagerInternal.updateRuntimePermissionsFingerprint(userId);
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
        final String[] sharedPkgNames = packageManagerInternal.getSharedUserPackagesForPackage(
                pkg.packageName, userId);

        for (String sharedPkgName : sharedPkgNames) {
            final AndroidPackage sharedPkg = packageManagerInternal
                    .getPackage(sharedPkgName);
            if (sharedPkg != null) {
                synchroniser.addPackage(sharedPkg.getPackageName());
            }
        }
        synchroniser.syncPackages();
    }

    /**
     * Synchronize all packages
     */
    private void synchronizePermissionsAndAppOpsForUser(@UserIdInt int userId) {
        if (DEBUG) Slog.i(LOG_TAG, "synchronizePermissionsAndAppOpsForUser(" + userId + ")");
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();

        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final PermissionToOpSynchroniser synchronizer = new PermissionToOpSynchroniser(
                getUserContext(getContext(), UserHandle.of(userId)));
        t.traceBegin("Permission_synchronize_addPackages-" + userId);
        packageManagerInternal.forEachPackage(
                (pkg) -> synchronizer.addPackage(pkg.getPackageName()));
        t.traceEnd();
        t.traceBegin("Permission_syncPackages-" + userId);
        synchronizer.syncPackages();
        t.traceEnd();
    }

    private void resetAppOpPermissionsIfNotRequestedForUidAsync(int uid) {
        if (isStarted(UserHandle.getUserId(uid))) {
            synchronized (mLock) {
                if (!mIsUidSyncScheduled.get(uid)) {
                    mIsUidSyncScheduled.put(uid, true);
                    FgThread.getHandler().sendMessage(PooledLambda.obtainMessage(
                            PermissionPolicyService::resetAppOpPermissionsIfNotRequestedForUid,
                            this, uid));
                }
            }
        }
    }

    private void resetAppOpPermissionsIfNotRequestedForUid(int uid) {
        synchronized (mLock) {
            mIsUidSyncScheduled.delete(uid);
        }

        final Context context = getContext();
        final PackageManager userPackageManager = getUserContext(context,
                UserHandle.getUserHandleForUid(uid)).getPackageManager();
        final String[] packageNames = userPackageManager.getPackagesForUid(uid);
        if (packageNames == null || packageNames.length == 0) {
            return;
        }

        final ArraySet<String> requestedPermissions = new ArraySet<>();
        for (String packageName : packageNames) {
            final PackageInfo packageInfo;
            try {
                packageInfo = userPackageManager.getPackageInfo(packageName, GET_PERMISSIONS);
            } catch (NameNotFoundException e) {
                continue;
            }
            if (packageInfo == null || packageInfo.requestedPermissions == null) {
                continue;
            }
            Collections.addAll(requestedPermissions, packageInfo.requestedPermissions);
        }

        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final AppOpsManagerInternal appOpsManagerInternal = LocalServices.getService(
                AppOpsManagerInternal.class);
        final int appOpPermissionsSize = mAppOpPermissions.size();
        for (int i = 0; i < appOpPermissionsSize; i++) {
            final String appOpPermission = mAppOpPermissions.get(i);

            if (!requestedPermissions.contains(appOpPermission)) {
                final int appOpCode = AppOpsManager.permissionToOpCode(appOpPermission);
                final int defaultAppOpMode = AppOpsManager.opToDefaultMode(appOpCode);
                for (String packageName : packageNames) {
                    final int appOpMode = appOpsManager.unsafeCheckOpRawNoThrow(appOpCode, uid,
                            packageName);
                    if (appOpMode != defaultAppOpMode) {
                        appOpsManagerInternal.setUidModeFromPermissionPolicy(appOpCode, uid,
                                defaultAppOpMode, mAppOpsCallback);
                        appOpsManagerInternal.setModeFromPermissionPolicy(appOpCode, uid,
                                packageName, defaultAppOpMode, mAppOpsCallback);
                    }
                }
            }
        }
    }

    /**
     * Synchronizes permission to app ops. You *must* always sync all packages
     * in a shared UID at the same time to ensure proper synchronization.
     */
    private class PermissionToOpSynchroniser {
        private final @NonNull Context mContext;
        private final @NonNull PackageManager mPackageManager;
        private final @NonNull AppOpsManager mAppOpsManager;
        private final @NonNull AppOpsManagerInternal mAppOpsManagerInternal;

        private final @NonNull ArrayMap<String, PermissionInfo> mRuntimeAndTheirBgPermissionInfos;

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
            mAppOpsManagerInternal = LocalServices.getService(AppOpsManagerInternal.class);

            mRuntimeAndTheirBgPermissionInfos = new ArrayMap<>();
            PermissionManagerServiceInternal permissionManagerInternal = LocalServices.getService(
                    PermissionManagerServiceInternal.class);
            List<PermissionInfo> permissionInfos =
                    permissionManagerInternal.getAllPermissionsWithProtection(
                            PermissionInfo.PROTECTION_DANGEROUS);
            int permissionInfosSize = permissionInfos.size();
            for (int i = 0; i < permissionInfosSize; i++) {
                PermissionInfo permissionInfo = permissionInfos.get(i);
                mRuntimeAndTheirBgPermissionInfos.put(permissionInfo.name, permissionInfo);
                // Make sure we scoop up all background permissions as they may not be runtime
                if (permissionInfo.backgroundPermission != null) {
                    String backgroundNonRuntimePermission = permissionInfo.backgroundPermission;
                    for (int j = 0; j < permissionInfosSize; j++) {
                        PermissionInfo bgPermissionCandidate = permissionInfos.get(j);
                        if (permissionInfo.backgroundPermission.equals(
                                bgPermissionCandidate.name)) {
                            backgroundNonRuntimePermission = null;
                            break;
                        }
                    }
                    if (backgroundNonRuntimePermission != null) {
                        try {
                            PermissionInfo backgroundPermissionInfo = mPackageManager
                                    .getPermissionInfo(backgroundNonRuntimePermission, 0);
                            mRuntimeAndTheirBgPermissionInfos.put(backgroundPermissionInfo.name,
                                    backgroundPermissionInfo);
                        } catch (NameNotFoundException e) {
                            Slog.w(LOG_TAG, "Unknown background permission: "
                                    + backgroundNonRuntimePermission);
                        }
                    }
                }
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
        private void addAppOps(@NonNull PackageInfo packageInfo, @NonNull AndroidPackage pkg,
                @NonNull String permissionName) {
            PermissionInfo permissionInfo = mRuntimeAndTheirBgPermissionInfos.get(permissionName);
            if (permissionInfo == null) {
                return;
            }
            addPermissionAppOp(packageInfo, pkg, permissionInfo);
            addExtraAppOp(packageInfo, pkg, permissionInfo);
        }

        private void addPermissionAppOp(@NonNull PackageInfo packageInfo,
                @NonNull AndroidPackage pkg, @NonNull PermissionInfo permissionInfo) {
            if (!permissionInfo.isRuntime()) {
                return;
            }

            String permissionName = permissionInfo.name;
            String packageName = packageInfo.packageName;
            UserHandle user = UserHandle.getUserHandleForUid(packageInfo.applicationInfo.uid);
            int permissionFlags = mPackageManager.getPermissionFlags(permissionName,
                    packageName, mContext.getUser());
            boolean isReviewRequired = (permissionFlags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0;
            if (isReviewRequired && !CompatChanges.isChangeEnabled(
                    NOTIFICATION_PERM_CHANGE_ID, packageName, user)) {
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
            boolean shouldGrantAppOp = shouldGrantAppOp(packageInfo, pkg, permissionInfo);
            if (shouldGrantAppOp) {
                if (permissionInfo.backgroundPermission != null) {
                    PermissionInfo backgroundPermissionInfo = mRuntimeAndTheirBgPermissionInfos.get(
                            permissionInfo.backgroundPermission);
                    boolean shouldGrantBackgroundAppOp = backgroundPermissionInfo != null
                            && shouldGrantAppOp(packageInfo, pkg, backgroundPermissionInfo);
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
                @NonNull AndroidPackage pkg, @NonNull PermissionInfo permissionInfo) {
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
                                packageInfo.applicationInfo, pkg, mContext.getUser(),
                                permissionName);
                return policy.mayGrantPermission();
            } else {
                return true;
            }
        }

        private void addExtraAppOp(@NonNull PackageInfo packageInfo, @NonNull AndroidPackage pkg,
                @NonNull PermissionInfo permissionInfo) {
            if (!permissionInfo.isSoftRestricted()) {
                return;
            }

            String permissionName = permissionInfo.name;
            SoftRestrictedPermissionPolicy policy =
                    SoftRestrictedPermissionPolicy.forPermission(mContext,
                            packageInfo.applicationInfo, pkg, mContext.getUser(), permissionName);
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
            PackageManagerInternal pmInternal =
                    LocalServices.getService(PackageManagerInternal.class);
            final PackageInfo pkgInfo;
            final AndroidPackage pkg;
            try {
                pkgInfo = mPackageManager.getPackageInfo(pkgName, GET_PERMISSIONS);
                pkg = pmInternal.getPackage(pkgName);
            } catch (NameNotFoundException e) {
                return;
            }

            if (pkgInfo == null || pkg == null || pkgInfo.applicationInfo == null
                    || pkgInfo.requestedPermissions == null) {
                return;
            }

            final int uid = pkgInfo.applicationInfo.uid;
            if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID) {
                // Root and system server always pass permission checks, so don't touch their app
                // ops to keep compatibility.
                return;
            }

            for (String permission : pkgInfo.requestedPermissions) {
                addAppOps(pkgInfo, pkg, permission);
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
                    mAppOpsManagerInternal.setUidModeFromPermissionPolicy(opCode, uid, MODE_IGNORED,
                            mAppOpsCallback);
                }
                return true;
            }
            return false;
        }

        private void setUidMode(int opCode, int uid, int mode,
                @NonNull String packageName) {
            final int oldMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager.opToPublicName(
                    opCode), uid, packageName);
            if (oldMode != mode) {
                mAppOpsManagerInternal.setUidModeFromPermissionPolicy(opCode, uid, mode,
                        mAppOpsCallback);
                final int newMode = mAppOpsManager.unsafeCheckOpRaw(AppOpsManager.opToPublicName(
                        opCode), uid, packageName);
                if (newMode != mode) {
                    // Work around incorrectly-set package mode. It never makes sense for app ops
                    // related to runtime permissions, but can get in the way and we have to reset
                    // it.
                    mAppOpsManagerInternal.setModeFromPermissionPolicy(opCode, uid, packageName,
                            AppOpsManager.opToDefaultMode(opCode), mAppOpsCallback);
                }
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

        // UIDs that, if a grant dialog is shown for POST_NOTIFICATIONS before next reboot,
        // should display a "continue allowing" message, rather than an "allow" message
        private final ArraySet<Integer> mContinueNotifGrantMessageUids = new ArraySet<>();

        private final ActivityInterceptorCallback mActivityInterceptorCallback =
                new ActivityInterceptorCallback() {
                    @Nullable
                    @Override
                    public ActivityInterceptorCallback.ActivityInterceptResult intercept(
                            ActivityInterceptorInfo info) {
                        String action = info.intent.getAction();
                        ActivityInterceptResult result = null;
                        if (!ACTION_REQUEST_PERMISSIONS_FOR_OTHER.equals(action)
                                && !PackageManager.ACTION_REQUEST_PERMISSIONS.equals(action)) {
                            return null;
                        }
                        // Only this interceptor can add LEGACY_ACCESS_PERMISSION_NAMES
                        if (info.intent.getStringArrayExtra(PackageManager
                                .EXTRA_REQUEST_PERMISSIONS_LEGACY_ACCESS_PERMISSION_NAMES)
                                != null) {
                            result = new ActivityInterceptResult(
                                    new Intent(info.intent), info.checkedOptions);
                            result.intent.removeExtra(PackageManager
                                    .EXTRA_REQUEST_PERMISSIONS_LEGACY_ACCESS_PERMISSION_NAMES);
                        }
                        if (PackageManager.ACTION_REQUEST_PERMISSIONS.equals(action)
                                && !mContinueNotifGrantMessageUids.contains(info.realCallingUid)) {
                            return result;
                        }
                        if (ACTION_REQUEST_PERMISSIONS_FOR_OTHER.equals(action)) {
                            String otherPkg = info.intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
                            if (otherPkg == null || (mPackageManager.getPermissionFlags(
                                    POST_NOTIFICATIONS, otherPkg, UserHandle.of(info.userId))
                                    & FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                                return result;
                            }
                        }

                        mContinueNotifGrantMessageUids.remove(info.realCallingUid);
                        return new ActivityInterceptResult(info.intent.putExtra(PackageManager
                                        .EXTRA_REQUEST_PERMISSIONS_LEGACY_ACCESS_PERMISSION_NAMES,
                                new String[] { POST_NOTIFICATIONS }), info.checkedOptions);
                    }

                    @Override
                    public void onActivityLaunched(TaskInfo taskInfo, ActivityInfo activityInfo,
                            ActivityInterceptorInfo info) {
                        super.onActivityLaunched(taskInfo, activityInfo, info);
                        if (!shouldShowNotificationDialogOrClearFlags(taskInfo,
                                activityInfo.packageName, info.intent, info.checkedOptions, true)) {
                            return;
                        }
                        UserHandle user = UserHandle.of(taskInfo.userId);
                        if (CompatChanges.isChangeEnabled(NOTIFICATION_PERM_CHANGE_ID,
                                activityInfo.packageName, user)) {
                            clearNotificationReviewFlagsIfNeeded(activityInfo.packageName, user);
                        } else {
                            // Post the activity start checks to ensure the notification channel
                            // checks happen outside the WindowManager global lock.
                            mHandler.post(() -> showNotificationPromptIfNeeded(
                                    activityInfo.packageName, taskInfo.userId, taskInfo.taskId,
                                    info));
                        }
                    }
                };

        private void onActivityManagerReady() {
            ActivityTaskManagerInternal atm =
                    LocalServices.getService(ActivityTaskManagerInternal.class);
            atm.registerActivityStartInterceptor(
                    ActivityInterceptorCallback.PERMISSION_POLICY_ORDERED_ID,
                    mActivityInterceptorCallback);
        }

        @Override
        public boolean checkStartActivity(@NonNull Intent intent, int callingUid,
                @Nullable String callingPackage) {
            if (callingPackage != null && isActionRemovedForCallingPackage(intent, callingUid,
                    callingPackage)) {
                Slog.w(LOG_TAG, "Action Removed: starting " + intent.toString() + " from "
                        + callingPackage + " (uid=" + callingUid + ")");
                return false;
            }

            if (ACTION_REQUEST_PERMISSIONS_FOR_OTHER.equals(intent.getAction())
                    && (callingUid != Process.SYSTEM_UID || !SYSTEM_PKG.equals(callingPackage))) {
                return false;
            }

            return true;
        }

        @Override
        public void showNotificationPromptIfNeeded(@NonNull String packageName, int userId,
                int taskId) {
            showNotificationPromptIfNeeded(packageName, userId, taskId, null /* info */);
        }

        void showNotificationPromptIfNeeded(@NonNull String packageName, int userId,
                int taskId, @Nullable ActivityInterceptorInfo info) {
            UserHandle user = UserHandle.of(userId);
            if (packageName == null || taskId == ActivityTaskManager.INVALID_TASK_ID
                    || !shouldForceShowNotificationPermissionRequest(packageName, user)) {
                return;
            }

            launchNotificationPermissionRequestDialog(packageName, user, taskId, info);
        }

        @Override
        public boolean isIntentToPermissionDialog(@NonNull Intent intent) {
            return Objects.equals(intent.getPackage(),
                    mPackageManager.getPermissionControllerPackageName())
                    && (Objects.equals(intent.getAction(), ACTION_REQUEST_PERMISSIONS_FOR_OTHER)
                    || Objects.equals(intent.getAction(), ACTION_REQUEST_PERMISSIONS));
        }

        @Override
        public boolean shouldShowNotificationDialogForTask(TaskInfo taskInfo, String currPkg,
                Intent intent) {
            return shouldShowNotificationDialogOrClearFlags(
                    taskInfo, currPkg, intent, null, false);
        }

        /**
         * Determine if a particular task is in the proper state to show a system-triggered
         * permission prompt. A prompt can be shown if the task is just starting, or the task is
         * currently focused, visible, and running, and,
         * 1. The isEligibleForLegacyPermissionPrompt ActivityOption is set, or
         * 2. The intent is a launcher intent (action is ACTION_MAIN, category is LAUNCHER), or
         * 3. The activity belongs to the same package as the one which launched the task
         * originally, and the task was started with a launcher intent
         * @param taskInfo The task to be checked
         * @param currPkg The package of the current top visible activity
         * @param intent The intent of the current top visible activity
         */
        private boolean shouldShowNotificationDialogOrClearFlags(TaskInfo taskInfo, String currPkg,
                Intent intent, ActivityOptions options, boolean activityStart) {
            if (intent == null || currPkg == null || taskInfo == null
                    || (!(taskInfo.isFocused && taskInfo.isVisible && taskInfo.isRunning)
                    && !activityStart)) {
                return false;
            }

            return isLauncherIntent(intent)
                    || (options != null && options.isEligibleForLegacyPermissionPrompt())
                    || (currPkg.equals(taskInfo.baseActivity.getPackageName())
                    && isLauncherIntent(taskInfo.baseIntent));
        }

        private boolean isLauncherIntent(Intent intent) {
            return Intent.ACTION_MAIN.equals(intent.getAction())
                    && intent.getCategories() != null
                    && (intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)
                    || intent.getCategories().contains(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    || intent.getCategories().contains(Intent.CATEGORY_CAR_LAUNCHER));
        }

        private void clearNotificationReviewFlagsIfNeeded(String packageName, UserHandle user) {
            if ((mPackageManager.getPermissionFlags(POST_NOTIFICATIONS, packageName, user)
                    & FLAG_PERMISSION_REVIEW_REQUIRED) == 0) {
                return;
            }
            try {
                int uid = mPackageManager.getPackageUidAsUser(packageName, 0,
                        user.getIdentifier());
                mContinueNotifGrantMessageUids.add(uid);
                mPackageManager.updatePermissionFlags(POST_NOTIFICATIONS, packageName,
                        FLAG_PERMISSION_REVIEW_REQUIRED, 0, user);
            } catch (PackageManager.NameNotFoundException e) {
                // Do nothing
            }
        }

        private void launchNotificationPermissionRequestDialog(String pkgName, UserHandle user,
                int taskId, @Nullable ActivityInterceptorInfo info) {
            Intent grantPermission = mPackageManager
                    .buildRequestPermissionsIntent(new String[] { POST_NOTIFICATIONS });
            // Prevent the front-most activity entering pip due to overlay activity started on top.
            grantPermission.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_USER_ACTION);
            grantPermission.setAction(
                    ACTION_REQUEST_PERMISSIONS_FOR_OTHER);
            grantPermission.putExtra(Intent.EXTRA_PACKAGE_NAME, pkgName);

            final boolean remoteAnimation = info != null && info.checkedOptions != null
                    && info.checkedOptions.getAnimationType() == ANIM_REMOTE_ANIMATION
                    && info.clearOptionsAnimation != null;
            ActivityOptions options = remoteAnimation ? ActivityOptions.makeRemoteAnimation(
                        info.checkedOptions.getRemoteAnimationAdapter(),
                        info.checkedOptions.getRemoteTransition())
                    : new ActivityOptions(new Bundle());
            options.setTaskOverlay(true, false);
            options.setLaunchTaskId(taskId);
            if (remoteAnimation) {
                // Remote animation set on the intercepted activity will be handled by the grant
                // permission activity, which is launched below. So we need to clear remote
                // animation from the intercepted activity and its siblings to prevent duplication.
                // This should trigger ActivityRecord#clearOptionsAnimationForSiblings for the
                // intercepted activity.
                info.clearOptionsAnimation.run();
            }
            try {
                mContext.startActivityAsUser(grantPermission, options.toBundle(), user);
            } catch (Exception e) {
                Log.e(LOG_TAG, "couldn't start grant permission dialog"
                        + "for other package " + pkgName, e);
            }
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

        private boolean shouldForceShowNotificationPermissionRequest(@NonNull String pkgName,
                @NonNull UserHandle user) {
            AndroidPackage pkg = mPackageManagerInternal.getPackage(pkgName);
            if (pkg == null || pkg.getPackageName() == null
                    || Objects.equals(pkgName, mPackageManager.getPermissionControllerPackageName())
                    || pkg.getTargetSdkVersion() < Build.VERSION_CODES.M) {
                if (pkg == null) {
                    Slog.w(LOG_TAG, "Cannot check for Notification prompt, no package for "
                            + pkgName);
                }
                return false;
            }

            synchronized (mLock) {
                if (!mBootCompleted) {
                    return false;
                }
            }

            try {
                if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.NOTIFICATION_PERMISSION_ENABLED, UserHandle.USER_SYSTEM)
                        == 0) {
                    return false;
                }
            } catch (Settings.SettingNotFoundException e) {
                return false;
            }

            if (!pkg.getRequestedPermissions().contains(POST_NOTIFICATIONS)
                    || CompatChanges.isChangeEnabled(NOTIFICATION_PERM_CHANGE_ID, pkgName, user)
                    || mKeyguardManager.isKeyguardLocked()) {
                return false;
            }

            int uid = user.getUid(pkg.getUid());
            if (mNotificationManager == null) {
                mNotificationManager = LocalServices.getService(NotificationManagerInternal.class);
            }
            boolean hasCreatedNotificationChannels = mNotificationManager
                    .getNumNotificationChannelsForPackage(pkgName, uid, true) > 0;
            boolean granted = mPermissionManagerInternal.checkUidPermission(uid, POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            int flags = mPackageManager.getPermissionFlags(POST_NOTIFICATIONS, pkgName, user);
            boolean explicitlySet = (flags & PermissionManager.EXPLICIT_SET_FLAGS) != 0;
            boolean needsReview = (flags & FLAG_PERMISSION_REVIEW_REQUIRED) != 0;
            return !granted && hasCreatedNotificationChannels && (needsReview || !explicitlySet);
        }
    }
}
