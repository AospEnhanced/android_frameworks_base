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

package com.android.server.display.mode;

import java.util.List;

interface Vote {
    // DEFAULT_RENDER_FRAME_RATE votes for render frame rate [0, DEFAULT]. As the lowest
    // priority vote, it's overridden by all other considerations. It acts to set a default
    // frame rate for a device.
    int PRIORITY_DEFAULT_RENDER_FRAME_RATE = 0;

    // PRIORITY_FLICKER_REFRESH_RATE votes for a single refresh rate like [60,60], [90,90] or
    // null. It is used to set a preferred refresh rate value in case the higher priority votes
    // result is a range.
    static final int PRIORITY_FLICKER_REFRESH_RATE = 1;

    // High-brightness-mode may need a specific range of refresh-rates to function properly.
    int PRIORITY_HIGH_BRIGHTNESS_MODE = 2;

    // SETTING_MIN_RENDER_FRAME_RATE is used to propose a lower bound of the render frame rate.
    // It votes [minRefreshRate, Float.POSITIVE_INFINITY]
    int PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE = 3;

    // User setting preferred display resolution.
    int PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE = 4;

    // APP_REQUEST_RENDER_FRAME_RATE_RANGE is used to for internal apps to limit the render
    // frame rate in certain cases, mostly to preserve power.
    // @see android.view.WindowManager.LayoutParams#preferredMinRefreshRate
    // @see android.view.WindowManager.LayoutParams#preferredMaxRefreshRate
    // It votes to [preferredMinRefreshRate, preferredMaxRefreshRate].
    int PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE = 5;

    // We split the app request into different priorities in case we can satisfy one desire
    // without the other.

    // Application can specify preferred refresh rate with below attrs.
    // @see android.view.WindowManager.LayoutParams#preferredRefreshRate
    // @see android.view.WindowManager.LayoutParams#preferredDisplayModeId
    //
    // When the app specifies a LayoutParams#preferredDisplayModeId, in addition to the
    // refresh rate, it also chooses a preferred size (resolution) as part of the selected
    // mode id. The app preference is then translated to APP_REQUEST_BASE_MODE_REFRESH_RATE and
    // optionally to APP_REQUEST_SIZE as well, if a mode id was selected.
    // The system also forces some apps like denylisted app to run at a lower refresh rate.
    // @see android.R.array#config_highRefreshRateBlacklist
    //
    // When summarizing the votes and filtering the allowed display modes, these votes determine
    // which mode id should be the base mode id to be sent to SurfaceFlinger:
    // - APP_REQUEST_BASE_MODE_REFRESH_RATE is used to validate the vote summary. If a summary
    //   includes a base mode refresh rate, but it is not in the refresh rate range, then the
    //   summary is considered invalid so we could drop a lower priority vote and try again.
    // - APP_REQUEST_SIZE is used to filter out display modes of a different size.
    //
    // The preferred refresh rate is set on the main surface of the app outside of
    // DisplayModeDirector.
    // @see com.android.server.wm.WindowState#updateFrameRateSelectionPriorityIfNeeded
    int PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE = 6;

    int PRIORITY_APP_REQUEST_SIZE = 7;

    // SETTING_PEAK_RENDER_FRAME_RATE has a high priority and will restrict the bounds of the
    // rest of low priority voters. It votes [0, max(PEAK, MIN)]
    int PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE = 8;

    // Restrict all displays to 60Hz when external display is connected. It votes [59Hz, 61Hz].
    int PRIORITY_SYNCHRONIZED_REFRESH_RATE = 9;

    // Restrict displays max available resolution and refresh rates. It votes [0, LIMIT]
    int PRIORITY_LIMIT_MODE = 10;

    // To avoid delay in switching between 60HZ -> 90HZ when activating LHBM, set refresh
    // rate to max value (same as for PRIORITY_UDFPS) on lock screen
    int PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE = 11;

    // For concurrent displays we want to limit refresh rate on all displays
    int PRIORITY_LAYOUT_LIMITED_FRAME_RATE = 12;

    // LOW_POWER_MODE force the render frame rate to [0, 60HZ] if
    // Settings.Global.LOW_POWER_MODE is on.
    int PRIORITY_LOW_POWER_MODE = 13;

    // PRIORITY_FLICKER_REFRESH_RATE_SWITCH votes for disabling refresh rate switching. If the
    // higher priority voters' result is a range, it will fix the rate to a single choice.
    // It's used to avoid refresh rate switches in certain conditions which may result in the
    // user seeing the display flickering when the switches occur.
    int PRIORITY_FLICKER_REFRESH_RATE_SWITCH = 14;

    // Force display to [0, 60HZ] if skin temperature is at or above CRITICAL.
    int PRIORITY_SKIN_TEMPERATURE = 15;

    // The proximity sensor needs the refresh rate to be locked in order to function, so this is
    // set to a high priority.
    int PRIORITY_PROXIMITY = 16;

