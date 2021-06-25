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

package com.android.systemui.biometrics;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.TypedArray;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.Vibrator;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class UdfpsControllerTest extends SysuiTestCase {

    // Use this for inputs going into SystemUI. Use UdfpsController.mUdfpsSensorId for things
    // leaving SystemUI.
    private static final int TEST_UDFPS_SENSOR_ID = 1;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    // Unit under test
    private UdfpsController mUdfpsController;

    // Dependencies
    private Execution mExecution;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private UdfpsHbmProvider mHbmProvider;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private IUdfpsOverlayControllerCallback mUdfpsOverlayControllerCallback;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Mock
    private Vibrator mVibrator;

    private FakeExecutor mFgExecutor;

    // Stuff for configuring mocks
    @Mock
    private UdfpsView mUdfpsView;
    @Mock
    private TypedArray mBrightnessValues;
    @Mock
    private TypedArray mBrightnessBacklight;

    // Capture listeners so that they can be used to send events
    @Captor private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor private ArgumentCaptor<UdfpsView.OnTouchListener> mTouchListenerCaptor;
    @Captor private ArgumentCaptor<Runnable> mOnIlluminatedRunnableCaptor;
    @Captor private ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    private ScreenLifecycle.Observer mScreenObserver;

    @Before
    public void setUp() {
        setUpResources();
        mExecution = new FakeExecution();

        when(mLayoutInflater.inflate(R.layout.udfps_view, null, false)).thenReturn(mUdfpsView);
        final List<FingerprintSensorPropertiesInternal> props = new ArrayList<>();

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        props.add(new FingerprintSensorPropertiesInternal(TEST_UDFPS_SENSOR_ID,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */));
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(props);
        mFgExecutor = new FakeExecutor(new FakeSystemClock());
        mUdfpsController = new UdfpsController(
                mContext,
                mExecution,
                mLayoutInflater,
                mFingerprintManager,
                mWindowManager,
                mStatusBarStateController,
                mFgExecutor,
                mStatusBar,
                mStatusBarKeyguardViewManager,
                mDumpManager,
                mKeyguardUpdateMonitor,
                mKeyguardViewMediator,
                mFalsingManager,
                mPowerManager,
                mAccessibilityManager,
                mLockscreenShadeTransitionController,
                mScreenLifecycle,
                mVibrator,
                Optional.of(mHbmProvider));
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());
        mScreenObserver = mScreenObserverCaptor.getValue();

        assertEquals(TEST_UDFPS_SENSOR_ID, mUdfpsController.mSensorProps.sensorId);
    }

    private void setUpResources() {
        when(mBrightnessValues.length()).thenReturn(2);
        when(mBrightnessValues.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessValues.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
        when(mBrightnessBacklight.length()).thenReturn(2);
        when(mBrightnessBacklight.getFloat(0, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(1f);
        when(mBrightnessBacklight.getFloat(1, PowerManager.BRIGHTNESS_OFF_FLOAT)).thenReturn(2f);
    }

    @Test
    public void dozeTimeTick() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        mUdfpsController.dozeTimeTick();
        verify(mUdfpsView).dozeTimeTick();
    }

    @Test
    public void showUdfpsOverlay_addsViewToWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mWindowManager).addView(eq(mUdfpsView), any());
    }

    @Test
    public void hideUdfpsOverlay_removesViewFromWindow() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mOverlayController.hideUdfpsOverlay(TEST_UDFPS_SENSOR_ID);
        mFgExecutor.runAllReady();
        verify(mWindowManager).removeView(eq(mUdfpsView));
    }

    @Test
    public void fingerDown() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();
        // THEN FingerprintManager is notified about onPointerDown
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(0f), eq(0f));
        // AND illumination begins
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        // AND onIlluminatedRunnable notifies FingerprintManager about onUiReady
        mOnIlluminatedRunnableCaptor.getValue().run();
        verify(mFingerprintManager).onUiReady(eq(mUdfpsController.mSensorProps.sensorId));
    }

    @Test
    public void aodInterrupt() throws RemoteException {
        // GIVEN that the overlay is showing and screen is on
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        // WHEN fingerprint is requested because of AOD interrupt
        mUdfpsController.onAodInterrupt(0, 0, 2f, 3f);
        // THEN illumination begins
        // AND onIlluminatedRunnable that notifies FingerprintManager is set
        verify(mUdfpsView).startIllumination(mOnIlluminatedRunnableCaptor.capture());
        mOnIlluminatedRunnableCaptor.getValue().run();
        verify(mFingerprintManager).onPointerDown(eq(mUdfpsController.mSensorProps.sensorId), eq(0),
                eq(0), eq(3f) /* minor */, eq(2f) /* major */);
    }

    @Test
    public void cancelAodInterrupt() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        when(mUdfpsView.isIlluminationRequested()).thenReturn(true);
        // WHEN it is cancelled
        mUdfpsController.onCancelUdfps();
        // THEN the illumination is hidden
        verify(mUdfpsView).stopIllumination();
    }

    @Test
    public void aodInterruptTimeout() throws RemoteException {
        // GIVEN AOD interrupt
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOn();
        mFgExecutor.runAllReady();
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);
        when(mUdfpsView.isIlluminationRequested()).thenReturn(true);
        // WHEN it times out
        mFgExecutor.advanceClockToNext();
        mFgExecutor.runAllReady();
        // THEN the illumination is hidden
        verify(mUdfpsView).stopIllumination();
    }

    @Test
    public void aodInterruptScreenOff() throws RemoteException {
        // GIVEN screen off
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mScreenObserver.onScreenTurnedOff();
        mFgExecutor.runAllReady();

        // WHEN aod interrupt is received
        mUdfpsController.onAodInterrupt(0, 0, 0f, 0f);

        // THEN no illumination because screen is off
        verify(mUdfpsView, never()).startIllumination(any());
    }

    @Test
    public void playHapticOnTouchUdfpsArea() throws RemoteException {
        // Configure UdfpsView to accept the ACTION_DOWN event
        when(mUdfpsView.isIlluminationRequested()).thenReturn(false);
        when(mUdfpsView.isWithinSensorArea(anyFloat(), anyFloat())).thenReturn(true);

        // GIVEN that the overlay is showing
        mOverlayController.showUdfpsOverlay(TEST_UDFPS_SENSOR_ID,
                IUdfpsOverlayController.REASON_AUTH_FPM_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // WHEN ACTION_DOWN is received
        verify(mUdfpsView).setOnTouchListener(mTouchListenerCaptor.capture());
        MotionEvent downEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, downEvent);
        downEvent.recycle();
        MotionEvent moveEvent = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 0, 0, 0);
        mTouchListenerCaptor.getValue().onTouch(mUdfpsView, moveEvent);
        moveEvent.recycle();

        // THEN click haptic is played
        verify(mVibrator).vibrate(mUdfpsController.mEffectClick,
                UdfpsController.VIBRATION_SONIFICATION_ATTRIBUTES);
    }
}
