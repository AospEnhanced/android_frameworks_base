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
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.R
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.staticProperties

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
    @JvmField val TEAMFOOD = UnreleasedFlag(1)

    // 100 - notification
    // TODO(b/254512751): Tracking Bug
    val NOTIFICATION_PIPELINE_DEVELOPER_LOGGING = UnreleasedFlag(103)

    // TODO(b/254512732): Tracking Bug
    @JvmField val NSSL_DEBUG_LINES = UnreleasedFlag(105)

    // TODO(b/254512505): Tracking Bug
    @JvmField val NSSL_DEBUG_REMOVE_ANIMATION = UnreleasedFlag(106)

    // TODO(b/254512624): Tracking Bug
    @JvmField
    val NOTIFICATION_DRAG_TO_CONTENTS =
        ResourceBooleanFlag(108, R.bool.config_notificationToContents)

    // TODO(b/254512517): Tracking Bug
    val FSI_REQUIRES_KEYGUARD = UnreleasedFlag(110, teamfood = true)

    // TODO(b/254512538): Tracking Bug
    val INSTANT_VOICE_REPLY = UnreleasedFlag(111, teamfood = true)

    // TODO(b/254512425): Tracking Bug
    val NOTIFICATION_MEMORY_MONITOR_ENABLED = ReleasedFlag(112)

    // TODO(b/254512731): Tracking Bug
    @JvmField val NOTIFICATION_DISMISSAL_FADE = UnreleasedFlag(113, teamfood = true)
    val STABILITY_INDEX_FIX = UnreleasedFlag(114, teamfood = true)
    val SEMI_STABLE_SORT = UnreleasedFlag(115, teamfood = true)

    @JvmField val NOTIFICATION_GROUP_CORNER = UnreleasedFlag(116, teamfood = true)

    // TODO(b/257506350): Tracking Bug
    val FSI_CHROME = UnreleasedFlag(117)

    // next id: 118

    // 200 - keyguard/lockscreen
    // ** Flag retired **
    // public static final BooleanFlag KEYGUARD_LAYOUT =
    //         new BooleanFlag(200, true);
    // TODO(b/254512713): Tracking Bug
    @JvmField val LOCKSCREEN_ANIMATIONS = ReleasedFlag(201)

    // TODO(b/254512750): Tracking Bug
    val NEW_UNLOCK_SWIPE_ANIMATION = ReleasedFlag(202)
    val CHARGING_RIPPLE = ResourceBooleanFlag(203, R.bool.flag_charging_ripple)

    // TODO(b/254512281): Tracking Bug
    @JvmField
    val BOUNCER_USER_SWITCHER = ResourceBooleanFlag(204, R.bool.config_enableBouncerUserSwitcher)

    // TODO(b/254512676): Tracking Bug
    @JvmField val LOCKSCREEN_CUSTOM_CLOCKS = UnreleasedFlag(207, teamfood = true)

    /**
     * Flag to enable the usage of the new bouncer data source. This is a refactor of and eventual
     * replacement of KeyguardBouncer.java.
     */
    // TODO(b/254512385): Tracking Bug
    @JvmField val MODERN_BOUNCER = ReleasedFlag(208)

    /**
     * Whether the user interactor and repository should use `UserSwitcherController`.
     *
     * If this is `false`, the interactor and repo skip the controller and directly access the
     * framework APIs.
     */
    // TODO(b/254513286): Tracking Bug
    val USER_INTERACTOR_AND_REPO_USE_CONTROLLER = UnreleasedFlag(210)

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
    val USER_CONTROLLER_USES_INTERACTOR = ReleasedFlag(211)

    /**
     * Whether the clock on a wide lock screen should use the new "stepping" animation for moving
     * the digits when the clock moves.
     */
    @JvmField val STEP_CLOCK_ANIMATION = UnreleasedFlag(212)

    /**
     * Migration from the legacy isDozing/dozeAmount paths to the new KeyguardTransitionRepository
     * will occur in stages. This is one stage of many to come.
     */
    // TODO(b/255607168): Tracking Bug
    @JvmField val DOZING_MIGRATION_1 = UnreleasedFlag(213)

    @JvmField val NEW_ELLIPSE_DETECTION = UnreleasedFlag(214)

    @JvmField val NEW_UDFPS_OVERLAY = UnreleasedFlag(215)

    /**
     * Whether to enable the code powering customizable lock screen quick affordances.
     *
     * Note that this flag does not enable individual implementations of quick affordances like the
     * new camera quick affordance. Look for individual flags for those.
     */
    @JvmField val CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES = UnreleasedFlag(216, teamfood = false)

    // 300 - power menu
    // TODO(b/254512600): Tracking Bug
    @JvmField val POWER_MENU_LITE = ReleasedFlag(300)

    // 400 - smartspace

    // TODO(b/254513100): Tracking Bug
    val SMARTSPACE_SHARED_ELEMENT_TRANSITION_ENABLED = ReleasedFlag(401)
    val SMARTSPACE = ResourceBooleanFlag(402, R.bool.flag_smartspace)

    // 500 - quick settings

    // TODO(b/254512321): Tracking Bug
    @JvmField val COMBINED_QS_HEADERS = ReleasedFlag(501)
    val PEOPLE_TILE = ResourceBooleanFlag(502, R.bool.flag_conversations)

    @JvmField
    val QS_USER_DETAIL_SHORTCUT =
        ResourceBooleanFlag(503, R.bool.flag_lockscreen_qs_user_detail_shortcut)

    // TODO(b/254512747): Tracking Bug
    val NEW_HEADER = ReleasedFlag(505)

    // TODO(b/254512383): Tracking Bug
    @JvmField
    val FULL_SCREEN_USER_SWITCHER =
        ResourceBooleanFlag(506, R.bool.config_enableFullscreenUserSwitcher)

    // TODO(b/254512678): Tracking Bug
    @JvmField val NEW_FOOTER_ACTIONS = ReleasedFlag(507)

    // TODO(b/244064524): Tracking Bug
    @JvmField val QS_SECONDARY_DATA_SUB_INFO = UnreleasedFlag(508, teamfood = true)

    // 600- status bar
    // TODO(b/254513246): Tracking Bug
    val STATUS_BAR_USER_SWITCHER = ResourceBooleanFlag(602, R.bool.flag_user_switcher_chip)

    // TODO(b/254512623): Tracking Bug
    @Deprecated("Replaced by mobile and wifi specific flags.")
    val NEW_STATUS_BAR_PIPELINE_BACKEND = UnreleasedFlag(604, teamfood = false)

    // TODO(b/254512660): Tracking Bug
    @Deprecated("Replaced by mobile and wifi specific flags.")
    val NEW_STATUS_BAR_PIPELINE_FRONTEND = UnreleasedFlag(605, teamfood = false)

    // TODO(b/256614753): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS = UnreleasedFlag(606)

    // TODO(b/256614210): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON = UnreleasedFlag(607)

    // TODO(b/256614751): Tracking Bug
    val NEW_STATUS_BAR_MOBILE_ICONS_BACKEND = UnreleasedFlag(608)

    // TODO(b/256613548): Tracking Bug
    val NEW_STATUS_BAR_WIFI_ICON_BACKEND = UnreleasedFlag(609)

    // 700 - dialer/calls
    // TODO(b/254512734): Tracking Bug
    val ONGOING_CALL_STATUS_BAR_CHIP = ReleasedFlag(700)

    // TODO(b/254512681): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE = ReleasedFlag(701)

    // TODO(b/254512753): Tracking Bug
    val ONGOING_CALL_IN_IMMERSIVE_CHIP_TAP = ReleasedFlag(702)

    // 800 - general visual/theme
    @JvmField val MONET = ResourceBooleanFlag(800, R.bool.flag_monet)

    // 801 - region sampling
    // TODO(b/254512848): Tracking Bug
    val REGION_SAMPLING = UnreleasedFlag(801)

    // 802 - wallpaper rendering
    // TODO(b/254512923): Tracking Bug
    @JvmField val USE_CANVAS_RENDERER = ReleasedFlag(802)

    // 803 - screen contents translation
    // TODO(b/254513187): Tracking Bug
    val SCREEN_CONTENTS_TRANSLATION = UnreleasedFlag(803)

    // 804 - monochromatic themes
    @JvmField
    val MONOCHROMATIC_THEMES = SysPropBooleanFlag(804, "persist.sysui.monochromatic", false)

    // 900 - media
    // TODO(b/254512697): Tracking Bug
    val MEDIA_TAP_TO_TRANSFER = ReleasedFlag(900)

    // TODO(b/254512502): Tracking Bug
    val MEDIA_SESSION_ACTIONS = UnreleasedFlag(901)

    // TODO(b/254512726): Tracking Bug
    val MEDIA_NEARBY_DEVICES = ReleasedFlag(903)

    // TODO(b/254512695): Tracking Bug
    val MEDIA_MUTE_AWAIT = ReleasedFlag(904)

    // TODO(b/254512654): Tracking Bug
    @JvmField val DREAM_MEDIA_COMPLICATION = UnreleasedFlag(905)

    // TODO(b/254512673): Tracking Bug
    @JvmField val DREAM_MEDIA_TAP_TO_OPEN = UnreleasedFlag(906)

    // TODO(b/254513168): Tracking Bug
    @JvmField val UMO_SURFACE_RIPPLE = UnreleasedFlag(907)

    // 1000 - dock
    val SIMULATE_DOCK_THROUGH_CHARGING = ReleasedFlag(1000)

    // TODO(b/254512758): Tracking Bug
    @JvmField val ROUNDED_BOX_RIPPLE = ReleasedFlag(1002)

    // 1100 - windowing
    @Keep
    @JvmField
    val WM_ENABLE_SHELL_TRANSITIONS =
        SysPropBooleanFlag(1100, "persist.wm.debug.shell_transit", false)

    // TODO(b/254513207): Tracking Bug
    @Keep
    @JvmField
    val WM_ENABLE_PARTIAL_SCREEN_SHARING =
        DeviceConfigBooleanFlag(
            1102,
            "record_task_content",
            DeviceConfig.NAMESPACE_WINDOW_MANAGER,
            false,
            teamfood = true
        )

    // TODO(b/254512674): Tracking Bug
    @Keep
    @JvmField
    val HIDE_NAVBAR_WINDOW = SysPropBooleanFlag(1103, "persist.wm.debug.hide_navbar_window", false)

    @Keep
    @JvmField
    val WM_DESKTOP_WINDOWING = SysPropBooleanFlag(1104, "persist.wm.debug.desktop_mode", false)

    @Keep
    @JvmField
    val WM_CAPTION_ON_SHELL = SysPropBooleanFlag(1105, "persist.wm.debug.caption_on_shell", false)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_BUBBLE =
        SysPropBooleanFlag(1108, "persist.wm.debug.fling_to_dismiss_bubble", true)

    @Keep
    @JvmField
    val ENABLE_FLING_TO_DISMISS_PIP =
        SysPropBooleanFlag(1109, "persist.wm.debug.fling_to_dismiss_pip", true)

    @Keep
    @JvmField
    val ENABLE_PIP_KEEP_CLEAR_ALGORITHM =
        SysPropBooleanFlag(1110, "persist.wm.debug.enable_pip_keep_clear_algorithm", false)

    // TODO(b/256873975): Tracking Bug
    @JvmField @Keep val WM_BUBBLE_BAR = UnreleasedFlag(1111)

    // 1200 - predictive back
    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK =
        SysPropBooleanFlag(1200, "persist.wm.debug.predictive_back", true)

    @Keep
    @JvmField
    val WM_ENABLE_PREDICTIVE_BACK_ANIM =
        SysPropBooleanFlag(1201, "persist.wm.debug.predictive_back_anim", false)

    @Keep
    @JvmField
    val WM_ALWAYS_ENFORCE_PREDICTIVE_BACK =
        SysPropBooleanFlag(1202, "persist.wm.debug.predictive_back_always_enforce", false)

    // TODO(b/254512728): Tracking Bug
    @JvmField val NEW_BACK_AFFORDANCE = UnreleasedFlag(1203, teamfood = false)

    // 1300 - screenshots
    // TODO(b/254512719): Tracking Bug
    @JvmField val SCREENSHOT_REQUEST_PROCESSOR = UnreleasedFlag(1300, teamfood = true)

    // TODO(b/254513155): Tracking Bug
    @JvmField val SCREENSHOT_WORK_PROFILE_POLICY = UnreleasedFlag(1301)

    // 1400 - columbus
    // TODO(b/254512756): Tracking Bug
    val QUICK_TAP_IN_PCC = ReleasedFlag(1400)

    // 1500 - chooser
    // TODO(b/254512507): Tracking Bug
    val CHOOSER_UNBUNDLED = UnreleasedFlag(1500, teamfood = true)

    // 1600 - accessibility
    @JvmField val A11Y_FLOATING_MENU_FLING_SPRING_ANIMATIONS = UnreleasedFlag(1600)

    // 1700 - clipboard
    @JvmField val CLIPBOARD_OVERLAY_REFACTOR = UnreleasedFlag(1700, teamfood = true)
    @JvmField val CLIPBOARD_REMOTE_BEHAVIOR = UnreleasedFlag(1701)

    // 1800 - shade container
    @JvmField val LEAVE_SHADE_OPEN_FOR_BUGREPORT = UnreleasedFlag(1800, teamfood = true)

    // 1900 - note task
    @JvmField val NOTE_TASKS = SysPropBooleanFlag(1900, "persist.sysui.debug.note_tasks")

    // 2000 - device controls
    @Keep @JvmField val USE_APP_PANELS = UnreleasedFlag(2000, teamfood = true)

    // 2100 - Falsing Manager
    @JvmField val FALSING_FOR_LONG_TAPS = ReleasedFlag(2100)

    // Pay no attention to the reflection behind the curtain.
    // ========================== Curtain ==========================
    // |                                                           |
    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    @JvmStatic
    fun collectFlags(): Map<Int, Flag<*>> {
        return flagFields.mapKeys { field -> field.value.id }
    }

    // |  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  .  |
    @JvmStatic
    val flagFields: Map<String, Flag<*>>
        get() = collectFlagsInClass(Flags)

    @VisibleForTesting
    fun collectFlagsInClass(instance: Any): Map<String, Flag<*>> {
        val cls = instance::class
        val javaPropNames = cls.java.fields.map { it.name }
        val props = cls.declaredMembers
        val staticProps = cls.staticProperties
        val staticPropNames = staticProps.map { it.name }
        return props
            .mapNotNull { property ->
                if ((property.returnType.classifier as KClass<*>).isSubclassOf(Flag::class)) {
                    // Fields with @JvmStatic should be accessed via java mechanisms
                    if (javaPropNames.contains(property.name)) {
                        property.name to cls.java.getField(property.name)[null] as Flag<*>
                        // Fields with @Keep but not @JvmField. Don't do this.
                    } else if (staticPropNames.contains(property.name)) {
                        // The below code causes access violation exceptions. I don't know why.
                        // property.name to (property.call() as Flag<*>)
                        // property.name to (staticProps.find { it.name == property.name }!!
                        // .getter.call() as Flag<*>)
                        throw java.lang.RuntimeException(
                            "The {$property.name} flag needs @JvmField"
                        )
                        // Everything else. Skip the `get` prefixed fields that kotlin adds.
                    } else if (property.name.subSequence(0, 3) != "get") {
                        property.name to (property.call(instance) as Flag<*>)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            .toMap()
    }
    // |                                                           |
    // \_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/\_/
}
