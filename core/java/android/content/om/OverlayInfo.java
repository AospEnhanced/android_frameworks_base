/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.om;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Immutable overlay information about a package. All PackageInfos that
 * represent an overlay package will have a corresponding OverlayInfo.
 *
 * @hide
 */
@SystemApi
public final class OverlayInfo implements Parcelable {

    /** @hide */
    @IntDef(prefix = "STATE_", value = {
            STATE_UNKNOWN,
            STATE_MISSING_TARGET,
            STATE_NO_IDMAP,
            STATE_DISABLED,
            STATE_ENABLED,
            STATE_ENABLED_IMMUTABLE,
            // @Deprecated STATE_TARGET_IS_BEING_REPLACED,
            STATE_OVERLAY_IS_BEING_REPLACED,
    })
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    /**
     * An internal state used as the initial state of an overlay. OverlayInfo
     * objects exposed outside the {@link
     * com.android.server.om.OverlayManagerService} should never have this
     * state.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = -1;

    /**
     * The target package of the overlay is not installed. The overlay cannot be enabled.
     *
     * @hide
     */
    public static final int STATE_MISSING_TARGET = 0;

    /**
     * Creation of idmap file failed (e.g. no matching resources). The overlay
     * cannot be enabled.
     *
     * @hide
     */
    public static final int STATE_NO_IDMAP = 1;

    /**
     * The overlay is currently disabled. It can be enabled.
     *
     * @see IOverlayManager#setEnabled
     * @hide
     */
    public static final int STATE_DISABLED = 2;

    /**
     * The overlay is currently enabled. It can be disabled.
     *
     * @see IOverlayManager#setEnabled
     * @hide
     */
    public static final int STATE_ENABLED = 3;

    /**
     * The target package is currently being upgraded or downgraded; the state
     * will change once the package installation has finished.
     * @hide
     *
     * @deprecated No longer used. Caused invalid transitions from enabled -> upgrading -> enabled,
     * where an update is propagated when nothing has changed. Can occur during --dont-kill
     * installs when code and resources are hot swapped and the Activity should not be relaunched.
     * In all other cases, the process and therefore Activity is killed, so the state loop is
     * irrelevant.
     */
    @Deprecated
    public static final int STATE_TARGET_IS_BEING_REPLACED = 4;

    /**
     * The overlay package is currently being upgraded or downgraded; the state
     * will change once the package installation has finished.
     * @hide
     */
    public static final int STATE_OVERLAY_IS_BEING_REPLACED = 5;

    /**
     * The overlay package is currently enabled because it is marked as
     * 'immutable'. It cannot be disabled but will change state if for instance
     * its target is uninstalled.
     * @hide
     */
    @Deprecated
    public static final int STATE_ENABLED_IMMUTABLE = 6;

    /**
     * Overlay category: theme.
     * <p>
     * Change how Android (including the status bar, dialogs, ...) looks.
     *
     * @hide
     */
    public static final String CATEGORY_THEME = "android.theme";

    /**
     * Package name of the overlay package
     *
     * @hide
     */
    @NonNull
    public final String packageName;

    /**
     * Package name of the target package
     *
     * @hide
     */
    @NonNull
    public final String targetPackageName;

    /**
     * Name of the target overlayable declaration.
     *
     * @hide
     */
    public final String targetOverlayableName;

    /**
     * Category of the overlay package
     *
     * @hide
     */
    public final String category;

    /**
     * Full path to the base APK for this overlay package
     * @hide
     */
    @NonNull
    public final String baseCodePath;

    /**
     * The state of this OverlayInfo as defined by the STATE_* constants in this class.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final @State int state;

    /**
     * User handle for which this overlay applies
     * @hide
     */
    public final int userId;

    /**
     * Priority as configured by {@link com.android.internal.content.om.OverlayConfig}.
     * Not intended to be exposed to 3rd party.
     *
     * @hide
     */
    public final int priority;

    /**
     * isMutable as configured by {@link com.android.internal.content.om.OverlayConfig}.
     * If false, the overlay is unconditionally loaded and cannot be unloaded. Not intended to be
     * exposed to 3rd party.
     *
     * @hide
     */
    public final boolean isMutable;

    /**
     * Create a new OverlayInfo based on source with an updated state.
     *
     * @param source the source OverlayInfo to base the new instance on
     * @param state the new state for the source OverlayInfo
     *
     * @hide
     */
    public OverlayInfo(@NonNull OverlayInfo source, @State int state) {
        this(source.packageName, source.targetPackageName, source.targetOverlayableName,
                source.category, source.baseCodePath, state, source.userId, source.priority,
                source.isMutable);
    }

    /** @hide */
    public OverlayInfo(@NonNull String packageName, @NonNull String targetPackageName,
            @Nullable String targetOverlayableName, @Nullable String category,
            @NonNull String baseCodePath, int state, int userId,
            int priority, boolean isMutable) {
        this.packageName = packageName;
        this.targetPackageName = targetPackageName;
        this.targetOverlayableName = targetOverlayableName;
        this.category = category;
        this.baseCodePath = baseCodePath;
        this.state = state;
        this.userId = userId;
        this.priority = priority;
        this.isMutable = isMutable;
        ensureValidState();
    }

