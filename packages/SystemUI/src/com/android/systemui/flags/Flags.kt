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
package com.android.systemui.flags

import android.provider.DeviceConfig
import com.android.internal.annotations.Keep
import com.android.systemui.R
import com.android.systemui.flags.FlagsFactory.releasedFlag
import com.android.systemui.flags.FlagsFactory.resourceBooleanFlag
import com.android.systemui.flags.FlagsFactory.sysPropBooleanFlag
import com.android.systemui.flags.FlagsFactory.unreleasedFlag

/**
 * List of [Flag] objects for use in SystemUI.
 *
 * Flag Ids are integers. Ids must be unique. This is enforced in a unit test. Ids need not be
 * sequential. Flags can "claim" a chunk of ids for flags in related features with a comment. This
 * is purely for organizational purposes.
 *
 * On public release builds, flags will always return their default value. There is no way to change
 * their value on release builds.
 *
 * See [FeatureFlagsDebug] for instructions on flipping the flags via adb.
 */
object Flags {
    @JvmField val TEAMFOOD = unreleasedFlag(1, "teamfood")

    // 100 - notification
    // TODO(b/254512751): Tracking Bug
    val NOTIFICATION_PIPELINE_DEVELOPER_LOGGING =
        unreleasedFlag(103, "notification_pipeline_developer_logging")

    // TODO(b/254512732): Tracking Bug
    @JvmField val NSSL_DEBUG_LINES = unreleasedFlag(105, "nssl_debug_lines")

    // TODO(b/254512505): Tracking Bug
    @JvmField val NSSL_DEBUG_REMOVE_ANIMATION = unreleasedFlag(106, "nssl_debug_remove_animation")

    // TODO(b/254512624): Tracking Bug
    @JvmField
    val NOTIFICATION_DRAG_TO_CONTENTS =
        resourceBooleanFlag(
            108,
            R.bool.config_notificationToContents,
            "notification_drag_to_contents"
        )

    // TODO(b/254512517): Tracking Bug
    val FSI_REQUIRES_KEYGUARD = unreleasedFlag(110, "fsi_requires_keyguard", teamfood = true)

    // TODO(b/254512538): Tracking Bug
    val INSTANT_VOICE_REPLY = unreleasedFlag(111, "instant_voice_reply", teamfood = true)

    // TODO(b/254512425): Tracking Bug
    val NOTIFICATION_MEMORY_MONITOR_ENABLED =
        releasedFlag(112, "notification_memory_monitor_enabled")

    // TODO(b/254512731): Tracking Bug
    @JvmField
    val NOTIFICATION_DISMISSAL_FADE =
        unreleasedFlag(113, "notification_dismissal_fade", teamfood = true)
    val STABILITY_INDEX_FIX = unreleasedFlag(114, "stability_index_fix", teamfood = true)
    val SEMI_STABLE_SORT = unreleasedFlag(115, "semi_stable_sort", teamfood = true)

    @JvmField
    val NOTIFICATION_GROUP_CORNER =
        unreleasedFlag(116, "notification_group_corner", teamfood = true)

    // TODO(b/257506350): Tracking Bug
    val FSI_CHROME = unreleasedFlag(117, "fsi_chrome")

    // next id: 118

    // 200 - keyguard/lockscreen
    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(200, true);
    // TODO(b/254512713): Tracking Bug
    @JvmField val LOCKSCREEN_ANIMATIONS = releasedFlag(201, "lockscreen_animations")

    // TODO(b/254512750): Tracking Bug
    val NEW_UNLOCK_SWIPE_ANIMATION = releasedFlag(202, "new_unlock_swipe_animation")
    val CHARGING_RIPPLE = resourceBooleanFlag(203, R.bool.flag_charging_ripple, "charging_ripple")

    // TODO(b/254512281): Tracking Bug
    @JvmField
    val BOUNCER_USER_SWITCHER =
        resourceBooleanFlag(204, R.bool.config_enableBouncerUserSwitcher, "bouncer_user_switcher")

    // TODO(b/254512676): Tracking Bug
    @JvmField
    val LOCKSCREEN_CUSTOM_CLOCKS = unreleasedFlag(207, "lockscreen_custom_clocks", teamfood = true)

