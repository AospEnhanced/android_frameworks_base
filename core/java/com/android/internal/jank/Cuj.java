/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.jank;

import android.annotation.IntDef;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/** @hide */
public class Cuj {
    @VisibleForTesting
    public static final int MAX_LENGTH_OF_CUJ_NAME = 82;

    // Every value must have a corresponding entry in CUJ_STATSD_INTERACTION_TYPE.
    public static final int CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE = 0;
    public static final int CUJ_NOTIFICATION_SHADE_SCROLL_FLING = 2;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_EXPAND = 3;
    public static final int CUJ_NOTIFICATION_SHADE_ROW_SWIPE = 4;
    public static final int CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE = 5;
    public static final int CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE = 6;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS = 7;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON = 8;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_HOME = 9;
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_PIP = 10;
    public static final int CUJ_LAUNCHER_QUICK_SWITCH = 11;
    public static final int CUJ_NOTIFICATION_HEADS_UP_APPEAR = 12;
    public static final int CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR = 13;
    public static final int CUJ_NOTIFICATION_ADD = 14;
    public static final int CUJ_NOTIFICATION_REMOVE = 15;
    public static final int CUJ_NOTIFICATION_APP_START = 16;
    public static final int CUJ_LOCKSCREEN_PASSWORD_APPEAR = 17;
    public static final int CUJ_LOCKSCREEN_PATTERN_APPEAR = 18;
    public static final int CUJ_LOCKSCREEN_PIN_APPEAR = 19;
    public static final int CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR = 20;
    public static final int CUJ_LOCKSCREEN_PATTERN_DISAPPEAR = 21;
    public static final int CUJ_LOCKSCREEN_PIN_DISAPPEAR = 22;
    public static final int CUJ_LOCKSCREEN_TRANSITION_FROM_AOD = 23;
    public static final int CUJ_LOCKSCREEN_TRANSITION_TO_AOD = 24;
    public static final int CUJ_LAUNCHER_OPEN_ALL_APPS = 25;
    public static final int CUJ_LAUNCHER_ALL_APPS_SCROLL = 26;
    public static final int CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET = 27;
    public static final int CUJ_SETTINGS_PAGE_SCROLL = 28;
    public static final int CUJ_LOCKSCREEN_UNLOCK_ANIMATION = 29;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON = 30;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER = 31;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE = 32;
    public static final int CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON = 33;
    public static final int CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP = 34;
    public static final int CUJ_PIP_TRANSITION = 35;
    public static final int CUJ_WALLPAPER_TRANSITION = 36;
    public static final int CUJ_USER_SWITCH = 37;
    public static final int CUJ_SPLASHSCREEN_AVD = 38;
    public static final int CUJ_SPLASHSCREEN_EXIT_ANIM = 39;
    public static final int CUJ_SCREEN_OFF = 40;
    public static final int CUJ_SCREEN_OFF_SHOW_AOD = 41;
    public static final int CUJ_ONE_HANDED_ENTER_TRANSITION = 42;
    public static final int CUJ_ONE_HANDED_EXIT_TRANSITION = 43;
    public static final int CUJ_UNFOLD_ANIM = 44;
    public static final int CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS = 45;
    public static final int CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS = 46;
    public static final int CUJ_SUW_LOADING_TO_NEXT_FLOW = 47;
    public static final int CUJ_SUW_LOADING_SCREEN_FOR_STATUS = 48;
    public static final int CUJ_SPLIT_SCREEN_ENTER = 49;
    public static final int CUJ_SPLIT_SCREEN_EXIT = 50;
    public static final int CUJ_LOCKSCREEN_LAUNCH_CAMERA = 51; // reserved.
    public static final int CUJ_SPLIT_SCREEN_RESIZE = 52;
    public static final int CUJ_SETTINGS_SLIDER = 53;
    public static final int CUJ_TAKE_SCREENSHOT = 54;
    public static final int CUJ_VOLUME_CONTROL = 55;
    public static final int CUJ_BIOMETRIC_PROMPT_TRANSITION = 56;
    public static final int CUJ_SETTINGS_TOGGLE = 57;
    public static final int CUJ_SHADE_DIALOG_OPEN = 58;
    public static final int CUJ_USER_DIALOG_OPEN = 59;
    public static final int CUJ_TASKBAR_EXPAND = 60;
    public static final int CUJ_TASKBAR_COLLAPSE = 61;
    public static final int CUJ_SHADE_CLEAR_ALL = 62;
    public static final int CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION = 63;
    public static final int CUJ_LOCKSCREEN_OCCLUSION = 64;
    public static final int CUJ_RECENTS_SCROLLING = 65;
    public static final int CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS = 66;
    public static final int CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE = 67;
    public static final int CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME = 68;
    public static final int CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION = 70;
    public static final int CUJ_LAUNCHER_OPEN_SEARCH_RESULT = 71;
    // 72 - 77 are reserved for b/281564325.

