/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.VibrationEffect.VibrationParameter.targetAmplitude;
import static android.os.VibrationEffect.VibrationParameter.targetFrequency;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.hardware.vibrator.Braking;
import android.hardware.vibrator.IVibrator;
import android.hardware.vibrator.IVibratorManager;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.LargeTest;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Tests for {@link VibrationThread}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationThreadTest
 */
@Presubmit
public class VibrationThreadTest {

    private static final int TEST_TIMEOUT_MILLIS = 900;
    private static final int UID = Process.ROOT_UID;
    private static final int VIBRATOR_ID = 1;
    private static final String PACKAGE_NAME = "package";
    private static final VibrationAttributes ATTRS = new VibrationAttributes.Builder().build();
    private static final int TEST_RAMP_STEP_DURATION = 5;

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private VibrationThread.VibratorManagerHooks mManagerHooks;
    @Mock private VibratorController.OnVibrationCompleteListener mControllerCallbacks;
    @Mock private IBinder mVibrationToken;
    @Mock private VibrationConfig mVibrationConfigMock;

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();
    private VibrationSettings mVibrationSettings;
    private DeviceVibrationEffectAdapter mEffectAdapter;
    private TestLooper mTestLooper;
    private TestLooperAutoDispatcher mCustomTestLooperDispatcher;
    private VibrationThread mThread;

    // Setup from the providers when VibrationThread is initialized.
    private SparseArray<VibratorController> mControllers;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();

        when(mVibrationConfigMock.getDefaultVibrationIntensity(anyInt()))
                .thenReturn(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        when(mVibrationConfigMock.getRampStepDurationMs()).thenReturn(TEST_RAMP_STEP_DURATION);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);

        Context context = InstrumentationRegistry.getContext();
        mVibrationSettings = new VibrationSettings(context, new Handler(mTestLooper.getLooper()),
                mVibrationConfigMock);

        mockVibrators(VIBRATOR_ID);

        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        PowerManager.WakeLock wakeLock = context.getSystemService(
                PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");
        mThread = new VibrationThread(wakeLock, mManagerHooks);
        mThread.start();
    }

    @After
    public void tearDown() {
        if (mCustomTestLooperDispatcher != null) {
            mCustomTestLooperDispatcher.cancel();
        }
    }

