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

package com.android.server.display.feature;

import android.os.Build;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.display.feature.flags.Flags;

import java.util.function.Supplier;

/**
 * Utility class to read the flags used in the display manager server.
 */
public class DisplayManagerFlags {
    private static final boolean DEBUG = false;
    private static final String TAG = "DisplayManagerFlags";

    private final FlagState mConnectedDisplayManagementFlagState = new FlagState(
            Flags.FLAG_ENABLE_CONNECTED_DISPLAY_MANAGEMENT,
            Flags::enableConnectedDisplayManagement);

    private final FlagState mNbmControllerFlagState = new FlagState(
            Flags.FLAG_ENABLE_NBM_CONTROLLER,
            Flags::enableNbmController);

    private final FlagState mHdrClamperFlagState = new FlagState(
            Flags.FLAG_ENABLE_HDR_CLAMPER,
            Flags::enableHdrClamper);

    private final FlagState mAdaptiveToneImprovements1 = new FlagState(
            Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1,
            Flags::enableAdaptiveToneImprovements1);

    private final FlagState mDisplayOffloadFlagState = new FlagState(
            Flags.FLAG_ENABLE_DISPLAY_OFFLOAD,
            Flags::enableDisplayOffload);

    private final FlagState mExternalDisplayLimitModeState = new FlagState(
            Flags.FLAG_ENABLE_MODE_LIMIT_FOR_EXTERNAL_DISPLAY,
            Flags::enableModeLimitForExternalDisplay);

    private final FlagState mBackUpSmoothDisplayAndForcePeakRefreshRateFlagState = new FlagState(
            Flags.FLAG_BACK_UP_SMOOTH_DISPLAY_AND_FORCE_PEAK_REFRESH_RATE,
            Flags::backUpSmoothDisplayAndForcePeakRefreshRate);

    /** Returns whether connected display management is enabled or not. */
    public boolean isConnectedDisplayManagementEnabled() {
        return mConnectedDisplayManagementFlagState.isEnabled();
    }

    /** Returns whether hdr clamper is enabled on not*/
    public boolean isNbmControllerEnabled() {
        return mNbmControllerFlagState.isEnabled();
    }

    public boolean isHdrClamperEnabled() {
        return mHdrClamperFlagState.isEnabled();
    }

    /**
     * Returns whether adaptive tone improvements are enabled
     */
    public boolean isAdaptiveTone1Enabled() {
        return mAdaptiveToneImprovements1.isEnabled();
    }

    /** Returns whether resolution range voting feature is enabled or not. */
    public boolean isDisplayResolutionRangeVotingEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /**
     * @return Whether user preferred mode is added as a vote in
     *      {@link com.android.server.display.mode.DisplayModeDirector}
     */
    public boolean isUserPreferredModeVoteEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /**
     * @return Whether external display mode limitation is enabled.
     */
    public boolean isExternalDisplayLimitModeEnabled() {
        return mExternalDisplayLimitModeState.isEnabled();
    }

    /**
     * @return Whether displays refresh rate synchronization is enabled.
     */
    public boolean isDisplaysRefreshRatesSynchronizationEnabled() {
        return isExternalDisplayLimitModeEnabled();
    }

    /** Returns whether displayoffload is enabled on not */
    public boolean isDisplayOffloadEnabled() {
        return mDisplayOffloadFlagState.isEnabled();
    }

    public boolean isBackUpSmoothDisplayAndForcePeakRefreshRateEnabled() {
        return mBackUpSmoothDisplayAndForcePeakRefreshRateFlagState.isEnabled();
    }

    private static class FlagState {

        private final String mName;

        private final Supplier<Boolean> mFlagFunction;
        private boolean mEnabledSet;
        private boolean mEnabled;

        private FlagState(String name, Supplier<Boolean> flagFunction) {
            mName = name;
            mFlagFunction = flagFunction;
        }

        // TODO(b/297159910): Simplify using READ-ONLY flags when available.
        private boolean isEnabled() {
            if (mEnabledSet) {
                if (DEBUG) {
                    Slog.d(TAG, mName + ": mEnabled. Recall = " + mEnabled);
                }
                return mEnabled;
            }
            mEnabled = flagOrSystemProperty(mFlagFunction, mName);
            if (DEBUG) {
                Slog.d(TAG, mName + ": mEnabled. Flag value = " + mEnabled);
            }
            mEnabledSet = true;
            return mEnabled;
        }

        private boolean flagOrSystemProperty(Supplier<Boolean> flagFunction, String flagName) {
            boolean flagValue = false;
            try {
                flagValue = flagFunction.get();
            } catch (Throwable ex) {
                if (DEBUG) {
                    Slog.i(TAG, "Flags not ready yet. Return false for " + flagName, ex);
                }
            }
            // TODO(b/299462337) Remove when the infrastructure is ready.
            if (Build.IS_ENG || Build.IS_USERDEBUG) {
                return SystemProperties.getBoolean("persist.sys." + flagName + "-override",
                        flagValue);
            }
            return flagValue;
        }
    }
}