    /**
     * In some cases when we do not have any end-target, we play a simple slide-down animation.
     * eg: Open an app from Overview/Task switcher such that there is no home-screen icon.
     * eg: Exit the app using back gesture.
     */
    public static final int CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK = 78;
    // 79 is reserved.
    public static final int CUJ_IME_INSETS_SHOW_ANIMATION = 80;
    public static final int CUJ_IME_INSETS_HIDE_ANIMATION = 81;

    public static final int CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER = 82;

    public static final int CUJ_LAUNCHER_UNFOLD_ANIM = 83;

    public static final int CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY = 84;
    public static final int CUJ_PREDICTIVE_BACK_CROSS_TASK = 85;
    public static final int CUJ_PREDICTIVE_BACK_HOME = 86;
    public static final int CUJ_LAUNCHER_SEARCH_QSB_OPEN = 87;
    public static final int CUJ_BACK_PANEL_ARROW = 88;
    public static final int CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK = 89;
    public static final int CUJ_LAUNCHER_SEARCH_QSB_WEB_SEARCH = 90;

    // When adding a CUJ, update this and make sure to also update CUJ_TO_STATSD_INTERACTION_TYPE.
    @VisibleForTesting
    static final int LAST_CUJ = CUJ_LAUNCHER_SEARCH_QSB_WEB_SEARCH;