    @Test
    public void vibrate_noVibrator_ignoresVibration() {
        mVibratorProviders.clear();
        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks, never()).onComplete(anyInt(), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.IGNORED_UNSUPPORTED);
    }

    @Test
    public void vibrate_missingVibrators_ignoresVibration() {
        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startSequential()
                .addNext(2, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(3, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks, never()).onComplete(anyInt(), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.IGNORED_UNSUPPORTED);
    }

    @Test
    public void vibrate_singleVibratorOneShot_runsVibrationAndSetsAmplitude() throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10, 100);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(10L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_oneShotWithoutAmplitudeControl_runsVibrationWithDefaultAmplitude()
            throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10, 100);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(10L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_singleVibratorWaveform_runsVibrationAndChangesAmplitudes()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{5, 5, 5}, new int[]{1, 2, 3}, -1);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(15L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(15)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(1, 2, 3),
                mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorRepeatingWaveform_runsVibrationUntilThreadCancelled()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2, 3};
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5, 5, 5}, amplitudes, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(
                waitUntil(() -> fakeVibrator.getAmplitudes().size() > 2 * amplitudes.length,
                        TEST_TIMEOUT_MILLIS));
        // Vibration still running after 2 cycles.
        assertTrue(mThread.isRunningVibrationId(vibrationId));
        assertTrue(mControllers.get(VIBRATOR_ID).isVibrating());

        conductor.notifyCancelled(Vibration.Status.CANCELLED_SUPERSEDED, /* immediate= */ false);
        waitForCompletion();
        assertFalse(mThread.isRunningVibrationId(vibrationId));

        verify(mManagerHooks).noteVibratorOn(eq(UID), anyLong());
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_SUPERSEDED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        List<Float> playedAmplitudes = fakeVibrator.getAmplitudes();
        assertFalse(fakeVibrator.getEffectSegments(vibrationId).isEmpty());
        assertFalse(playedAmplitudes.isEmpty());

        for (int i = 0; i < playedAmplitudes.size(); i++) {
            assertEquals(amplitudes[i % amplitudes.length] / 255f, playedAmplitudes.get(i), 1e-5);
        }
    }

    @Test
    public void vibrate_singleVibratorRepeatingShortAlwaysOnWaveform_turnsVibratorOnForLonger()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2, 3};
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{1, 10, 100}, amplitudes, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> !fakeVibrator.getAmplitudes().isEmpty(), TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(expectedOneShot(5000)),
                fakeVibrator.getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_singleVibratorRepeatingPwle_generatesLargestPwles() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        fakeVibrator.setMinFrequency(100);
        fakeVibrator.setResonantFrequency(150);
        fakeVibrator.setFrequencyResolution(50);
        fakeVibrator.setMaxAmplitudes(1, 1, 1);
        fakeVibrator.setPwleSizeMax(10);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startWaveform(targetAmplitude(1))
                // Very long segment so thread will be cancelled after first PWLE is triggered.
                .addTransition(Duration.ofMillis(100), targetFrequency(100))
                .build();
        VibrationEffect repeatingEffect = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(effect)
                .compose();
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, repeatingEffect);

        assertTrue(waitUntil(() -> !fakeVibrator.getEffectSegments(vibrationId).isEmpty(),
                TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        // PWLE size max was used to generate a single vibrate call with 10 segments.
        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(10, fakeVibrator.getEffectSegments(vibrationId).size());
    }

    @Test
    public void vibrate_singleVibratorRepeatingPrimitives_generatesLargestComposition()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK);
        fakeVibrator.setCompositionSizeMax(10);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                // Very long delay so thread will be cancelled after first PWLE is triggered.
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .compose();
        VibrationEffect repeatingEffect = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(effect)
                .compose();
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, repeatingEffect);

        assertTrue(waitUntil(() -> !fakeVibrator.getEffectSegments(vibrationId).isEmpty(),
                TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_SUPERSEDED, /* immediate= */ false);
        waitForCompletion();

        // Composition size max was used to generate a single vibrate call with 10 primitives.
        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_SUPERSEDED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(10, fakeVibrator.getEffectSegments(vibrationId).size());
    }

    @Test
    public void vibrate_singleVibratorRepeatingLongAlwaysOnWaveform_turnsVibratorOnForACycle()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2, 3};
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{5000, 500, 50}, amplitudes, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> !fakeVibrator.getAmplitudes().isEmpty(), TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(expectedOneShot(5550)),
                fakeVibrator.getEffectSegments(vibrationId));
    }

    @LargeTest
    @Test
    public void vibrate_singleVibratorRepeatingAlwaysOnWaveform_turnsVibratorBackOn()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2};
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{4900, 50}, amplitudes, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> fakeVibrator.getEffectSegments(vibrationId).size() > 1,
                5000 + TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        // First time turn vibrator ON for minimum of 5s.
        assertEquals(5000L, fakeVibrator.getEffectSegments(vibrationId).get(0).getDuration());
        // Vibrator turns off in the middle of the second execution of first step, turn it back ON
        // for another 5s + remaining of 850ms.
        assertEquals(4900 + 50 + 4900,
                fakeVibrator.getEffectSegments(vibrationId).get(1).getDuration(), /* delta= */ 20);
        // Set amplitudes for a cycle {1, 2}, start second loop then turn it back on to same value.
        assertEquals(expectedAmplitudes(1, 2, 1, 1),
                mVibratorProviders.get(VIBRATOR_ID).getAmplitudes().subList(0, 4));
    }

    @Test
    public void vibrate_singleVibratorPredefinedCancel_cancelsVibrationImmediately()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .compose();
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> mControllers.get(VIBRATOR_ID).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread =
                new Thread(() -> conductor.notifyCancelled(
                        Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE, /* immediate= */ false));
        cancellingThread.start();

        waitForCompletion(/* timeout= */ 50);
        cancellingThread.join();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_singleVibratorWaveformCancel_cancelsVibrationImmediately()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{100}, new int[]{100}, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> mControllers.get(VIBRATOR_ID).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread =
                new Thread(() -> conductor.notifyCancelled(
                        Vibration.Status.CANCELLED_BY_SCREEN_OFF, /* immediate= */ false));
        cancellingThread.start();

        waitForCompletion(/* timeout= */ 50);
        cancellingThread.join();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_SCREEN_OFF);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_singleVibratorPrebaked_runsVibration() throws Exception {
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_THUD);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_THUD);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_THUD)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_singleVibratorPrebakedAndUnsupportedEffectWithFallback_runsFallback()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect fallback = VibrationEffect.createOneShot(10, 100);
        Vibration vibration = createVibration(vibrationId, CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK)));
        vibration.addFallback(VibrationEffect.EFFECT_CLICK, fallback);
        startThreadAndDispatcher(vibration);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(10L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorPrebakedAndUnsupportedEffect_ignoresVibration()
            throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks, never()).noteVibratorOn(eq(UID), anyLong());
        verify(mManagerHooks, never()).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks, never()).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.IGNORED_UNSUPPORTED);
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId).isEmpty());
    }

    @Test
    public void vibrate_singleVibratorComposed_runsVibration() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(40L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0),
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0)),
                fakeVibrator.getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_singleVibratorComposedAndNoCapability_ignoresVibration() throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .compose();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks, never()).noteVibratorOn(eq(UID), anyLong());
        verify(mManagerHooks, never()).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks, never()).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.IGNORED_UNSUPPORTED);
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId).isEmpty());
    }

    @Test
    public void vibrate_singleVibratorLargeComposition_splitsVibratorComposeCalls() {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        fakeVibrator.setCompositionSizeMax(2);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, 0.8f)
                .compose();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        // Vibrator compose called twice.
        verify(mControllerCallbacks, times(2)).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        assertEquals(3, fakeVibrator.getEffectSegments(vibrationId).size());
    }

    @Test
    public void vibrate_singleVibratorComposedEffects_runsDifferentVibrations() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        fakeVibrator.setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS, IVibrator.CAP_AMPLITUDE_CONTROL);
        fakeVibrator.setMinFrequency(100);
        fakeVibrator.setResonantFrequency(150);
        fakeVibrator.setFrequencyResolution(50);
        fakeVibrator.setMaxAmplitudes(
                0.5f /* 100Hz*/, 1 /* 150Hz */, 0.6f /* 200Hz */);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addEffect(VibrationEffect.createOneShot(10, 100))
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .addEffect(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addEffect(VibrationEffect.startWaveform()
                        .addTransition(Duration.ofMillis(10),
                                targetAmplitude(1), targetFrequency(100))
                        .addTransition(Duration.ofMillis(20), targetFrequency(120))
                        .build())
                .addEffect(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .compose();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        // Use first duration the vibrator is turned on since we cannot estimate the clicks.
        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(10L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks, times(5)).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(
                expectedOneShot(10),
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0),
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0),
                expectedPrebaked(VibrationEffect.EFFECT_CLICK),
                expectedRamp(/* startAmplitude= */ 0, /* endAmplitude= */ 0.5f,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 100, /* duration= */ 10),
                expectedRamp(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.7f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 120, /* duration= */ 20),
                expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorPwle_runsComposePwle() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        fakeVibrator.setSupportedBraking(Braking.CLAB);
        fakeVibrator.setMinFrequency(100);
        fakeVibrator.setResonantFrequency(150);
        fakeVibrator.setFrequencyResolution(50);
        fakeVibrator.setMaxAmplitudes(
                0.5f /* 100Hz*/, 1 /* 150Hz */, 0.6f /* 200Hz */);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startWaveform(targetAmplitude(1))
                .addSustain(Duration.ofMillis(10))
                .addTransition(Duration.ofMillis(20), targetAmplitude(0))
                .addTransition(Duration.ZERO, targetAmplitude(0.8f), targetFrequency(100))
                .addSustain(Duration.ofMillis(30))
                .addTransition(Duration.ofMillis(40), targetAmplitude(0.6f), targetFrequency(200))
                .build();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(100L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(
                expectedRamp(/* amplitude= */ 1, /* frequencyHz= */ 150, /* duration= */ 10),
                expectedRamp(/* startAmplitude= */ 1, /* endAmplitude= */ 0,
                        /* startFrequencyHz= */ 150, /* endFrequencyHz= */ 150, /* duration= */ 20),
                expectedRamp(/* amplitude= */ 0.5f, /* frequencyHz= */ 100, /* duration= */ 30),
                expectedRamp(/* startAmplitude= */ 0.5f, /* endAmplitude= */ 0.6f,
                        /* startFrequencyHz= */ 100, /* endFrequencyHz= */ 200,
                        /* duration= */ 40)),
                fakeVibrator.getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(Braking.CLAB), fakeVibrator.getBraking(vibrationId));
    }

    @Test
    public void vibrate_singleVibratorLargePwle_splitsComposeCallWhenAmplitudeIsLowest() {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        fakeVibrator.setMinFrequency(100);
        fakeVibrator.setResonantFrequency(150);
        fakeVibrator.setFrequencyResolution(50);
        fakeVibrator.setMaxAmplitudes(1, 1, 1);
        fakeVibrator.setPwleSizeMax(3);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startWaveform(targetAmplitude(1))
                .addSustain(Duration.ofMillis(10))
                .addTransition(Duration.ofMillis(20), targetAmplitude(0))
                // Waveform will be split here, after vibration goes to zero amplitude
                .addTransition(Duration.ZERO, targetAmplitude(0.8f), targetFrequency(100))
                .addSustain(Duration.ofMillis(30))
                .addTransition(Duration.ofMillis(40), targetAmplitude(0.6f), targetFrequency(200))
                // Waveform will be split here at lowest amplitude.
                .addTransition(Duration.ofMillis(40), targetAmplitude(0.7f), targetFrequency(200))
                .addTransition(Duration.ofMillis(40), targetAmplitude(0.6f), targetFrequency(200))
                .build();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        // Vibrator compose called 3 times with 2 segments instead of 2 times with 3 segments.
        // Using best split points instead of max-packing PWLEs.
        verify(mControllerCallbacks, times(3)).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        assertEquals(6, fakeVibrator.getEffectSegments(vibrationId).size());
    }

    @Test
    public void vibrate_singleVibratorCancelled_vibratorStopped() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5}, new int[]{100}, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> fakeVibrator.getAmplitudes().size() > 2, TEST_TIMEOUT_MILLIS));
        // Vibration still running after 2 cycles.
        assertTrue(mThread.isRunningVibrationId(vibrationId));
        assertTrue(mControllers.get(VIBRATOR_ID).isVibrating());

        conductor.binderDied();
        waitForCompletion();
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BINDER_DIED);
    }

    @Test
    public void vibrate_singleVibrator_skipsSyncedCallbacks() {
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        startThreadAndDispatcher(vibrationId,
                VibrationEffect.createOneShot(10, 100));
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        verify(mManagerHooks, never()).prepareSyncedVibration(anyLong(), any());
        verify(mManagerHooks, never()).triggerSyncedVibration(anyLong());
        verify(mManagerHooks, never()).cancelSyncedVibration();
    }

    @Test
    public void vibrate_multipleExistingAndMissingVibrators_vibratesOnlyExistingOnes()
            throws Exception {
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_TICK);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(VIBRATOR_ID, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mControllerCallbacks, never()).onComplete(eq(2), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_TICK)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_multipleMono_runsSameEffectInAllVibrators() throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(3).setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.createParallel(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
        assertFalse(mControllers.get(3).isVibrating());

        VibrationEffectSegment expected = expectedPrebaked(VibrationEffect.EFFECT_CLICK);
        assertEquals(Arrays.asList(expected),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expected),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expected),
                mVibratorProviders.get(3).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_multipleStereo_runsVibrationOnRightVibrators() throws Exception {
        mockVibrators(1, 2, 3, 4);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .addVibrator(3, VibrationEffect.createWaveform(
                        new long[]{10, 10}, new int[]{1, 2}, -1))
                .addVibrator(4, composed)
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(4), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
        assertFalse(mControllers.get(3).isVibrating());
        assertFalse(mControllers.get(4).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(Arrays.asList(expectedOneShot(20)),
                mVibratorProviders.get(3).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(1, 2), mVibratorProviders.get(3).getAmplitudes());
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                mVibratorProviders.get(4).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_multipleSequential_runsVibrationInOrderWithDelays() throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(3).setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibration effect = CombinedVibration.startSequential()
                .addNext(3, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), /* delay= */ 50)
                .addNext(1, VibrationEffect.createOneShot(10, 100), /* delay= */ 50)
                .addNext(2, composed, /* delay= */ 50)
                .combine();
        startThreadAndDispatcher(vibrationId, effect);

        waitForCompletion();
        InOrder controllerVerifier = inOrder(mControllerCallbacks);
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));

        InOrder batteryVerifier = inOrder(mManagerHooks);
        batteryVerifier.verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        batteryVerifier.verify(mManagerHooks).noteVibratorOff(eq(UID));
        batteryVerifier.verify(mManagerHooks).noteVibratorOn(eq(UID), eq(10L));
        batteryVerifier.verify(mManagerHooks).noteVibratorOff(eq(UID));
        batteryVerifier.verify(mManagerHooks).noteVibratorOn(eq(UID), eq(20L));
        batteryVerifier.verify(mManagerHooks).noteVibratorOff(eq(UID));

        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
        assertFalse(mControllers.get(3).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(3).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_multipleSyncedCallbackTriggered_finishSteps() throws Exception {
        int[] vibratorIds = new int[]{1, 2};
        long vibrationId = 1;
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mManagerHooks.prepareSyncedVibration(anyLong(), eq(vibratorIds))).thenReturn(true);
        when(mManagerHooks.triggerSyncedVibration(eq(vibrationId))).thenReturn(true);

        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100)
                .compose();
        CombinedVibration effect = CombinedVibration.createParallel(composed);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(
                () -> !mVibratorProviders.get(1).getEffectSegments(vibrationId).isEmpty()
                        && !mVibratorProviders.get(2).getEffectSegments(vibrationId).isEmpty(),
                TEST_TIMEOUT_MILLIS));
        conductor.notifySyncedVibrationComplete();
        waitForCompletion();

        long expectedCap = IVibratorManager.CAP_SYNC | IVibratorManager.CAP_PREPARE_COMPOSE;
        verify(mManagerHooks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mManagerHooks).triggerSyncedVibration(eq(vibrationId));
        verify(mManagerHooks, never()).cancelSyncedVibration();
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        VibrationEffectSegment expected = expectedPrimitive(
                VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100);
        assertEquals(Arrays.asList(expected),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expected),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
    }

    @Test
    public void vibrate_multipleSynced_callsPrepareAndTriggerCallbacks() {
        int[] vibratorIds = new int[]{1, 2, 3, 4};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mManagerHooks.prepareSyncedVibration(anyLong(), any())).thenReturn(true);
        when(mManagerHooks.triggerSyncedVibration(anyLong())).thenReturn(true);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .addVibrator(3, VibrationEffect.createWaveform(new long[]{10}, new int[]{100}, -1))
                .addVibrator(4, composed)
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        long expectedCap = IVibratorManager.CAP_SYNC
                | IVibratorManager.CAP_PREPARE_ON
                | IVibratorManager.CAP_PREPARE_PERFORM
                | IVibratorManager.CAP_PREPARE_COMPOSE
                | IVibratorManager.CAP_MIXED_TRIGGER_ON
                | IVibratorManager.CAP_MIXED_TRIGGER_PERFORM
                | IVibratorManager.CAP_MIXED_TRIGGER_COMPOSE;
        verify(mManagerHooks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mManagerHooks).triggerSyncedVibration(eq(vibrationId));
        verify(mManagerHooks, never()).cancelSyncedVibration();
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
    }

    @Test
    public void vibrate_multipleSyncedPrepareFailed_skipTriggerStepAndVibrates() {
        int[] vibratorIds = new int[]{1, 2};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        when(mManagerHooks.prepareSyncedVibration(anyLong(), any())).thenReturn(false);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(10, 100))
                .addVibrator(2, VibrationEffect.createWaveform(new long[]{5}, new int[]{200}, -1))
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        long expectedCap = IVibratorManager.CAP_SYNC | IVibratorManager.CAP_PREPARE_ON;
        verify(mManagerHooks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mManagerHooks, never()).triggerSyncedVibration(eq(vibrationId));
        verify(mManagerHooks, never()).cancelSyncedVibration();

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(expectedOneShot(5)),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(200), mVibratorProviders.get(2).getAmplitudes());
    }

    @Test
    public void vibrate_multipleSyncedTriggerFailed_cancelPreparedVibrationAndSkipSetAmplitude() {
        int[] vibratorIds = new int[]{1, 2};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(2).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        when(mManagerHooks.prepareSyncedVibration(anyLong(), any())).thenReturn(true);
        when(mManagerHooks.triggerSyncedVibration(anyLong())).thenReturn(false);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createOneShot(10, 100))
                .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        long expectedCap = IVibratorManager.CAP_SYNC
                | IVibratorManager.CAP_PREPARE_ON
                | IVibratorManager.CAP_PREPARE_PERFORM
                | IVibratorManager.CAP_MIXED_TRIGGER_ON
                | IVibratorManager.CAP_MIXED_TRIGGER_PERFORM;
        verify(mManagerHooks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mManagerHooks).triggerSyncedVibration(eq(vibrationId));
        verify(mManagerHooks).cancelSyncedVibration();
        assertTrue(mVibratorProviders.get(1).getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_multipleWaveforms_playsWaveformsInParallel() throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createWaveform(
                        new long[]{5, 10, 10}, new int[]{1, 2, 3}, -1))
                .addVibrator(2, VibrationEffect.createWaveform(
                        new long[]{20, 60}, new int[]{4, 5}, -1))
                .addVibrator(3, VibrationEffect.createWaveform(
                        new long[]{60}, new int[]{6}, -1))
                .combine();
        startThreadAndDispatcher(vibrationId, effect);

        // All vibrators are turned on in parallel.
        assertTrue(waitUntil(
                () -> mControllers.get(1).isVibrating()
                        && mControllers.get(2).isVibrating()
                        && mControllers.get(3).isVibrating(),
                TEST_TIMEOUT_MILLIS));

        waitForCompletion();

        verify(mManagerHooks).noteVibratorOn(eq(UID), eq(80L));
        verify(mManagerHooks).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
        assertFalse(mControllers.get(3).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(25)),
                mVibratorProviders.get(1).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expectedOneShot(80)),
                mVibratorProviders.get(2).getEffectSegments(vibrationId));
        assertEquals(Arrays.asList(expectedOneShot(60)),
                mVibratorProviders.get(3).getEffectSegments(vibrationId));
        assertEquals(expectedAmplitudes(1, 2, 3), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(expectedAmplitudes(4, 5), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(expectedAmplitudes(6), mVibratorProviders.get(3).getAmplitudes());
    }

    @LargeTest
    @Test
    public void vibrate_withWaveform_totalVibrationTimeRespected() {
        int totalDuration = 10_000; // 10s
        int stepDuration = 25; // 25ms

        // 25% of the first waveform step will be spent on the native on() call.
        // 25% of each waveform step will be spent on the native setAmplitude() call..
        mVibratorProviders.get(VIBRATOR_ID).setLatency(stepDuration / 4);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        int stepCount = totalDuration / stepDuration;
        long[] timings = new long[stepCount];
        int[] amplitudes = new int[stepCount];
        Arrays.fill(timings, stepDuration);
        Arrays.fill(amplitudes, VibrationEffect.DEFAULT_AMPLITUDE);
        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);

        long vibrationId = 1;
        startThreadAndDispatcher(vibrationId, effect);
        long startTime = SystemClock.elapsedRealtime();

        waitForCompletion(totalDuration + TEST_TIMEOUT_MILLIS);
        long delay = Math.abs(SystemClock.elapsedRealtime() - startTime - totalDuration);

        // Allow some delay for thread scheduling and callback triggering.
        int maxDelay = (int) (0.05 * totalDuration); // < 5% of total duration
        assertTrue("Waveform with perceived delay of " + delay + "ms,"
                        + " expected less than " + maxDelay + "ms",
                delay < maxDelay);
    }

    @LargeTest
    @Test
    public void vibrate_cancelSlowVibrator_cancelIsNotBlockedByVibrationThread() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long latency = 5_000; // 5s
        fakeVibrator.setLatency(latency);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> !fakeVibrator.getEffectSegments(vibrationId).isEmpty(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(cancellingThread).
        Thread cancellingThread = new Thread(
                () -> conductor.notifyCancelled(
                        Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false));
        cancellingThread.start();

        // Cancelling the vibration should be fast and return right away, even if the thread is
        // stuck at the slow call to the vibrator.
        waitForCompletion(/* timeout= */ 50);

        // After the vibrator call ends the vibration is cancelled and the vibrator is turned off.
        waitForCompletion(/* timeout= */ latency + TEST_TIMEOUT_MILLIS);
        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_multiplePredefinedCancel_cancelsVibrationImmediately() throws Exception {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .compose())
                .combine();
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> mControllers.get(2).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread = new Thread(
                () -> conductor.notifyCancelled(
                        Vibration.Status.CANCELLED_SUPERSEDED, /* immediate= */ false));
        cancellingThread.start();

        waitForCompletion(/* timeout= */ 50);
        cancellingThread.join();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_SUPERSEDED);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
    }

    @Test
    public void vibrate_multipleWaveformCancel_cancelsVibrationImmediately() throws Exception {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        CombinedVibration effect = CombinedVibration.startParallel()
                .addVibrator(1, VibrationEffect.createWaveform(
                        new long[]{100, 100}, new int[]{1, 2}, 0))
                .addVibrator(2, VibrationEffect.createOneShot(100, 100))
                .combine();
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> mControllers.get(1).isVibrating()
                        && mControllers.get(2).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread =
                new Thread(() -> conductor.notifyCancelled(
                        Vibration.Status.CANCELLED_BY_SCREEN_OFF, /* immediate= */ false));
        cancellingThread.start();

        waitForCompletion(/* timeout= */ 50);
        cancellingThread.join();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_SCREEN_OFF);
        assertFalse(mControllers.get(1).isVibrating());
        assertFalse(mControllers.get(2).isVibrating());
    }

    @Test
    public void vibrate_binderDied_cancelsVibration() throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5}, new int[]{100}, 0);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(() -> mControllers.get(VIBRATOR_ID).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        conductor.binderDied();
        waitForCompletion();

        verify(mVibrationToken).linkToDeath(same(conductor), eq(0));
        verify(mVibrationToken).unlinkToDeath(same(conductor), eq(0));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BINDER_DIED);
        assertFalse(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId).isEmpty());
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_waveformWithRampDown_addsRampDownAfterVibrationCompleted() {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(15);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{5, 5, 5}, new int[]{60, 120, 240}, -1);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        // Duration extended for 5 + 5 + 5 + 15.
        assertEquals(Arrays.asList(expectedOneShot(30)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        List<Float> amplitudes = mVibratorProviders.get(VIBRATOR_ID).getAmplitudes();
        assertTrue(amplitudes.size() > 3);
        assertEquals(expectedAmplitudes(60, 120, 240), amplitudes.subList(0, 3));
        for (int i = 3; i < amplitudes.size(); i++) {
            assertTrue(amplitudes.get(i) < amplitudes.get(i - 1));
        }
    }

    @Test
    public void vibrate_waveformWithRampDown_triggersCallbackWhenOriginalVibrationEnds() {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(10_000);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10, 200);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);

        // Vibration completed but vibrator not yet released.
        verify(mManagerHooks, timeout(TEST_TIMEOUT_MILLIS)).onVibrationCompleted(eq(vibrationId),
                eq(Vibration.Status.FINISHED));
        verify(mManagerHooks, never()).onVibrationThreadReleased(anyLong());

        // Thread still running ramp down.
        assertTrue(mThread.isRunningVibrationId(vibrationId));

        // Duration extended for 10 + 10000.
        assertEquals(Arrays.asList(expectedOneShot(10_010)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));

        // Will stop the ramp down right away.
        conductor.notifyCancelled(
                Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE, /* immediate= */ true);
        waitForCompletion();

        // Does not cancel already finished vibration, but releases vibrator.
        verify(mManagerHooks, never()).onVibrationCompleted(eq(vibrationId),
                eq(Vibration.Status.CANCELLED_BY_SETTINGS_UPDATE));
        verify(mManagerHooks).onVibrationThreadReleased(vibrationId);
    }

    @Test
    public void vibrate_waveformCancelledWithRampDown_addsRampDownAfterVibrationCancelled()
            throws Exception {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(15);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10_000, 240);
        VibrationStepConductor conductor = startThreadAndDispatcher(vibrationId, effect);
        assertTrue(waitUntil(() -> mControllers.get(VIBRATOR_ID).isVibrating(),
                TEST_TIMEOUT_MILLIS));
        conductor.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        verifyCallbacksTriggered(vibrationId, Vibration.Status.CANCELLED_BY_USER);

        // Duration extended for 10000 + 15.
        assertEquals(Arrays.asList(expectedOneShot(10_015)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        List<Float> amplitudes = mVibratorProviders.get(VIBRATOR_ID).getAmplitudes();
        assertTrue(amplitudes.size() > 1);
        for (int i = 1; i < amplitudes.size(); i++) {
            assertTrue(amplitudes.get(i) < amplitudes.get(i - 1));
        }
    }

    @Test
    public void vibrate_predefinedWithRampDown_doesNotAddRampDown() {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(15);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(VIBRATOR_ID).setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_composedWithRampDown_doesNotAddRampDown() {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(15);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL,
                IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(VIBRATOR_ID).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        assertEquals(
                Arrays.asList(expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments(vibrationId));
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_pwleWithRampDown_doesNotAddRampDown() {
        when(mVibrationConfigMock.getRampDownDurationMs()).thenReturn(15);
        mEffectAdapter = new DeviceVibrationEffectAdapter(mVibrationSettings);
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL,
                IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        fakeVibrator.setMinFrequency(100);
        fakeVibrator.setResonantFrequency(150);
        fakeVibrator.setFrequencyResolution(50);
        fakeVibrator.setMaxAmplitudes(1, 1, 1);
        fakeVibrator.setPwleSizeMax(2);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startWaveform()
                .addTransition(Duration.ofMillis(1), targetAmplitude(1))
                .build();
        startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion();

        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verifyCallbacksTriggered(vibrationId, Vibration.Status.FINISHED);

        assertEquals(Arrays.asList(expectedRamp(0, 1, 150, 150, 1)),
                fakeVibrator.getEffectSegments(vibrationId));
        assertTrue(fakeVibrator.getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_multipleVibrations_withCancel() throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setSupportedEffects(
                VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_TICK);
        mVibratorProviders.get(VIBRATOR_ID).setSupportedPrimitives(
                VibrationEffect.Composition.PRIMITIVE_CLICK);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL,
                IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId1 = 1;
        long vibrationId2 = 2;
        long vibrationId3 = 3;
        long vibrationId4 = 4;
        long vibrationId5 = 5;

        // A simple effect, followed by a repeating effect that gets cancelled, followed by another
        // simple effect.
        VibrationEffect effect1 = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationEffect effect2 = VibrationEffect.startComposition()
                .repeatEffectIndefinitely(VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .compose();
        VibrationEffect effect3 = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        VibrationEffect effect4 = VibrationEffect.createOneShot(8000, 100);
        VibrationEffect effect5 = VibrationEffect.createOneShot(20, 222);

        startThreadAndDispatcher(vibrationId1, effect1);
        waitForCompletion();
        verify(mControllerCallbacks).onComplete(VIBRATOR_ID, vibrationId1);
        verifyCallbacksTriggered(vibrationId1, Vibration.Status.FINISHED);

        VibrationStepConductor conductor2 = startThreadAndDispatcher(vibrationId2, effect2);
        // Effect2 won't complete on its own. Cancel it after a couple of repeats.
        Thread.sleep(150);  // More than two TICKs.
        conductor2.notifyCancelled(Vibration.Status.CANCELLED_BY_USER, /* immediate= */ false);
        waitForCompletion();

        startThreadAndDispatcher(vibrationId3, effect3);
        waitForCompletion();

        // Effect4 is a long oneshot, but it gets cancelled as fast as possible.
        long start4 = System.currentTimeMillis();
        VibrationStepConductor conductor4 = startThreadAndDispatcher(vibrationId4, effect4);
        conductor4.notifyCancelled(Vibration.Status.CANCELLED_SUPERSEDED, /* immediate= */ true);
        waitForCompletion();
        long duration4 = System.currentTimeMillis() - start4;

        // Effect5 is to show that things keep going after the immediate cancel.
        startThreadAndDispatcher(vibrationId5, effect5);
        waitForCompletion();

        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        assertFalse(mControllers.get(VIBRATOR_ID).isVibrating());

        // Effect1
        verify(mControllerCallbacks).onComplete(VIBRATOR_ID, vibrationId1);
        verifyCallbacksTriggered(vibrationId1, Vibration.Status.FINISHED);

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                fakeVibrator.getEffectSegments(vibrationId1));

        // Effect2: repeating, cancelled.
        verify(mControllerCallbacks, atLeast(2)).onComplete(VIBRATOR_ID, vibrationId2);
        verifyCallbacksTriggered(vibrationId2, Vibration.Status.CANCELLED_BY_USER);

        // The exact count of segments might vary, so just check that there's more than 2 and
        // all elements are the same segment.
        List<VibrationEffectSegment> actualSegments2 = fakeVibrator.getEffectSegments(vibrationId2);
        assertTrue(actualSegments2.size() + " > 2", actualSegments2.size() > 2);
        for (VibrationEffectSegment segment : actualSegments2) {
            assertEquals(expectedPrebaked(VibrationEffect.EFFECT_TICK), segment);
        }

        // Effect3
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId3));
        verifyCallbacksTriggered(vibrationId3, Vibration.Status.FINISHED);
        assertEquals(Arrays.asList(
                        expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                fakeVibrator.getEffectSegments(vibrationId3));

        // Effect4: cancelled quickly.
        verifyCallbacksTriggered(vibrationId4, Vibration.Status.CANCELLED_SUPERSEDED);
        assertTrue("Tested duration=" + duration4, duration4 < 2000);

        // Effect5: normal oneshot. Don't worry about amplitude, as effect4 may or may not have
        // started.

        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId5));
        verifyCallbacksTriggered(vibrationId5, Vibration.Status.FINISHED);

        assertEquals(Arrays.asList(expectedOneShot(20)),
                fakeVibrator.getEffectSegments(vibrationId5));
    }

    private void mockVibrators(int... vibratorIds) {
        for (int vibratorId : vibratorIds) {
            mVibratorProviders.put(vibratorId,
                    new FakeVibratorControllerProvider(mTestLooper.getLooper()));
        }
    }

    private VibrationStepConductor startThreadAndDispatcher(
            long vibrationId, VibrationEffect effect) {
        return startThreadAndDispatcher(vibrationId, CombinedVibration.createParallel(effect));
    }

    private VibrationStepConductor startThreadAndDispatcher(long vibrationId,
            CombinedVibration effect) {
        return startThreadAndDispatcher(createVibration(vibrationId, effect));
    }

    private VibrationStepConductor startThreadAndDispatcher(Vibration vib) {
        mControllers = createVibratorControllers();
        VibrationStepConductor conductor = new VibrationStepConductor(vib, mVibrationSettings,
                mEffectAdapter, mControllers, mManagerHooks);
        doAnswer(answer -> {
            conductor.notifyVibratorComplete(answer.getArgument(0));
            return null;
        }).when(mControllerCallbacks).onComplete(anyInt(), eq(vib.id));
        assertTrue(mThread.runVibrationOnVibrationThread(conductor));
        return conductor;
    }

    private boolean waitUntil(BooleanSupplier predicate, long timeout)
            throws InterruptedException {
        long timeoutTimestamp = SystemClock.uptimeMillis() + timeout;
        boolean predicateResult = false;
        while (!predicateResult && SystemClock.uptimeMillis() < timeoutTimestamp) {
            Thread.sleep(10);
            predicateResult = predicate.getAsBoolean();
        }
        return predicateResult;
    }

    private void waitForCompletion() {
        waitForCompletion(TEST_TIMEOUT_MILLIS);
    }

    private void waitForCompletion(long timeout) {
        mThread.waitForThreadIdle(timeout);
        mTestLooper.dispatchAll();  // Flush callbacks
    }

    private Vibration createVibration(long id, CombinedVibration effect) {
        return new Vibration(mVibrationToken, (int) id, effect, ATTRS, UID, PACKAGE_NAME, "reason");
    }

    private SparseArray<VibratorController> createVibratorControllers() {
        SparseArray<VibratorController> array = new SparseArray<>();
        for (Map.Entry<Integer, FakeVibratorControllerProvider> e : mVibratorProviders.entrySet()) {
            int id = e.getKey();
            array.put(id, e.getValue().newVibratorController(id, mControllerCallbacks));
        }
        // Start a looper for the vibrationcontrollers if it's not already running.
        // TestLooper.AutoDispatchThread has a fixed 1s duration. Use a custom auto-dispatcher.
        if (mCustomTestLooperDispatcher == null) {
            mCustomTestLooperDispatcher = new TestLooperAutoDispatcher(mTestLooper);
            mCustomTestLooperDispatcher.start();
        }
        return array;
    }

    private VibrationEffectSegment expectedOneShot(long millis) {
        return new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE,
                /* frequencyHz= */ 0, (int) millis);
    }

    private VibrationEffectSegment expectedPrebaked(int effectId) {
        return new PrebakedSegment(effectId, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    private VibrationEffectSegment expectedPrimitive(int primitiveId, float scale, int delay) {
        return new PrimitiveSegment(primitiveId, scale, delay);
    }

    private VibrationEffectSegment expectedRamp(float amplitude, float frequencyHz, int duration) {
        return expectedRamp(amplitude, amplitude, frequencyHz, frequencyHz, duration);
    }

    private VibrationEffectSegment expectedRamp(float startAmplitude, float endAmplitude,
            float startFrequencyHz, float endFrequencyHz, int duration) {
        return new RampSegment(startAmplitude, endAmplitude, startFrequencyHz, endFrequencyHz,
                duration);
    }

    private List<Float> expectedAmplitudes(int... amplitudes) {
        return Arrays.stream(amplitudes)
                .mapToObj(amplitude -> amplitude / 255f)
                .collect(Collectors.toList());
    }

    private void verifyCallbacksTriggered(long vibrationId, Vibration.Status expectedStatus) {
        verify(mManagerHooks).onVibrationCompleted(eq(vibrationId), eq(expectedStatus));
        verify(mManagerHooks).onVibrationThreadReleased(vibrationId);
    }

    private static final class TestLooperAutoDispatcher extends Thread {
        private final TestLooper mTestLooper;
        private boolean mCancelled;

        TestLooperAutoDispatcher(TestLooper testLooper) {
            mTestLooper = testLooper;
        }

        @Override
        public void run() {
            while (!mCancelled) {
                mTestLooper.dispatchAll();
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        public void cancel() {
            mCancelled = true;
        }
    }
}