    // The Under-Display Fingerprint Sensor (UDFPS) needs the refresh rate to be locked in order
    // to function, so this needs to be the highest priority of all votes.
    int PRIORITY_UDFPS = 17;

    // Whenever a new priority is added, remember to update MIN_PRIORITY, MAX_PRIORITY, and
    // APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF, as well as priorityToString.

    int MIN_PRIORITY = PRIORITY_DEFAULT_RENDER_FRAME_RATE;
    int MAX_PRIORITY = PRIORITY_UDFPS;

    // The cutoff for the app request refresh rate range. Votes with priorities lower than this
    // value will not be considered when constructing the app request refresh rate range.
    int APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF =
            PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE;

    /**
     * A value signifying an invalid width or height in a vote.
     */
    int INVALID_SIZE = -1;

    void updateSummary(VoteSummary summary);

    static Vote forPhysicalRefreshRates(float minRefreshRate, float maxRefreshRate) {
        return new CombinedVote(
                List.of(
                        new RefreshRateVote.PhysicalVote(minRefreshRate, maxRefreshRate),
                        new DisableRefreshRateSwitchingVote(minRefreshRate == maxRefreshRate)
                )
        );
    }

    static Vote forRenderFrameRates(float minFrameRate, float maxFrameRate) {
        return new RefreshRateVote.RenderVote(minFrameRate, maxFrameRate);
    }

    static Vote forSize(int width, int height) {
        return new SizeVote(width, height, width, height);
    }

    static Vote forSizeAndPhysicalRefreshRatesRange(int minWidth, int minHeight,
            int width, int height, float minRefreshRate, float maxRefreshRate) {
        return new CombinedVote(
                List.of(
                        new SizeVote(width, height, minWidth, minHeight),
                        new RefreshRateVote.PhysicalVote(minRefreshRate, maxRefreshRate),
                        new DisableRefreshRateSwitchingVote(minRefreshRate == maxRefreshRate)
                )
        );
    }

    static Vote forDisableRefreshRateSwitching() {
        return new DisableRefreshRateSwitchingVote(true);
    }

    static Vote forBaseModeRefreshRate(float baseModeRefreshRate) {
        return new BaseModeRefreshRateVote(baseModeRefreshRate);
    }

    static Vote forSupportedModes(List<SupportedModesVote.SupportedMode> supportedModes) {
        return new SupportedModesVote(supportedModes);
    }


    static Vote forSupportedModesAndDisableRefreshRateSwitching(
            List<SupportedModesVote.SupportedMode> supportedModes) {
        return new CombinedVote(
                List.of(forDisableRefreshRateSwitching(), forSupportedModes(supportedModes)));
    }

    static String priorityToString(int priority) {
        switch (priority) {
            case PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE:
                return "PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE";
            case PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE:
                return "PRIORITY_APP_REQUEST_RENDER_FRAME_RATE_RANGE";
            case PRIORITY_APP_REQUEST_SIZE:
                return "PRIORITY_APP_REQUEST_SIZE";
            case PRIORITY_DEFAULT_RENDER_FRAME_RATE:
                return "PRIORITY_DEFAULT_REFRESH_RATE";
            case PRIORITY_FLICKER_REFRESH_RATE:
                return "PRIORITY_FLICKER_REFRESH_RATE";
            case PRIORITY_FLICKER_REFRESH_RATE_SWITCH:
                return "PRIORITY_FLICKER_REFRESH_RATE_SWITCH";
            case PRIORITY_HIGH_BRIGHTNESS_MODE:
                return "PRIORITY_HIGH_BRIGHTNESS_MODE";
            case PRIORITY_PROXIMITY:
                return "PRIORITY_PROXIMITY";
            case PRIORITY_LOW_POWER_MODE:
                return "PRIORITY_LOW_POWER_MODE";
            case PRIORITY_SKIN_TEMPERATURE:
                return "PRIORITY_SKIN_TEMPERATURE";
            case PRIORITY_UDFPS:
                return "PRIORITY_UDFPS";
            case PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE:
                return "PRIORITY_USER_SETTING_MIN_RENDER_FRAME_RATE";
            case PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE:
                return "PRIORITY_USER_SETTING_DISPLAY_PREFERRED_SIZE";
            case PRIORITY_LIMIT_MODE:
                return "PRIORITY_LIMIT_MODE";
            case PRIORITY_SYNCHRONIZED_REFRESH_RATE:
                return "PRIORITY_SYNCHRONIZED_REFRESH_RATE";
            case PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE:
                return "PRIORITY_USER_SETTING_PEAK_RENDER_FRAME_RATE";
            case PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE:
                return "PRIORITY_AUTH_OPTIMIZER_RENDER_FRAME_RATE";
            case PRIORITY_LAYOUT_LIMITED_FRAME_RATE:
                return "PRIORITY_LAYOUT_LIMITED_FRAME_RATE";
            default:
                return Integer.toString(priority);
        }
    }
}