    /**
     * Flag to enable the usage of the new bouncer data source. This is a refactor of and eventual
     * replacement of KeyguardBouncer.java.
     */
    // TODO(b/254512385): Tracking Bug
    @JvmField val MODERN_BOUNCER = releasedFlag(208, "modern_bouncer")

    /**
     * Whether the user interactor and repository should use `UserSwitcherController`.
     *
     * If this is `false`, the interactor and repo skip the controller and directly access the
     * framework APIs.
     */
    // TODO(b/254513286): Tracking Bug
    val USER_INTERACTOR_AND_REPO_USE_CONTROLLER =
        unreleasedFlag(210, "user_interactor_and_repo_use_controller")

    /**
     * Whether `UserSwitcherController` should use the user interactor.
     *
     * When this is `true`, the controller does not directly access framework APIs. Instead, it goes
     * through the interactor.
     *
     * Note: do not set this to true if [.USER_INTERACTOR_AND_REPO_USE_CONTROLLER] is `true` as it
     * would created a cycle between controller -> interactor -> controller.
     */
    // TODO(b/254513102): Tracking Bug
    val USER_CONTROLLER_USES_INTERACTOR = releasedFlag(211, "user_controller_uses_interactor")

    /**
     * Whether the clock on a wide lock screen should use the new "stepping" animation for moving
     * the digits when the clock moves.
     */
    @JvmField val STEP_CLOCK_ANIMATION = unreleasedFlag(212, "step_clock_animation")

    /**
     * Migration from the legacy isDozing/dozeAmount paths to the new KeyguardTransitionRepository
     * will occur in stages. This is one stage of many to come.
     */
    // TODO(b/255607168): Tracking Bug
    @JvmField val DOZING_MIGRATION_1 = unreleasedFlag(213, "dozing_migration_1")

    // TODO(b/252897742): Tracking Bug
    @JvmField val NEW_ELLIPSE_DETECTION = unreleasedFlag(214, "new_ellipse_detection")

    // TODO(b/252897742): Tracking Bug
    @JvmField val NEW_UDFPS_OVERLAY = unreleasedFlag(215, "new_udfps_overlay")

    /**
     * Whether to enable the code powering customizable lock screen quick affordances.
     *
     * Note that this flag does not enable individual implementations of quick affordances like the
     * new camera quick affordance. Look for individual flags for those.
     */
    @JvmField
    val CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES =
        unreleasedFlag(216, "customizable_lock_screen_quick_affordances", teamfood = false)

    /** Shows chipbar UI whenever the device is unlocked by ActiveUnlock (watch). */
    // TODO(b/240196500): Tracking Bug
    @JvmField val ACTIVE_UNLOCK_CHIPBAR = unreleasedFlag(217, "active_unlock_chipbar")

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = releasedFlag(300, "power_menu_lite")

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED =
        releasedFlag(401, "smartspace_shared_element_transition_enabled")
    val SMARTSPACE = resourceBooleanFlag(402, R.bool.flag_smartspace, "smartspace")

    // 500 - quick settings

    // TODO(b/254512321): Tracking Bug
    @JvmField val COMBINED_QS_HEADERS = releasedFlag(501, "combined_qs_headers")
    val PEOPLE_TILE = resourceBooleanFlag(502, R.bool.flag_conversations, "people_tile")

    @JvmField
    val QS_USER_DETAIL_SHORTCUT =
        resourceBooleanFlag(
            503,
            R.bool.flag_lockscreen_qs_user_detail_shortcut,
            "qs_user_detail_shortcut"
        )

    // TODO(b/254512747): Tracking Bug
    val NEW_HEADER = releasedFlag(505, "new_header")

    // TODO(b/254512383): Tracking Bug
    @JvmField
    val FULL_SCREEN_USER_SWITCHER =
        resourceBooleanFlag(
            506,
            R.bool.config_enableFullscreenUserSwitcher,
            "full_screen_user_switcher"
        )

    // TODO(b/254512678): Tracking Bug
    @JvmField val NEW_FOOTER_ACTIONS = releasedFlag(507, "new_footer_actions")

