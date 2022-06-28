/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.timedetector;

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_EXTERNAL;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_GNSS;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timedetector.TimeDetectorStrategy.Origin;
import com.android.server.timezonedetector.ConfigurationChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorStrategyImplTest {

    private static final @UserIdInt int ARBITRARY_USER_ID = 9876;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant TIME_LOWER_BOUND = createUnixEpochTime(2009, 1, 1, 12, 0, 0);

    private static final TimestampedValue<Instant> ARBITRARY_CLOCK_INITIALIZATION_INFO =
            new TimestampedValue<>(
                    123456789L /* realtimeClockMillis */,
                    createUnixEpochTime(2010, 5, 23, 12, 0, 0));

    // This is the traditional ordering for time detection on Android.
    private static final @Origin int [] ORIGIN_PRIORITIES = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /**
     * An arbitrary time, very different from the {@link #ARBITRARY_CLOCK_INITIALIZATION_INFO}
     * time. Can be used as the basis for time suggestions.
     */
    private static final Instant ARBITRARY_TEST_TIME = createUnixEpochTime(2018, 1, 1, 12, 0, 0);

    private static final int ARBITRARY_SLOT_INDEX = 123456;

    private static final ConfigurationInternal CONFIG_AUTO_DISABLED =
            new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setSystemClockUpdateThresholdMillis(
                            ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                    .setAutoTimeLowerBound(TIME_LOWER_BOUND)
                    .setOriginPriorities(ORIGIN_PRIORITIES)
                    .setDeviceHasY2038Issue(true)
                    .setAutoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED =
            new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setSystemClockUpdateThresholdMillis(
                            ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                    .setAutoTimeLowerBound(TIME_LOWER_BOUND)
                    .setOriginPriorities(ORIGIN_PRIORITIES)
                    .setDeviceHasY2038Issue(true)
                    .setAutoDetectionEnabledSetting(true)
                    .build();

    private FakeEnvironment mFakeEnvironment;

    @Before
    public void setUp() {
        mFakeEnvironment = new FakeEnvironment();
        mFakeEnvironment.initializeConfig(CONFIG_AUTO_DISABLED);
        mFakeEnvironment.initializeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO);
    }

    @Test
    public void testSuggestTelephonyTime_autoTimeEnabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;

        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        script.simulateTimePassing()
                .simulateTelephonyTimeSuggestion(timeSuggestion);

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_emptySuggestionIgnored() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, null);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, null);
    }

    @Test
    public void testSuggestTelephonyTime_systemClockThreshold() {
        final int systemClockUpdateThresholdMillis = 1000;
        final int clockIncrementMillis = 100;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Send the first time signal. It should be used.
        {
            TelephonyTimeSuggestion timeSuggestion1 =
                    script.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME);

            // Increment the device clocks to simulate the passage of time.
            script.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis1 =
                    script.calculateTimeInMillisForNow(timeSuggestion1.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);
        }

        // Now send another time signal, but one that is too similar to the last one and should be
        // stored, but not used to set the system clock.
        {
            int underThresholdMillis = systemClockUpdateThresholdMillis - 1;
            TelephonyTimeSuggestion timeSuggestion2 = script.generateTelephonyTimeSuggestion(
                    slotIndex, script.peekSystemClockMillis() + underThresholdMillis);
            script.simulateTimePassing(clockIncrementMillis)
                    .simulateTelephonyTimeSuggestion(timeSuggestion2)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
        }

        // Now send another time signal, but one that is on the threshold and so should be used.
        {
            TelephonyTimeSuggestion timeSuggestion3 = script.generateTelephonyTimeSuggestion(
                    slotIndex,
                    script.peekSystemClockMillis() + systemClockUpdateThresholdMillis);
            script.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis3 =
                    script.calculateTimeInMillisForNow(timeSuggestion3.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(timeSuggestion3)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis3)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion3);
        }
    }

    @Test
    public void testSuggestTelephonyTime_multipleSlotIndexsAndBucketing() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        // There are 2 slotIndexes in this test. slotIndex1 and slotIndex2 have different opinions
        // about the current time. slotIndex1 < slotIndex2 (which is important because the strategy
        // uses the lowest slotIndex when multiple telephony suggestions are available.
        int slotIndex1 = ARBITRARY_SLOT_INDEX;
        int slotIndex2 = ARBITRARY_SLOT_INDEX + 1;
        Instant slotIndex1Time = ARBITRARY_TEST_TIME;
        Instant slotIndex2Time = ARBITRARY_TEST_TIME.plus(Duration.ofDays(1));

        // Make a suggestion with slotIndex2.
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex2TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, null)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        script.simulateTimePassing();

        // Now make a different suggestion with slotIndex1.
        {
            TelephonyTimeSuggestion slotIndex1TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex1, slotIndex1Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex1TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex1TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, slotIndex1TimeSuggestion);

        }

        script.simulateTimePassing();

        // Make another suggestion with slotIndex2. It should be stored but not used because the
        // slotIndex1 suggestion will still "win".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        // Let enough time pass that slotIndex1's suggestion should now be too old.
        script.simulateTimePassing(TimeDetectorStrategyImpl.TELEPHONY_BUCKET_SIZE_MILLIS);

        // Make another suggestion with slotIndex2. It should be used because the slotIndex1
        // is in an older "bucket".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex2TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }
    }

    @Test
    public void testSuggestTelephonyTime_autoTimeDisabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME);
        script.simulateTimePassing()
                .simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_invalidNitzReferenceTimesIgnored() {
        final int systemClockUpdateThresholdMillis = 2000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant testTime = ARBITRARY_TEST_TIME;
        int slotIndex = ARBITRARY_SLOT_INDEX;

        TelephonyTimeSuggestion timeSuggestion1 =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        TimestampedValue<Long> unixEpochTime1 = timeSuggestion1.getUnixEpochTime();

        // Initialize the strategy / device with a time set from a telephony suggestion.
        script.simulateTimePassing();
        long expectedSystemClockMillis1 = script.calculateTimeInMillisForNow(unixEpochTime1);
        script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // The Unix epoch time increment should be larger than the system clock update threshold so
        // we know it shouldn't be ignored for other reasons.
        long validUnixEpochTimeMillis = unixEpochTime1.getValue()
                + (2 * systemClockUpdateThresholdMillis);

        // Now supply a new signal that has an obviously bogus reference time : older than the last
        // one.
        long referenceTimeBeforeLastSignalMillis = unixEpochTime1.getReferenceTimeMillis() - 1;
        TimestampedValue<Long> unixEpochTime2 = new TimestampedValue<>(
                referenceTimeBeforeLastSignalMillis, validUnixEpochTimeMillis);
        TelephonyTimeSuggestion timeSuggestion2 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime2);
        script.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Now supply a new signal that has an obviously bogus reference time : substantially in the
        // future.
        long referenceTimeInFutureMillis =
                unixEpochTime1.getReferenceTimeMillis() + Integer.MAX_VALUE + 1;
        TimestampedValue<Long> unixEpochTime3 = new TimestampedValue<>(
                referenceTimeInFutureMillis, validUnixEpochTimeMillis);
        TelephonyTimeSuggestion timeSuggestion3 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime3);
        script.simulateTelephonyTimeSuggestion(timeSuggestion3)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Just to prove validUnixEpochTimeMillis is valid.
        long validReferenceTimeMillis = unixEpochTime1.getReferenceTimeMillis() + 100;
        TimestampedValue<Long> unixEpochTime4 = new TimestampedValue<>(
                validReferenceTimeMillis, validUnixEpochTimeMillis);
        long expectedSystemClockMillis4 = script.calculateTimeInMillisForNow(unixEpochTime4);
        TelephonyTimeSuggestion timeSuggestion4 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime4);
        script.simulateTelephonyTimeSuggestion(timeSuggestion4)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis4)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion4);
    }

    @Test
    public void telephonyTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));

        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(
                        slotIndex, suggestedTime);

        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, null);
    }

    @Test
    public void testSuggestTelephonyTime_timeDetectionToggled() {
        final int clockIncrementMillis = 100;
        final int systemClockUpdateThresholdMillis = 2000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion1 =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        TimestampedValue<Long> unixEpochTime1 = timeSuggestion1.getUnixEpochTime();

        // Simulate time passing.
        script.simulateTimePassing(clockIncrementMillis);

        // Simulate the time signal being received. It should not be used because auto time
        // detection is off but it should be recorded.
        script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Simulate more time passing.
        script.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis1 = script.calculateTimeInMillisForNow(unixEpochTime1);

        // Turn on auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Turn off auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Receive another valid time signal.
        // It should be on the threshold and accounting for the clock increments.
        TelephonyTimeSuggestion timeSuggestion2 = script.generateTelephonyTimeSuggestion(
                slotIndex, script.peekSystemClockMillis() + systemClockUpdateThresholdMillis);

        // Simulate more time passing.
        script.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis2 =
                script.calculateTimeInMillisForNow(timeSuggestion2.getUnixEpochTime());

        // The new time, though valid, should not be set in the system clock because auto time is
        // disabled.
        script.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);

        // Turn on auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis2)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
    }

    @Test
    public void testSuggestTelephonyTime_maxSuggestionAge() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonySuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(telephonySuggestion.getUnixEpochTime());
        script.simulateTelephonyTimeSuggestion(telephonySuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis  /* expectedNetworkBroadcast */)
                .assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        assertEquals(telephonySuggestion, script.peekBestTelephonySuggestion());

        // Simulate time passing, long enough that telephonySuggestion is now too old.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        // It should still be the, it's just no longer used.
        assertNull(script.peekBestTelephonySuggestion());
        script.assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);
    }

    @Test
    public void testSuggestManualTime_autoTimeDisabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestManualTime_retainsAutoSignal() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Simulate a telephony suggestion.
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        // Simulate the passage of time.
        script.simulateTimePassing();

        long expectedAutoClockMillis =
                script.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUnixEpochTime());
        script.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Switch to manual.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Simulate a manual suggestion 1 day different from the auto suggestion.
        Instant manualTime = testTime.plus(Duration.ofDays(1));
        ManualTimeSuggestion manualTimeSuggestion =
                script.generateManualTimeSuggestion(manualTime);
        script.simulateTimePassing();

        long expectedManualClockMillis =
                script.calculateTimeInMillisForNow(manualTimeSuggestion.getUnixEpochTime());
        script.simulateManualTimeSuggestion(
                        ARBITRARY_USER_ID, manualTimeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(expectedManualClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Switch back to auto.
        script.simulateAutoTimeDetectionToggle();

        expectedAutoClockMillis =
                script.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUnixEpochTime());
        script.verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Switch back to manual - nothing should happen to the clock.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);
    }

    @Test
    public void manualTimeSuggestion_isIgnored_whenAutoTimeEnabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateManualTimeSuggestion(
                        ARBITRARY_USER_ID, timeSuggestion, false /* expectedResult */)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void manualTimeSuggestion_ignoresTimeLowerBound() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED);
        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(suggestedTime);

        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(suggestedTime.toEpochMilli());
    }

    @Test
    public void testSuggestNetworkTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestNetworkTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void networkTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));
        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(suggestedTime);

        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestNetworkSuggestion(null);
    }

    @Test
    public void testSuggestGnssTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestGnssTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestExternalTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestExternalTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void externalTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));
        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(suggestedTime);

        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestExternalSuggestion(null);
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_telephonyNetworkOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant networkTime1 = ARBITRARY_TEST_TIME;
        Instant networkTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A network suggestion is made. It should be used because there is no telephony suggestion.
        NetworkTimeSuggestion networkTimeSuggestion1 =
                script.generateNetworkTimeSuggestion(networkTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, null)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, script.peekLatestValidNetworkSuggestion());
        assertNull("No telephony suggestions were made:", script.peekBestTelephonySuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a telephony suggestion is made. Telephony suggestions are prioritized over network
        // suggestions so it should "win".
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                telephonyTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, script.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, script.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another network suggestion is made. Telephony suggestions are prioritized over
        // network suggestions so the latest telephony suggestion should still "win".
        NetworkTimeSuggestion networkTimeSuggestion2 =
                script.generateNetworkTimeSuggestion(networkTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, script.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should be expired:",
                script.peekBestTelephonySuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest network time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(networkTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should still be expired:",
                script.peekBestTelephonySuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkGnssOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK, ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant gnssTime1 = ARBITRARY_TEST_TIME;
        Instant gnssTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A gnss suggestion is made. It should be used because there is no network suggestion.
        GnssTimeSuggestion gnssTimeSuggestion1 =
                script.generateGnssTimeSuggestion(gnssTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                gnssTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(null)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, script.peekLatestValidGnssSuggestion());
        assertNull("No network suggestions were made:", script.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over gnss
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, script.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another gnss suggestion is made. Network suggestions are prioritized over
        // gnss suggestions so the latest network suggestion should still "win".
        GnssTimeSuggestion gnssTimeSuggestion2 =
                script.generateGnssTimeSuggestion(gnssTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                script.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest gnss time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(gnssTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                script.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkExternalOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK, ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant externalTime1 = ARBITRARY_TEST_TIME;
        Instant externalTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A external suggestion is made. It should be used because there is no network suggestion.
        ExternalTimeSuggestion externalTimeSuggestion1 =
                script.generateExternalTimeSuggestion(externalTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                externalTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(null)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, script.peekLatestValidExternalSuggestion());
        assertNull("No network suggestions were made:", script.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over external
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, script.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another external suggestion is made. Network suggestions are prioritized over
        // external suggestions so the latest network suggestion should still "win".
        ExternalTimeSuggestion externalTimeSuggestion2 =
                script.generateExternalTimeSuggestion(externalTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that networkTimeSuggestion is now too old to
        // be used but externalTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                script.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest external time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(externalTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                script.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void whenAllTimeSuggestionsAreAvailable_higherPriorityWins_lowerPriorityComesFirst() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(15));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                script.generateExternalTimeSuggestion(externalTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                script.generateGnssTimeSuggestion(gnssTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);

        script.simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .simulateExternalTimeSuggestion(externalTimeSuggestion)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion)
                .simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(telephonyTime.toEpochMilli());
    }

    @Test
    public void whenAllTimeSuggestionsAreAvailable_higherPriorityWins_higherPriorityComesFirst() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(50));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                script.generateGnssTimeSuggestion(gnssTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                script.generateExternalTimeSuggestion(externalTime);

        script.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion)
                .simulateExternalTimeSuggestion(externalTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(telephonyTime.toEpochMilli());
    }

    @Test
    public void whenHighestPrioritySuggestionIsNotAvailable_fallbacksToNext() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void whenHigherPrioritySuggestionsAreNotAvailable_fallbacksToNext() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void suggestionsFromTelephonyOriginNotInPriorityList_areIgnored() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromNetworkOriginNotInPriorityList_areIgnored() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion = script.generateNetworkTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromGnssOriginNotInPriorityList_areIgnored() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion = script.generateGnssTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromExternalOriginNotInPriorityList_areIgnored() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ExternalTimeSuggestion timeSuggestion = script.generateExternalTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateExternalTimeSuggestion(timeSuggestion)
                .assertLatestExternalSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void autoOriginPrioritiesList_doesNotAffectManualSuggestion() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void manualY2038SuggestionsAreRejectedOnAffectedDevices() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setDeviceHasY2038Issue(true)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant y2038IssueTime = Instant.ofEpochMilli((1L + Integer.MAX_VALUE) * 1000L);
        ManualTimeSuggestion timeSuggestion = script.generateManualTimeSuggestion(y2038IssueTime);
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, false /* expectedResult */)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void telephonyY2038SuggestionsAreRejectedOnAffectedDevices() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setDeviceHasY2038Issue(true)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        final int slotIndex = 0;
        Instant y2038IssueTime = Instant.ofEpochMilli((1L + Integer.MAX_VALUE) * 1000L);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, y2038IssueTime);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void telephonyY2038SuggestionsAreNotRejectedOnUnaffectedDevices() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setDeviceHasY2038Issue(false)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        final int slotIndex = 0;
        Instant y2038IssueTime = Instant.ofEpochMilli((1L + Integer.MAX_VALUE) * 1000L);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, y2038IssueTime);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(y2038IssueTime.toEpochMilli());
    }

    /**
     * A fake implementation of {@link TimeDetectorStrategyImpl.Environment}. Besides tracking
     * changes and behaving like the real thing should, it also asserts preconditions.
     */
    private static class FakeEnvironment implements TimeDetectorStrategyImpl.Environment {

        private ConfigurationInternal mConfigurationInternal;
        private boolean mWakeLockAcquired;
        private long mElapsedRealtimeMillis;
        private long mSystemClockMillis;
        private ConfigurationChangeListener mConfigurationInternalChangeListener;

        // Tracking operations.
        private boolean mSystemClockWasSet;

        void initializeConfig(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
        }

        public void initializeFakeClocks(TimestampedValue<Instant> timeInfo) {
            pokeElapsedRealtimeMillis(timeInfo.getReferenceTimeMillis());
            pokeSystemClockMillis(timeInfo.getValue().toEpochMilli());
        }

        @Override
        public void setConfigurationInternalChangeListener(ConfigurationChangeListener listener) {
            mConfigurationInternalChangeListener = Objects.requireNonNull(listener);
        }

        @Override
        public ConfigurationInternal getCurrentUserConfigurationInternal() {
            return mConfigurationInternal;
        }

        @Override
        public void acquireWakeLock() {
            if (mWakeLockAcquired) {
                fail("Wake lock already acquired");
            }
            mWakeLockAcquired = true;
        }

        @Override
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        @Override
        public long systemClockMillis() {
            return mSystemClockMillis;
        }

        @Override
        public void setSystemClock(long newTimeMillis) {
            assertWakeLockAcquired();
            mSystemClockWasSet = true;
            mSystemClockMillis = newTimeMillis;
        }

        @Override
        public void releaseWakeLock() {
            assertWakeLockAcquired();
            mWakeLockAcquired = false;
        }

        // Methods below are for managing the fake's behavior.

        void simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
            mConfigurationInternalChangeListener.onChange();
        }

        void pokeElapsedRealtimeMillis(long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        void pokeSystemClockMillis(long systemClockMillis) {
            mSystemClockMillis = systemClockMillis;
        }

        long peekElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        long peekSystemClockMillis() {
            return mSystemClockMillis;
        }

        void simulateTimePassing(long incrementMillis) {
            mElapsedRealtimeMillis += incrementMillis;
            mSystemClockMillis += incrementMillis;
        }

        void verifySystemClockNotSet() {
            assertFalse(
                    String.format("System clock was manipulated and set to %s(=%s)",
                            Instant.ofEpochMilli(mSystemClockMillis), mSystemClockMillis),
                    mSystemClockWasSet);
        }

        void verifySystemClockWasSet(long expectedSystemClockMillis) {
            assertTrue(mSystemClockWasSet);
            assertEquals(expectedSystemClockMillis, mSystemClockMillis);
        }

        void resetCallTracking() {
            mSystemClockWasSet = false;
        }

        private void assertWakeLockAcquired() {
            assertTrue("The operation must be performed only after acquiring the wakelock",
                    mWakeLockAcquired);
        }
    }

    /**
     * A fluent helper class for tests.
     */
    private class Script {

        private final TimeDetectorStrategyImpl mTimeDetectorStrategy;

        Script() {
            mFakeEnvironment = new FakeEnvironment();
            mTimeDetectorStrategy = new TimeDetectorStrategyImpl(mFakeEnvironment);
        }

        long peekElapsedRealtimeMillis() {
            return mFakeEnvironment.peekElapsedRealtimeMillis();
        }

        long peekSystemClockMillis() {
            return mFakeEnvironment.peekSystemClockMillis();
        }

        /**
         * Simulates the user / user's configuration changing.
         */
        Script simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mFakeEnvironment.simulateConfigurationInternalChange(configurationInternal);
            return this;
        }

        Script simulateTelephonyTimeSuggestion(TelephonyTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestTelephonyTime(timeSuggestion);
            return this;
        }

        Script simulateManualTimeSuggestion(
                @UserIdInt int userId, ManualTimeSuggestion timeSuggestion,
                boolean expectedResult) {
            String errorMessage = expectedResult
                    ? "Manual time suggestion was ignored, but expected to be accepted."
                    : "Manual time suggestion was accepted, but expected to be ignored.";
            assertEquals(
                    errorMessage,
                    expectedResult,
                    mTimeDetectorStrategy.suggestManualTime(userId, timeSuggestion));
            return this;
        }

        Script simulateNetworkTimeSuggestion(NetworkTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestNetworkTime(timeSuggestion);
            return this;
        }

        Script simulateGnssTimeSuggestion(GnssTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestGnssTime(timeSuggestion);
            return this;
        }

        Script simulateExternalTimeSuggestion(ExternalTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestExternalTime(timeSuggestion);
            return this;
        }

        Script simulateAutoTimeDetectionToggle() {
            ConfigurationInternal configurationInternal =
                    mFakeEnvironment.getCurrentUserConfigurationInternal();
            boolean autoDetectionEnabledSetting =
                    !configurationInternal.getAutoDetectionEnabledSetting();
            ConfigurationInternal newConfigurationInternal =
                    new ConfigurationInternal.Builder(configurationInternal)
                            .setAutoDetectionEnabledSetting(autoDetectionEnabledSetting)
                            .build();
            mFakeEnvironment.simulateConfigurationInternalChange(newConfigurationInternal);
            return this;
        }

        Script simulateTimePassing(long clockIncrementMillis) {
            mFakeEnvironment.simulateTimePassing(clockIncrementMillis);
            return this;
        }

        /**
         * Simulates time passing by an arbitrary (but relatively small) amount.
         */
        Script simulateTimePassing() {
            return simulateTimePassing(999);
        }

        Script verifySystemClockWasNotSetAndResetCallTracking() {
            mFakeEnvironment.verifySystemClockNotSet();
            mFakeEnvironment.resetCallTracking();
            return this;
        }

        Script verifySystemClockWasSetAndResetCallTracking(long expectedSystemClockMillis) {
            mFakeEnvironment.verifySystemClockWasSet(expectedSystemClockMillis);
            mFakeEnvironment.resetCallTracking();
            return this;
        }

        /**
         * White box test info: Asserts the latest suggestion for the slotIndex is as expected.
         */
        Script assertLatestTelephonySuggestion(int slotIndex, TelephonyTimeSuggestion expected) {
            assertEquals(
                    "Expected to see " + expected + " at slotIndex=" + slotIndex + ", but got "
                            + mTimeDetectorStrategy.getLatestTelephonySuggestion(slotIndex),
                    expected, mTimeDetectorStrategy.getLatestTelephonySuggestion(slotIndex));
            return this;
        }

        /**
         * White box test info: Asserts the latest network suggestion is as expected.
         */
        Script assertLatestNetworkSuggestion(NetworkTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestNetworkSuggestion());
            return this;
        }

        /**
         * White box test info: Asserts the latest gnss suggestion is as expected.
         */
        Script assertLatestGnssSuggestion(GnssTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestGnssSuggestion());
            return this;
        }

        /**
         * White box test info: Asserts the latest external suggestion is as expected.
         */
        Script assertLatestExternalSuggestion(ExternalTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestExternalSuggestion());
            return this;
        }

        /**
         * White box test info: Returns the telephony suggestion that would be used, if any, given
         * the current elapsed real time clock and regardless of origin prioritization.
         */
        TelephonyTimeSuggestion peekBestTelephonySuggestion() {
            return mTimeDetectorStrategy.findBestTelephonySuggestionForTests();
        }

        /**
         * White box test info: Returns the network suggestion that would be used, if any, given the
         * current elapsed real time clock and regardless of origin prioritization.
         */
        NetworkTimeSuggestion peekLatestValidNetworkSuggestion() {
            return mTimeDetectorStrategy.findLatestValidNetworkSuggestionForTests();
        }

        /**
         * White box test info: Returns the gnss suggestion that would be used, if any, given the
         * current elapsed real time clock and regardless of origin prioritization.
         */
        GnssTimeSuggestion peekLatestValidGnssSuggestion() {
            return mTimeDetectorStrategy.findLatestValidGnssSuggestionForTests();
        }

        /**
         * White box test info: Returns the external suggestion that would be used, if any, given
         * the current elapsed real time clock and regardless of origin prioritization.
         */
        ExternalTimeSuggestion peekLatestValidExternalSuggestion() {
            return mTimeDetectorStrategy.findLatestValidExternalSuggestionForTests();
        }

        /**
         * Generates a ManualTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        ManualTimeSuggestion generateManualTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> unixEpochTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new ManualTimeSuggestion(unixEpochTime);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the reference time.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(int slotIndex, long timeMillis) {
            TimestampedValue<Long> time =
                    new TimestampedValue<>(peekElapsedRealtimeMillis(), timeMillis);
            return createTelephonyTimeSuggestion(slotIndex, time);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the reference time.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(
                int slotIndex, Instant suggestedTime) {
            if (suggestedTime == null) {
                return createTelephonyTimeSuggestion(slotIndex, null);
            }
            return generateTelephonyTimeSuggestion(slotIndex, suggestedTime.toEpochMilli());
        }

        /**
         * Generates a NetworkTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        NetworkTimeSuggestion generateNetworkTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> unixEpochTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new NetworkTimeSuggestion(unixEpochTime, 123);
        }

        /**
         * Generates a GnssTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        GnssTimeSuggestion generateGnssTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> unixEpochTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new GnssTimeSuggestion(unixEpochTime);
        }

        /**
         * Generates a ExternalTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        ExternalTimeSuggestion generateExternalTimeSuggestion(Instant suggestedTime) {
            return new ExternalTimeSuggestion(mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
        }

        /**
         * Calculates what the supplied time would be when adjusted for the movement of the fake
         * elapsed realtime clock.
         */
        long calculateTimeInMillisForNow(TimestampedValue<Long> unixEpochTime) {
            return TimeDetectorStrategy.getTimeAt(unixEpochTime, peekElapsedRealtimeMillis());
        }
    }

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion(int slotIndex,
            TimestampedValue<Long> unixEpochTime) {
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(unixEpochTime)
                .build();
    }

    private static Instant createUnixEpochTime(int year, int monthInYear, int day, int hourOfDay,
            int minute, int second) {
        return LocalDateTime.of(year, monthInYear, day, hourOfDay, minute, second)
                .toInstant(ZoneOffset.UTC);
    }
}
