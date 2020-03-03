/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util;

import android.annotation.TestApi;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Util class to get feature flag information.
 *
 * @hide
 */
@TestApi
public class FeatureFlagUtils {

    public static final String FFLAG_PREFIX = "sys.fflag.";
    public static final String FFLAG_OVERRIDE_PREFIX = FFLAG_PREFIX + "override.";
    public static final String PERSIST_PREFIX = "persist." + FFLAG_OVERRIDE_PREFIX;
    public static final String SEAMLESS_TRANSFER = "settings_seamless_transfer";
    public static final String HEARING_AID_SETTINGS = "settings_bluetooth_hearing_aid";
    public static final String SCREENRECORD_LONG_PRESS = "settings_screenrecord_long_press";
    public static final String DYNAMIC_SYSTEM = "settings_dynamic_system";
    public static final String SETTINGS_WIFITRACKER2 = "settings_wifitracker2";
    public static final String SETTINGS_FUSE_FLAG = "settings_fuse";
    public static final String NOTIF_CONVO_BYPASS_SHORTCUT_REQ =
            "settings_notif_convo_bypass_shortcut_req";
    /** @hide */
    public static final String SETTINGS_DO_NOT_RESTORE_PRESERVED =
            "settings_do_not_restore_preserved";
    /** @hide */
    public static final String SETTINGS_SCHEDULES_FLAG = "settings_schedules";

    private static final Map<String, String> DEFAULT_FLAGS;

    static {
        DEFAULT_FLAGS = new HashMap<>();
        DEFAULT_FLAGS.put("settings_audio_switcher", "true");
        DEFAULT_FLAGS.put("settings_systemui_theme", "true");
        DEFAULT_FLAGS.put(SETTINGS_FUSE_FLAG, "true");
        DEFAULT_FLAGS.put(DYNAMIC_SYSTEM, "false");
        DEFAULT_FLAGS.put(SEAMLESS_TRANSFER, "false");
        DEFAULT_FLAGS.put(HEARING_AID_SETTINGS, "false");
        DEFAULT_FLAGS.put(SCREENRECORD_LONG_PRESS, "false");
        DEFAULT_FLAGS.put("settings_wifi_details_datausage_header", "false");
        DEFAULT_FLAGS.put("settings_skip_direction_mutable", "true");
        DEFAULT_FLAGS.put(SETTINGS_WIFITRACKER2, "true");
        DEFAULT_FLAGS.put("settings_controller_loading_enhancement", "false");
        DEFAULT_FLAGS.put("settings_conditionals", "false");
        DEFAULT_FLAGS.put(NOTIF_CONVO_BYPASS_SHORTCUT_REQ, "true");
        // Disabled by default until b/148278926 is resolved. This flags guards a feature
        // introduced in R and will be removed in the next release (b/148367230).
        DEFAULT_FLAGS.put(SETTINGS_DO_NOT_RESTORE_PRESERVED, "false");

        DEFAULT_FLAGS.put("settings_tether_all_in_one", "false");
        DEFAULT_FLAGS.put(SETTINGS_SCHEDULES_FLAG, "false");
        DEFAULT_FLAGS.put("settings_contextual_home2", "false");
    }

    /**
     * Whether or not a flag is enabled.
     *
     * @param feature the flag name
     * @return true if the flag is enabled (either by default in system, or override by user)
     */
    public static boolean isEnabled(Context context, String feature) {
        // Override precedence:
        // Settings.Global -> sys.fflag.override.* -> static list

        // Step 1: check if feature flag is set in Settings.Global.
        String value;
        if (context != null) {
            value = Settings.Global.getString(context.getContentResolver(), feature);
            if (!TextUtils.isEmpty(value)) {
                return Boolean.parseBoolean(value);
            }
        }

        // Step 2: check if feature flag has any override. Flag name: sys.fflag.override.<feature>
        value = SystemProperties.get(FFLAG_OVERRIDE_PREFIX + feature);
        if (!TextUtils.isEmpty(value)) {
            return Boolean.parseBoolean(value);
        }
        // Step 3: check if feature flag has any default value.
        value = getAllFeatureFlags().get(feature);
        return Boolean.parseBoolean(value);
    }

    /**
     * Override feature flag to new state.
     */
    public static void setEnabled(Context context, String feature, boolean enabled) {
        SystemProperties.set(FFLAG_OVERRIDE_PREFIX + feature, enabled ? "true" : "false");
    }

    /**
     * Returns all feature flags in their raw form.
     */
    public static Map<String, String> getAllFeatureFlags() {
        return DEFAULT_FLAGS;
    }
}