    // TODO(b/244064524): Tracking Bug
    @JvmField
    val QS_SECONDARY_DATA_SUB_INFO =
        unreleasedFlag(508, "qs_secondary_data_sub_info", teamfood = true)

    // 600- status bar
    // TODO(b/254513246): Tracking Bug
    val STATUS_BAR_USER_SWITCHER =
        resourceBooleanFlag(602, R.bool.flag_user_switcher_chip, "status_bar_user_switcher")

    // TODO(b/254512623): Tracking Bug
    @Deprecated("Replaced by mobile and wifi specific flags.")
    val NEW_STATUS_BAR_PIPELINE_BACKEND =
        unreleasedFlag(604, "new_status_bar_pipeline_backend", teamfood = false)

    // TODO(b/254512660): Tracking Bug
    @Deprecated("Replaced by mobile and wifi specific flags.")
    val NEW_STATUS_BAR_PIPELINE_FRONTEND =
        unreleasedFlag(605, "new_status_bar_pipeline_frontend", teamfood = false)

    // TODO(b/256614753): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS = unreleasedFlag(606, "new_status_bar_mobile_icons")

    // TODO(b/256614210): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON = unreleasedFlag(607, "new_status_bar_wifi_icon")

    // TODO(b/256614751): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS_BACKEND =
        unreleasedFlag(608, "new_status_bar_mobile_icons_backend")

    // TODO(b/256613548): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON_BACKEND = unreleasedFlag(609, "new_status_bar_wifi_icon_backend")

    // 700 - dialer/calls
    // TODO(b/254512734): Tracking Bug
    val ONGOING_CALL_STATUS_BAR_CHIP = releasedFlag(700, "ongoing_call_status_bar_chip")

    // TODO(b/254512681): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE = releasedFlag(701, "ongoing_call_in_immersive")

    // TODO(b/254512753): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP = releasedFlag(702, "ongoing_call_in_immersive_chip_tap")

    // 800 - general visual/theme
    @JvmField val MONET = resourceBooleanFlag(800, R.bool.flag_monet, "monet")

    // 801 - region sampling
    // TODO(b/254512848): Tracking Bug
    val REGION_SAMPLING = unreleasedFlag(801, "region_sampling")

    // 802 - wallpaper rendering
    // TODO(b/254512923): Tracking Bug
    @JvmField val USE_CANVAS_RENDERER = unreleasedFlag(802, "use_canvas_renderer")

    // 803 - screen contents translation
    // TODO(b/254513187): Tracking Bug
    val SCREEN_CONTENTS_TRANSLATION = unreleasedFlag(803, "screen_contents_translation")

    // 804 - monochromatic themes
    @JvmField
    val MONOCHROMATIC_THEMES =
        sysPropBooleanFlag(804, "persist.sysui.monochromatic", default = false)

    // 900 - media
    // TODO(b/254512697): Tracking Bug
    val MEDIA_TAP_TO_TRANSFER = releasedFlag(900, "media_tap_to_transfer")

    // TODO(b/254512502): Tracking Bug
    val MEDIA_SESSION_ACTIONS = unreleasedFlag(901, "media_session_actions")

    // TODO(b/254512726): Tracking Bug
    val MEDIA_NEARBY_DEVICES = releasedFlag(903, "media_nearby_devices")

    // TODO(b/254512695): Tracking Bug
    val MEDIA_MUTE_AWAIT = releasedFlag(904, "media_mute_await")

    // TODO(b/254512654): Tracking Bug
    @JvmField val DREAM_MEDIA_COMPLICATION = unreleasedFlag(905, "dream_media_complication")

    // TODO(b/254512673): Tracking Bug
    @JvmField val DREAM_MEDIA_TAP_TO_OPEN = unreleasedFlag(906, "dream_media_tap_to_open")

    // TODO(b/254513168): Tracking Bug
    @JvmField val UMO_SURFACE_RIPPLE = unreleasedFlag(907, "umo_surface_ripple")

    // 1000 - dock
    val SIMULATE_DOCK_THROUGH_CHARGING = releasedFlag(1000, "simulate_dock_through_charging")

    // TODO(b/254512758): Tracking Bug
    @JvmField val ROUNDED_BOX_RIPPLE = releasedFlag(1002, "rounded_box_ripple")

