/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.COMPONENT_VISIBLE_STATUS;

import android.content.pm.ApplicationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.os.storage.VolumeInfo;
import android.service.pm.PackageProto;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.google.android.collect.Lists;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Settings base class for pending and resolved classes.
 */
abstract class PackageSettingBase extends SettingBase {

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * Indicates the state of installation. Used by PackageManager to figure out
     * incomplete installations. Say a package is being installed (the state is
     * set to PKG_INSTALL_INCOMPLETE) and remains so till the package
     * installation is successful or unsuccessful in which case the
     * PackageManager will no longer maintain state information associated with
     * the package. If some exception(like device freeze or battery being pulled
     * out) occurs during installation of a package, the PackageManager needs
     * this information to clean up the previously failed installation.
     */
    static final int PKG_INSTALL_COMPLETE = 1;
    static final int PKG_INSTALL_INCOMPLETE = 0;

    final String name;
    final String realName;

    String parentPackageName;
    List<String> childPackageNames;

    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    File codePath;
    String codePathString;
    File resourcePath;
    String resourcePathString;

    String[] usesStaticLibraries;
    int[] usesStaticLibrariesVersions;

    /**
     * The path under which native libraries have been unpacked. This path is
     * always derived at runtime, and is only stored here for cleanup when a
     * package is uninstalled.
     */
    @Deprecated
    String legacyNativeLibraryPathString;

    /**
     * The primary CPU abi for this package.
     */
    String primaryCpuAbiString;

    /**
     * The secondary CPU abi for this package.
     */
    String secondaryCpuAbiString;

    /**
     * The install time CPU override, if any. This value is written at install time
     * and doesn't change during the life of an install. If non-null,
     * {@code primaryCpuAbiString} will contain the same value.
     */
    String cpuAbiOverrideString;

    long timeStamp;
    long firstInstallTime;
    long lastUpdateTime;
    int versionCode;

    boolean uidError;

    PackageSignatures signatures;

    boolean installPermissionsFixed;

    PackageKeySetData keySetData = new PackageKeySetData();

    static final PackageUserState DEFAULT_USER_STATE = new PackageUserState();

    // Whether this package is currently stopped, thus can not be
    // started until explicitly launched by the user.
    private final SparseArray<PackageUserState> userState = new SparseArray<PackageUserState>();

    int installStatus = PKG_INSTALL_COMPLETE;

    /**
     * Non-persisted value. During an "upgrade without restart", we need the set
     * of all previous code paths so we can surgically add the new APKs to the
     * active classloader. If at any point an application is upgraded with a
     * restart, this field will be cleared since the classloader would be created
     * using the full set of code paths when the package's process is started.
     */
    Set<String> oldCodePaths;
    PackageSettingBase origPackage;

    /** Package name of the app that installed this package */
    String installerPackageName;
    /** Indicates if the package that installed this app has been uninstalled */
    boolean isOrphaned;
    /** UUID of {@link VolumeInfo} hosting this app */
    String volumeUuid;
    /** The category of this app, as hinted by the installer */
    int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
    /** Whether or not an update is available. Ostensibly only for instant apps. */
    boolean updateAvailable;

    IntentFilterVerificationInfo verificationInfo;

    PackageSettingBase(String name, String realName, File codePath, File resourcePath,
            String legacyNativeLibraryPathString, String primaryCpuAbiString,
            String secondaryCpuAbiString, String cpuAbiOverrideString,
            int pVersionCode, int pkgFlags, int pkgPrivateFlags,
            String parentPackageName, List<String> childPackageNames,
            String[] usesStaticLibraries, int[] usesStaticLibrariesVersions) {
        super(pkgFlags, pkgPrivateFlags);
        this.name = name;
        this.realName = realName;
        this.parentPackageName = parentPackageName;
        this.childPackageNames = (childPackageNames != null)
                ? new ArrayList<>(childPackageNames) : null;
        this.usesStaticLibraries = usesStaticLibraries;
        this.usesStaticLibrariesVersions = usesStaticLibrariesVersions;
        init(codePath, resourcePath, legacyNativeLibraryPathString, primaryCpuAbiString,
                secondaryCpuAbiString, cpuAbiOverrideString, pVersionCode);
    }

    /**
     * New instance of PackageSetting with one-level-deep cloning.
     * <p>
     * IMPORTANT: With a shallow copy, we do NOT create new contained objects.
     * This means, for example, changes to the user state of the original PackageSetting
     * will also change the user state in its copy.
     */
    PackageSettingBase(PackageSettingBase base, String realName) {
        super(base);
        name = base.name;
        this.realName = realName;
        doCopy(base);
    }

