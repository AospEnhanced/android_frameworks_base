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

package android.provider.settings.backup;

import android.compat.annotation.UnsupportedAppUsage;
import android.provider.Settings;

/** Information relating to the Secure settings which should be backed up */
public class SecureSettings {

    /**
     * NOTE: Settings are backed up and restored in the order they appear
     *       in this array. If you have one setting depending on another,
     *       make sure that they are ordered appropriately.
     */
    @UnsupportedAppUsage
    public static final String[] SETTINGS_TO_BACKUP = {
        Settings.Secure.BUGREPORT_IN_POWER_MENU,
        Settings.Secure.ALLOW_MOCK_LOCATION,
        Settings.Secure.USB_MASS_STORAGE_ENABLED,                           // moved to global
        Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
        Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER,
        Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
        Settings.Secure.ADAPTIVE_CHARGING_ENABLED,
        Settings.Secure.ADAPTIVE_SLEEP,
        Settings.Secure.CAMERA_AUTOROTATE,
        Settings.Secure.AUTOFILL_SERVICE,
        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        Settings.Secure.ENABLED_ACCESSIBILITY_AUDIO_DESCRIPTION_BY_DEFAULT,
        Settings.Secure.ENABLED_VR_LISTENERS,
        Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
        Settings.Secure.TOUCH_EXPLORATION_ENABLED,
        Settings.Secure.ACCESSIBILITY_ENABLED,
        Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
        Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
        Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
        Settings.Secure.ACCESSIBILITY_SHORTCUT_ON_LOCK_SCREEN,
        Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED,
        Settings.Secure.CONTRAST_LEVEL,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_PRESET,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_ENABLED,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_LOCALE,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_BACKGROUND_COLOR,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_FOREGROUND_COLOR,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_TYPE,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_EDGE_COLOR,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_TYPEFACE,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_FONT_SCALE,
        Settings.Secure.ACCESSIBILITY_CAPTIONING_WINDOW_COLOR,
        Settings.Secure.FONT_WEIGHT_ADJUSTMENT,
        Settings.Secure.REDUCE_BRIGHT_COLORS_LEVEL,
        Settings.Secure.REDUCE_BRIGHT_COLORS_PERSIST_ACROSS_REBOOTS,
        Settings.Secure.TTS_DEFAULT_RATE,
        Settings.Secure.TTS_DEFAULT_PITCH,
        Settings.Secure.TTS_DEFAULT_SYNTH,
        Settings.Secure.TTS_ENABLED_PLUGINS,
        Settings.Secure.TTS_DEFAULT_LOCALE,
        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
        Settings.Secure.ACCESSIBILITY_BOUNCE_KEYS,
        Settings.Secure.ACCESSIBILITY_SLOW_KEYS,
        Settings.Secure.ACCESSIBILITY_STICKY_KEYS,
        Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,            // moved to global
        Settings.Secure.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,               // moved to global
        Settings.Secure.WIFI_NUM_OPEN_NETWORKS_KEPT,                        // moved to global
        Settings.Secure.MOUNT_PLAY_NOTIFICATION_SND,
        Settings.Secure.MOUNT_UMS_AUTOSTART,
        Settings.Secure.MOUNT_UMS_PROMPT,
        Settings.Secure.MOUNT_UMS_NOTIFY_ENABLED,
        Settings.Secure.DOUBLE_TAP_TO_WAKE,
        Settings.Secure.WAKE_GESTURE_ENABLED,
        Settings.Secure.LONG_PRESS_TIMEOUT,
        Settings.Secure.KEY_REPEAT_TIMEOUT_MS,
        Settings.Secure.KEY_REPEAT_DELAY_MS,
        Settings.Secure.CAMERA_GESTURE_DISABLED,
        Settings.Secure.ACCESSIBILITY_AUTOCLICK_ENABLED,
        Settings.Secure.ACCESSIBILITY_AUTOCLICK_DELAY,
        Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
        Settings.Secure.PREFERRED_TTY_MODE,
        Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED,
        Settings.Secure.TTY_MODE_ENABLED,
        Settings.Secure.RTT_CALLING_MODE,
        Settings.Secure.INCALL_POWER_BUTTON_BEHAVIOR,
        Settings.Secure.MINIMAL_POST_PROCESSING_ALLOWED,
        Settings.Secure.MATCH_CONTENT_FRAME_RATE,
        Settings.Secure.NIGHT_DISPLAY_CUSTOM_START_TIME,
        Settings.Secure.NIGHT_DISPLAY_CUSTOM_END_TIME,
        Settings.Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE,
        Settings.Secure.NIGHT_DISPLAY_AUTO_MODE,
        Settings.Secure.DISPLAY_WHITE_BALANCE_ENABLED,
        Settings.Secure.SYNC_PARENT_SOUNDS,
        Settings.Secure.CAMERA_DOUBLE_TWIST_TO_FLIP_ENABLED,
        Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED,
        Settings.Secure.SYSTEM_NAVIGATION_KEYS_ENABLED,
        // ACCESSIBILITY_QS_TARGETS needs to be restored after ENABLED_ACCESSIBILITY_SERVICES
        // but before QS_TILES
        Settings.Secure.ACCESSIBILITY_QS_TARGETS,
        Settings.Secure.QS_TILES,
        Settings.Secure.QS_AUTO_ADDED_TILES,
        Settings.Secure.CONTROLS_ENABLED,
        Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT,
        Settings.Secure.DOZE_ENABLED,
        Settings.Secure.DOZE_ALWAYS_ON,
        Settings.Secure.DOZE_PICK_UP_GESTURE,
        Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
        Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
        Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
        Settings.Secure.AUTOMATIC_STORAGE_MANAGER_DAYS_TO_RETAIN,
        Settings.Secure.FACE_UNLOCK_KEYGUARD_ENABLED,
        Settings.Secure.SHOW_MEDIA_WHEN_BYPASSING,
        Settings.Secure.FACE_UNLOCK_DISMISSES_KEYGUARD,
        Settings.Secure.FACE_UNLOCK_APP_ENABLED,
        Settings.Secure.FACE_UNLOCK_ALWAYS_REQUIRE_CONFIRMATION,
        Settings.Secure.FINGERPRINT_SIDE_FPS_KG_POWER_WINDOW,
        Settings.Secure.FINGERPRINT_SIDE_FPS_BP_POWER_WINDOW,
        Settings.Secure.FINGERPRINT_SIDE_FPS_ENROLL_TAP_WINDOW,
        Settings.Secure.FINGERPRINT_SIDE_FPS_AUTH_DOWNTIME,
        Settings.Secure.SFPS_PERFORMANT_AUTH_ENABLED,
        Settings.Secure.ACTIVE_UNLOCK_ON_WAKE,
        Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT,
        Settings.Secure.ACTIVE_UNLOCK_ON_BIOMETRIC_FAIL,
        Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ERRORS,
        Settings.Secure.ACTIVE_UNLOCK_ON_FACE_ACQUIRE_INFO,
        Settings.Secure.ACTIVE_UNLOCK_ON_UNLOCK_INTENT_WHEN_BIOMETRIC_ENROLLED,
        Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_CONSIDERED_UNLOCK_INTENTS,
        Settings.Secure.ACTIVE_UNLOCK_WAKEUPS_TO_FORCE_DISMISS_KEYGUARD,
        Settings.Secure.VR_DISPLAY_MODE,
        Settings.Secure.NOTIFICATION_BADGING,
        Settings.Secure.NOTIFICATION_DISMISS_RTL,
        Settings.Secure.SCREENSAVER_ENABLED,
        Settings.Secure.SCREENSAVER_COMPONENTS,
        Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
        Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
        Settings.Secure.SCREENSAVER_HOME_CONTROLS_ENABLED,
        Settings.Secure.SHOW_FIRST_CRASH_DIALOG_DEV_OPTION,
        Settings.Secure.VOLUME_DIALOG_DISMISS_TIMEOUT,
        Settings.Secure.VOLUME_HUSH_GESTURE,
        Settings.Secure.MANUAL_RINGER_TOGGLE_COUNT,
        Settings.Secure.LOW_POWER_WARNING_ACKNOWLEDGED,
        Settings.Secure.EXTRA_LOW_POWER_WARNING_ACKNOWLEDGED,
        Settings.Secure.HUSH_GESTURE_USED,
        Settings.Secure.IN_CALL_NOTIFICATION_ENABLED,
        Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS,
        Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
        Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS,
        Settings.Secure.LOCK_SCREEN_SHOW_SILENT_NOTIFICATIONS,
        Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
        Settings.Secure.SHOW_NOTIFICATION_SNOOZE,
        Settings.Secure.NOTIFICATION_HISTORY_ENABLED,
        Settings.Secure.ZEN_DURATION,
        Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION,
        Settings.Secure.SHOW_ZEN_SETTINGS_SUGGESTION,
        Settings.Secure.ZEN_SETTINGS_UPDATED,
        Settings.Secure.ZEN_SETTINGS_SUGGESTION_VIEWED,
        Settings.Secure.CHARGING_SOUNDS_ENABLED,
        Settings.Secure.CHARGING_VIBRATION_ENABLED,
        Settings.Secure.ACCESSIBILITY_NON_INTERACTIVE_UI_TIMEOUT_MS,
        Settings.Secure.ACCESSIBILITY_INTERACTIVE_UI_TIMEOUT_MS,
        Settings.Secure.UI_NIGHT_MODE,
        Settings.Secure.UI_NIGHT_MODE_CUSTOM_TYPE,
        Settings.Secure.DARK_THEME_CUSTOM_START_TIME,
        Settings.Secure.DARK_THEME_CUSTOM_END_TIME,
        Settings.Secure.SKIP_DIRECTION,
        Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
        Settings.Secure.BACK_GESTURE_INSET_SCALE_LEFT,
        Settings.Secure.BACK_GESTURE_INSET_SCALE_RIGHT,
        Settings.Secure.NAVIGATION_MODE,
        Settings.Secure.TRACKPAD_GESTURE_BACK_ENABLED,
        Settings.Secure.TRACKPAD_GESTURE_HOME_ENABLED,
        Settings.Secure.TRACKPAD_GESTURE_OVERVIEW_ENABLED,
        Settings.Secure.TRACKPAD_GESTURE_NOTIFICATION_ENABLED,
        Settings.Secure.TRACKPAD_GESTURE_QUICK_SWITCH_ENABLED,
        Settings.Secure.SKIP_GESTURE_COUNT,
        Settings.Secure.SKIP_TOUCH_COUNT,
        Settings.Secure.SILENCE_ALARMS_GESTURE_COUNT,
        Settings.Secure.SILENCE_CALL_GESTURE_COUNT,
        Settings.Secure.SILENCE_TIMER_GESTURE_COUNT,
        Settings.Secure.SILENCE_ALARMS_TOUCH_COUNT,
        Settings.Secure.SILENCE_CALL_TOUCH_COUNT,
        Settings.Secure.SILENCE_TIMER_TOUCH_COUNT,
        Settings.Secure.DARK_MODE_DIALOG_SEEN,
        Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED,
        Settings.Secure.AWARE_LOCK_ENABLED,
        Settings.Secure.AWARE_TAP_PAUSE_GESTURE_COUNT,
        Settings.Secure.AWARE_TAP_PAUSE_TOUCH_COUNT,
        Settings.Secure.PEOPLE_STRIP,
        Settings.Secure.MEDIA_CONTROLS_RESUME,
        Settings.Secure.MEDIA_CONTROLS_RECOMMENDATION,
        Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
        Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_FOLLOW_TYPING_ENABLED,
        Settings.Secure.ONE_HANDED_MODE_ACTIVATED,
        Settings.Secure.ONE_HANDED_MODE_ENABLED,
        Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
        Settings.Secure.TAPS_APP_TO_EXIT,
        Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
        Settings.Secure.EMERGENCY_GESTURE_ENABLED,
        Settings.Secure.EMERGENCY_GESTURE_SOUND_ENABLED,
        Settings.Secure.ADAPTIVE_CONNECTIVITY_ENABLED,
        Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS,
        Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT,
        Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_ICON_TYPE,
        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_OPACITY,
        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_FADE_ENABLED,
        Settings.Secure.ACCESSIBILITY_FORCE_INVERT_COLOR_ENABLED,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_ALWAYS_ON_ENABLED,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_JOYSTICK_ENABLED,
        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED,
        Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED,
        Settings.Secure.NOTIFICATION_BUBBLES,
        Settings.Secure.LOCATION_TIME_ZONE_DETECTION_ENABLED,
        Settings.Secure.LOCKSCREEN_SHOW_CONTROLS,
        Settings.Secure.LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
        Settings.Secure.LOCKSCREEN_SHOW_WALLET,
        Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER,
        Settings.Secure.LOCKSCREEN_USE_DOUBLE_LINE_CLOCK,
        Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
        Settings.Secure.WEAR_TALKBACK_ENABLED,
        Settings.Secure.HBM_SETTING_KEY,
        Settings.Secure.ASSIST_TOUCH_GESTURE_ENABLED,
        Settings.Secure.ASSIST_LONG_PRESS_HOME_ENABLED,
        Settings.Secure.BLUETOOTH_LE_BROADCAST_PROGRAM_INFO,
        Settings.Secure.BLUETOOTH_LE_BROADCAST_CODE,
        Settings.Secure.BLUETOOTH_LE_BROADCAST_APP_SOURCE_NAME,
        Settings.Secure.BLUETOOTH_LE_BROADCAST_IMPROVE_COMPATIBILITY,
        Settings.Secure.BLUETOOTH_LE_BROADCAST_FALLBACK_ACTIVE_DEVICE_ADDRESS,
        Settings.Secure.CUSTOM_BUGREPORT_HANDLER_APP,
        Settings.Secure.CUSTOM_BUGREPORT_HANDLER_USER,
        Settings.Secure.LOCK_SCREEN_WEATHER_ENABLED,
        Settings.Secure.HEARING_AID_RINGTONE_ROUTING,
        Settings.Secure.HEARING_AID_CALL_ROUTING,
        Settings.Secure.HEARING_AID_MEDIA_ROUTING,
        Settings.Secure.HEARING_AID_NOTIFICATION_ROUTING,
        Settings.Secure.ACCESSIBILITY_FONT_SCALING_HAS_BEEN_CHANGED,
        Settings.Secure.SEARCH_PRESS_HOLD_NAV_HANDLE_ENABLED,
        Settings.Secure.SEARCH_LONG_PRESS_HOME_ENABLED,
        Settings.Secure.HUB_MODE_TUTORIAL_STATE,
        Settings.Secure.STYLUS_BUTTONS_ENABLED,
        Settings.Secure.STYLUS_HANDWRITING_ENABLED,
        Settings.Secure.DEFAULT_NOTE_TASK_PROFILE,
        Settings.Secure.CREDENTIAL_SERVICE,
        Settings.Secure.CREDENTIAL_SERVICE_PRIMARY,
        Settings.Secure.EVEN_DIMMER_ACTIVATED,
        Settings.Secure.EVEN_DIMMER_MIN_NITS,
        Settings.Secure.STYLUS_POINTER_ICON_ENABLED,
    };
}
