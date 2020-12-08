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

package android.app;

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Build;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#sendBroadcast(android.content.Intent)
 * Context.sendBroadcast(Intent)} and related methods.
 * {@hide}
 */
@SystemApi
public class BroadcastOptions {
    private long mTemporaryAppWhitelistDuration;
    private @TempAllowListType int mTemporaryAppWhitelistType;
    private int mMinManifestReceiverApiLevel = 0;
    private int mMaxManifestReceiverApiLevel = Build.VERSION_CODES.CUR_DEVELOPMENT;
    private boolean mDontSendToRestrictedApps = false;
    private boolean mAllowBackgroundActivityStarts;

    /**
     * How long to temporarily put an app on the power allowlist when executing this broadcast
     * to it.
     */
    static final String KEY_TEMPORARY_APP_WHITELIST_DURATION
            = "android:broadcast.temporaryAppWhitelistDuration";

    static final String KEY_TEMPORARY_APP_WHITELIST_TYPE
            = "android:broadcast.temporaryAppWhitelistType";

    /**
     * Corresponds to {@link #setMinManifestReceiverApiLevel}.
     */
    static final String KEY_MIN_MANIFEST_RECEIVER_API_LEVEL
            = "android:broadcast.minManifestReceiverApiLevel";

    /**
     * Corresponds to {@link #setMaxManifestReceiverApiLevel}.
     */
    static final String KEY_MAX_MANIFEST_RECEIVER_API_LEVEL
            = "android:broadcast.maxManifestReceiverApiLevel";

    /**
     * Corresponds to {@link #setDontSendToRestrictedApps}.
     */
    static final String KEY_DONT_SEND_TO_RESTRICTED_APPS =
            "android:broadcast.dontSendToRestrictedApps";

    /**
     * Corresponds to {@link #setBackgroundActivityStartsAllowed}.
     */
    static final String KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS =
            "android:broadcast.allowBackgroundActivityStarts";

    /**
     * Allow the temp allowlist behavior, plus allow foreground service start from background.
     */
    public static final int TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_ALLOWED = 0;
    /**
     * Only allow the temp allowlist behavior, not allow foreground service start from
     * background.
     */
    public static final int TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED = 1;