    /** @hide */
    public OverlayInfo(Parcel source) {
        packageName = source.readString();
        targetPackageName = source.readString();
        targetOverlayableName = source.readString();
        category = source.readString();
        baseCodePath = source.readString();
        state = source.readInt();
        userId = source.readInt();
        priority = source.readInt();
        isMutable = source.readBoolean();
        ensureValidState();
    }

    /**
     * Returns package name of the current overlay.
     * @hide
     */
    @SystemApi
    @NonNull
    public String getPackageName() {
        return packageName;
    }

    /**
     * Returns the target package name of the current overlay.
     * @hide
     */
    @SystemApi
    @NonNull
    public String getTargetPackageName() {
        return targetPackageName;
    }

    /**
     * Returns the category of the current overlay.
     * @hide\
     */
    @SystemApi
    @Nullable
    public String getCategory() {
        return category;
    }

    /**
     * Returns user handle for which this overlay applies to.
     * @hide
     */
    @SystemApi
    @UserIdInt
    public int getUserId() {
        return userId;
    }

    /**
     * Returns name of the target overlayable declaration.
     * @hide
     */
    @SystemApi
    @Nullable
    public String getTargetOverlayableName() {
        return targetOverlayableName;
    }

    @SuppressWarnings("ConstantConditions")
    private void ensureValidState() {
        if (packageName == null) {
            throw new IllegalArgumentException("packageName must not be null");
        }
        if (targetPackageName == null) {
            throw new IllegalArgumentException("targetPackageName must not be null");
        }
        if (baseCodePath == null) {
            throw new IllegalArgumentException("baseCodePath must not be null");
        }
        switch (state) {
            case STATE_UNKNOWN:
            case STATE_MISSING_TARGET:
            case STATE_NO_IDMAP:
            case STATE_DISABLED:
            case STATE_ENABLED:
            case STATE_ENABLED_IMMUTABLE:
            case STATE_TARGET_IS_BEING_REPLACED:
            case STATE_OVERLAY_IS_BEING_REPLACED:
                break;
            default:
                throw new IllegalArgumentException("State " + state + " is not a valid state");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeString(targetPackageName);
        dest.writeString(targetOverlayableName);
        dest.writeString(category);
        dest.writeString(baseCodePath);
        dest.writeInt(state);
        dest.writeInt(userId);
        dest.writeInt(priority);
        dest.writeBoolean(isMutable);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<OverlayInfo> CREATOR =
            new Parcelable.Creator<OverlayInfo>() {
        @Override
        public OverlayInfo createFromParcel(Parcel source) {
            return new OverlayInfo(source);
        }

        @Override
        public OverlayInfo[] newArray(int size) {
            return new OverlayInfo[size];
        }
    };

    /**
     * Return true if this overlay is enabled, i.e. should be used to overlay
     * the resources in the target package.
     *
     * Disabled overlay packages are installed but are currently not in use.
     *
     * @return true if the overlay is enabled, else false.
     * @hide
     */
    @SystemApi
    public boolean isEnabled() {
        switch (state) {
            case STATE_ENABLED:
            case STATE_ENABLED_IMMUTABLE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Translate a state to a human readable string. Only intended for
     * debugging purposes.
     *
     * @return a human readable String representing the state.
     * @hide
     */
    public static String stateToString(@State int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "STATE_UNKNOWN";
            case STATE_MISSING_TARGET:
                return "STATE_MISSING_TARGET";
            case STATE_NO_IDMAP:
                return "STATE_NO_IDMAP";
            case STATE_DISABLED:
                return "STATE_DISABLED";
            case STATE_ENABLED:
                return "STATE_ENABLED";
            case STATE_ENABLED_IMMUTABLE:
                return "STATE_ENABLED_IMMUTABLE";
            case STATE_TARGET_IS_BEING_REPLACED:
                return "STATE_TARGET_IS_BEING_REPLACED";
            case STATE_OVERLAY_IS_BEING_REPLACED:
                return "STATE_OVERLAY_IS_BEING_REPLACED";
            default:
                return "<unknown state>";
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + userId;
        result = prime * result + state;
        result = prime * result + ((packageName == null) ? 0 : packageName.hashCode());
        result = prime * result + ((targetPackageName == null) ? 0 : targetPackageName.hashCode());
        result = prime * result + ((targetOverlayableName == null) ? 0
                : targetOverlayableName.hashCode());
        result = prime * result + ((category == null) ? 0 : category.hashCode());
        result = prime * result + ((baseCodePath == null) ? 0 : baseCodePath.hashCode());
        return result;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        OverlayInfo other = (OverlayInfo) obj;
        if (userId != other.userId) {
            return false;
        }
        if (state != other.state) {
            return false;
        }
        if (!packageName.equals(other.packageName)) {
            return false;
        }
        if (!targetPackageName.equals(other.targetPackageName)) {
            return false;
        }
        if (!Objects.equals(targetOverlayableName, other.targetOverlayableName)) {
            return false;
        }
        if (!Objects.equals(category, other.category)) {
            return false;
        }
        if (!baseCodePath.equals(other.baseCodePath)) {
            return false;
        }
        return true;
    }

    @NonNull
    @Override
    public String toString() {
        return "OverlayInfo { overlay=" + packageName + ", targetPackage=" + targetPackageName
                + ((targetOverlayableName == null) ? ""
                : ", targetOverlayable=" + targetOverlayableName)
                + ", state=" + state + " (" + stateToString(state) + "), userId=" + userId + " }";
    }
}