    /** @hide */
    @IntDef({
            CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_SCROLL_FLING,
            CUJ_NOTIFICATION_SHADE_ROW_EXPAND,
            CUJ_NOTIFICATION_SHADE_ROW_SWIPE,
            CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
            CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON,
            CUJ_LAUNCHER_APP_CLOSE_TO_HOME,
            CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
            CUJ_LAUNCHER_QUICK_SWITCH,
            CUJ_NOTIFICATION_HEADS_UP_APPEAR,
            CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
            CUJ_NOTIFICATION_ADD,
            CUJ_NOTIFICATION_REMOVE,
            CUJ_NOTIFICATION_APP_START,
            CUJ_LOCKSCREEN_PASSWORD_APPEAR,
            CUJ_LOCKSCREEN_PATTERN_APPEAR,
            CUJ_LOCKSCREEN_PIN_APPEAR,
            CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR,
            CUJ_LOCKSCREEN_PATTERN_DISAPPEAR,
            CUJ_LOCKSCREEN_PIN_DISAPPEAR,
            CUJ_LOCKSCREEN_TRANSITION_FROM_AOD,
            CUJ_LOCKSCREEN_TRANSITION_TO_AOD,
            CUJ_LAUNCHER_OPEN_ALL_APPS,
            CUJ_LAUNCHER_ALL_APPS_SCROLL,
            CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET,
            CUJ_SETTINGS_PAGE_SCROLL,
            CUJ_LOCKSCREEN_UNLOCK_ANIMATION,
            CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON,
            CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER,
            CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE,
            CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON,
            CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
            CUJ_PIP_TRANSITION,
            CUJ_WALLPAPER_TRANSITION,
            CUJ_USER_SWITCH,
            CUJ_SPLASHSCREEN_AVD,
            CUJ_SPLASHSCREEN_EXIT_ANIM,
            CUJ_SCREEN_OFF,
            CUJ_SCREEN_OFF_SHOW_AOD,
            CUJ_ONE_HANDED_ENTER_TRANSITION,
            CUJ_ONE_HANDED_EXIT_TRANSITION,
            CUJ_UNFOLD_ANIM,
            CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS,
            CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS,
            CUJ_SUW_LOADING_TO_NEXT_FLOW,
            CUJ_SUW_LOADING_SCREEN_FOR_STATUS,
            CUJ_SPLIT_SCREEN_ENTER,
            CUJ_SPLIT_SCREEN_EXIT,
            CUJ_LOCKSCREEN_LAUNCH_CAMERA,
            CUJ_SPLIT_SCREEN_RESIZE,
            CUJ_SETTINGS_SLIDER,
            CUJ_TAKE_SCREENSHOT,
            CUJ_VOLUME_CONTROL,
            CUJ_BIOMETRIC_PROMPT_TRANSITION,
            CUJ_SETTINGS_TOGGLE,
            CUJ_SHADE_DIALOG_OPEN,
            CUJ_USER_DIALOG_OPEN,
            CUJ_TASKBAR_EXPAND,
            CUJ_TASKBAR_COLLAPSE,
            CUJ_SHADE_CLEAR_ALL,
            CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION,
            CUJ_LOCKSCREEN_OCCLUSION,
            CUJ_RECENTS_SCROLLING,
            CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS,
            CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE,
            CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME,
            CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION,
            CUJ_LAUNCHER_OPEN_SEARCH_RESULT,
            CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK,
            CUJ_IME_INSETS_SHOW_ANIMATION,
            CUJ_IME_INSETS_HIDE_ANIMATION,
            CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER,
            CUJ_LAUNCHER_UNFOLD_ANIM,
            CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY,
            CUJ_PREDICTIVE_BACK_CROSS_TASK,
            CUJ_PREDICTIVE_BACK_HOME,
            CUJ_LAUNCHER_SEARCH_QSB_OPEN,
            CUJ_BACK_PANEL_ARROW,
            CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK,
            CUJ_LAUNCHER_SEARCH_QSB_WEB_SEARCH,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CujType {
    }

    private static final int NO_STATSD_LOGGING = -1;

    // Used to convert CujType to InteractionType enum value for statsd logging.
    // Use NO_STATSD_LOGGING in case the measurement for a given CUJ should not be logged to statsd.
    private static final int[] CUJ_TO_STATSD_INTERACTION_TYPE = new int[LAST_CUJ + 1];
    static {
        Arrays.fill(CUJ_TO_STATSD_INTERACTION_TYPE, NO_STATSD_LOGGING);

        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__NOTIFICATION_SHADE_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_SCROLL_FLING] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_SCROLL_FLING;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_ROW_EXPAND] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_EXPAND;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_ROW_SWIPE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_ROW_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_EXPAND_COLLAPSE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_QS_SCROLL_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_RECENTS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_ICON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_CLOSE_TO_HOME] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_CLOSE_TO_PIP] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_PIP;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_QUICK_SWITCH] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_QUICK_SWITCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_HEADS_UP_APPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_HEADS_UP_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_ADD] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_ADD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_REMOVE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_NOTIFICATION_REMOVE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_NOTIFICATION_APP_START] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PASSWORD_APPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PATTERN_APPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PIN_APPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_APPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PASSWORD_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PATTERN_DISAPPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PATTERN_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_PIN_DISAPPEAR] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_PIN_DISAPPEAR;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_TRANSITION_FROM_AOD] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_FROM_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_TRANSITION_TO_AOD] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_TRANSITION_TO_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_OPEN_ALL_APPS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_ALL_APPS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_ALL_APPS_SCROLL] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_ALL_APPS_SCROLL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_LAUNCH_FROM_WIDGET;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_PAGE_SCROLL] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_PAGE_SCROLL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_UNLOCK_ANIMATION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_UNLOCK_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_QS_TILE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_PIP_TRANSITION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PIP_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_WALLPAPER_TRANSITION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__WALLPAPER_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_USER_SWITCH] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_SWITCH;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLASHSCREEN_AVD] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_AVD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLASHSCREEN_EXIT_ANIM] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLASHSCREEN_EXIT_ANIM;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SCREEN_OFF] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SCREEN_OFF_SHOW_AOD] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SCREEN_OFF_SHOW_AOD;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_ONE_HANDED_ENTER_TRANSITION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_ENTER_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_ONE_HANDED_EXIT_TRANSITION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__ONE_HANDED_EXIT_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_UNFOLD_ANIM] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__UNFOLD_ANIM;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_TO_NEXT_FLOW] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_TO_NEXT_FLOW;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SUW_LOADING_SCREEN_FOR_STATUS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SUW_LOADING_SCREEN_FOR_STATUS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_ENTER] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_ENTER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_EXIT] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_EXIT;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_LAUNCH_CAMERA] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_LAUNCH_CAMERA;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_RESIZE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_RESIZE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_SLIDER] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_SLIDER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TAKE_SCREENSHOT] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TAKE_SCREENSHOT;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_VOLUME_CONTROL] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__VOLUME_CONTROL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_BIOMETRIC_PROMPT_TRANSITION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__BIOMETRIC_PROMPT_TRANSITION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SETTINGS_TOGGLE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SETTINGS_TOGGLE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_DIALOG_OPEN] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_DIALOG_OPEN;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_USER_DIALOG_OPEN] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__USER_DIALOG_OPEN;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TASKBAR_EXPAND] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_EXPAND;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_TASKBAR_COLLAPSE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__TASKBAR_COLLAPSE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SHADE_CLEAR_ALL] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SHADE_CLEAR_ALL;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_UNLOCK_ENTRANCE_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_OCCLUSION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_OCCLUSION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_RECENTS_SCROLLING] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__RECENTS_SCROLLING;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_SWIPE_TO_RECENTS;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_SWIPE;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_TO_HOME;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LOCKSCREEN_CLOCK_MOVE_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_OPEN_SEARCH_RESULT] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_OPEN_SEARCH_RESULT;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_IME_INSETS_SHOW_ANIMATION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__IME_INSETS_SHOW_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_IME_INSETS_HIDE_ANIMATION] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__IME_INSETS_HIDE_ANIMATION;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__SPLIT_SCREEN_DOUBLE_TAP_DIVIDER;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_UNFOLD_ANIM] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_UNFOLD_ANIM;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PREDICTIVE_BACK_CROSS_ACTIVITY;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_PREDICTIVE_BACK_CROSS_TASK] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PREDICTIVE_BACK_CROSS_TASK;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_PREDICTIVE_BACK_HOME] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__PREDICTIVE_BACK_HOME;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_SEARCH_QSB_OPEN] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_SEARCH_QSB_OPEN;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_BACK_PANEL_ARROW] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__BACK_PANEL_ARROW;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_CLOSE_ALL_APPS_BACK;
        CUJ_TO_STATSD_INTERACTION_TYPE[CUJ_LAUNCHER_SEARCH_QSB_WEB_SEARCH] = FrameworkStatsLog.UIINTERACTION_FRAME_INFO_REPORTED__INTERACTION_TYPE__LAUNCHER_SEARCH_QSB_WEB_SEARCH;
    }

    private Cuj() {
    }

    /**
     * A helper method to translate CUJ type to CUJ name.
     *
     * @param cujType the cuj type defined in this file
     * @return the name of the cuj type
     */
    public static String getNameOfCuj(int cujType) {
        // Please note:
        // 1. The length of the returned string shouldn't exceed MAX_LENGTH_OF_CUJ_NAME.
        // 2. The returned string should be the same with the name defined in atoms.proto.
        switch (cujType) {
            case CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE:
                return "NOTIFICATION_SHADE_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_SCROLL_FLING:
                return "NOTIFICATION_SHADE_SCROLL_FLING";
            case CUJ_NOTIFICATION_SHADE_ROW_EXPAND:
                return "NOTIFICATION_SHADE_ROW_EXPAND";
            case CUJ_NOTIFICATION_SHADE_ROW_SWIPE:
                return "NOTIFICATION_SHADE_ROW_SWIPE";
            case CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE:
                return "NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE";
            case CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE:
                return "NOTIFICATION_SHADE_QS_SCROLL_SWIPE";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS:
                return "LAUNCHER_APP_LAUNCH_FROM_RECENTS";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON:
                return "LAUNCHER_APP_LAUNCH_FROM_ICON";
            case CUJ_LAUNCHER_APP_CLOSE_TO_HOME:
                return "LAUNCHER_APP_CLOSE_TO_HOME";
            case CUJ_LAUNCHER_APP_CLOSE_TO_PIP:
                return "LAUNCHER_APP_CLOSE_TO_PIP";
            case CUJ_LAUNCHER_QUICK_SWITCH:
                return "LAUNCHER_QUICK_SWITCH";
            case CUJ_NOTIFICATION_HEADS_UP_APPEAR:
                return "NOTIFICATION_HEADS_UP_APPEAR";
            case CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR:
                return "NOTIFICATION_HEADS_UP_DISAPPEAR";
            case CUJ_NOTIFICATION_ADD:
                return "NOTIFICATION_ADD";
            case CUJ_NOTIFICATION_REMOVE:
                return "NOTIFICATION_REMOVE";
            case CUJ_NOTIFICATION_APP_START:
                return "NOTIFICATION_APP_START";
            case CUJ_LOCKSCREEN_PASSWORD_APPEAR:
                return "LOCKSCREEN_PASSWORD_APPEAR";
            case CUJ_LOCKSCREEN_PATTERN_APPEAR:
                return "LOCKSCREEN_PATTERN_APPEAR";
            case CUJ_LOCKSCREEN_PIN_APPEAR:
                return "LOCKSCREEN_PIN_APPEAR";
            case CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR:
                return "LOCKSCREEN_PASSWORD_DISAPPEAR";
            case CUJ_LOCKSCREEN_PATTERN_DISAPPEAR:
                return "LOCKSCREEN_PATTERN_DISAPPEAR";
            case CUJ_LOCKSCREEN_PIN_DISAPPEAR:
                return "LOCKSCREEN_PIN_DISAPPEAR";
            case CUJ_LOCKSCREEN_TRANSITION_FROM_AOD:
                return "LOCKSCREEN_TRANSITION_FROM_AOD";
            case CUJ_LOCKSCREEN_TRANSITION_TO_AOD:
                return "LOCKSCREEN_TRANSITION_TO_AOD";
            case CUJ_LAUNCHER_OPEN_ALL_APPS :
                return "LAUNCHER_OPEN_ALL_APPS";
            case CUJ_LAUNCHER_ALL_APPS_SCROLL:
                return "LAUNCHER_ALL_APPS_SCROLL";
            case CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET:
                return "LAUNCHER_APP_LAUNCH_FROM_WIDGET";
            case CUJ_SETTINGS_PAGE_SCROLL:
                return "SETTINGS_PAGE_SCROLL";
            case CUJ_LOCKSCREEN_UNLOCK_ANIMATION:
                return "LOCKSCREEN_UNLOCK_ANIMATION";
            case CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON";
            case CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER:
                return "SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER";
            case CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE:
                return "SHADE_APP_LAUNCH_FROM_QS_TILE";
            case CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON:
                return "SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON";
            case CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP:
                return "STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP";
            case CUJ_PIP_TRANSITION:
                return "PIP_TRANSITION";
            case CUJ_WALLPAPER_TRANSITION:
                return "WALLPAPER_TRANSITION";
            case CUJ_USER_SWITCH:
                return "USER_SWITCH";
            case CUJ_SPLASHSCREEN_AVD:
                return "SPLASHSCREEN_AVD";
            case CUJ_SPLASHSCREEN_EXIT_ANIM:
                return "SPLASHSCREEN_EXIT_ANIM";
            case CUJ_SCREEN_OFF:
                return "SCREEN_OFF";
            case CUJ_SCREEN_OFF_SHOW_AOD:
                return "SCREEN_OFF_SHOW_AOD";
            case CUJ_ONE_HANDED_ENTER_TRANSITION:
                return "ONE_HANDED_ENTER_TRANSITION";
            case CUJ_ONE_HANDED_EXIT_TRANSITION:
                return "ONE_HANDED_EXIT_TRANSITION";
            case CUJ_UNFOLD_ANIM:
                return "UNFOLD_ANIM";
            case CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS:
                return "SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS";
            case CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS:
                return "SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS";
            case CUJ_SUW_LOADING_TO_NEXT_FLOW:
                return "SUW_LOADING_TO_NEXT_FLOW";
            case CUJ_SUW_LOADING_SCREEN_FOR_STATUS:
                return "SUW_LOADING_SCREEN_FOR_STATUS";
            case CUJ_SPLIT_SCREEN_ENTER:
                return "SPLIT_SCREEN_ENTER";
            case CUJ_SPLIT_SCREEN_EXIT:
                return "SPLIT_SCREEN_EXIT";
            case CUJ_LOCKSCREEN_LAUNCH_CAMERA:
                return "LOCKSCREEN_LAUNCH_CAMERA";
            case CUJ_SPLIT_SCREEN_RESIZE:
                return "SPLIT_SCREEN_RESIZE";
            case CUJ_SETTINGS_SLIDER:
                return "SETTINGS_SLIDER";
            case CUJ_TAKE_SCREENSHOT:
                return "TAKE_SCREENSHOT";
            case CUJ_VOLUME_CONTROL:
                return "VOLUME_CONTROL";
            case CUJ_BIOMETRIC_PROMPT_TRANSITION:
                return "BIOMETRIC_PROMPT_TRANSITION";
            case CUJ_SETTINGS_TOGGLE:
                return "SETTINGS_TOGGLE";
            case CUJ_SHADE_DIALOG_OPEN:
                return "SHADE_DIALOG_OPEN";
            case CUJ_USER_DIALOG_OPEN:
                return "USER_DIALOG_OPEN";
            case CUJ_TASKBAR_EXPAND:
                return "TASKBAR_EXPAND";
            case CUJ_TASKBAR_COLLAPSE:
                return "TASKBAR_COLLAPSE";
            case CUJ_SHADE_CLEAR_ALL:
                return "SHADE_CLEAR_ALL";
            case CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION:
                return "LAUNCHER_UNLOCK_ENTRANCE_ANIMATION";
            case CUJ_LOCKSCREEN_OCCLUSION:
                return "LOCKSCREEN_OCCLUSION";
            case CUJ_RECENTS_SCROLLING:
                return "RECENTS_SCROLLING";
            case CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS:
                return "LAUNCHER_APP_SWIPE_TO_RECENTS";
            case CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE:
                return "LAUNCHER_CLOSE_ALL_APPS_SWIPE";
            case CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME:
                return "LAUNCHER_CLOSE_ALL_APPS_TO_HOME";
            case CUJ_LOCKSCREEN_CLOCK_MOVE_ANIMATION:
                return "LOCKSCREEN_CLOCK_MOVE_ANIMATION";
            case CUJ_LAUNCHER_OPEN_SEARCH_RESULT:
                return "LAUNCHER_OPEN_SEARCH_RESULT";
            case CUJ_LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK:
                return "LAUNCHER_APP_CLOSE_TO_HOME_FALLBACK";
            case CUJ_IME_INSETS_SHOW_ANIMATION:
                return "IME_INSETS_SHOW_ANIMATION";
            case CUJ_IME_INSETS_HIDE_ANIMATION:
                return "IME_INSETS_HIDE_ANIMATION";
            case CUJ_SPLIT_SCREEN_DOUBLE_TAP_DIVIDER:
                return "SPLIT_SCREEN_DOUBLE_TAP_DIVIDER";
            case CUJ_LAUNCHER_UNFOLD_ANIM:
                return "LAUNCHER_UNFOLD_ANIM";
            case CUJ_PREDICTIVE_BACK_CROSS_ACTIVITY:
                return "PREDICTIVE_BACK_CROSS_ACTIVITY";
            case CUJ_PREDICTIVE_BACK_CROSS_TASK:
                return "PREDICTIVE_BACK_CROSS_TASK";
            case CUJ_PREDICTIVE_BACK_HOME:
                return "PREDICTIVE_BACK_HOME";
            case CUJ_LAUNCHER_SEARCH_QSB_OPEN:
                return "LAUNCHER_SEARCH_QSB_OPEN";
            case CUJ_BACK_PANEL_ARROW:
                return "BACK_PANEL_ARROW";
            case CUJ_LAUNCHER_CLOSE_ALL_APPS_BACK:
                return "LAUNCHER_CLOSE_ALL_APPS_BACK";
            case CUJ_LAUNCHER_SEARCH_QSB_WEB_SEARCH:
                return "LAUNCHER_SEARCH_QSB_WEB_SEARCH";
        }
        return "UNKNOWN";
    }

    public static int getStatsdInteractionType(@CujType int cujType) {
        return CUJ_TO_STATSD_INTERACTION_TYPE[cujType];
    }

    /** Returns whether the measurements for the given CUJ should be written to statsd. */
    public static boolean logToStatsd(@CujType int cujType) {
        return getStatsdInteractionType(cujType) != NO_STATSD_LOGGING;
    }

    /**
     * A helper method to translate interaction type to CUJ name.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the name of the interaction type
     */
    public static String getNameOfInteraction(int interactionType) {
        // There is an offset amount of 1 between cujType and interactionType.
        return Cuj.getNameOfCuj(getCujTypeFromInteraction(interactionType));
    }

    /**
     * A helper method to translate interaction type to CUJ type.
     *
     * @param interactionType the interaction type defined in AtomsProto.java
     * @return the integer in {@link Cuj.CujType}
     */
    private static int getCujTypeFromInteraction(int interactionType) {
        return interactionType - 1;
    }
}