    // 1100 - windowing
    @Keep
    @JvmField
    val WM_ENABLE_SHELL_TRANSITIONS =
        sysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", default = true)

    // TODO(b/254513207): Tracking Bug
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING =
        unreleasedFlag(
            1102,
            name = "record_task_content",
            namespace = DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            teamfood = true
        )

    // TODO(b/254512674): Tracking Bug
    @Keep
    @JvmField
    val HIDE_NAVBAR_WINDOW =
        sysPropBooleanFlag(1103, "persist.wm.debug.hide_navbar_window", default = false)

    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING =
        sysPropBooleanFlag(1104, "persist.wm.debug.desktop_mode", default = false)

    @Keep
    @JvmField
    val WM_CAPTION_ON_SHELL =
        sysPropBooleanFlag(1105, "persist.wm.debug.caption_on_shell", default = false)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_BUBBLE =
        sysPropBooleanFlag(1108, "persist.wm.debug.fling_to_dismiss_bubble", default = true)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_PIP =
        sysPropBooleanFlag(1109, "persist.wm.debug.fling_to_dismiss_pip", default = true)

    @Keep
    @JvmField
    val ENABLE_PIP_KEEP_CLEAR_ALGORITHM =
        sysPropBooleanFlag(
            1110,
            "persist.wm.debug.enable_pip_keep_clear_algorithm",
            default = false
        )

    // TODO(b/256873975): Tracking Bug
    @JvmField @Keep val WM_BUBBLE_BAR = unreleasedFlag(1111, "wm_bubble_bar")

    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1200, "persist.wm.debug.predictive_back", default = true)

    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        sysPropBooleanFlag(1201, "persist.wm.debug.predictive_back_anim", default = false)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        sysPropBooleanFlag(1202, "persist.wm.debug.predictive_back_always_enforce", default = false)

    // TODO(b/254512728): Tracking Bug
    @JvmField
    val NEW_BACK_AFFORDANCE = unreleasedFlag(1203, "new_back_affordance", teamfood = false)

    // 1300 - screenshots
    // TODO(b/254512719): Tracking Bug
    @JvmField
    val SCREENSHOT_REQUEST_PROCESSOR =
        unreleasedFlag(1300, "screenshot_request_processor", teamfood = true)

    // TODO(b/254513155): Tracking Bug
    @JvmField
    val SCREENSHOT_WORK_PROFILE_POLICY = unreleasedFlag(1301, "screenshot_work_profile_policy")

    // 1400 - columbus
    // TODO(b/254512756): Tracking Bug
    val QUICK_TAP_IN_PCC = releasedFlag(1400, "quick_tap_in_pcc")

    // 1500 - chooser
    // TODO(b/254512507): Tracking Bug
    val CHOOSER_UNBUNDLED = unreleasedFlag(1500, "chooser_unbundled", teamfood = true)

    // 1600 - accessibility
    @JvmField
    val A11Y_FLOATING_MENU_FLING_SPRING_ANIMATIONS =
        unreleasedFlag(1600, "a11y_floating_menu_fling_spring_animations")

    // 1700 - clipboard
    @JvmField
    val CLIPBOARD_OVERLAY_REFACTOR =
        unreleasedFlag(1700, "clipboard_overlay_refactor", teamfood = true)
    @JvmField val CLIPBOARD_REMOTE_BEHAVIOR = unreleasedFlag(1701, "clipboard_remote_behavior")

    // 1800 - shade container
    @JvmField
    val LEAVE_SHADE_OPEN_FOR_BUGREPORT =
        unreleasedFlag(1800, "leave_shade_open_for_bugreport", teamfood = true)

    // 1900 - note task
    @JvmField val NOTE_TASKS = sysPropBooleanFlag(1900, "persist.sysui.debug.note_tasks")

    // 2000 - device controls
    @Keep @JvmField val USE_APP_PANELS = unreleasedFlag(2000, "use_app_panels", teamfood = true)

    // 2100 - Falsing Manager
    @JvmField val FALSING_FOR_LONG_TAPS = releasedFlag(2100, "falsing_for_long_taps")
}