    void init(File codePath, File resourcePath, String legacyNativeLibraryPathString,
              String primaryCpuAbiString, String secondaryCpuAbiString,
              String cpuAbiOverrideString, int pVersionCode) {
        this.codePath = codePath;
        this.codePathString = codePath.toString();
        this.resourcePath = resourcePath;
        this.resourcePathString = resourcePath.toString();
        this.legacyNativeLibraryPathString = legacyNativeLibraryPathString;
        this.primaryCpuAbiString = primaryCpuAbiString;
        this.secondaryCpuAbiString = secondaryCpuAbiString;
        this.cpuAbiOverrideString = cpuAbiOverrideString;
        this.versionCode = pVersionCode;
        this.signatures = new PackageSignatures();
    }

    public void setInstallerPackageName(String packageName) {
        installerPackageName = packageName;
    }

    public String getInstallerPackageName() {
        return installerPackageName;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setInstallStatus(int newStatus) {
        installStatus = newStatus;
    }

    public int getInstallStatus() {
        return installStatus;
    }

    public void setTimeStamp(long newStamp) {
        timeStamp = newStamp;
    }

    public void setUpdateAvailable(boolean updateAvailable) {
        this.updateAvailable = updateAvailable;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    /**
     * Makes a shallow copy of the given package settings.
     *
     * NOTE: For some fields [such as keySetData, signatures, userState, verificationInfo, etc...],
     * the original object is copied and a new one is not created.
     */
    public void copyFrom(PackageSettingBase orig) {
        super.copyFrom(orig);
        doCopy(orig);
    }

    private void doCopy(PackageSettingBase orig) {
        childPackageNames = (orig.childPackageNames != null)
                ? new ArrayList<>(orig.childPackageNames) : null;
        codePath = orig.codePath;
        codePathString = orig.codePathString;
        cpuAbiOverrideString = orig.cpuAbiOverrideString;
        firstInstallTime = orig.firstInstallTime;
        installPermissionsFixed = orig.installPermissionsFixed;
        installStatus = orig.installStatus;
        installerPackageName = orig.installerPackageName;
        isOrphaned = orig.isOrphaned;
        keySetData = orig.keySetData;
        lastUpdateTime = orig.lastUpdateTime;
        legacyNativeLibraryPathString = orig.legacyNativeLibraryPathString;
        // Intentionally skip oldCodePaths; it's not relevant for copies
        origPackage = orig.origPackage;
        parentPackageName = orig.parentPackageName;
        primaryCpuAbiString = orig.primaryCpuAbiString;
        resourcePath = orig.resourcePath;
        resourcePathString = orig.resourcePathString;
        secondaryCpuAbiString = orig.secondaryCpuAbiString;
        signatures = orig.signatures;
        timeStamp = orig.timeStamp;
        uidError = orig.uidError;
        userState.clear();
        for (int i=0; i<orig.userState.size(); i++) {
            userState.put(orig.userState.keyAt(i), orig.userState.valueAt(i));
        }
        verificationInfo = orig.verificationInfo;
        versionCode = orig.versionCode;
        volumeUuid = orig.volumeUuid;
        categoryHint = orig.categoryHint;
        usesStaticLibraries = orig.usesStaticLibraries != null
                ? Arrays.copyOf(orig.usesStaticLibraries,
                        orig.usesStaticLibraries.length) : null;
        usesStaticLibrariesVersions = orig.usesStaticLibrariesVersions != null
                ? Arrays.copyOf(orig.usesStaticLibrariesVersions,
                       orig.usesStaticLibrariesVersions.length) : null;
        updateAvailable = orig.updateAvailable;
    }

    private PackageUserState modifyUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state == null) {
            state = new PackageUserState();
            userState.put(userId, state);
        }
        return state;
    }

    public PackageUserState readUserState(int userId) {
        PackageUserState state = userState.get(userId);
        if (state == null) {
            return DEFAULT_USER_STATE;
        }
        state.categoryHint = categoryHint;
        return state;
    }

    void setEnabled(int state, int userId, String callingPackage) {
        PackageUserState st = modifyUserState(userId);
        st.enabled = state;
        st.lastDisableAppCaller = callingPackage;
    }

    int getEnabled(int userId) {
        return readUserState(userId).enabled;
    }

    String getLastDisabledAppCaller(int userId) {
        return readUserState(userId).lastDisableAppCaller;
    }

    void setInstalled(boolean inst, int userId) {
        modifyUserState(userId).installed = inst;
    }

    boolean getInstalled(int userId) {
        return readUserState(userId).installed;
    }

    int getInstallReason(int userId) {
        return readUserState(userId).installReason;
    }

    void setInstallReason(int installReason, int userId) {
        modifyUserState(userId).installReason = installReason;
    }

    void setOverlayPaths(List<String> overlayPaths, int userId) {
        modifyUserState(userId).overlayPaths = overlayPaths == null ? null :
            overlayPaths.toArray(new String[overlayPaths.size()]);
    }

