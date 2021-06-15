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

package com.android.server.display;

import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_HIGH_ZONE;
import static android.hardware.display.DisplayManager.DeviceConfig.KEY_REFRESH_RATE_IN_LOW_ZONE;

import static com.android.server.display.DisplayModeDirector.Vote.INVALID_SIZE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.Preconditions;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;
import com.android.server.display.DisplayModeDirector.BrightnessObserver;
import com.android.server.display.DisplayModeDirector.DesiredDisplayModeSpecs;
import com.android.server.display.DisplayModeDirector.Vote;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.testutils.FakeDeviceConfigInterface;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayModeDirectorTest {
    // The tolerance within which we consider something approximately equals.
    private static final String TAG = "DisplayModeDirectorTest";
    private static final boolean DEBUG = false;
    private static final float FLOAT_TOLERANCE = 0.01f;
    private static final int DISPLAY_ID = 0;

    private Context mContext;
    private FakesInjector mInjector;
    private Handler mHandler;
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Mock
    public StatusBarManagerInternal mStatusBarMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        final MockContentResolver resolver = mSettingsProviderRule.mockContentResolver(mContext);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mInjector = new FakesInjector();
        mHandler = new Handler(Looper.getMainLooper());

        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarMock);
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId) {
        return createDirectorFromRefreshRateArray(refreshRates, baseModeId, refreshRates[0]);
    }

    private DisplayModeDirector createDirectorFromRefreshRateArray(
            float[] refreshRates, int baseModeId, float defaultRefreshRate) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector);
        Display.Mode[] modes = new Display.Mode[refreshRates.length];
        Display.Mode defaultMode = null;
        for (int i = 0; i < refreshRates.length; i++) {
            modes[i] = new Display.Mode(
                    /*modeId=*/baseModeId + i, /*width=*/1000, /*height=*/1000, refreshRates[i]);
            if (refreshRates[i] == defaultRefreshRate) {
                defaultMode = modes[i];
            }
        }
        assertThat(defaultMode).isNotNull();
        return createDirectorFromModeArray(modes, defaultMode);
    }

    private DisplayModeDirector createDirectorFromModeArray(Display.Mode[] modes,
            Display.Mode defaultMode) {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector);
        director.setLoggingEnabled(true);
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, defaultMode);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        return director;
    }

    private DisplayModeDirector createDirectorFromFpsRange(int minFps, int maxFps) {
        int numRefreshRates = maxFps - minFps + 1;
        float[] refreshRates = new float[numRefreshRates];
        for (int i = 0; i < numRefreshRates; i++) {
            refreshRates[i] = minFps + i;
        }
        return createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/minFps,
                /*defaultRefreshRate=*/minFps);
    }

    @Test
    public void testDisplayModeVoting() {
        // With no votes present, DisplayModeDirector should allow any refresh rate.
        DesiredDisplayModeSpecs modeSpecs =
                createDirectorFromFpsRange(60, 90).getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(modeSpecs.baseModeId).isEqualTo(60);
        assertThat(modeSpecs.primaryRefreshRateRange.min).isEqualTo(0f);
        assertThat(modeSpecs.primaryRefreshRateRange.max).isEqualTo(Float.POSITIVE_INFINITY);

        int numPriorities =
                DisplayModeDirector.Vote.MAX_PRIORITY - DisplayModeDirector.Vote.MIN_PRIORITY + 1;

        // Ensure vote priority works as expected. As we add new votes with higher priority, they
        // should take precedence over lower priority votes.
        {
            int minFps = 60;
            int maxFps = 90;
            DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
            assertTrue(2 * numPriorities < maxFps - minFps + 1);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(DISPLAY_ID, votes);
            for (int i = 0; i < numPriorities; i++) {
                int priority = Vote.MIN_PRIORITY + i;
                votes.put(priority, Vote.forRefreshRates(minFps + i, maxFps - i));
                director.injectVotesByDisplay(votesByDisplay);
                modeSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
                assertThat(modeSpecs.baseModeId).isEqualTo(minFps + i);
                assertThat(modeSpecs.primaryRefreshRateRange.min)
                        .isEqualTo((float) (minFps + i));
                assertThat(modeSpecs.primaryRefreshRateRange.max)
                        .isEqualTo((float) (maxFps - i));
            }
        }

        // Ensure lower priority votes are able to influence the final decision, even in the
        // presence of higher priority votes.
        {
            assertTrue(numPriorities >= 2);
            DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
            SparseArray<Vote> votes = new SparseArray<>();
            SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
            votesByDisplay.put(DISPLAY_ID, votes);
            votes.put(Vote.MAX_PRIORITY, Vote.forRefreshRates(65, 85));
            votes.put(Vote.MIN_PRIORITY, Vote.forRefreshRates(70, 80));
            director.injectVotesByDisplay(votesByDisplay);
            modeSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
            assertThat(modeSpecs.baseModeId).isEqualTo(70);
            assertThat(modeSpecs.primaryRefreshRateRange.min).isEqualTo(70f);
            assertThat(modeSpecs.primaryRefreshRateRange.max).isEqualTo(80f);
        }
    }

    @Test
    public void testVotingWithFloatingPointErrors() {
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        float error = FLOAT_TOLERANCE / 4;
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, Vote.forRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE,
                Vote.forRefreshRates(60 + error, 60 + error));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forRefreshRates(60 - error, 60 - error));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testFlickerHasLowerPriorityThanUserAndRangeIsSingle() {
        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE
                < Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE
                < Vote.PRIORITY_APP_REQUEST_SIZE);

        assertTrue(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH
                > Vote.PRIORITY_LOW_POWER_MODE);

        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primaryRefreshRateRange.min);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(90, 90));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primaryRefreshRateRange.min);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primaryRefreshRateRange.min);

        votes.clear();
        appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(90, 90));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max)
                .isWithin(FLOAT_TOLERANCE).of(desiredSpecs.primaryRefreshRateRange.min);
    }

    @Test
    public void testLPMHasHigherPriorityThanUser() {
        assertTrue(Vote.PRIORITY_LOW_POWER_MODE > Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertTrue(Vote.PRIORITY_LOW_POWER_MODE > Vote.PRIORITY_APP_REQUEST_SIZE);


        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);

        votes.clear();
        appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);

        votes.clear();
        appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(90, 90));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);
    }

    @Test
    public void testAppRequestRefreshRateRange() {
        // Confirm that the app request range doesn't include flicker or min refresh rate settings,
        // but does include everything else.
        assertTrue(
                Vote.PRIORITY_FLICKER_REFRESH_RATE
                        < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE
                < Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);
        assertTrue(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/75, /*width=*/2000, /*height=*/2000, 75);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(75);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);
    }

    void verifySpecsWithRefreshRateSettings(DisplayModeDirector director, float minFps,
            float peakFps, float defaultFps, float primaryMin, float primaryMax,
            float appRequestMin, float appRequestMax) {
        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecsWithInjectedFpsSettings(
                minFps, peakFps, defaultFps);
        assertThat(specs.primaryRefreshRateRange.min).isEqualTo(primaryMin);
        assertThat(specs.primaryRefreshRateRange.max).isEqualTo(primaryMax);
        assertThat(specs.appRequestRefreshRateRange.min).isEqualTo(appRequestMin);
        assertThat(specs.appRequestRefreshRateRange.max).isEqualTo(appRequestMax);
    }

    @Test
    public void testSpecsFromRefreshRateSettings() {
        // Confirm that, with varying settings for min, peak, and default refresh rate,
        // DesiredDisplayModeSpecs is calculated correctly.
        float[] refreshRates = {30.f, 60.f, 90.f, 120.f, 150.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);

        float inf = Float.POSITIVE_INFINITY;
        verifySpecsWithRefreshRateSettings(director, 0, 0, 0, 0, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 0, 0, 90, 0, 90, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 0, 0, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 60, 0, 60, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 0, 90, 120, 0, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 0, 90, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 120, 90, 120, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 0, 60, 90, inf, 0, inf);
        verifySpecsWithRefreshRateSettings(director, 90, 120, 0, 90, 120, 0, 120);
        verifySpecsWithRefreshRateSettings(director, 90, 60, 0, 90, 90, 0, 90);
        verifySpecsWithRefreshRateSettings(director, 60, 120, 90, 60, 90, 0, 120);
    }

    void verifyBrightnessObserverCall(DisplayModeDirector director, float minFps, float peakFps,
            float defaultFps, float brightnessObserverMin, float brightnessObserverMax) {
        BrightnessObserver brightnessObserver = mock(BrightnessObserver.class);
        director.injectBrightnessObserver(brightnessObserver);
        director.getDesiredDisplayModeSpecsWithInjectedFpsSettings(minFps, peakFps, defaultFps);
        verify(brightnessObserver)
                .onRefreshRateSettingChangedLocked(brightnessObserverMin, brightnessObserverMax);
    }

    @Test
    public void testBrightnessObserverCallWithRefreshRateSettings() {
        // Confirm that, with varying settings for min, peak, and default refresh rate, we make the
        // correct call to the brightness observer.
        float[] refreshRates = {60.f, 90.f, 120.f};
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(refreshRates, /*baseModeId=*/0);
        verifyBrightnessObserverCall(director, 0, 0, 0, 0, 0);
        verifyBrightnessObserverCall(director, 0, 0, 90, 0, 90);
        verifyBrightnessObserverCall(director, 0, 90, 0, 0, 90);
        verifyBrightnessObserverCall(director, 0, 90, 60, 0, 60);
        verifyBrightnessObserverCall(director, 90, 90, 0, 90, 90);
        verifyBrightnessObserverCall(director, 120, 90, 0, 120, 90);
    }

    @Test
    public void testVotingWithAlwaysRespectAppRequest() {
        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/50, /*width=*/1000, /*height=*/1000, 50);
        modes[1] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);


        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(0, 60));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE, Vote.forRefreshRates(60, 90));
        Display.Mode appRequestedMode = modes[2];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_USER_SETTING_PEAK_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);

        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isFalse();
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);

        director.setShouldAlwaysRespectAppRequestedMode(true);
        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isTrue();
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isAtMost(50);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);

        director.setShouldAlwaysRespectAppRequestedMode(false);
        assertThat(director.shouldAlwaysRespectAppRequestedMode()).isFalse();

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(60);
    }

    @Test
    public void testVotingWithSwitchingTypeNone() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE, Vote.forRefreshRates(30, 90));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));


        director.injectVotesByDisplay(votesByDisplay);
        assertThat(director.getModeSwitchingType())
                .isNotEqualTo(DisplayManager.SWITCHING_TYPE_NONE);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);

        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(30);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_NONE);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_NONE);

        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(30);
        assertThat(desiredSpecs.baseModeId).isEqualTo(30);
    }

    @Test
    public void testVotingWithSwitchingTypeWithinGroups() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.allowGroupSwitching).isFalse();
    }

    @Test
    public void testVotingWithSwitchingTypeWithinAndAcrossGroups() {
        DisplayModeDirector director = createDirectorFromFpsRange(0, 90);

        director.setModeSwitchingType(DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS);
        assertThat(director.getModeSwitchingType())
                .isEqualTo(DisplayManager.SWITCHING_TYPE_ACROSS_AND_WITHIN_GROUPS);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.allowGroupSwitching).isTrue();
    }

    @Test
    public void testDefaultDisplayModeIsSelectedIfAvailable() {
        final float[] refreshRates = new float[]{24f, 25f, 30f, 60f, 90f};
        final int defaultModeId = 3;
        DisplayModeDirector director = createDirectorFromRefreshRateArray(
                refreshRates, /*baseModeId=*/0, refreshRates[defaultModeId]);

        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(specs.baseModeId).isEqualTo(defaultModeId);
    }

    @Test
    public void testStaleAppRequestSize() {
        DisplayModeDirector director =
                new DisplayModeDirector(mContext, mHandler, mInjector);
        Display.Mode[] modes = new Display.Mode[] {
                new Display.Mode(1, 1280, 720, 60),
        };
        Display.Mode defaultMode = modes[0];

        // Inject supported modes
        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, modes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);

        // Inject default mode
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, defaultMode);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);

        // Inject votes
        SparseArray<Vote> votes = new SparseArray<>();
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(1920, 1080));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(60));
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        director.injectVotesByDisplay(votesByDisplay);

        director.setShouldAlwaysRespectAppRequestedMode(true);

        // We should return the only available mode
        DesiredDisplayModeSpecs specs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(specs.baseModeId).isEqualTo(defaultMode.getModeId());
    }

    @Test
    public void testBrightnessObserverGetsUpdatedRefreshRatesForZone() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);
        SensorManager sensorManager = createMockSensorManager(createLightSensor());

        final int initialRefreshRate = 60;
        mInjector.getDeviceConfig().setRefreshRateInLowZone(initialRefreshRate);
        director.start(sensorManager);
        assertThat(director.getBrightnessObserver().getRefreshRateInLowZone())
                .isEqualTo(initialRefreshRate);

        final int updatedRefreshRate = 90;
        mInjector.getDeviceConfig().setRefreshRateInLowZone(updatedRefreshRate);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getBrightnessObserver().getRefreshRateInLowZone())
                .isEqualTo(updatedRefreshRate);
    }

    @Test
    public void testBrightnessObserverThresholdsInZone() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, /* baseModeId= */ 0);
        SensorManager sensorManager = createMockSensorManager(createLightSensor());

        final int[] initialDisplayThresholds = { 10 };
        final int[] initialAmbientThresholds = { 20 };

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setLowDisplayBrightnessThresholds(initialDisplayThresholds);
        config.setLowAmbientBrightnessThresholds(initialAmbientThresholds);
        director.start(sensorManager);

        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(initialDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(initialAmbientThresholds);

        final int[] updatedDisplayThresholds = { 9, 14 };
        final int[] updatedAmbientThresholds = { -1, 19 };
        config.setLowDisplayBrightnessThresholds(updatedDisplayThresholds);
        config.setLowAmbientBrightnessThresholds(updatedAmbientThresholds);
        // Need to wait for the property change to propagate to the main thread.
        waitForIdleSync();
        assertThat(director.getBrightnessObserver().getLowDisplayBrightnessThresholds())
                .isEqualTo(updatedDisplayThresholds);
        assertThat(director.getBrightnessObserver().getLowAmbientBrightnessThresholds())
                .isEqualTo(updatedAmbientThresholds);
    }

    @Test
    public void testLockFpsForLowZone() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInLowZone(90);
        config.setLowDisplayBrightnessThresholds(new int[] { 10 });
        config.setLowAmbientBrightnessThresholds(new int[] { 20 });

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        Mockito.verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        setBrightness(10);
        // Sensor reads 20 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 20 /*lux*/));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForRefreshRate(vote, 90 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote.disableRefreshRateSwitching).isTrue();

        setBrightness(125);
        // Sensor reads 1000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 1000 /*lux*/));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();
    }

    @Test
    public void testLockFpsForHighZone() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInHighZone(60);
        config.setHighDisplayBrightnessThresholds(new int[] { 255 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);

        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));
        SensorEventListener listener = listenerCaptor.getValue();

        setBrightness(100);
        // Sensor reads 2000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 2000));

        Vote vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertThat(vote).isNull();
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNull();

        setBrightness(255);
        // Sensor reads 9000 lux,
        listener.onSensorChanged(TestUtils.createSensorEvent(lightSensor, 9000));

        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE);
        assertVoteForRefreshRate(vote, 60 /*fps*/);
        vote = director.getVote(Display.DEFAULT_DISPLAY, Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH);
        assertThat(vote).isNotNull();
        assertThat(vote.disableRefreshRateSwitching).isTrue();
    }

    @Test
    public void testSensorRegistration() {
        // First, configure brightness zones or DMD won't register for sensor data.
        final FakeDeviceConfig config = mInjector.getDeviceConfig();
        config.setRefreshRateInHighZone(60);
        config.setHighDisplayBrightnessThresholds(new int[] { 255 });
        config.setHighAmbientBrightnessThresholds(new int[] { 8000 });

        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f}, 0);
        setPeakRefreshRate(90 /*fps*/);
        director.getSettingsObserver().setDefaultRefreshRate(90);
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);

        Sensor lightSensor = createLightSensor();
        SensorManager sensorManager = createMockSensorManager(lightSensor);

        director.start(sensorManager);
        ArgumentCaptor<SensorEventListener> listenerCaptor =
                ArgumentCaptor.forClass(SensorEventListener.class);
        verify(sensorManager, Mockito.timeout(TimeUnit.SECONDS.toMillis(1)))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

        // Display state changed from On to Doze
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_DOZE);
        verify(sensorManager)
                .unregisterListener(listenerCaptor.capture());

        // Display state changed from Doze to On
        director.getBrightnessObserver().setDefaultDisplayState(Display.STATE_ON);
        verify(sensorManager, times(2))
                .registerListener(
                        listenerCaptor.capture(),
                        eq(lightSensor),
                        anyInt(),
                        any(Handler.class));

    }

    @Test
    public void testUdfpsListenerGetsRegistered() {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 110.f}, 0);
        verify(mStatusBarMock, never()).setUdfpsHbmListener(any());

        director.onBootCompleted();
        verify(mStatusBarMock).setUdfpsHbmListener(eq(director.getUdpfsObserver()));
    }

    @Test
    public void testGbhmVotesFor60hz() throws Exception {
        DisplayModeDirector director =
                createDirectorFromRefreshRateArray(new float[] {60.f, 90.f, 110.f}, 0);
        director.start(createMockSensorManager());
        director.onBootCompleted();
        ArgumentCaptor<IUdfpsHbmListener> captor =
                ArgumentCaptor.forClass(IUdfpsHbmListener.class);
        verify(mStatusBarMock).setUdfpsHbmListener(captor.capture());
        IUdfpsHbmListener hbmListener = captor.getValue();

        // Should be no vote initially
        Vote vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertNull(vote);

        // Enabling GHBM votes for 60hz
        hbmListener.onHbmEnabled(IUdfpsHbmListener.GLOBAL_HBM, DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertVoteForRefreshRate(vote, 60.f);

        // Disabling GHBM removes the vote
        hbmListener.onHbmDisabled(IUdfpsHbmListener.GLOBAL_HBM, DISPLAY_ID);
        vote = director.getVote(DISPLAY_ID, Vote.PRIORITY_UDFPS);
        assertNull(vote);
    }

    @Test
    public void testAppRequestMaxRefreshRate() {
        // Confirm that the app max request range doesn't include flicker or min refresh rate
        // settings but does include everything else.
        assertTrue(Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE
                >= Vote.APP_REQUEST_REFRESH_RATE_RANGE_PRIORITY_CUTOFF);

        Display.Mode[] modes = new Display.Mode[3];
        modes[0] = new Display.Mode(
                /*modeId=*/60, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/75, /*width=*/1000, /*height=*/1000, 75);
        modes[2] = new Display.Mode(
                /*modeId=*/90, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[1]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE, Vote.forRefreshRates(60, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(90, Float.POSITIVE_INFINITY));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isAtMost(60f);
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isAtLeast(90f);

        votes.put(Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE, Vote.forRefreshRates(0, 75));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(75);
        assertThat(desiredSpecs.appRequestRefreshRateRange.min).isZero();
        assertThat(desiredSpecs.appRequestRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(75);
    }

    @Test
    public void testAppRequestObserver_modeId() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 60, 0);

        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(appRequestRefreshRate);
        assertThat(appRequestRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestRefreshRate.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestRefreshRate.disableRefreshRateSwitching).isFalse();
        assertThat(appRequestRefreshRate.baseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(appRequestRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestRefreshRate.width).isEqualTo(INVALID_SIZE);

        Vote appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(appRequestSize);
        assertThat(appRequestSize.refreshRateRange.min).isZero();
        assertThat(appRequestSize.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestSize.disableRefreshRateSwitching).isFalse();
        assertThat(appRequestSize.baseModeRefreshRate).isZero();
        assertThat(appRequestSize.height).isEqualTo(1000);
        assertThat(appRequestSize.width).isEqualTo(1000);

        Vote appRequestMaxRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE);
        assertNull(appRequestMaxRefreshRate);

        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 90, 0);

        appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(appRequestRefreshRate);
        assertThat(appRequestRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestRefreshRate.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestRefreshRate.disableRefreshRateSwitching).isFalse();
        assertThat(appRequestRefreshRate.baseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(appRequestRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestRefreshRate.width).isEqualTo(INVALID_SIZE);

        appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(appRequestSize);
        assertThat(appRequestSize.refreshRateRange.min).isZero();
        assertThat(appRequestSize.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestSize.height).isEqualTo(1000);
        assertThat(appRequestSize.width).isEqualTo(1000);

        appRequestMaxRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE);
        assertNull(appRequestMaxRefreshRate);
    }

    @Test
    public void testAppRequestObserver_maxRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 90);
        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        Vote appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        Vote appRequestMaxRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE);
        assertNotNull(appRequestMaxRefreshRate);
        assertThat(appRequestMaxRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestMaxRefreshRate.refreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(appRequestMaxRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestMaxRefreshRate.width).isEqualTo(INVALID_SIZE);

        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, -1, 60);
        appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNull(appRequestRefreshRate);

        appRequestSize = director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNull(appRequestSize);

        appRequestMaxRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE);
        assertNotNull(appRequestMaxRefreshRate);
        assertThat(appRequestMaxRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestMaxRefreshRate.refreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(appRequestMaxRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestMaxRefreshRate.width).isEqualTo(INVALID_SIZE);
    }

    @Test
    public void testAppRequestObserver_modeIdAndMaxRefreshRate() {
        DisplayModeDirector director = createDirectorFromFpsRange(60, 90);
        director.getAppRequestObserver().setAppRequest(DISPLAY_ID, 60, 90);

        Vote appRequestRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE);
        assertNotNull(appRequestRefreshRate);
        assertThat(appRequestRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestRefreshRate.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestRefreshRate.disableRefreshRateSwitching).isFalse();
        assertThat(appRequestRefreshRate.baseModeRefreshRate).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(appRequestRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestRefreshRate.width).isEqualTo(INVALID_SIZE);

        Vote appRequestSize =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_SIZE);
        assertNotNull(appRequestSize);
        assertThat(appRequestSize.refreshRateRange.min).isZero();
        assertThat(appRequestSize.refreshRateRange.max).isPositiveInfinity();
        assertThat(appRequestSize.height).isEqualTo(1000);
        assertThat(appRequestSize.width).isEqualTo(1000);

        Vote appRequestMaxRefreshRate =
                director.getVote(DISPLAY_ID, Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE);
        assertNotNull(appRequestMaxRefreshRate);
        assertThat(appRequestMaxRefreshRate.refreshRateRange.min).isZero();
        assertThat(appRequestMaxRefreshRate.refreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(appRequestMaxRefreshRate.height).isEqualTo(INVALID_SIZE);
        assertThat(appRequestMaxRefreshRate.width).isEqualTo(INVALID_SIZE);
    }

    @Test
    public void testAppRequestsIsTheDefaultMode() {
        Display.Mode[] modes = new Display.Mode[2];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/1000, /*height=*/1000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(1);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isAtMost(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90);

        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[1];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                        appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(2);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isAtMost(60);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isAtLeast(90);
    }

    @Test
    public void testDisableRefreshRateSwitchingVote() {
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(90, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(50);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(50);
        assertThat(desiredSpecs.baseModeId).isEqualTo(50);

        votes.clear();
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE,
                Vote.forRefreshRates(70, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(80, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 90));
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(80);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(80);
        assertThat(desiredSpecs.baseModeId).isEqualTo(80);

        votes.clear();
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE,
                Vote.forRefreshRates(90, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_USER_SETTING_MIN_REFRESH_RATE,
                Vote.forRefreshRates(80, Float.POSITIVE_INFINITY));
        votes.put(Vote.PRIORITY_FLICKER_REFRESH_RATE_SWITCH, Vote.forDisableRefreshRateSwitching());
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 90));
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(90);
        assertThat(desiredSpecs.baseModeId).isEqualTo(90);
    }

    @Test
    public void testBaseModeIdInPrimaryRange() {
        DisplayModeDirector director = createDirectorFromFpsRange(50, 90);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(70));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(50);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE, Vote.forRefreshRates(0, 52));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(60);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);

        votes.clear();
        votes.put(Vote.PRIORITY_APP_REQUEST_MAX_REFRESH_RATE, Vote.forRefreshRates(0, 58));
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(55));
        votes.put(Vote.PRIORITY_LOW_POWER_MODE, Vote.forRefreshRates(0, 60));
        director.injectVotesByDisplay(votesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.primaryRefreshRateRange.min).isWithin(FLOAT_TOLERANCE).of(0);
        assertThat(desiredSpecs.primaryRefreshRateRange.max).isWithin(FLOAT_TOLERANCE).of(58);
        assertThat(desiredSpecs.baseModeId).isEqualTo(55);
    }

    @Test
    public void testStaleAppVote() {
        Display.Mode[] modes = new Display.Mode[4];
        modes[0] = new Display.Mode(
                /*modeId=*/1, /*width=*/1000, /*height=*/1000, 60);
        modes[1] = new Display.Mode(
                /*modeId=*/2, /*width=*/2000, /*height=*/2000, 60);
        modes[2] = new Display.Mode(
                /*modeId=*/3, /*width=*/1000, /*height=*/1000, 90);
        modes[3] = new Display.Mode(
                /*modeId=*/4, /*width=*/2000, /*height=*/2000, 90);

        DisplayModeDirector director = createDirectorFromModeArray(modes, modes[0]);
        SparseArray<Vote> votes = new SparseArray<>();
        SparseArray<SparseArray<Vote>> votesByDisplay = new SparseArray<>();
        votesByDisplay.put(DISPLAY_ID, votes);
        Display.Mode appRequestedMode = modes[3];
        votes.put(Vote.PRIORITY_APP_REQUEST_BASE_MODE_REFRESH_RATE,
                Vote.forBaseModeRefreshRate(appRequestedMode.getRefreshRate()));
        votes.put(Vote.PRIORITY_APP_REQUEST_SIZE, Vote.forSize(appRequestedMode.getPhysicalWidth(),
                appRequestedMode.getPhysicalHeight()));
        director.injectVotesByDisplay(votesByDisplay);
        DesiredDisplayModeSpecs desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(4);

        // Change mode Id's to simulate that a hotplug has occurred.
        Display.Mode[] newModes = new Display.Mode[4];
        newModes[0] = new Display.Mode(
                /*modeId=*/5, /*width=*/1000, /*height=*/1000, 60);
        newModes[1] = new Display.Mode(
                /*modeId=*/6, /*width=*/2000, /*height=*/2000, 60);
        newModes[2] = new Display.Mode(
                /*modeId=*/7, /*width=*/1000, /*height=*/1000, 90);
        newModes[3] = new Display.Mode(
                /*modeId=*/8, /*width=*/2000, /*height=*/2000, 90);

        SparseArray<Display.Mode[]> supportedModesByDisplay = new SparseArray<>();
        supportedModesByDisplay.put(DISPLAY_ID, newModes);
        director.injectSupportedModesByDisplay(supportedModesByDisplay);
        SparseArray<Display.Mode> defaultModesByDisplay = new SparseArray<>();
        defaultModesByDisplay.put(DISPLAY_ID, newModes[0]);
        director.injectDefaultModeByDisplay(defaultModesByDisplay);
        desiredSpecs = director.getDesiredDisplayModeSpecs(DISPLAY_ID);
        assertThat(desiredSpecs.baseModeId).isEqualTo(8);
    }

    private void assertVoteForRefreshRate(Vote vote, float refreshRate) {
        assertThat(vote).isNotNull();
        final DisplayModeDirector.RefreshRateRange expectedRange =
                new DisplayModeDirector.RefreshRateRange(refreshRate, refreshRate);
        assertThat(vote.refreshRateRange).isEqualTo(expectedRange);
    }

    private static class FakeDeviceConfig extends FakeDeviceConfigInterface {
        @Override
        public String getProperty(String namespace, String name) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            return super.getProperty(namespace, name);
        }

        @Override
        public void addOnPropertiesChangedListener(
                String namespace,
                Executor executor,
                DeviceConfig.OnPropertiesChangedListener listener) {
            Preconditions.checkArgument(DeviceConfig.NAMESPACE_DISPLAY_MANAGER.equals(namespace));
            super.addOnPropertiesChangedListener(namespace, executor, listener);
        }

        void setRefreshRateInLowZone(int fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_LOW_ZONE,
                    String.valueOf(fps));
        }

        void setLowDisplayBrightnessThresholds(int[] brightnessThresholds) {
            String thresholds = toPropertyValue(brightnessThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Brightness Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_LOW_DISPLAY_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setLowAmbientBrightnessThresholds(int[] ambientThresholds) {
            String thresholds = toPropertyValue(ambientThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Ambient Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_LOW_AMBIENT_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setRefreshRateInHighZone(int fps) {
            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_REFRESH_RATE_IN_HIGH_ZONE,
                    String.valueOf(fps));
        }

        void setHighDisplayBrightnessThresholds(int[] brightnessThresholds) {
            String thresholds = toPropertyValue(brightnessThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Brightness Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_HIGH_DISPLAY_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        void setHighAmbientBrightnessThresholds(int[] ambientThresholds) {
            String thresholds = toPropertyValue(ambientThresholds);

            if (DEBUG) {
                Slog.e(TAG, "Ambient Thresholds = " + thresholds);
            }

            putPropertyAndNotify(
                    DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                    KEY_FIXED_REFRESH_RATE_HIGH_AMBIENT_BRIGHTNESS_THRESHOLDS,
                    thresholds);
        }

        @NonNull
        private static String toPropertyValue(@NonNull int[] intArray) {
            return Arrays.stream(intArray)
                    .mapToObj(Integer::toString)
                    .collect(Collectors.joining(","));
        }
    }

    private void setBrightness(int brightness) {
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                brightness);
        mInjector.notifyBrightnessChanged();
        waitForIdleSync();
    }

    private void setPeakRefreshRate(float fps) {
        Settings.System.putFloat(mContext.getContentResolver(), Settings.System.PEAK_REFRESH_RATE,
                 fps);
        mInjector.notifyPeakRefreshRateChanged();
        waitForIdleSync();
    }

    private static SensorManager createMockSensorManager(Sensor... sensors) {
        SensorManager sensorManager = mock(SensorManager.class);
        when(sensorManager.getSensorList(anyInt())).then((invocation) -> {
            List<Sensor> requestedSensors = new ArrayList<>();
            int type = invocation.getArgument(0);
            for (Sensor sensor : sensors) {
                if (sensor.getType() == type || type == Sensor.TYPE_ALL) {
                    requestedSensors.add(sensor);
                }
            }
            return requestedSensors;
        });

        when(sensorManager.getDefaultSensor(anyInt())).then((invocation) -> {
            int type = invocation.getArgument(0);
            for (Sensor sensor : sensors) {
                if (sensor.getType() == type) {
                    return sensor;
                }
            }
            return null;
        });
        return sensorManager;
    }

    private static Sensor createLightSensor() {
        try {
            return TestUtils.createSensor(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        } catch (Exception e) {
            // There's nothing we can do if this fails, just throw a RuntimeException so that we
            // don't have to mark every function that might call this as throwing Exception
            throw new RuntimeException("Failed to create a light sensor", e);
        }
    }

    private void waitForIdleSync() {
        mHandler.runWithScissors(() -> { }, 500 /*timeout*/);
    }

    static class FakesInjector implements DisplayModeDirector.Injector {
        private final FakeDeviceConfig mDeviceConfig;
        private ContentObserver mBrightnessObserver;
        private ContentObserver mPeakRefreshRateObserver;

        FakesInjector() {
            mDeviceConfig = new FakeDeviceConfig();
        }

        @NonNull
        public FakeDeviceConfig getDeviceConfig() {
            return mDeviceConfig;
        }

        @Override
        public void registerBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            if (mBrightnessObserver != null) {
                throw new IllegalStateException("Tried to register a second brightness observer");
            }
            mBrightnessObserver = observer;
        }

        @Override
        public void unregisterBrightnessObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mBrightnessObserver = null;
        }

        void notifyBrightnessChanged() {
            if (mBrightnessObserver != null) {
                mBrightnessObserver.dispatchChange(false /*selfChange*/, DISPLAY_BRIGHTNESS_URI);
            }
        }

        @Override
        public void registerPeakRefreshRateObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mPeakRefreshRateObserver = observer;
        }

        void notifyPeakRefreshRateChanged() {
            if (mPeakRefreshRateObserver != null) {
                mPeakRefreshRateObserver.dispatchChange(false /*selfChange*/,
                        PEAK_REFRESH_RATE_URI);
            }
        }
    }
}
