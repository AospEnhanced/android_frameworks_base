/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class holding the properties of a user that derive mostly from its user type.
 */
public final class UserProperties implements Parcelable {
    private static final String LOG_TAG = UserProperties.class.getSimpleName();

    // Attribute strings for reading/writing properties to/from XML.
    private static final String ATTR_SHOW_IN_LAUNCHER = "showInLauncher";
    private static final String ATTR_START_WITH_PARENT = "startWithParent";
    private static final String ATTR_SHOW_IN_SETTINGS = "showInSettings";
    private static final String ATTR_INHERIT_DEVICE_POLICY = "inheritDevicePolicy";

    /** Index values of each property (to indicate whether they are present in this object). */
    @IntDef(prefix = "INDEX_", value = {
            INDEX_SHOW_IN_LAUNCHER,
            INDEX_START_WITH_PARENT,
            INDEX_SHOW_IN_SETTINGS,
            INDEX_INHERIT_DEVICE_POLICY,
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface PropertyIndex {
    }
    private static final int INDEX_SHOW_IN_LAUNCHER = 0;
    private static final int INDEX_START_WITH_PARENT = 1;
    private static final int INDEX_SHOW_IN_SETTINGS = 2;
    private static final int INDEX_INHERIT_DEVICE_POLICY = 3;
    /** A bit set, mapping each PropertyIndex to whether it is present (1) or absent (0). */
    private long mPropertiesPresent = 0;


    /**
     * Possible values for whether or how to show this user in the Launcher.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_LAUNCHER_", value = {
            SHOW_IN_LAUNCHER_WITH_PARENT,
            SHOW_IN_LAUNCHER_SEPARATE,
            SHOW_IN_LAUNCHER_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInLauncher {
    }
    /**
     * Suggests that the launcher should show this user's apps in the main tab.
     * That is, either this user is a full user, so its apps should be presented accordingly, or, if
     * this user is a profile, then its apps should be shown alongside its parent's apps.
     */
    public static final int SHOW_IN_LAUNCHER_WITH_PARENT = 0;
    /**
     * Suggests that the launcher should show this user's apps, but separately from the apps of this
     * user's parent.
     */
    public static final int SHOW_IN_LAUNCHER_SEPARATE = 1;
    /**
     * Suggests that the launcher should not show this user.
     */
    public static final int SHOW_IN_LAUNCHER_NO = 2;

    /**
     * Possible values for whether or how to show this user in the Settings app.
     * @hide
     */
    @IntDef(prefix = "SHOW_IN_SETTINGS_", value = {
            SHOW_IN_SETTINGS_WITH_PARENT,
            SHOW_IN_SETTINGS_SEPARATE,
            SHOW_IN_SETTINGS_NO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowInSettings {
    }
    /**
     * Suggests that the Settings app should show this user's apps in the main tab.
     * That is, either this user is a full user, so its apps should be presented accordingly, or, if
     * this user is a profile, then its apps should be shown alongside its parent's apps.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_WITH_PARENT = 0;
    /**
     * Suggests that the Settings app should show this user's apps, but separately from the apps of
     * this user's parent.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_SEPARATE = 1;
    /**
     * Suggests that the Settings app should not show this user.
     * @hide
     */
    public static final int SHOW_IN_SETTINGS_NO = 2;

    /**
     * Possible values for whether (and from whom) to inherit select user restrictions
     * or device policies.
     *
     * @hide
     */
    @IntDef(prefix = "INHERIT_DEVICE_POLICY", value = {
            INHERIT_DEVICE_POLICY_NO,
            INHERIT_DEVICE_POLICY_FROM_PARENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InheritDevicePolicy {
    }
    /**
     * Suggests that the given user profile should not inherit user restriction or device policy
     * from any other user. This is the default value for any new user type.
     * @hide
     */
    public static final int INHERIT_DEVICE_POLICY_NO = 0;
    /**
     * Suggests that the given user profile should inherit select user restrictions or
     * device policies from its parent profile.
     *
     *<p> All the user restrictions and device policies would be not propagated to the profile
     * with this property value. The {(TODO:b/256978256) @link DevicePolicyEngine}
     * uses this property to determine and propagate only select ones to the given profile.
     *
     * @hide
     */
    public static final int INHERIT_DEVICE_POLICY_FROM_PARENT = 1;

    /**
     * Reference to the default user properties for this user's user type.
     * <li>If non-null, then any absent property will use the default property from here instead.
     * <li>If null, then any absent property indicates that the caller lacks permission to see it,
     *          so attempting to get that property will trigger a SecurityException.
     */
    private final @Nullable UserProperties mDefaultProperties;

    /**
     * Creates a UserProperties (intended for the SystemServer) that stores a reference to the given
     * default properties, which it uses for any property not subsequently set.
     * @hide
     */
    public UserProperties(@NonNull UserProperties defaultProperties) {
        mDefaultProperties = defaultProperties;
        mPropertiesPresent = 0;
    }

    /**
     * Copies the given UserProperties, excluding any information that doesn't satisfy the specified
     * permissions.
     * Can only be used on the original version (one that won't throw on permission errors).
     * Note that, internally, this does not perform an exact copy.
     * @hide
     */
    public UserProperties(UserProperties orig,
            boolean exposeAllFields,
            boolean hasManagePermission,
            boolean hasQueryOrManagePermission) {

        if (orig.mDefaultProperties == null) {
            throw new IllegalArgumentException("Attempting to copy a non-original UserProperties.");
        }

        this.mDefaultProperties = null;

        // Insert each setter into the following hierarchy based on its permission requirements.
        // NOTE: Copy each property using getters to ensure default values are copied if needed.
        if (exposeAllFields) {
            // Add items that require exposeAllFields to be true (strictest permission level).
            setStartWithParent(orig.getStartWithParent());
            setInheritDevicePolicy(orig.getInheritDevicePolicy());
        }
        if (hasManagePermission) {
            // Add items that require MANAGE_USERS or stronger.
            setShowInSettings(orig.getShowInSettings());
        }
        if (hasQueryOrManagePermission) {
            // Add items that require QUERY_USERS or stronger.
        }
        // Add items that have no permission requirements at all.
        setShowInLauncher(orig.getShowInLauncher());
    }

    /**
     * Indicates that the given property is being stored explicitly in this object.
     * If false, it means that either
     * <li>the default property for the user type should be used instead (for SystemServer callers)
     * <li>the caller lacks permission to see this property (for all other callers)
     */
    private boolean isPresent(@PropertyIndex long index) {
        return (mPropertiesPresent & (1L << index)) != 0;
    }

    /** Indicates that the given property is henceforth being explicitly stored in this object. */
    private void setPresent(@PropertyIndex long index) {
        mPropertiesPresent |= (1L << index);
    }

    /** @hide Returns the internal mPropertiesPresent value. Only for testing purposes. */
    @VisibleForTesting
    public long getPropertiesPresent() {
        return mPropertiesPresent;
    }

    /**
     * Returns whether, and how, a user should be shown in the Launcher.
     * This is generally inapplicable for non-profile users.
     *
     * Possible return values include
     *    {@link #SHOW_IN_LAUNCHER_WITH_PARENT}},
     *    {@link #SHOW_IN_LAUNCHER_SEPARATE},
     *    and {@link #SHOW_IN_LAUNCHER_NO}.
     *
     * @return whether, and how, a profile should be shown in the Launcher.
     */
    public @ShowInLauncher int getShowInLauncher() {
        if (isPresent(INDEX_SHOW_IN_LAUNCHER)) return mShowInLauncher;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInLauncher;
        throw new SecurityException("You don't have permission to query showInLauncher");
    }
    /** @hide */
    public void setShowInLauncher(@ShowInLauncher int val) {
        this.mShowInLauncher = val;
        setPresent(INDEX_SHOW_IN_LAUNCHER);
    }
    private @ShowInLauncher int mShowInLauncher;

    /**
     * Returns whether, and how, a user should be shown in the Settings app.
     * This is generally inapplicable for non-profile users.
     *
     * Possible return values include
     *    {@link #SHOW_IN_SETTINGS_WITH_PARENT}},
     *    {@link #SHOW_IN_SETTINGS_SEPARATE},
     *    and {@link #SHOW_IN_SETTINGS_NO}.
     *
     * <p> The caller must have {@link android.Manifest.permission#MANAGE_USERS} to query this
     * property.
     *
     * @return whether, and how, a profile should be shown in the Settings.
     * @hide
     */
    public @ShowInSettings int getShowInSettings() {
        if (isPresent(INDEX_SHOW_IN_SETTINGS)) return mShowInSettings;
        if (mDefaultProperties != null) return mDefaultProperties.mShowInSettings;
        throw new SecurityException("You don't have permission to query mShowInSettings");
    }
    /** @hide */
    public void setShowInSettings(@ShowInSettings int val) {
        this.mShowInSettings = val;
        setPresent(INDEX_SHOW_IN_SETTINGS);
    }
    private @ShowInSettings int mShowInSettings;

    /**
     * Returns whether a profile should be started when its parent starts (unless in quiet mode).
     * This only applies for users that have parents (i.e. for profiles).
     * @hide
     */
    public boolean getStartWithParent() {
        if (isPresent(INDEX_START_WITH_PARENT)) return mStartWithParent;
        if (mDefaultProperties != null) return mDefaultProperties.mStartWithParent;
        throw new SecurityException("You don't have permission to query startWithParent");
    }
    /** @hide */
    public void setStartWithParent(boolean val) {
        this.mStartWithParent = val;
        setPresent(INDEX_START_WITH_PARENT);
    }
    private boolean mStartWithParent;

    /**
     * Return whether, and how, select user restrictions or device policies should be inherited
     * from other user.
     *
     * Possible return values include
     * {@link #INHERIT_DEVICE_POLICY_FROM_PARENT} or {@link #INHERIT_DEVICE_POLICY_NO}
     *
     * @hide
     */
    public @InheritDevicePolicy int getInheritDevicePolicy() {
        if (isPresent(INDEX_INHERIT_DEVICE_POLICY)) return mInheritDevicePolicy;
        if (mDefaultProperties != null) return mDefaultProperties.mInheritDevicePolicy;
        throw new SecurityException("You don't have permission to query inheritDevicePolicy");
    }
    /** @hide */
    public void setInheritDevicePolicy(@InheritDevicePolicy int val) {
        this.mInheritDevicePolicy = val;
        setPresent(INDEX_INHERIT_DEVICE_POLICY);
    }
    private @InheritDevicePolicy int mInheritDevicePolicy;

    @Override
    public String toString() {
        // Please print in increasing order of PropertyIndex.
        return "UserProperties{"
                + "mPropertiesPresent=" + Long.toBinaryString(mPropertiesPresent)
                + ", mShowInLauncher=" + getShowInLauncher()
                + ", mStartWithParent=" + getStartWithParent()
                + ", mShowInSettings=" + getShowInSettings()
                + ", mInheritDevicePolicy=" + getInheritDevicePolicy()
                + "}";
    }

    /**
     * Print the UserProperties to the given PrintWriter.
     * @hide
     */
    public void println(PrintWriter pw, String prefix) {
        // Please print in increasing order of PropertyIndex.
        pw.println(prefix + "UserProperties:");
        pw.println(prefix + "    mPropertiesPresent=" + Long.toBinaryString(mPropertiesPresent));
        pw.println(prefix + "    mShowInLauncher=" + getShowInLauncher());
        pw.println(prefix + "    mStartWithParent=" + getStartWithParent());
        pw.println(prefix + "    mShowInSettings=" + getShowInSettings());
        pw.println(prefix + "    mInheritDevicePolicy=" + getInheritDevicePolicy());
    }

    /**
     * Reads in a UserProperties from an xml file, for use by the SystemServer.
     *
     * The serializer should already be inside a tag from which to read the user properties.
     *
     * @param defaultUserPropertiesReference the default UserProperties to use for this user type.
     * @see #writeToXml
     * @hide
     */
    public UserProperties(
            TypedXmlPullParser parser,
            @NonNull UserProperties defaultUserPropertiesReference)
            throws IOException, XmlPullParserException {

        this(defaultUserPropertiesReference);
        updateFromXml(parser);
    }

    /**
     * Parses the given xml file and updates this UserProperties with its data.
     * I.e., if a piece of data is present in the xml, it will overwrite whatever was
     * previously stored in this UserProperties.
     * @hide
     */
    public void updateFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {

        final int attributeCount = parser.getAttributeCount();
        for (int i = 0; i < attributeCount; i++) {
            final String attributeName = parser.getAttributeName(i);
            switch(attributeName) {
                case ATTR_SHOW_IN_LAUNCHER:
                    setShowInLauncher(parser.getAttributeInt(i));
                    break;
                case ATTR_START_WITH_PARENT:
                    setStartWithParent(parser.getAttributeBoolean(i));
                    break;
                case ATTR_SHOW_IN_SETTINGS:
                    setShowInSettings(parser.getAttributeInt(i));
                    break;
                case ATTR_INHERIT_DEVICE_POLICY:
                    setInheritDevicePolicy(parser.getAttributeInt(i));
                    break;
                default:
                    Slog.w(LOG_TAG, "Skipping unknown property " + attributeName);
            }
        }
    }

    /**
     * Writes the UserProperties, as used by the SystemServer, to the xml file.
     *
     * The serializer should already be inside a tag in which to write the user properties.
     *
     * @see  #UserProperties(TypedXmlPullParser, UserProperties)
     * @hide
     */
    public void writeToXml(TypedXmlSerializer serializer)
            throws IOException, XmlPullParserException {

        if (isPresent(INDEX_SHOW_IN_LAUNCHER)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_LAUNCHER, mShowInLauncher);
        }
        if (isPresent(INDEX_START_WITH_PARENT)) {
            serializer.attributeBoolean(null, ATTR_START_WITH_PARENT, mStartWithParent);
        }
        if (isPresent(INDEX_SHOW_IN_SETTINGS)) {
            serializer.attributeInt(null, ATTR_SHOW_IN_SETTINGS, mShowInSettings);
        }
        if (isPresent(INDEX_INHERIT_DEVICE_POLICY)) {
            serializer.attributeInt(null, ATTR_INHERIT_DEVICE_POLICY,
                    mInheritDevicePolicy);
        }
    }

    // For use only with an object that has already had any permission-lacking fields stripped out.
    @Override
    public void writeToParcel(@NonNull Parcel dest, int parcelableFlags) {
        dest.writeLong(mPropertiesPresent);
        dest.writeInt(mShowInLauncher);
        dest.writeBoolean(mStartWithParent);
        dest.writeInt(mShowInSettings);
        dest.writeInt(mInheritDevicePolicy);
    }

    /**
     * Reads a UserProperties object from the parcel.
     * Not suitable for the canonical SystemServer version since it lacks mDefaultProperties.
      */
    private UserProperties(@NonNull Parcel source) {
        mDefaultProperties = null;

        mPropertiesPresent = source.readLong();
        mShowInLauncher = source.readInt();
        mStartWithParent = source.readBoolean();
        mShowInSettings = source.readInt();
        mInheritDevicePolicy = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<UserProperties> CREATOR
            = new Parcelable.Creator<UserProperties>() {
        public UserProperties createFromParcel(Parcel source) {
            return new UserProperties(source);
        }
        public UserProperties[] newArray(int size) {
            return new UserProperties[size];
        }
    };

    /**
     * Builder for the SystemServer's {@link UserProperties}; see that class for documentation.
     * Intended for building default values (and so all properties are present in the built object).
     * @hide
     */
    public static final class Builder {
        // UserProperties fields and their default values.
        private @ShowInLauncher int mShowInLauncher = SHOW_IN_LAUNCHER_WITH_PARENT;
        private boolean mStartWithParent = false;
        private @ShowInSettings int mShowInSettings = SHOW_IN_SETTINGS_WITH_PARENT;
        private @InheritDevicePolicy int mInheritDevicePolicy = INHERIT_DEVICE_POLICY_NO;

        public Builder setShowInLauncher(@ShowInLauncher int showInLauncher) {
            mShowInLauncher = showInLauncher;
            return this;
        }

        public Builder setStartWithParent(boolean startWithParent) {
            mStartWithParent = startWithParent;
            return this;
        }

        /** Sets the value for {@link #mShowInSettings} */
        public Builder setShowInSettings(@ShowInSettings int showInSettings) {
            mShowInSettings = showInSettings;
            return this;
        }

        /** Sets the value for {@link #mInheritDevicePolicy}*/
        public Builder setInheritDevicePolicy(
                @InheritDevicePolicy int inheritRestrictionsDevicePolicy) {
            mInheritDevicePolicy = inheritRestrictionsDevicePolicy;
            return this;
        }

        /** Builds a UserProperties object with *all* values populated. */
        public UserProperties build() {
            return new UserProperties(
                    mShowInLauncher,
                    mStartWithParent,
                    mShowInSettings,
                    mInheritDevicePolicy);
        }
    } // end Builder

    /** Creates a UserProperties with the given properties. Intended for building default values. */
    private UserProperties(
            @ShowInLauncher int showInLauncher,
            boolean startWithParent,
            @ShowInSettings int showInSettings,
            @InheritDevicePolicy int inheritDevicePolicy) {

        mDefaultProperties = null;
        setShowInLauncher(showInLauncher);
        setStartWithParent(startWithParent);
        setShowInSettings(showInSettings);
        setInheritDevicePolicy(inheritDevicePolicy);
    }
}