    String[] getOverlayPaths(int userId) {
        return readUserState(userId).overlayPaths;
    }

    /** Only use for testing. Do NOT use in production code. */
    @VisibleForTesting
    SparseArray<PackageUserState> getUserState() {
        return userState;
    }

    boolean isAnyInstalled(int[] users) {
        for (int user: users) {
            if (readUserState(user).installed) {
                return true;
            }
        }
        return false;
    }

    int[] queryInstalledUsers(int[] users, boolean installed) {
        int num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                num++;
            }
        }
        int[] res = new int[num];
        num = 0;
        for (int user : users) {
            if (getInstalled(user) == installed) {
                res[num] = user;
                num++;
            }
        }
        return res;
    }

    long getCeDataInode(int userId) {
        return readUserState(userId).ceDataInode;
    }

    void setCeDataInode(long ceDataInode, int userId) {
        modifyUserState(userId).ceDataInode = ceDataInode;
    }

    boolean getStopped(int userId) {
        return readUserState(userId).stopped;
    }

    void setStopped(boolean stop, int userId) {
        modifyUserState(userId).stopped = stop;
    }

    boolean getNotLaunched(int userId) {
        return readUserState(userId).notLaunched;
    }

    void setNotLaunched(boolean stop, int userId) {
        modifyUserState(userId).notLaunched = stop;
    }

    boolean getHidden(int userId) {
        return readUserState(userId).hidden;
    }

    void setHidden(boolean hidden, int userId) {
        modifyUserState(userId).hidden = hidden;
    }

    boolean getSuspended(int userId) {
        return readUserState(userId).suspended;
    }

    void setSuspended(boolean suspended, int userId) {
        modifyUserState(userId).suspended = suspended;
    }

    boolean getInstantApp(int userId) {
        return readUserState(userId).instantApp;
    }

    void setInstantApp(boolean instantApp, int userId) {
        modifyUserState(userId).instantApp = instantApp;
    }

    void setUserState(int userId, long ceDataInode, int enabled, boolean installed, boolean stopped,
            boolean notLaunched, boolean hidden, boolean suspended, boolean instantApp,
            String lastDisableAppCaller, ArraySet<String> enabledComponents,
            ArraySet<String> disabledComponents, int domainVerifState,
            int linkGeneration, int installReason,
            ArraySet<String> protectedComponents, ArraySet<String> visibleComponents) {
        PackageUserState state = modifyUserState(userId);
        state.ceDataInode = ceDataInode;
        state.enabled = enabled;
        state.installed = installed;
        state.stopped = stopped;
        state.notLaunched = notLaunched;
        state.hidden = hidden;
        state.suspended = suspended;
        state.lastDisableAppCaller = lastDisableAppCaller;
        state.enabledComponents = enabledComponents;
        state.disabledComponents = disabledComponents;
        state.domainVerificationStatus = domainVerifState;
        state.appLinkGeneration = linkGeneration;
        state.installReason = installReason;
        state.instantApp = instantApp;
        state.protectedComponents = protectedComponents;
        state.visibleComponents = visibleComponents;
    }

    ArraySet<String> getEnabledComponents(int userId) {
        return readUserState(userId).enabledComponents;
    }

    ArraySet<String> getDisabledComponents(int userId) {
        return readUserState(userId).disabledComponents;
    }

    void setEnabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components;
    }

    void setDisabledComponents(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components;
    }

    void setEnabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).enabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    void setDisabledComponentsCopy(ArraySet<String> components, int userId) {
        modifyUserState(userId).disabledComponents = components != null
                ? new ArraySet<String>(components) : null;
    }

    PackageUserState modifyUserStateComponents(int userId, boolean disabled, boolean enabled) {
        PackageUserState state = modifyUserState(userId);
        if (disabled && state.disabledComponents == null) {
            state.disabledComponents = new ArraySet<String>(1);
        }
        if (enabled && state.enabledComponents == null) {
            state.enabledComponents = new ArraySet<String>(1);
        }
        return state;
    }

    PackageUserState modifyUserStateComponents(int userId) {
        PackageUserState state = modifyUserState(userId);
        if (state.protectedComponents == null) {
           state.protectedComponents = new ArraySet<String>(1);
        }
        if (state.visibleComponents == null) {
            state.visibleComponents = new ArraySet<String>(1);
        }
        return state;
    }

    void addDisabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, true, false).disabledComponents.add(componentClassName);
    }

    void addEnabledComponent(String componentClassName, int userId) {
        modifyUserStateComponents(userId, false, true).enabledComponents.add(componentClassName);
    }

    boolean enableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, false, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents.add(componentClassName);
        return changed;
    }

    boolean disableComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, false);
        boolean changed = state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        changed |= state.disabledComponents.add(componentClassName);
        return changed;
    }

    boolean restoreComponentLPw(String componentClassName, int userId) {
        PackageUserState state = modifyUserStateComponents(userId, true, true);
        boolean changed = state.disabledComponents != null
                ? state.disabledComponents.remove(componentClassName) : false;
        changed |= state.enabledComponents != null
                ? state.enabledComponents.remove(componentClassName) : false;
        return changed;
    }

    int getCurrentEnabledStateLPr(String componentName, int userId) {
        PackageUserState state = readUserState(userId);
        if (state.enabledComponents != null && state.enabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_ENABLED;
        } else if (state.disabledComponents != null
                && state.disabledComponents.contains(componentName)) {
            return COMPONENT_ENABLED_STATE_DISABLED;
        } else {
            return COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }

    boolean protectComponentLPw(String componentClassName, boolean protect, int userId) {
        PackageUserState state = modifyUserStateComponents(userId);
        boolean changed = false;
        if (protect == COMPONENT_VISIBLE_STATUS) {
            changed = state.protectedComponents != null
                    ? state.protectedComponents.remove(componentClassName) : false;
            changed |= state.visibleComponents.add(componentClassName);
        } else {
            changed = state.visibleComponents != null
                    ? state.visibleComponents.remove(componentClassName) : false;
            changed |= state.protectedComponents.add(componentClassName);
        }

        return changed;
    }

    ArraySet<String> getProtectedComponents(int userId) {
        PackageUserState state = modifyUserStateComponents(userId);
        return state.protectedComponents;
    }

    void removeUser(int userId) {
        userState.delete(userId);
    }

    public int[] getNotInstalledUserIds() {
        int count = 0;
        int userStateCount = userState.size();
        for (int i = 0; i < userStateCount; i++) {
            if (userState.valueAt(i).installed == false) {
                count++;
            }
        }
        if (count == 0) return EMPTY_INT_ARRAY;
        int[] excludedUserIds = new int[count];
        int idx = 0;
        for (int i = 0; i < userStateCount; i++) {
            if (userState.valueAt(i).installed == false) {
                excludedUserIds[idx++] = userState.keyAt(i);
            }
        }
        return excludedUserIds;
    }

    IntentFilterVerificationInfo getIntentFilterVerificationInfo() {
        return verificationInfo;
    }

    void setIntentFilterVerificationInfo(IntentFilterVerificationInfo info) {
        verificationInfo = info;
    }

    // Returns a packed value as a long:
    //
    // high 'int'-sized word: link status: undefined/ask/never/always.
    // low 'int'-sized word: relative priority among 'always' results.
    long getDomainVerificationStatusForUser(int userId) {
        PackageUserState state = readUserState(userId);
        long result = (long) state.appLinkGeneration;
        result |= ((long) state.domainVerificationStatus) << 32;
        return result;
    }

    void setDomainVerificationStatusForUser(final int status, int generation, int userId) {
        PackageUserState state = modifyUserState(userId);
        state.domainVerificationStatus = status;
        if (status == PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
            state.appLinkGeneration = generation;
        }
    }

    void clearDomainVerificationStatusForUser(int userId) {
        modifyUserState(userId).domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
    }

    protected void writeUsersInfoToProto(ProtoOutputStream proto, long fieldId) {
        int count = userState.size();
        for (int i = 0; i < count; i++) {
            final long userToken = proto.start(fieldId);
            final int userId = userState.keyAt(i);
            final PackageUserState state = userState.valueAt(i);
            proto.write(PackageProto.UserInfoProto.ID, userId);
            final int installType;
            if (state.instantApp) {
                installType = PackageProto.UserInfoProto.INSTANT_APP_INSTALL;
            } else if (state.installed) {
                installType = PackageProto.UserInfoProto.FULL_APP_INSTALL;
            } else {
                installType = PackageProto.UserInfoProto.NOT_INSTALLED_FOR_USER;
            }
            proto.write(PackageProto.UserInfoProto.INSTALL_TYPE, installType);
            proto.write(PackageProto.UserInfoProto.IS_HIDDEN, state.hidden);
            proto.write(PackageProto.UserInfoProto.IS_SUSPENDED, state.suspended);
            proto.write(PackageProto.UserInfoProto.IS_STOPPED, state.stopped);
            proto.write(PackageProto.UserInfoProto.IS_LAUNCHED, !state.notLaunched);
            proto.write(PackageProto.UserInfoProto.ENABLED_STATE, state.enabled);
            proto.write(
                    PackageProto.UserInfoProto.LAST_DISABLED_APP_CALLER,
                    state.lastDisableAppCaller);
            proto.end(userToken);
        }
    }
}