    /**
     * The list of temp allowlist types.
     * @hide
     */
    @IntDef(flag = true, prefix = { "TEMPORARY_WHITELIST_TYPE_" }, value = {
            TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
            TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_NOT_ALLOWED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TempAllowListType {}

    public static BroadcastOptions makeBasic() {
        BroadcastOptions opts = new BroadcastOptions();
        return opts;
    }

    private BroadcastOptions() {
    }

    /** @hide */
    public BroadcastOptions(Bundle opts) {
        mTemporaryAppWhitelistDuration = opts.getLong(KEY_TEMPORARY_APP_WHITELIST_DURATION);
        mTemporaryAppWhitelistType = opts.getInt(KEY_TEMPORARY_APP_WHITELIST_TYPE);
        mMinManifestReceiverApiLevel = opts.getInt(KEY_MIN_MANIFEST_RECEIVER_API_LEVEL, 0);
        mMaxManifestReceiverApiLevel = opts.getInt(KEY_MAX_MANIFEST_RECEIVER_API_LEVEL,
                Build.VERSION_CODES.CUR_DEVELOPMENT);
        mDontSendToRestrictedApps = opts.getBoolean(KEY_DONT_SEND_TO_RESTRICTED_APPS, false);
        mAllowBackgroundActivityStarts = opts.getBoolean(KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS,
                false);
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power allowlist when this broadcast is being delivered to it.
     * @param duration The duration in milliseconds; 0 means to not place on allowlist.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
            android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
            android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND})
    public void setTemporaryAppWhitelistDuration(long duration) {
        mTemporaryAppWhitelistDuration = duration;
        mTemporaryAppWhitelistType = TEMPORARY_WHITELIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
    }

    /**
     * Set a duration for which the system should temporary place an application on the
     * power allowlist when this broadcast is being delivered to it, specify the temp allowlist
     * type.
     * @param type one of {@link TempAllowListType}
     * @param duration the duration in milliseconds; 0 means to not place on allowlist.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.CHANGE_DEVICE_IDLE_TEMP_WHITELIST,
            android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND,
            android.Manifest.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND})
    public void setTemporaryAppWhitelistDuration(@TempAllowListType int type, long duration) {
        mTemporaryAppWhitelistDuration = duration;
        mTemporaryAppWhitelistType = type;
    }

    /**
     * Return {@link #setTemporaryAppWhitelistDuration}.
     * @hide
     */
    public long getTemporaryAppWhitelistDuration() {
        return mTemporaryAppWhitelistDuration;
    }

    /**
     * Return {@link #mTemporaryAppWhitelistType}.
     * @hide
     */
    public @TempAllowListType int getTemporaryAppWhitelistType() {
        return mTemporaryAppWhitelistType;
    }

    /**
     * Set the minimum target API level of receivers of the broadcast.  If an application
     * is targeting an API level less than this, the broadcast will not be delivered to
     * them.  This only applies to receivers declared in the app's AndroidManifest.xml.
     * @hide
     */
    public void setMinManifestReceiverApiLevel(int apiLevel) {
        mMinManifestReceiverApiLevel = apiLevel;
    }

    /**
     * Return {@link #setMinManifestReceiverApiLevel}.
     * @hide
     */
    public int getMinManifestReceiverApiLevel() {
        return mMinManifestReceiverApiLevel;
    }

    /**
     * Set the maximum target API level of receivers of the broadcast.  If an application
     * is targeting an API level greater than this, the broadcast will not be delivered to
     * them.  This only applies to receivers declared in the app's AndroidManifest.xml.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void setMaxManifestReceiverApiLevel(int apiLevel) {
        mMaxManifestReceiverApiLevel = apiLevel;
    }

    /**
     * Return {@link #setMaxManifestReceiverApiLevel}.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public int getMaxManifestReceiverApiLevel() {
        return mMaxManifestReceiverApiLevel;
    }

    /**
     * Sets whether pending intent can be sent for an application with background restrictions
     * @param dontSendToRestrictedApps if true, pending intent will not be sent for an application
     * with background restrictions. Default value is {@code false}
     */
    public void setDontSendToRestrictedApps(boolean dontSendToRestrictedApps) {
        mDontSendToRestrictedApps = dontSendToRestrictedApps;
    }

    /**
     * @hide
     * @return #setDontSendToRestrictedApps
     */
    public boolean isDontSendToRestrictedApps() {
        return mDontSendToRestrictedApps;
    }

    /**
     * Sets the process will be able to start activities from background for the duration of
     * the broadcast dispatch. Default value is {@code false}
     */
    @RequiresPermission(android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND)
    public void setBackgroundActivityStartsAllowed(boolean allowBackgroundActivityStarts) {
        mAllowBackgroundActivityStarts = allowBackgroundActivityStarts;
    }

    /**
     * @hide
     * @return #setAllowBackgroundActivityStarts
     */
    public boolean allowsBackgroundActivityStarts() {
        return mAllowBackgroundActivityStarts;
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#sendBroadcast(android.content.Intent)
     * Context.sendBroadcast(Intent)} and related methods.
     * Note that the returned Bundle is still owned by the BroadcastOptions
     * object; you must not modify it, but can supply it to the sendBroadcast
     * methods that take an options Bundle.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        if (mTemporaryAppWhitelistDuration > 0) {
            b.putLong(KEY_TEMPORARY_APP_WHITELIST_DURATION, mTemporaryAppWhitelistDuration);
        }
        if (mTemporaryAppWhitelistType != 0) {
            b.putInt(KEY_TEMPORARY_APP_WHITELIST_TYPE, mTemporaryAppWhitelistType);
        }
        if (mMinManifestReceiverApiLevel != 0) {
            b.putInt(KEY_MIN_MANIFEST_RECEIVER_API_LEVEL, mMinManifestReceiverApiLevel);
        }
        if (mMaxManifestReceiverApiLevel != Build.VERSION_CODES.CUR_DEVELOPMENT) {
            b.putInt(KEY_MAX_MANIFEST_RECEIVER_API_LEVEL, mMaxManifestReceiverApiLevel);
        }
        if (mDontSendToRestrictedApps) {
            b.putBoolean(KEY_DONT_SEND_TO_RESTRICTED_APPS, true);
        }
        if (mAllowBackgroundActivityStarts) {
            b.putBoolean(KEY_ALLOW_BACKGROUND_ACTIVITY_STARTS, true);
        }
        return b.isEmpty() ? null : b;
    }
}
