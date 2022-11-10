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

package com.android.server.display;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.display.AmbientBrightnessDayStats;
import android.hardware.display.BrightnessChangeEvent;
import android.hardware.display.BrightnessConfiguration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.FloatProperty;
import android.util.Log;
import android.util.MathUtils;
import android.util.MutableFloat;
import android.util.MutableInt;
import android.util.Slog;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.RingBuffer;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.color.ColorDisplayService.ColorDisplayServiceInternal;
import com.android.server.display.color.ColorDisplayService.ReduceBrightColorsListener;
import com.android.server.display.utils.SensorUtils;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.display.whitebalance.DisplayWhiteBalanceFactory;
import com.android.server.display.whitebalance.DisplayWhiteBalanceSettings;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;

/**
 * Controls the power state of the display.
 *
 * Handles the proximity sensor, light sensor, and animations between states
 * including the screen off animation.
 *
 * This component acts independently of the rest of the power manager service.
 * In particular, it does not share any state and it only communicates
 * via asynchronous callbacks to inform the power manager that something has
 * changed.
 *
 * Everything this class does internally is serialized on its handler although
 * it may be accessed by other threads from the outside.
 *
 * Note that the power manager service guarantees that it will hold a suspend
 * blocker as long as the display is not ready.  So most of the work done here
 * does not need to worry about holding a suspend blocker unless it happens
 * independently of the display ready signal.
 *
 * For debugging, you can make the color fade and brightness animations run
 * slower by changing the "animator duration scale" option in Development Settings.
 */
final class DisplayPowerController2 implements AutomaticBrightnessController.Callbacks,
        DisplayWhiteBalanceController.Callbacks, DisplayPowerControllerInterface {
    private static final String SCREEN_ON_BLOCKED_TRACE_NAME = "Screen on blocked";
    private static final String SCREEN_OFF_BLOCKED_TRACE_NAME = "Screen off blocked";

    private static final boolean DEBUG = false;


    // If true, uses the color fade on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_COLOR_FADE_ON_ANIMATION = false;

    private static final float SCREEN_ANIMATION_RATE_MINIMUM = 0.0f;

    private static final int COLOR_FADE_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final int COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_SCREEN_ON_UNBLOCKED = 2;
    private static final int MSG_SCREEN_OFF_UNBLOCKED = 3;
    private static final int MSG_CONFIGURE_BRIGHTNESS = 4;
    private static final int MSG_SET_TEMPORARY_BRIGHTNESS = 5;
    private static final int MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT = 6;
    private static final int MSG_STOP = 7;
    private static final int MSG_UPDATE_BRIGHTNESS = 8;
    private static final int MSG_UPDATE_RBC = 9;
    private static final int MSG_BRIGHTNESS_RAMP_DONE = 10;
    private static final int MSG_STATSD_HBM_BRIGHTNESS = 11;

    private static final int BRIGHTNESS_CHANGE_STATSD_REPORT_INTERVAL_MS = 500;


    // State machine constants for tracking initial brightness ramp skipping when enabled.
    private static final int RAMP_STATE_SKIP_NONE = 0;
    private static final int RAMP_STATE_SKIP_INITIAL = 1;
    private static final int RAMP_STATE_SKIP_AUTOBRIGHT = 2;

    private static final int REPORTED_TO_POLICY_UNREPORTED = -1;
    private static final int REPORTED_TO_POLICY_SCREEN_OFF = 0;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_ON = 1;
    private static final int REPORTED_TO_POLICY_SCREEN_ON = 2;
    private static final int REPORTED_TO_POLICY_SCREEN_TURNING_OFF = 3;

    private static final int RINGBUFFER_MAX = 100;

    private final String mTag;

    private final Object mLock = new Object();

    private final Context mContext;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Battery stats.
    @Nullable
    private final IBatteryStats mBatteryStats;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The window manager policy.
    private final WindowManagerPolicy mWindowManagerPolicy;

    // The display blanker.
    private final DisplayBlanker mBlanker;

    // The LogicalDisplay tied to this DisplayPowerController2.
    private final LogicalDisplay mLogicalDisplay;

    // The ID of the LogicalDisplay tied to this DisplayPowerController2.
    private final int mDisplayId;

    // The unique ID of the primary display device currently tied to this logical display
    private String mUniqueDisplayId;

    // Tracker for brightness changes.
    @Nullable
    private final BrightnessTracker mBrightnessTracker;

    // Tracker for brightness settings changes.
    private final SettingsObserver mSettingsObserver;

    // The doze screen brightness.
    private final float mScreenBrightnessDozeConfig;

    // The dim screen brightness.
    private final float mScreenBrightnessDimConfig;

    // The minimum dim amount to use if the screen brightness is already below
    // mScreenBrightnessDimConfig.
    private final float mScreenBrightnessMinimumDimAmount;

    private final float mScreenBrightnessDefault;

    // The minimum allowed brightness while in VR.
    private final float mScreenBrightnessForVrRangeMinimum;

    // The maximum allowed brightness while in VR.
    private final float mScreenBrightnessForVrRangeMaximum;

    // The default screen brightness for VR.
    private final float mScreenBrightnessForVrDefault;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // True if should use light sensor to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;

    // Whether or not the color fade on screen on / off is enabled.
    private final boolean mColorFadeEnabled;

    @GuardedBy("mCachedBrightnessInfo")
    private final CachedBrightnessInfo mCachedBrightnessInfo = new CachedBrightnessInfo();

    private DisplayDevice mDisplayDevice;

    // True if we should fade the screen while turning it off, false if we should play
    // a stylish color fade animation instead.
    private final boolean mColorFadeFadesConfig;

    // True if we need to fake a transition to off when coming out of a doze state.
    // Some display hardware will blank itself when coming out of doze in order to hide
    // artifacts. For these displays we fake a transition into OFF so that policy can appropriately
    // blank itself and begin an appropriate power on animation.
    private final boolean mDisplayBlanksAfterDozeConfig;

    // True if there are only buckets of brightness values when the display is in the doze state,
    // rather than a full range of values. If this is true, then we'll avoid animating the screen
    // brightness since it'd likely be multiple jarring brightness transitions instead of just one
    // to reach the final state.
    private final boolean mBrightnessBucketsInDozeConfig;

    private final Clock mClock;
    private final Injector mInjector;

    //  Maximum time a ramp animation can take.
    private long mBrightnessRampIncreaseMaxTimeMillis;
    private long mBrightnessRampDecreaseMaxTimeMillis;

    // The pending power request.
    // Initially null until the first call to requestPowerState.
    @GuardedBy("mLock")
    private DisplayPowerRequest mPendingRequestLocked;

    // True if the pending power request or wait for negative proximity flag
    // has been changed since the last update occurred.
    @GuardedBy("mLock")
    private boolean mPendingRequestChangedLocked;

    // Set to true when the important parts of the pending power request have been applied.
    // The important parts are mainly the screen state.  Brightness changes may occur
    // concurrently.
    @GuardedBy("mLock")
    private boolean mDisplayReadyLocked;

    // Set to true if a power state update is required.
    @GuardedBy("mLock")
    private boolean mPendingUpdatePowerStateLocked;

    /* The following state must only be accessed by the handler thread. */

    // The currently requested power state.
    // The power controller will progressively update its internal state to match
    // the requested power state.  Initially null until the first update.
    private DisplayPowerRequest mPowerRequest;

    // The current power state.
    // Must only be accessed on the handler thread.
    private DisplayPowerState mPowerState;



    // The currently active screen on unblocker.  This field is non-null whenever
    // we are waiting for a callback to release it and unblock the screen.
    private ScreenOnUnblocker mPendingScreenOnUnblocker;
    private ScreenOffUnblocker mPendingScreenOffUnblocker;

    // True if we were in the process of turning off the screen.
    // This allows us to recover more gracefully from situations where we abort
    // turning off the screen.
    private boolean mPendingScreenOff;

    // The elapsed real time when the screen on was blocked.
    private long mScreenOnBlockStartRealTime;
    private long mScreenOffBlockStartRealTime;

    // Screen state we reported to policy. Must be one of REPORTED_TO_POLICY_* fields.
    private int mReportedScreenStateToPolicy = REPORTED_TO_POLICY_UNREPORTED;

    // If the last recorded screen state was dozing or not.
    private boolean mDozing;

    // Remembers whether certain kinds of brightness adjustments
    // were recently applied so that we can decide how to transition.
    private boolean mAppliedAutoBrightness;
    private boolean mAppliedDimming;
    private boolean mAppliedLowPower;
    private boolean mAppliedScreenBrightnessOverride;
    private boolean mAppliedTemporaryBrightness;
    private boolean mAppliedTemporaryAutoBrightnessAdjustment;
    private boolean mAppliedBrightnessBoost;
    private boolean mAppliedThrottling;

    // Reason for which the brightness was last changed. See {@link BrightnessReason} for more
    // information.
    // At the time of this writing, this value is changed within updatePowerState() only, which is
    // limited to the thread used by DisplayControllerHandler.
    private final BrightnessReason mBrightnessReason = new BrightnessReason();
    private final BrightnessReason mBrightnessReasonTemp = new BrightnessReason();

    // Brightness animation ramp rates in brightness units per second
    private float mBrightnessRampRateFastDecrease;
    private float mBrightnessRampRateFastIncrease;
    private float mBrightnessRampRateSlowDecrease;
    private float mBrightnessRampRateSlowIncrease;

    // Report HBM brightness change to StatsD
    private int mDisplayStatsId;
    private float mLastStatsBrightness = PowerManager.BRIGHTNESS_MIN;

    // Whether or not to skip the initial brightness ramps into STATE_ON.
    private final boolean mSkipScreenOnBrightnessRamp;

    // Display white balance components.
    @Nullable
    private final DisplayWhiteBalanceSettings mDisplayWhiteBalanceSettings;
    @Nullable
    private final DisplayWhiteBalanceController mDisplayWhiteBalanceController;

    @Nullable
    private final ColorDisplayServiceInternal mCdsi;
    private float[] mNitsRange;

    private final HighBrightnessModeController mHbmController;

    private final BrightnessThrottler mBrightnessThrottler;

    private final BrightnessSetting mBrightnessSetting;

    private final Runnable mOnBrightnessChangeRunnable;

    private final BrightnessEvent mLastBrightnessEvent;
    private final BrightnessEvent mTempBrightnessEvent;

    // Keeps a record of brightness changes for dumpsys.
    private RingBuffer<BrightnessEvent> mBrightnessEventRingBuffer;

    // Controls and tracks all the wakelocks that are acquired/released by the system. Also acts as
    // a medium of communication between this class and the PowerManagerService.
    private final WakelockController mWakelockController;

    // Tracks and manages the proximity state of the associated display.
    private final DisplayPowerProximityStateController mDisplayPowerProximityStateController;

    // A record of state for skipping brightness ramps.
    private int mSkipRampState = RAMP_STATE_SKIP_NONE;

    // The first autobrightness value set when entering RAMP_STATE_SKIP_INITIAL.
    private float mInitialAutoBrightness;

    // The controller for the automatic brightness level.
    @Nullable
    private AutomaticBrightnessController mAutomaticBrightnessController;

    private Sensor mLightSensor;

    // The mappers between ambient lux, display backlight values, and display brightness.
    // We will switch between the idle mapper and active mapper in AutomaticBrightnessController.
    // Mapper used for active (normal) screen brightness mode
    @Nullable
    private BrightnessMappingStrategy mInteractiveModeBrightnessMapper;
    // Mapper used for idle screen brightness mode
    @Nullable
    private BrightnessMappingStrategy mIdleModeBrightnessMapper;

    // The current brightness configuration.
    @Nullable
    private BrightnessConfiguration mBrightnessConfiguration;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    private float mLastUserSetScreenBrightness = Float.NaN;

    // The screen brightness setting has changed but not taken effect yet. If this is different
    // from the current screen brightness setting then this is coming from something other than us
    // and should be considered a user interaction.
    private float mPendingScreenBrightnessSetting;

    // The last observed screen brightness setting, either set by us or by the settings app on
    // behalf of the user.
    private float mCurrentScreenBrightnessSetting;

    // The temporary screen brightness. Typically set when a user is interacting with the
    // brightness slider but hasn't settled on a choice yet. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when there's no temporary brightness set.
    private float mTemporaryScreenBrightness;

    // The current screen brightness while in VR mode.
    private float mScreenBrightnessForVr;

    // The last auto brightness adjustment that was set by the user and not temporary. Set to
    // Float.NaN when an auto-brightness adjustment hasn't been recorded yet.
    private float mAutoBrightnessAdjustment;

    // The pending auto brightness adjustment that will take effect on the next power state update.
    private float mPendingAutoBrightnessAdjustment;

    // The temporary auto brightness adjustment. Typically set when a user is interacting with the
    // adjustment slider but hasn't settled on a choice yet. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when there's no temporary adjustment set.
    private float mTemporaryAutoBrightnessAdjustment;

    private boolean mIsRbcActive;

    // Animators.
    private ObjectAnimator mColorFadeOnAnimator;
    private ObjectAnimator mColorFadeOffAnimator;
    private DualRampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;
    private BrightnessSetting.BrightnessSettingListener mBrightnessSettingListener;

    // True if this DisplayPowerController2 has been stopped and should no longer be running.
    private boolean mStopped;

    private DisplayDeviceConfig mDisplayDeviceConfig;

    /**
     * Creates the display power controller.
     */
    DisplayPowerController2(Context context, Injector injector,
            DisplayPowerCallbacks callbacks, Handler handler,
            SensorManager sensorManager, DisplayBlanker blanker, LogicalDisplay logicalDisplay,
            BrightnessTracker brightnessTracker, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable) {

        mInjector = injector != null ? injector : new Injector();
        mClock = mInjector.getClock();
        mLogicalDisplay = logicalDisplay;
        mDisplayId = mLogicalDisplay.getDisplayIdLocked();
        mSensorManager = sensorManager;
        mHandler = new DisplayControllerHandler(handler.getLooper());
        mDisplayDeviceConfig = logicalDisplay.getPrimaryDisplayDeviceLocked()
                .getDisplayDeviceConfig();
        mWakelockController = mInjector.getWakelockController(mDisplayId, callbacks);
        mDisplayPowerProximityStateController = mInjector.getDisplayPowerProximityStateController(
                mWakelockController, mDisplayDeviceConfig, mHandler.getLooper(),
                () -> updatePowerState(), mDisplayId, mSensorManager);
        mTag = "DisplayPowerController2[" + mDisplayId + "]";

        mDisplayDevice = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        mUniqueDisplayId = logicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
        mDisplayStatsId = mUniqueDisplayId.hashCode();

        mLastBrightnessEvent = new BrightnessEvent(mDisplayId);
        mTempBrightnessEvent = new BrightnessEvent(mDisplayId);

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mBatteryStats = BatteryStatsService.getService();
        } else {
            mBatteryStats = null;
        }

        mSettingsObserver = new SettingsObserver(mHandler);
        mWindowManagerPolicy = LocalServices.getService(WindowManagerPolicy.class);
        mBlanker = blanker;
        mContext = context;
        mBrightnessTracker = brightnessTracker;
        // TODO: b/186428377 update brightness setting when display changes
        mBrightnessSetting = brightnessSetting;
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;

        PowerManager pm = context.getSystemService(PowerManager.class);

        final Resources resources = context.getResources();

        // DOZE AND DIM SETTINGS
        mScreenBrightnessDozeConfig = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE));
        mScreenBrightnessDimConfig = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DIM));
        mScreenBrightnessMinimumDimAmount = resources.getFloat(
                R.dimen.config_screenBrightnessMinimumDimAmountFloat);


        // NORMAL SCREEN SETTINGS
        mScreenBrightnessDefault = clampAbsoluteBrightness(
                mLogicalDisplay.getDisplayInfoLocked().brightnessDefault);

        // VR SETTINGS
        mScreenBrightnessForVrDefault = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT_VR));
        mScreenBrightnessForVrRangeMaximum = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR));
        mScreenBrightnessForVrRangeMinimum = clampAbsoluteBrightness(
                pm.getBrightnessConstraint(PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR));

        mAllowAutoBrightnessWhileDozingConfig = resources.getBoolean(
                R.bool.config_allowAutoBrightnessWhileDozing);

        loadBrightnessRampRates();
        mSkipScreenOnBrightnessRamp = resources.getBoolean(
                R.bool.config_skipScreenOnBrightnessRamp);

        mHbmController = createHbmControllerLocked();

        mBrightnessThrottler = createBrightnessThrottlerLocked();

        // Seed the cached brightness
        saveBrightnessInfo(getScreenBrightnessSetting());

        DisplayWhiteBalanceSettings displayWhiteBalanceSettings = null;
        DisplayWhiteBalanceController displayWhiteBalanceController = null;
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            try {
                displayWhiteBalanceSettings = new DisplayWhiteBalanceSettings(mContext, mHandler);
                displayWhiteBalanceController = DisplayWhiteBalanceFactory.create(mHandler,
                        mSensorManager, resources);
                displayWhiteBalanceSettings.setCallbacks(this);
                displayWhiteBalanceController.setCallbacks(this);
            } catch (Exception e) {
                Slog.e(mTag, "failed to set up display white-balance: " + e);
            }
        }
        mDisplayWhiteBalanceSettings = displayWhiteBalanceSettings;
        mDisplayWhiteBalanceController = displayWhiteBalanceController;

        loadNitsRange(resources);

        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mCdsi = LocalServices.getService(ColorDisplayServiceInternal.class);
            boolean active = mCdsi.setReduceBrightColorsListener(new ReduceBrightColorsListener() {
                @Override
                public void onReduceBrightColorsActivationChanged(boolean activated,
                        boolean userInitiated) {
                    applyReduceBrightColorsSplineAdjustment();

                }

                @Override
                public void onReduceBrightColorsStrengthChanged(int strength) {
                    applyReduceBrightColorsSplineAdjustment();
                }
            });
            if (active) {
                applyReduceBrightColorsSplineAdjustment();
            }
        } else {
            mCdsi = null;
        }

        setUpAutoBrightness(resources, handler);

        mColorFadeEnabled = !ActivityManager.isLowRamDeviceStatic();
        mColorFadeFadesConfig = resources.getBoolean(
                R.bool.config_animateScreenLights);

        mDisplayBlanksAfterDozeConfig = resources.getBoolean(
                R.bool.config_displayBlanksAfterDoze);

        mBrightnessBucketsInDozeConfig = resources.getBoolean(
                R.bool.config_displayBrightnessBucketsInDoze);

        mCurrentScreenBrightnessSetting = getScreenBrightnessSetting();
        mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        mAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mTemporaryAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mPendingAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    }

    private void applyReduceBrightColorsSplineAdjustment() {
        mHandler.obtainMessage(MSG_UPDATE_RBC).sendToTarget();
        sendUpdatePowerState();
    }

    private void handleRbcChanged() {
        if (mAutomaticBrightnessController == null) {
            return;
        }
        if ((!mAutomaticBrightnessController.isInIdleMode()
                && mInteractiveModeBrightnessMapper == null)
                || (mAutomaticBrightnessController.isInIdleMode()
                && mIdleModeBrightnessMapper == null)) {
            Log.w(mTag, "No brightness mapping available to recalculate splines for this mode");
            return;
        }

        float[] adjustedNits = new float[mNitsRange.length];
        for (int i = 0; i < mNitsRange.length; i++) {
            adjustedNits[i] = mCdsi.getReduceBrightColorsAdjustedBrightnessNits(mNitsRange[i]);
        }
        mIsRbcActive = mCdsi.isReduceBrightColorsActivated();
        mAutomaticBrightnessController.recalculateSplines(mIsRbcActive, adjustedNits);
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    @Override
    public boolean isProximitySensorAvailable() {
        return mDisplayPowerProximityStateController.isProximitySensorAvailable();
    }

    /**
     * Get the {@link BrightnessChangeEvent}s for the specified user.
     *
     * @param userId         userId to fetch data for
     * @param includePackage if false will null out the package name in events
     */
    @Nullable
    @Override
    public ParceledListSlice<BrightnessChangeEvent> getBrightnessEvents(
            @UserIdInt int userId, boolean includePackage) {
        if (mBrightnessTracker == null) {
            return null;
        }
        return mBrightnessTracker.getEvents(userId, includePackage);
    }

    @Override
    public void onSwitchUser(@UserIdInt int newUserId) {
        handleSettingsChange(true /* userSwitch */);
        if (mBrightnessTracker != null) {
            mBrightnessTracker.onSwitchUser(newUserId);
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<AmbientBrightnessDayStats> getAmbientBrightnessStats(
            @UserIdInt int userId) {
        if (mBrightnessTracker == null) {
            return null;
        }
        return mBrightnessTracker.getAmbientBrightnessStats(userId);
    }

    /**
     * Persist the brightness slider events and ambient brightness stats to disk.
     */
    @Override
    public void persistBrightnessTrackerState() {
        if (mBrightnessTracker != null) {
            mBrightnessTracker.persistBrightnessTrackerState();
        }
    }

    /**
     * Requests a new power state.
     * The controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     *
     * @param request                  The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     *                                 negative proximity before turning the screen back on,
     *                                 assuming the screen
     *                                 was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link DisplayPowerCallbacks#onStateChanged()}
     * then try the request again later until the state converges.
     */
    public boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity) {
        if (DEBUG) {
            Slog.d(mTag, "requestPowerState: "
                    + request + ", waitForNegativeProximity=" + waitForNegativeProximity);
        }

        synchronized (mLock) {
            if (mStopped) {
                return true;
            }

            boolean changed = mDisplayPowerProximityStateController
                    .setPendingWaitForNegativeProximityLocked(waitForNegativeProximity);

            if (mPendingRequestLocked == null) {
                mPendingRequestLocked = new DisplayPowerRequest(request);
                changed = true;
            } else if (!mPendingRequestLocked.equals(request)) {
                mPendingRequestLocked.copyFrom(request);
                changed = true;
            }

            if (changed) {
                mDisplayReadyLocked = false;
                if (!mPendingRequestChangedLocked) {
                    mPendingRequestChangedLocked = true;
                    sendUpdatePowerStateLocked();
                }
            }

            return mDisplayReadyLocked;
        }
    }

    @Override
    public BrightnessConfiguration getDefaultBrightnessConfiguration() {
        if (mAutomaticBrightnessController == null) {
            return null;
        }
        return mAutomaticBrightnessController.getDefaultConfig();
    }

    /**
     * Notified when the display is changed. We use this to apply any changes that might be needed
     * when displays get swapped on foldable devices.  For example, different brightness properties
     * of each display need to be properly reflected in AutomaticBrightnessController.
     *
     * Make sure DisplayManagerService.mSyncRoot lock is held when this is called
     */
    @Override
    public void onDisplayChanged() {
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        if (device == null) {
            Slog.wtf(mTag, "Display Device is null in DisplayPowerController2 for display: "
                    + mLogicalDisplay.getDisplayIdLocked());
            return;
        }

        final String uniqueId = device.getUniqueId();
        final DisplayDeviceConfig config = device.getDisplayDeviceConfig();
        final IBinder token = device.getDisplayTokenLocked();
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        mHandler.post(() -> {
            if (mDisplayDevice != device) {
                mDisplayDevice = device;
                mUniqueDisplayId = uniqueId;
                mDisplayStatsId = mUniqueDisplayId.hashCode();
                mDisplayDeviceConfig = config;
                loadFromDisplayDeviceConfig(token, info);
                mDisplayPowerProximityStateController.notifyDisplayDeviceChanged(config);
                updatePowerState();
            }
        });
    }

    /**
     * Called when the displays are preparing to transition from one device state to another.
     * This process involves turning off some displays so we need updatePowerState() to run and
     * calculate the new state.
     */
    @Override
    public void onDeviceStateTransition() {
        sendUpdatePowerState();
    }

    /**
     * Unregisters all listeners and interrupts all running threads; halting future work.
     *
     * This method should be called when the DisplayPowerController2 is no longer in use; i.e. when
     * the {@link #mDisplayId display} has been removed.
     */
    @Override
    public void stop() {
        synchronized (mLock) {
            mStopped = true;
            Message msg = mHandler.obtainMessage(MSG_STOP);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());

            if (mDisplayWhiteBalanceController != null) {
                mDisplayWhiteBalanceController.setEnabled(false);
            }

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }

            if (mBrightnessSetting != null) {
                mBrightnessSetting.unregisterListener(mBrightnessSettingListener);
            }

            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
    }

    private void loadFromDisplayDeviceConfig(IBinder token, DisplayDeviceInfo info) {
        // All properties that depend on the associated DisplayDevice and the DDC must be
        // updated here.
        loadBrightnessRampRates();
        loadNitsRange(mContext.getResources());
        setUpAutoBrightness(mContext.getResources(), mHandler);
        reloadReduceBrightColours();
        if (mScreenBrightnessRampAnimator != null) {
            mScreenBrightnessRampAnimator.setAnimationTimeLimits(
                    mBrightnessRampIncreaseMaxTimeMillis,
                    mBrightnessRampDecreaseMaxTimeMillis);
        }
        mHbmController.resetHbmData(info.width, info.height, token, info.uniqueId,
                mDisplayDeviceConfig.getHighBrightnessModeData(),
                new HighBrightnessModeController.HdrBrightnessDeviceConfig() {
                    @Override
                    public float getHdrBrightnessFromSdr(float sdrBrightness) {
                        return mDisplayDeviceConfig.getHdrBrightnessFromSdr(sdrBrightness);
                    }
                });
        mBrightnessThrottler.resetThrottlingData(
                mDisplayDeviceConfig.getBrightnessThrottlingData(), mUniqueDisplayId);
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    @GuardedBy("mLock")
    private void sendUpdatePowerStateLocked() {
        if (!mStopped && !mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            mHandler.sendMessageAtTime(msg, mClock.uptimeMillis());
        }
    }

    private void initialize(int displayState) {
        mPowerState = mInjector.getDisplayPowerState(mBlanker,
                mColorFadeEnabled ? new ColorFade(mDisplayId) : null, mDisplayId, displayState);

        if (mColorFadeEnabled) {
            mColorFadeOnAnimator = ObjectAnimator.ofFloat(
                    mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 0.0f, 1.0f);
            mColorFadeOnAnimator.setDuration(COLOR_FADE_ON_ANIMATION_DURATION_MILLIS);
            mColorFadeOnAnimator.addListener(mAnimatorListener);

            mColorFadeOffAnimator = ObjectAnimator.ofFloat(
                    mPowerState, DisplayPowerState.COLOR_FADE_LEVEL, 1.0f, 0.0f);
            mColorFadeOffAnimator.setDuration(COLOR_FADE_OFF_ANIMATION_DURATION_MILLIS);
            mColorFadeOffAnimator.addListener(mAnimatorListener);
        }

        mScreenBrightnessRampAnimator = mInjector.getDualRampAnimator(mPowerState,
                DisplayPowerState.SCREEN_BRIGHTNESS_FLOAT,
                DisplayPowerState.SCREEN_SDR_BRIGHTNESS_FLOAT);
        mScreenBrightnessRampAnimator.setAnimationTimeLimits(
                mBrightnessRampIncreaseMaxTimeMillis,
                mBrightnessRampDecreaseMaxTimeMillis);
        mScreenBrightnessRampAnimator.setListener(mRampAnimatorListener);

        noteScreenState(mPowerState.getScreenState());
        noteScreenBrightness(mPowerState.getScreenBrightness());

        // Initialize all of the brightness tracking state
        final float brightness = convertToNits(mPowerState.getScreenBrightness());
        if (mBrightnessTracker != null && brightness >= PowerManager.BRIGHTNESS_MIN) {
            mBrightnessTracker.start(brightness);
        }
        mBrightnessSettingListener = brightnessValue -> {
            Message msg = mHandler.obtainMessage(MSG_UPDATE_BRIGHTNESS, brightnessValue);
            mHandler.sendMessage(msg);
        };

        mBrightnessSetting.registerListener(mBrightnessSettingListener);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ),
                false /*notifyForDescendants*/, mSettingsObserver, UserHandle.USER_ALL);
    }

    private void setUpAutoBrightness(Resources resources, Handler handler) {
        mUseSoftwareAutoBrightnessConfig = mDisplayDeviceConfig.isAutoBrightnessAvailable();

        if (!mUseSoftwareAutoBrightnessConfig) {
            return;
        }

        final boolean isIdleScreenBrightnessEnabled = resources.getBoolean(
                R.bool.config_enableIdleScreenBrightnessMode);
        mInteractiveModeBrightnessMapper = BrightnessMappingStrategy.create(resources,
                mDisplayDeviceConfig, mDisplayWhiteBalanceController);
        if (isIdleScreenBrightnessEnabled) {
            mIdleModeBrightnessMapper = BrightnessMappingStrategy.createForIdleMode(resources,
                    mDisplayDeviceConfig, mDisplayWhiteBalanceController);
        }

        if (mInteractiveModeBrightnessMapper != null) {
            final float dozeScaleFactor = resources.getFraction(
                    R.fraction.config_screenAutoBrightnessDozeScaleFactor,
                    1, 1);

            // Ambient Lux - Active Mode Brightness Thresholds
            float[] ambientBrighteningThresholds =
                    mDisplayDeviceConfig.getAmbientBrighteningPercentages();
            float[] ambientDarkeningThresholds =
                    mDisplayDeviceConfig.getAmbientDarkeningPercentages();
            float[] ambientBrighteningLevels =
                    mDisplayDeviceConfig.getAmbientBrighteningLevels();
            float[] ambientDarkeningLevels =
                    mDisplayDeviceConfig.getAmbientDarkeningLevels();
            float ambientDarkeningMinThreshold =
                    mDisplayDeviceConfig.getAmbientLuxDarkeningMinThreshold();
            float ambientBrighteningMinThreshold =
                    mDisplayDeviceConfig.getAmbientLuxBrighteningMinThreshold();
            HysteresisLevels ambientBrightnessThresholds = new HysteresisLevels(
                    ambientBrighteningThresholds, ambientDarkeningThresholds,
                    ambientBrighteningLevels, ambientDarkeningLevels, ambientDarkeningMinThreshold,
                    ambientBrighteningMinThreshold);

            // Display - Active Mode Brightness Thresholds
            float[] screenBrighteningThresholds =
                    mDisplayDeviceConfig.getScreenBrighteningPercentages();
            float[] screenDarkeningThresholds =
                    mDisplayDeviceConfig.getScreenDarkeningPercentages();
            float[] screenBrighteningLevels =
                    mDisplayDeviceConfig.getScreenBrighteningLevels();
            float[] screenDarkeningLevels =
                    mDisplayDeviceConfig.getScreenDarkeningLevels();
            float screenDarkeningMinThreshold =
                    mDisplayDeviceConfig.getScreenDarkeningMinThreshold();
            float screenBrighteningMinThreshold =
                    mDisplayDeviceConfig.getScreenBrighteningMinThreshold();
            HysteresisLevels screenBrightnessThresholds = new HysteresisLevels(
                    screenBrighteningThresholds, screenDarkeningThresholds,
                    screenBrighteningLevels, screenDarkeningLevels, screenDarkeningMinThreshold,
                    screenBrighteningMinThreshold, true);

            // Ambient Lux - Idle Screen Brightness Thresholds
            float ambientDarkeningMinThresholdIdle =
                    mDisplayDeviceConfig.getAmbientLuxDarkeningMinThresholdIdle();
            float ambientBrighteningMinThresholdIdle =
                    mDisplayDeviceConfig.getAmbientLuxBrighteningMinThresholdIdle();
            float[] ambientBrighteningThresholdsIdle =
                    mDisplayDeviceConfig.getAmbientBrighteningPercentagesIdle();
            float[] ambientDarkeningThresholdsIdle =
                    mDisplayDeviceConfig.getAmbientDarkeningPercentagesIdle();
            float[] ambientBrighteningLevelsIdle =
                    mDisplayDeviceConfig.getAmbientBrighteningLevelsIdle();
            float[] ambientDarkeningLevelsIdle =
                    mDisplayDeviceConfig.getAmbientDarkeningLevelsIdle();
            HysteresisLevels ambientBrightnessThresholdsIdle = new HysteresisLevels(
                    ambientBrighteningThresholdsIdle, ambientDarkeningThresholdsIdle,
                    ambientBrighteningLevelsIdle, ambientDarkeningLevelsIdle,
                    ambientDarkeningMinThresholdIdle, ambientBrighteningMinThresholdIdle);

            // Display - Idle Screen Brightness Thresholds
            float screenDarkeningMinThresholdIdle =
                    mDisplayDeviceConfig.getScreenDarkeningMinThresholdIdle();
            float screenBrighteningMinThresholdIdle =
                    mDisplayDeviceConfig.getScreenBrighteningMinThresholdIdle();
            float[] screenBrighteningThresholdsIdle =
                    mDisplayDeviceConfig.getScreenBrighteningPercentagesIdle();
            float[] screenDarkeningThresholdsIdle =
                    mDisplayDeviceConfig.getScreenDarkeningPercentagesIdle();
            float[] screenBrighteningLevelsIdle =
                    mDisplayDeviceConfig.getScreenBrighteningLevelsIdle();
            float[] screenDarkeningLevelsIdle =
                    mDisplayDeviceConfig.getScreenDarkeningLevelsIdle();
            HysteresisLevels screenBrightnessThresholdsIdle = new HysteresisLevels(
                    screenBrighteningThresholdsIdle, screenDarkeningThresholdsIdle,
                    screenBrighteningLevelsIdle, screenDarkeningLevelsIdle,
                    screenDarkeningMinThresholdIdle, screenBrighteningMinThresholdIdle);

            long brighteningLightDebounce = mDisplayDeviceConfig
                    .getAutoBrightnessBrighteningLightDebounce();
            long darkeningLightDebounce = mDisplayDeviceConfig
                    .getAutoBrightnessDarkeningLightDebounce();
            boolean autoBrightnessResetAmbientLuxAfterWarmUp = resources.getBoolean(
                    R.bool.config_autoBrightnessResetAmbientLuxAfterWarmUp);

            int lightSensorWarmUpTimeConfig = resources.getInteger(
                    R.integer.config_lightSensorWarmupTime);
            int lightSensorRate = resources.getInteger(
                    R.integer.config_autoBrightnessLightSensorRate);
            int initialLightSensorRate = resources.getInteger(
                    R.integer.config_autoBrightnessInitialLightSensorRate);
            if (initialLightSensorRate == -1) {
                initialLightSensorRate = lightSensorRate;
            } else if (initialLightSensorRate > lightSensorRate) {
                Slog.w(mTag, "Expected config_autoBrightnessInitialLightSensorRate ("
                        + initialLightSensorRate + ") to be less than or equal to "
                        + "config_autoBrightnessLightSensorRate (" + lightSensorRate + ").");
            }

            loadAmbientLightSensor();
            // BrightnessTracker should only use one light sensor, we want to use the light sensor
            // from the default display and not e.g. temporary displays when switching layouts.
            if (mBrightnessTracker != null && mDisplayId == Display.DEFAULT_DISPLAY) {
                mBrightnessTracker.setLightSensor(mLightSensor);
            }

            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.stop();
            }
            mAutomaticBrightnessController = new AutomaticBrightnessController(this,
                    handler.getLooper(), mSensorManager, mLightSensor,
                    mInteractiveModeBrightnessMapper, lightSensorWarmUpTimeConfig,
                    PowerManager.BRIGHTNESS_MIN, PowerManager.BRIGHTNESS_MAX, dozeScaleFactor,
                    lightSensorRate, initialLightSensorRate, brighteningLightDebounce,
                    darkeningLightDebounce, autoBrightnessResetAmbientLuxAfterWarmUp,
                    ambientBrightnessThresholds, screenBrightnessThresholds,
                    ambientBrightnessThresholdsIdle, screenBrightnessThresholdsIdle, mContext,
                    mHbmController, mBrightnessThrottler, mIdleModeBrightnessMapper,
                    mDisplayDeviceConfig.getAmbientHorizonShort(),
                    mDisplayDeviceConfig.getAmbientHorizonLong());

            mBrightnessEventRingBuffer =
                    new RingBuffer<>(BrightnessEvent.class, RINGBUFFER_MAX);
        } else {
            mUseSoftwareAutoBrightnessConfig = false;
        }
    }

    private void loadBrightnessRampRates() {
        mBrightnessRampRateFastDecrease = mDisplayDeviceConfig.getBrightnessRampFastDecrease();
        mBrightnessRampRateFastIncrease = mDisplayDeviceConfig.getBrightnessRampFastIncrease();
        mBrightnessRampRateSlowDecrease = mDisplayDeviceConfig.getBrightnessRampSlowDecrease();
        mBrightnessRampRateSlowIncrease = mDisplayDeviceConfig.getBrightnessRampSlowIncrease();
        mBrightnessRampDecreaseMaxTimeMillis =
                mDisplayDeviceConfig.getBrightnessRampDecreaseMaxMillis();
        mBrightnessRampIncreaseMaxTimeMillis =
                mDisplayDeviceConfig.getBrightnessRampIncreaseMaxMillis();
    }

    private void loadNitsRange(Resources resources) {
        if (mDisplayDeviceConfig != null && mDisplayDeviceConfig.getNits() != null) {
            mNitsRange = mDisplayDeviceConfig.getNits();
        } else {
            Slog.w(mTag, "Screen brightness nits configuration is unavailable; falling back");
            mNitsRange = BrightnessMappingStrategy.getFloatArray(resources
                    .obtainTypedArray(R.array.config_screenBrightnessNits));
        }
    }

    private void reloadReduceBrightColours() {
        if (mCdsi != null && mCdsi.isReduceBrightColorsActivated()) {
            applyReduceBrightColorsSplineAdjustment();
        }
    }

    @Override
    public void setAutomaticScreenBrightnessMode(boolean isIdle) {
        if (mAutomaticBrightnessController != null) {
            if (isIdle) {
                mAutomaticBrightnessController.switchToIdleMode();
            } else {
                mAutomaticBrightnessController.switchToInteractiveScreenBrightnessMode();
            }
        }
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setStrongModeEnabled(isIdle);
        }
    }

    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            sendUpdatePowerState();
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }
    };

    private final RampAnimator.Listener mRampAnimatorListener = new RampAnimator.Listener() {
        @Override
        public void onAnimationEnd() {
            sendUpdatePowerState();
            Message msg = mHandler.obtainMessage(MSG_BRIGHTNESS_RAMP_DONE);
            mHandler.sendMessage(msg);
        }
    };

    /** Clean up all resources that are accessed via the {@link #mHandler} thread. */
    private void cleanupHandlerThreadAfterStop() {
        mDisplayPowerProximityStateController.cleanup();
        mHbmController.stop();
        mBrightnessThrottler.stop();
        mHandler.removeCallbacksAndMessages(null);

        // Release any outstanding wakelocks we're still holding because of pending messages.
        mWakelockController.releaseAll();

        final float brightness = mPowerState != null
                ? mPowerState.getScreenBrightness()
                : PowerManager.BRIGHTNESS_MIN;
        reportStats(brightness);

        if (mPowerState != null) {
            mPowerState.stop();
            mPowerState = null;
        }
    }

    private void updatePowerState() {
        Trace.traceBegin(Trace.TRACE_TAG_POWER,
                "DisplayPowerController#updatePowerState");
        updatePowerStateInternal();
        Trace.traceEnd(Trace.TRACE_TAG_POWER);
    }

    private void updatePowerStateInternal() {
        // Update the power state request.
        final boolean mustNotify;
        final int previousPolicy;
        boolean mustInitialize = false;
        int brightnessAdjustmentFlags = 0;
        mBrightnessReasonTemp.set(null);
        mTempBrightnessEvent.reset();
        synchronized (mLock) {
            if (mStopped) {
                return;
            }
            mPendingUpdatePowerStateLocked = false;
            if (mPendingRequestLocked == null) {
                return; // wait until first actual power request
            }

            if (mPowerRequest == null) {
                mPowerRequest = new DisplayPowerRequest(mPendingRequestLocked);
                mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
                // Assume we're on and bright until told otherwise, since that's the state we turn
                // on in.
                previousPolicy = DisplayPowerRequest.POLICY_BRIGHT;
            } else if (mPendingRequestChangedLocked) {
                previousPolicy = mPowerRequest.policy;
                mPowerRequest.copyFrom(mPendingRequestLocked);
                mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            } else {
                previousPolicy = mPowerRequest.policy;
            }

            mustNotify = !mDisplayReadyLocked;
        }

        // Compute the basic display state using the policy.
        // We might override this below based on other factors.
        // Initialise brightness as invalid.
        int state;
        float brightnessState = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        boolean performScreenOffTransition = false;
        switch (mPowerRequest.policy) {
            case DisplayPowerRequest.POLICY_OFF:
                state = Display.STATE_OFF;
                performScreenOffTransition = true;
                break;
            case DisplayPowerRequest.POLICY_DOZE:
                if (mPowerRequest.dozeScreenState != Display.STATE_UNKNOWN) {
                    state = mPowerRequest.dozeScreenState;
                } else {
                    state = Display.STATE_DOZE;
                }
                if (!mAllowAutoBrightnessWhileDozingConfig) {
                    brightnessState = mPowerRequest.dozeScreenBrightness;
                    mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE);
                }
                break;
            case DisplayPowerRequest.POLICY_VR:
                state = Display.STATE_VR;
                break;
            case DisplayPowerRequest.POLICY_DIM:
            case DisplayPowerRequest.POLICY_BRIGHT:
            default:
                state = Display.STATE_ON;
                break;
        }
        assert (state != Display.STATE_UNKNOWN);

        mDisplayPowerProximityStateController.updateProximityState(mPowerRequest, state);

        if (!mLogicalDisplay.isEnabled()
                || mLogicalDisplay.getPhase() == LogicalDisplay.DISPLAY_PHASE_LAYOUT_TRANSITION
                || mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
            state = Display.STATE_OFF;
        }

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize(state);
        }

        // Animate the screen state change unless already animating.
        // The transition may be deferred, so after this point we will use the
        // actual state instead of the desired one.
        final int oldState = mPowerState.getScreenState();
        animateScreenStateChange(state, performScreenOffTransition);
        state = mPowerState.getScreenState();

        if (state == Display.STATE_OFF) {
            brightnessState = PowerManager.BRIGHTNESS_OFF_FLOAT;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_SCREEN_OFF);
        }

        // Always use the VR brightness when in the VR state.
        if (state == Display.STATE_VR) {
            brightnessState = mScreenBrightnessForVr;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_VR);
        }

        if ((Float.isNaN(brightnessState))
                && isValidBrightnessValue(mPowerRequest.screenBrightnessOverride)) {
            brightnessState = mPowerRequest.screenBrightnessOverride;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_OVERRIDE);
            mAppliedScreenBrightnessOverride = true;
        } else {
            mAppliedScreenBrightnessOverride = false;
        }

        final boolean autoBrightnessEnabledInDoze =
                mAllowAutoBrightnessWhileDozingConfig && Display.isDozeState(state);
        final boolean autoBrightnessEnabled = mPowerRequest.useAutoBrightness
                && (state == Display.STATE_ON || autoBrightnessEnabledInDoze)
                && Float.isNaN(brightnessState)
                && mAutomaticBrightnessController != null;
        final boolean autoBrightnessDisabledDueToDisplayOff = mPowerRequest.useAutoBrightness
                && !(state == Display.STATE_ON || autoBrightnessEnabledInDoze);
        final int autoBrightnessState = autoBrightnessEnabled
                ? AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED
                : autoBrightnessDisabledDueToDisplayOff
                        ? AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE
                        : AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED;

        final boolean userSetBrightnessChanged = updateUserSetScreenBrightness();

        // Use the temporary screen brightness if there isn't an override, either from
        // WindowManager or based on the display state.
        if (isValidBrightnessValue(mTemporaryScreenBrightness)) {
            brightnessState = mTemporaryScreenBrightness;
            mAppliedTemporaryBrightness = true;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_TEMPORARY);
        } else {
            mAppliedTemporaryBrightness = false;
        }

        final boolean autoBrightnessAdjustmentChanged = updateAutoBrightnessAdjustment();

        // Use the autobrightness adjustment override if set.
        final float autoBrightnessAdjustment;
        if (!Float.isNaN(mTemporaryAutoBrightnessAdjustment)) {
            autoBrightnessAdjustment = mTemporaryAutoBrightnessAdjustment;
            brightnessAdjustmentFlags = BrightnessReason.ADJUSTMENT_AUTO_TEMP;
            mAppliedTemporaryAutoBrightnessAdjustment = true;
        } else {
            autoBrightnessAdjustment = mAutoBrightnessAdjustment;
            brightnessAdjustmentFlags = BrightnessReason.ADJUSTMENT_AUTO;
            mAppliedTemporaryAutoBrightnessAdjustment = false;
        }
        // Apply brightness boost.
        // We do this here after deciding whether auto-brightness is enabled so that we don't
        // disable the light sensor during this temporary state.  That way when boost ends we will
        // be able to resume normal auto-brightness behavior without any delay.
        if (mPowerRequest.boostScreenBrightness
                && brightnessState != PowerManager.BRIGHTNESS_OFF_FLOAT) {
            brightnessState = PowerManager.BRIGHTNESS_MAX;
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_BOOST);
            mAppliedBrightnessBoost = true;
        } else {
            mAppliedBrightnessBoost = false;
        }

        // If the brightness is already set then it's been overridden by something other than the
        // user, or is a temporary adjustment.
        boolean userInitiatedChange = (Float.isNaN(brightnessState))
                && (autoBrightnessAdjustmentChanged || userSetBrightnessChanged);
        boolean hadUserBrightnessPoint = false;
        // Configure auto-brightness.
        if (mAutomaticBrightnessController != null) {
            hadUserBrightnessPoint = mAutomaticBrightnessController.hasUserDataPoints();
            mAutomaticBrightnessController.configure(autoBrightnessState,
                    mBrightnessConfiguration,
                    mLastUserSetScreenBrightness,
                    userSetBrightnessChanged, autoBrightnessAdjustment,
                    autoBrightnessAdjustmentChanged, mPowerRequest.policy);
        }

        if (mBrightnessTracker != null) {
            mBrightnessTracker.setBrightnessConfiguration(mBrightnessConfiguration);
        }

        boolean updateScreenBrightnessSetting = false;

        // Apply auto-brightness.
        boolean slowChange = false;
        if (Float.isNaN(brightnessState)) {
            float newAutoBrightnessAdjustment = autoBrightnessAdjustment;
            if (autoBrightnessEnabled) {
                brightnessState = mAutomaticBrightnessController.getAutomaticScreenBrightness(
                        mTempBrightnessEvent);
                newAutoBrightnessAdjustment =
                        mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment();
            }
            if (isValidBrightnessValue(brightnessState)
                    || brightnessState == PowerManager.BRIGHTNESS_OFF_FLOAT) {
                // Use current auto-brightness value and slowly adjust to changes.
                brightnessState = clampScreenBrightness(brightnessState);
                if (mAppliedAutoBrightness && !autoBrightnessAdjustmentChanged) {
                    slowChange = true; // slowly adapt to auto-brightness
                }
                updateScreenBrightnessSetting = mCurrentScreenBrightnessSetting != brightnessState;
                mAppliedAutoBrightness = true;
                mBrightnessReasonTemp.setReason(BrightnessReason.REASON_AUTOMATIC);
            } else {
                mAppliedAutoBrightness = false;
            }
            if (autoBrightnessAdjustment != newAutoBrightnessAdjustment) {
                // If the autobrightness controller has decided to change the adjustment value
                // used, make sure that's reflected in settings.
                putAutoBrightnessAdjustmentSetting(newAutoBrightnessAdjustment);
            } else {
                // Adjustment values resulted in no change
                brightnessAdjustmentFlags = 0;
            }
        } else {
            // Any non-auto-brightness values such as override or temporary should still be subject
            // to clamping so that they don't go beyond the current max as specified by HBM
            // Controller.
            brightnessState = clampScreenBrightness(brightnessState);
            mAppliedAutoBrightness = false;
            brightnessAdjustmentFlags = 0;
        }

        // Use default brightness when dozing unless overridden.
        if ((Float.isNaN(brightnessState))
                && Display.isDozeState(state)) {
            brightnessState = clampScreenBrightness(mScreenBrightnessDozeConfig);
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_DOZE_DEFAULT);
        }

        // Apply manual brightness.
        if (Float.isNaN(brightnessState)) {
            brightnessState = clampScreenBrightness(mCurrentScreenBrightnessSetting);
            if (brightnessState != mCurrentScreenBrightnessSetting) {
                // The manually chosen screen brightness is outside of the currently allowed
                // range (i.e., high-brightness-mode), make sure we tell the rest of the system
                // by updating the setting.
                updateScreenBrightnessSetting = true;
            }
            mBrightnessReasonTemp.setReason(BrightnessReason.REASON_MANUAL);
        }

        // Now that a desired brightness has been calculated, apply brightness throttling. The
        // dimming and low power transformations that follow can only dim brightness further.
        //
        // We didn't do this earlier through brightness clamping because we need to know both
        // unthrottled (unclamped/ideal) and throttled brightness levels for subsequent operations.
        // Note throttling effectively changes the allowed brightness range, so, similarly to HBM,
        // we broadcast this change through setting.
        final float unthrottledBrightnessState = brightnessState;
        if (mBrightnessThrottler.isThrottled()) {
            mTempBrightnessEvent.setThermalMax(mBrightnessThrottler.getBrightnessCap());
            brightnessState = Math.min(brightnessState, mBrightnessThrottler.getBrightnessCap());
            mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_THROTTLED);
            if (!mAppliedThrottling) {
                // Brightness throttling is needed, so do so quickly.
                // Later, when throttling is removed, we let other mechanisms decide on speed.
                slowChange = false;
            }
            mAppliedThrottling = true;
        } else if (mAppliedThrottling) {
            mAppliedThrottling = false;
        }

        if (updateScreenBrightnessSetting) {
            // Tell the rest of the system about the new brightness in case we had to change it
            // for things like auto-brightness or high-brightness-mode. Note that we do this
            // before applying the low power or dim transformations so that the slider
            // accurately represents the full possible range, even if they range changes what
            // it means in absolute terms.
            updateScreenBrightnessSetting(brightnessState);
        }

        // Apply dimming by at least some minimum amount when user activity
        // timeout is about to expire.
        if (mPowerRequest.policy == DisplayPowerRequest.POLICY_DIM) {
            if (brightnessState > PowerManager.BRIGHTNESS_MIN) {
                brightnessState = Math.max(
                        Math.min(brightnessState - mScreenBrightnessMinimumDimAmount,
                                mScreenBrightnessDimConfig),
                        PowerManager.BRIGHTNESS_MIN);
                mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_DIMMED);
            }
            if (!mAppliedDimming) {
                slowChange = false;
            }
            mAppliedDimming = true;
        } else if (mAppliedDimming) {
            slowChange = false;
            mAppliedDimming = false;
        }
        // If low power mode is enabled, scale brightness by screenLowPowerBrightnessFactor
        // as long as it is above the minimum threshold.
        if (mPowerRequest.lowPowerMode) {
            if (brightnessState > PowerManager.BRIGHTNESS_MIN) {
                final float brightnessFactor =
                        Math.min(mPowerRequest.screenLowPowerBrightnessFactor, 1);
                final float lowPowerBrightnessFloat = (brightnessState * brightnessFactor);
                brightnessState = Math.max(lowPowerBrightnessFloat, PowerManager.BRIGHTNESS_MIN);
                mBrightnessReasonTemp.addModifier(BrightnessReason.MODIFIER_LOW_POWER);
            }
            if (!mAppliedLowPower) {
                slowChange = false;
            }
            mAppliedLowPower = true;
        } else if (mAppliedLowPower) {
            slowChange = false;
            mAppliedLowPower = false;
        }

        // The current brightness to use has been calculated at this point, and HbmController should
        // be notified so that it can accurately calculate HDR or HBM levels. We specifically do it
        // here instead of having HbmController listen to the brightness setting because certain
        // brightness sources (such as an app override) are not saved to the setting, but should be
        // reflected in HBM calculations.
        mHbmController.onBrightnessChanged(brightnessState, unthrottledBrightnessState,
                mBrightnessThrottler.getBrightnessMaxReason());

        // Animate the screen brightness when the screen is on or dozing.
        // Skip the animation when the screen is off or suspended or transition to/from VR.
        boolean brightnessAdjusted = false;
        final boolean brightnessIsTemporary =
                mAppliedTemporaryBrightness || mAppliedTemporaryAutoBrightnessAdjustment;
        if (!mPendingScreenOff) {
            if (mSkipScreenOnBrightnessRamp) {
                if (state == Display.STATE_ON) {
                    if (mSkipRampState == RAMP_STATE_SKIP_NONE && mDozing) {
                        mInitialAutoBrightness = brightnessState;
                        mSkipRampState = RAMP_STATE_SKIP_INITIAL;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_INITIAL
                            && mUseSoftwareAutoBrightnessConfig
                            && !BrightnessSynchronizer.floatEquals(brightnessState,
                            mInitialAutoBrightness)) {
                        mSkipRampState = RAMP_STATE_SKIP_AUTOBRIGHT;
                    } else if (mSkipRampState == RAMP_STATE_SKIP_AUTOBRIGHT) {
                        mSkipRampState = RAMP_STATE_SKIP_NONE;
                    }
                } else {
                    mSkipRampState = RAMP_STATE_SKIP_NONE;
                }
            }

            final boolean wasOrWillBeInVr =
                    (state == Display.STATE_VR || oldState == Display.STATE_VR);
            final boolean initialRampSkip = (state == Display.STATE_ON && mSkipRampState
                    != RAMP_STATE_SKIP_NONE) || mDisplayPowerProximityStateController
                    .shouldSkipRampBecauseOfProximityChangeToNegative();
            // While dozing, sometimes the brightness is split into buckets. Rather than animating
            // through the buckets, which is unlikely to be smooth in the first place, just jump
            // right to the suggested brightness.
            final boolean hasBrightnessBuckets =
                    Display.isDozeState(state) && mBrightnessBucketsInDozeConfig;
            // If the color fade is totally covering the screen then we can change the backlight
            // level without it being a noticeable jump since any actual content isn't yet visible.
            final boolean isDisplayContentVisible =
                    mColorFadeEnabled && mPowerState.getColorFadeLevel() == 1.0f;
            // We only want to animate the brightness if it is between 0.0f and 1.0f.
            // brightnessState can contain the values -1.0f and NaN, which we do not want to
            // animate to. To avoid this, we check the value first.
            // If the brightnessState is off (-1.0f) we still want to animate to the minimum
            // brightness (0.0f) to accommodate for LED displays, which can appear bright to the
            // user even when the display is all black. We also clamp here in case some
            // transformations to the brightness have pushed it outside of the currently
            // allowed range.
            float animateValue = clampScreenBrightness(brightnessState);

            // If there are any HDR layers on the screen, we have a special brightness value that we
            // use instead. We still preserve the calculated brightness for Standard Dynamic Range
            // (SDR) layers, but the main brightness value will be the one for HDR.
            float sdrAnimateValue = animateValue;
            // TODO(b/216365040): The decision to prevent HBM for HDR in low power mode should be
            // done in HighBrightnessModeController.
            if (mHbmController.getHighBrightnessMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR
                    && (mBrightnessReason.getModifier() & BrightnessReason.MODIFIER_DIMMED) == 0
                    && (mBrightnessReason.getModifier() & BrightnessReason.MODIFIER_LOW_POWER)
                    == 0) {
                // We want to scale HDR brightness level with the SDR level
                animateValue = mHbmController.getHdrBrightnessValue();
            }

            final float currentBrightness = mPowerState.getScreenBrightness();
            final float currentSdrBrightness = mPowerState.getSdrScreenBrightness();
            if (isValidBrightnessValue(animateValue)
                    && (animateValue != currentBrightness
                    || sdrAnimateValue != currentSdrBrightness)) {
                if (initialRampSkip || hasBrightnessBuckets
                        || wasOrWillBeInVr || !isDisplayContentVisible || brightnessIsTemporary) {
                    animateScreenBrightness(animateValue, sdrAnimateValue,
                            SCREEN_ANIMATION_RATE_MINIMUM);
                } else {
                    boolean isIncreasing = animateValue > currentBrightness;
                    final float rampSpeed;
                    if (isIncreasing && slowChange) {
                        rampSpeed = mBrightnessRampRateSlowIncrease;
                    } else if (isIncreasing && !slowChange) {
                        rampSpeed = mBrightnessRampRateFastIncrease;
                    } else if (!isIncreasing && slowChange) {
                        rampSpeed = mBrightnessRampRateSlowDecrease;
                    } else {
                        rampSpeed = mBrightnessRampRateFastDecrease;
                    }
                    animateScreenBrightness(animateValue, sdrAnimateValue, rampSpeed);
                }
            }

            // Report brightness to brightnesstracker:
            // If brightness is not temporary (ie the slider has been released)
            // AND if we are not in idle screen brightness mode.
            if (!brightnessIsTemporary
                    && (mAutomaticBrightnessController != null
                    && !mAutomaticBrightnessController.isInIdleMode())) {
                if (userInitiatedChange && (mAutomaticBrightnessController == null
                        || !mAutomaticBrightnessController.hasValidAmbientLux())) {
                    // If we don't have a valid lux reading we can't report a valid
                    // slider event so notify as if the system changed the brightness.
                    userInitiatedChange = false;
                }
                notifyBrightnessTrackerChanged(brightnessState, userInitiatedChange,
                        hadUserBrightnessPoint);
            }

            // We save the brightness info *after* the brightness setting has been changed and
            // adjustments made so that the brightness info reflects the latest value.
            brightnessAdjusted = saveBrightnessInfo(getScreenBrightnessSetting(), animateValue);
        } else {
            brightnessAdjusted = saveBrightnessInfo(getScreenBrightnessSetting());
        }

        // Only notify if the brightness adjustment is not temporary (i.e. slider has been released)
        if (brightnessAdjusted && !brightnessIsTemporary) {
            postBrightnessChangeRunnable();
        }

        // Log any changes to what is currently driving the brightness setting.
        if (!mBrightnessReasonTemp.equals(mBrightnessReason) || brightnessAdjustmentFlags != 0) {
            Slog.v(mTag, "Brightness [" + brightnessState + "] reason changing to: '"
                    + mBrightnessReasonTemp.toString(brightnessAdjustmentFlags)
                    + "', previous reason: '" + mBrightnessReason + "'.");
            mBrightnessReason.set(mBrightnessReasonTemp);
        } else if (mBrightnessReasonTemp.getReason() == BrightnessReason.REASON_MANUAL
                && userSetBrightnessChanged) {
            Slog.v(mTag, "Brightness [" + brightnessState + "] manual adjustment.");
        }


        // Log brightness events when a detail of significance has changed. Generally this is the
        // brightness itself changing, but also includes data like HBM cap, thermal throttling
        // brightness cap, RBC state, etc.
        mTempBrightnessEvent.setTime(System.currentTimeMillis());
        mTempBrightnessEvent.setBrightness(brightnessState);
        mTempBrightnessEvent.setPhysicalDisplayId(mUniqueDisplayId);
        mTempBrightnessEvent.setReason(mBrightnessReason);
        mTempBrightnessEvent.setHbmMax(mHbmController.getCurrentBrightnessMax());
        mTempBrightnessEvent.setHbmMode(mHbmController.getHighBrightnessMode());
        mTempBrightnessEvent.setFlags(mTempBrightnessEvent.getFlags()
                | (mIsRbcActive ? BrightnessEvent.FLAG_RBC : 0)
                | (mPowerRequest.lowPowerMode ? BrightnessEvent.FLAG_LOW_POWER_MODE : 0));
        mTempBrightnessEvent.setRbcStrength(mCdsi != null
                ? mCdsi.getReduceBrightColorsStrength() : -1);
        mTempBrightnessEvent.setPowerFactor(mPowerRequest.screenLowPowerBrightnessFactor);
        // Temporary is what we use during slider interactions. We avoid logging those so that
        // we don't spam logcat when the slider is being used.
        boolean tempToTempTransition =
                mTempBrightnessEvent.getReason().getReason() == BrightnessReason.REASON_TEMPORARY
                        && mLastBrightnessEvent.getReason().getReason()
                        == BrightnessReason.REASON_TEMPORARY;
        if ((!mTempBrightnessEvent.equalsMainData(mLastBrightnessEvent) && !tempToTempTransition)
                || brightnessAdjustmentFlags != 0) {
            float lastBrightness = mLastBrightnessEvent.getBrightness();
            mTempBrightnessEvent.setInitialBrightness(lastBrightness);
            mTempBrightnessEvent.setFastAmbientLux(
                    mAutomaticBrightnessController == null
                        ? -1f : mAutomaticBrightnessController.getFastAmbientLux());
            mTempBrightnessEvent.setSlowAmbientLux(
                    mAutomaticBrightnessController == null
                        ? -1f : mAutomaticBrightnessController.getSlowAmbientLux());
            mTempBrightnessEvent.setAutomaticBrightnessEnabled(mPowerRequest.useAutoBrightness);
            mLastBrightnessEvent.copyFrom(mTempBrightnessEvent);
            BrightnessEvent newEvent = new BrightnessEvent(mTempBrightnessEvent);
            // Adjustment flags (and user-set flag) only get added after the equality checks since
            // they are transient.
            newEvent.setAdjustmentFlags(brightnessAdjustmentFlags);
            newEvent.setFlags(newEvent.getFlags() | (userSetBrightnessChanged
                    ? BrightnessEvent.FLAG_USER_SET : 0));
            Slog.i(mTag, newEvent.toString(/* includeTime= */ false));

            if (userSetBrightnessChanged) {
                logManualBrightnessEvent(newEvent);
            }
            if (mBrightnessEventRingBuffer != null) {
                mBrightnessEventRingBuffer.append(newEvent);
            }
        }

        // Update display white-balance.
        if (mDisplayWhiteBalanceController != null) {
            if (state == Display.STATE_ON && mDisplayWhiteBalanceSettings.isEnabled()) {
                mDisplayWhiteBalanceController.setEnabled(true);
                mDisplayWhiteBalanceController.updateDisplayColorTemperature();
            } else {
                mDisplayWhiteBalanceController.setEnabled(false);
            }
        }

        // Determine whether the display is ready for use in the newly requested state.
        // Note that we do not wait for the brightness ramp animation to complete before
        // reporting the display is ready because we only need to ensure the screen is in the
        // right power state even as it continues to converge on the desired brightness.
        final boolean ready = mPendingScreenOnUnblocker == null
                && (!mColorFadeEnabled || (!mColorFadeOnAnimator.isStarted()
                        && !mColorFadeOffAnimator.isStarted()))
                && mPowerState.waitUntilClean(mCleanListener);
        final boolean finished = ready
                && !mScreenBrightnessRampAnimator.isAnimating();

        // Notify policy about screen turned on.
        if (ready && state != Display.STATE_OFF
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_ON) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_ON);
            mWindowManagerPolicy.screenTurnedOn(mDisplayId);
        }

        // Grab a wake lock if we have unfinished business.
        if (!finished) {
            mWakelockController.acquireWakelock(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
        }

        // Notify the power manager when ready.
        if (ready && mustNotify) {
            // Send state change.
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;

                    if (DEBUG) {
                        Slog.d(mTag, "Display ready!");
                    }
                }
            }
            sendOnStateChangedWithWakelock();
        }

        // Release the wake lock when we have no unfinished business.
        if (finished) {
            mWakelockController.releaseWakelock(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
        }

        // Record if dozing for future comparison.
        mDozing = state != Display.STATE_ON;

        if (previousPolicy != mPowerRequest.policy) {
            logDisplayPolicyChanged(mPowerRequest.policy);
        }
    }

    @Override
    public void updateBrightness() {
        sendUpdatePowerState();
    }

    /**
     * Ignores the proximity sensor until the sensor state changes, but only if the sensor is
     * currently enabled and forcing the screen to be dark.
     */
    @Override
    public void ignoreProximitySensorUntilChanged() {
        mDisplayPowerProximityStateController.ignoreProximitySensorUntilChanged();
    }

    @Override
    public void setBrightnessConfiguration(BrightnessConfiguration c) {
        Message msg = mHandler.obtainMessage(MSG_CONFIGURE_BRIGHTNESS, c);
        msg.sendToTarget();
    }

    @Override
    public void setTemporaryBrightness(float brightness) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_BRIGHTNESS,
                Float.floatToIntBits(brightness), 0 /*unused*/);
        msg.sendToTarget();
    }

    @Override
    public void setTemporaryAutoBrightnessAdjustment(float adjustment) {
        Message msg = mHandler.obtainMessage(MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT,
                Float.floatToIntBits(adjustment), 0 /*unused*/);
        msg.sendToTarget();
    }

    @Override
    public BrightnessInfo getBrightnessInfo() {
        synchronized (mCachedBrightnessInfo) {
            return new BrightnessInfo(
                    mCachedBrightnessInfo.brightness.value,
                    mCachedBrightnessInfo.adjustedBrightness.value,
                    mCachedBrightnessInfo.brightnessMin.value,
                    mCachedBrightnessInfo.brightnessMax.value,
                    mCachedBrightnessInfo.hbmMode.value,
                    mCachedBrightnessInfo.hbmTransitionPoint.value,
                    mCachedBrightnessInfo.brightnessMaxReason.value);
        }
    }

    private boolean saveBrightnessInfo(float brightness) {
        return saveBrightnessInfo(brightness, brightness);
    }

    private boolean saveBrightnessInfo(float brightness, float adjustedBrightness) {
        synchronized (mCachedBrightnessInfo) {
            final float minBrightness = Math.min(mHbmController.getCurrentBrightnessMin(),
                    mBrightnessThrottler.getBrightnessCap());
            final float maxBrightness = Math.min(mHbmController.getCurrentBrightnessMax(),
                    mBrightnessThrottler.getBrightnessCap());
            boolean changed = false;

            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightness,
                            brightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.adjustedBrightness,
                            adjustedBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightnessMin,
                            minBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.brightnessMax,
                            maxBrightness);
            changed |=
                    mCachedBrightnessInfo.checkAndSetInt(mCachedBrightnessInfo.hbmMode,
                            mHbmController.getHighBrightnessMode());
            changed |=
                    mCachedBrightnessInfo.checkAndSetFloat(mCachedBrightnessInfo.hbmTransitionPoint,
                            mHbmController.getTransitionPoint());
            changed |=
                    mCachedBrightnessInfo.checkAndSetInt(mCachedBrightnessInfo.brightnessMaxReason,
                            mBrightnessThrottler.getBrightnessMaxReason());

            return changed;
        }
    }

    void postBrightnessChangeRunnable() {
        mHandler.post(mOnBrightnessChangeRunnable);
    }

    private HighBrightnessModeController createHbmControllerLocked() {
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        final DisplayDeviceConfig ddConfig = device.getDisplayDeviceConfig();
        final IBinder displayToken =
                mLogicalDisplay.getPrimaryDisplayDeviceLocked().getDisplayTokenLocked();
        final String displayUniqueId =
                mLogicalDisplay.getPrimaryDisplayDeviceLocked().getUniqueId();
        final DisplayDeviceConfig.HighBrightnessModeData hbmData =
                ddConfig != null ? ddConfig.getHighBrightnessModeData() : null;
        final DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        return new HighBrightnessModeController(mHandler, info.width, info.height, displayToken,
                displayUniqueId, PowerManager.BRIGHTNESS_MIN, PowerManager.BRIGHTNESS_MAX, hbmData,
                new HighBrightnessModeController.HdrBrightnessDeviceConfig() {
                    @Override
                    public float getHdrBrightnessFromSdr(float sdrBrightness) {
                        return mDisplayDeviceConfig.getHdrBrightnessFromSdr(sdrBrightness);
                    }
                },
                () -> {
                    sendUpdatePowerState();
                    postBrightnessChangeRunnable();
                    // TODO(b/192258832): Switch the HBMChangeCallback to a listener pattern.
                    if (mAutomaticBrightnessController != null) {
                        mAutomaticBrightnessController.update();
                    }
                }, mContext);
    }

    private BrightnessThrottler createBrightnessThrottlerLocked() {
        final DisplayDevice device = mLogicalDisplay.getPrimaryDisplayDeviceLocked();
        final DisplayDeviceConfig ddConfig = device.getDisplayDeviceConfig();
        final DisplayDeviceConfig.BrightnessThrottlingData data =
                ddConfig != null ? ddConfig.getBrightnessThrottlingData() : null;
        return new BrightnessThrottler(mHandler, data,
                () -> {
                    sendUpdatePowerState();
                    postBrightnessChangeRunnable();
                }, mUniqueDisplayId);
    }

    private void blockScreenOn() {
        if (mPendingScreenOnUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOnUnblocker = new ScreenOnUnblocker();
            mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(mTag, "Blocking screen on until initial contents have been drawn.");
        }
    }

    private void unblockScreenOn() {
        if (mPendingScreenOnUnblocker != null) {
            mPendingScreenOnUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOnBlockStartRealTime;
            Slog.i(mTag, "Unblocked screen on after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_ON_BLOCKED_TRACE_NAME, 0);
        }
    }

    private void blockScreenOff() {
        if (mPendingScreenOffUnblocker == null) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
            mPendingScreenOffUnblocker = new ScreenOffUnblocker();
            mScreenOffBlockStartRealTime = SystemClock.elapsedRealtime();
            Slog.i(mTag, "Blocking screen off");
        }
    }

    private void unblockScreenOff() {
        if (mPendingScreenOffUnblocker != null) {
            mPendingScreenOffUnblocker = null;
            long delay = SystemClock.elapsedRealtime() - mScreenOffBlockStartRealTime;
            Slog.i(mTag, "Unblocked screen off after " + delay + " ms");
            Trace.asyncTraceEnd(Trace.TRACE_TAG_POWER, SCREEN_OFF_BLOCKED_TRACE_NAME, 0);
        }
    }

    private boolean setScreenState(int state) {
        return setScreenState(state, false /*reportOnly*/);
    }

    private boolean setScreenState(int state, boolean reportOnly) {
        final boolean isOff = (state == Display.STATE_OFF);

        if (mPowerState.getScreenState() != state
                || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED) {
            // If we are trying to turn screen off, give policy a chance to do something before we
            // actually turn the screen off.
            if (isOff && !mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
                if (mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_ON
                        || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED) {
                    setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_OFF);
                    blockScreenOff();
                    mWindowManagerPolicy.screenTurningOff(mDisplayId, mPendingScreenOffUnblocker);
                    unblockScreenOff();
                } else if (mPendingScreenOffUnblocker != null) {
                    // Abort doing the state change until screen off is unblocked.
                    return false;
                }
            }

            if (!reportOnly && mPowerState.getScreenState() != state) {
                Trace.traceCounter(Trace.TRACE_TAG_POWER, "ScreenState", state);
                // TODO(b/153319140) remove when we can get this from the above trace invocation
                SystemProperties.set("debug.tracing.screen_state", String.valueOf(state));
                mPowerState.setScreenState(state);
                // Tell battery stats about the transition.
                noteScreenState(state);
            }
        }

        // Tell the window manager policy when the screen is turned off or on unless it's due
        // to the proximity sensor.  We temporarily block turning the screen on until the
        // window manager is ready by leaving a black surface covering the screen.
        // This surface is essentially the final state of the color fade animation and
        // it is only removed once the window manager tells us that the activity has
        // finished drawing underneath.
        if (isOff && mReportedScreenStateToPolicy != REPORTED_TO_POLICY_SCREEN_OFF
                && !mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
            unblockScreenOn();
            mWindowManagerPolicy.screenTurnedOff(mDisplayId);
        } else if (!isOff
                && mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_TURNING_OFF) {

            // We told policy already that screen was turning off, but now we changed our minds.
            // Complete the full state transition on -> turningOff -> off.
            unblockScreenOff();
            mWindowManagerPolicy.screenTurnedOff(mDisplayId);
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_OFF);
        }
        if (!isOff
                && (mReportedScreenStateToPolicy == REPORTED_TO_POLICY_SCREEN_OFF
                || mReportedScreenStateToPolicy == REPORTED_TO_POLICY_UNREPORTED)) {
            setReportedScreenState(REPORTED_TO_POLICY_SCREEN_TURNING_ON);
            if (mPowerState.getColorFadeLevel() == 0.0f) {
                blockScreenOn();
            } else {
                unblockScreenOn();
            }
            mWindowManagerPolicy.screenTurningOn(mDisplayId, mPendingScreenOnUnblocker);
        }

        // Return true if the screen isn't blocked.
        return mPendingScreenOnUnblocker == null;
    }

    private void setReportedScreenState(int state) {
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "ReportedScreenStateToPolicy", state);
        mReportedScreenStateToPolicy = state;
    }

    private void loadAmbientLightSensor() {
        DisplayDeviceConfig.SensorData lightSensor = mDisplayDeviceConfig.getAmbientLightSensor();
        final int fallbackType = mDisplayId == Display.DEFAULT_DISPLAY
                ? Sensor.TYPE_LIGHT : SensorUtils.NO_FALLBACK;
        mLightSensor = SensorUtils.findSensor(mSensorManager, lightSensor.type, lightSensor.name,
                fallbackType);
    }

    private float clampScreenBrightnessForVr(float value) {
        return MathUtils.constrain(
                value, mScreenBrightnessForVrRangeMinimum,
                mScreenBrightnessForVrRangeMaximum);
    }

    private float clampScreenBrightness(float value) {
        if (Float.isNaN(value)) {
            value = PowerManager.BRIGHTNESS_MIN;
        }
        return MathUtils.constrain(value,
                mHbmController.getCurrentBrightnessMin(), mHbmController.getCurrentBrightnessMax());
    }

    // Checks whether the brightness is within the valid brightness range, not including off.
    private boolean isValidBrightnessValue(float brightness) {
        return brightness >= PowerManager.BRIGHTNESS_MIN
                && brightness <= PowerManager.BRIGHTNESS_MAX;
    }

    private void animateScreenBrightness(float target, float sdrTarget, float rate) {
        if (DEBUG) {
            Slog.d(mTag, "Animating brightness: target=" + target + ", sdrTarget=" + sdrTarget
                    + ", rate=" + rate);
        }
        if (mScreenBrightnessRampAnimator.animateTo(target, sdrTarget, rate)) {
            Trace.traceCounter(Trace.TRACE_TAG_POWER, "TargetScreenBrightness", (int) target);
            // TODO(b/153319140) remove when we can get this from the above trace invocation
            SystemProperties.set("debug.tracing.screen_brightness", String.valueOf(target));
            noteScreenBrightness(target);
        }
    }

    private void animateScreenStateChange(int target, boolean performScreenOffTransition) {
        // If there is already an animation in progress, don't interfere with it.
        if (mColorFadeEnabled
                && (mColorFadeOnAnimator.isStarted() || mColorFadeOffAnimator.isStarted())) {
            if (target != Display.STATE_ON) {
                return;
            }
            // If display state changed to on, proceed and stop the color fade and turn screen on.
            mPendingScreenOff = false;
        }

        if (mDisplayBlanksAfterDozeConfig
                && Display.isDozeState(mPowerState.getScreenState())
                && !Display.isDozeState(target)) {
            // Skip the screen off animation and add a black surface to hide the
            // contents of the screen.
            mPowerState.prepareColorFade(mContext,
                    mColorFadeFadesConfig ? ColorFade.MODE_FADE : ColorFade.MODE_WARM_UP);
            if (mColorFadeOffAnimator != null) {
                mColorFadeOffAnimator.end();
            }
            // Some display hardware will blank itself on the transition between doze and non-doze
            // but still on display states. In this case we want to report to policy that the
            // display has turned off so it can prepare the appropriate power on animation, but we
            // don't want to actually transition to the fully off state since that takes
            // significantly longer to transition from.
            setScreenState(Display.STATE_OFF, target != Display.STATE_OFF /*reportOnly*/);
        }

        // If we were in the process of turning off the screen but didn't quite
        // finish.  Then finish up now to prevent a jarring transition back
        // to screen on if we skipped blocking screen on as usual.
        if (mPendingScreenOff && target != Display.STATE_OFF) {
            setScreenState(Display.STATE_OFF);
            mPendingScreenOff = false;
            mPowerState.dismissColorFadeResources();
        }

        if (target == Display.STATE_ON) {
            // Want screen on.  The contents of the screen may not yet
            // be visible if the color fade has not been dismissed because
            // its last frame of animation is solid black.
            if (!setScreenState(Display.STATE_ON)) {
                return; // screen on blocked
            }
            if (USE_COLOR_FADE_ON_ANIMATION && mColorFadeEnabled && mPowerRequest.isBrightOrDim()) {
                // Perform screen on animation.
                if (mPowerState.getColorFadeLevel() == 1.0f) {
                    mPowerState.dismissColorFade();
                } else if (mPowerState.prepareColorFade(mContext,
                        mColorFadeFadesConfig
                                ? ColorFade.MODE_FADE : ColorFade.MODE_WARM_UP)) {
                    mColorFadeOnAnimator.start();
                } else {
                    mColorFadeOnAnimator.end();
                }
            } else {
                // Skip screen on animation.
                mPowerState.setColorFadeLevel(1.0f);
                mPowerState.dismissColorFade();
            }
        } else if (target == Display.STATE_VR) {
            // Wait for brightness animation to complete beforehand when entering VR
            // from screen on to prevent a perceptible jump because brightness may operate
            // differently when the display is configured for dozing.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() == Display.STATE_ON) {
                return;
            }

            // Set screen state.
            if (!setScreenState(Display.STATE_VR)) {
                return; // screen on blocked
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_DOZE) {
            // Want screen dozing.
            // Wait for brightness animation to complete beforehand when entering doze
            // from screen on to prevent a perceptible jump because brightness may operate
            // differently when the display is configured for dozing.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() == Display.STATE_ON) {
                return;
            }

            // Set screen state.
            if (!setScreenState(Display.STATE_DOZE)) {
                return; // screen on blocked
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_DOZE_SUSPEND) {
            // Want screen dozing and suspended.
            // Wait for brightness animation to complete beforehand unless already
            // suspended because we may not be able to change it after suspension.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                return;
            }

            // If not already suspending, temporarily set the state to doze until the
            // screen on is unblocked, then suspend.
            if (mPowerState.getScreenState() != Display.STATE_DOZE_SUSPEND) {
                if (!setScreenState(Display.STATE_DOZE)) {
                    return; // screen on blocked
                }
                setScreenState(Display.STATE_DOZE_SUSPEND); // already on so can't block
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else if (target == Display.STATE_ON_SUSPEND) {
            // Want screen full-power and suspended.
            // Wait for brightness animation to complete beforehand unless already
            // suspended because we may not be able to change it after suspension.
            if (mScreenBrightnessRampAnimator.isAnimating()
                    && mPowerState.getScreenState() != Display.STATE_ON_SUSPEND) {
                return;
            }

            // If not already suspending, temporarily set the state to on until the
            // screen on is unblocked, then suspend.
            if (mPowerState.getScreenState() != Display.STATE_ON_SUSPEND) {
                if (!setScreenState(Display.STATE_ON)) {
                    return;
                }
                setScreenState(Display.STATE_ON_SUSPEND);
            }

            // Dismiss the black surface without fanfare.
            mPowerState.setColorFadeLevel(1.0f);
            mPowerState.dismissColorFade();
        } else {
            // Want screen off.
            mPendingScreenOff = true;
            if (!mColorFadeEnabled) {
                mPowerState.setColorFadeLevel(0.0f);
            }

            if (mPowerState.getColorFadeLevel() == 0.0f) {
                // Turn the screen off.
                // A black surface is already hiding the contents of the screen.
                setScreenState(Display.STATE_OFF);
                mPendingScreenOff = false;
                mPowerState.dismissColorFadeResources();
            } else if (performScreenOffTransition
                    && mPowerState.prepareColorFade(mContext,
                    mColorFadeFadesConfig
                            ? ColorFade.MODE_FADE : ColorFade.MODE_COOL_DOWN)
                    && mPowerState.getScreenState() != Display.STATE_OFF) {
                // Perform the screen off animation.
                mColorFadeOffAnimator.start();
            } else {
                // Skip the screen off animation and add a black surface to hide the
                // contents of the screen.
                mColorFadeOffAnimator.end();
            }
        }
    }

    private final Runnable mCleanListener = this::sendUpdatePowerState;

    private void sendOnStateChangedWithWakelock() {
        boolean wakeLockAcquired = mWakelockController.acquireWakelock(
                WakelockController.WAKE_LOCK_STATE_CHANGED);
        if (wakeLockAcquired) {
            mHandler.post(mWakelockController.getOnStateChangedRunnable());
        }
    }

    private void logDisplayPolicyChanged(int newPolicy) {
        LogMaker log = new LogMaker(MetricsEvent.DISPLAY_POLICY);
        log.setType(MetricsEvent.TYPE_UPDATE);
        log.setSubtype(newPolicy);
        MetricsLogger.action(log);
    }

    private void handleSettingsChange(boolean userSwitch) {
        mPendingScreenBrightnessSetting = getScreenBrightnessSetting();
        mPendingAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        if (userSwitch) {
            // Don't treat user switches as user initiated change.
            setCurrentScreenBrightness(mPendingScreenBrightnessSetting);
            updateAutoBrightnessAdjustment();
            if (mAutomaticBrightnessController != null) {
                mAutomaticBrightnessController.resetShortTermModel();
            }
        }
        // We don't bother with a pending variable for VR screen brightness since we just
        // immediately adapt to it.
        mScreenBrightnessForVr = getScreenBrightnessForVrSetting();
        sendUpdatePowerState();
    }

    private float getAutoBrightnessAdjustmentSetting() {
        final float adj = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f, UserHandle.USER_CURRENT);
        return Float.isNaN(adj) ? 0.0f : clampAutoBrightnessAdjustment(adj);
    }

    @Override
    public float getScreenBrightnessSetting() {
        float brightness = mBrightnessSetting.getBrightness();
        if (Float.isNaN(brightness)) {
            brightness = mScreenBrightnessDefault;
        }
        return clampAbsoluteBrightness(brightness);
    }

    private float getScreenBrightnessForVrSetting() {
        final float brightnessFloat = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT, mScreenBrightnessForVrDefault,
                UserHandle.USER_CURRENT);
        return clampScreenBrightnessForVr(brightnessFloat);
    }

    @Override
    public void setBrightness(float brightnessValue) {
        // Update the setting, which will eventually call back into DPC to have us actually update
        // the display with the new value.
        mBrightnessSetting.setBrightness(brightnessValue);
    }

    private void updateScreenBrightnessSetting(float brightnessValue) {
        if (!isValidBrightnessValue(brightnessValue)
                || brightnessValue == mCurrentScreenBrightnessSetting) {
            return;
        }
        setCurrentScreenBrightness(brightnessValue);
        mBrightnessSetting.setBrightness(brightnessValue);
    }

    private void setCurrentScreenBrightness(float brightnessValue) {
        if (brightnessValue != mCurrentScreenBrightnessSetting) {
            mCurrentScreenBrightnessSetting = brightnessValue;
            postBrightnessChangeRunnable();
        }
    }

    private void putAutoBrightnessAdjustmentSetting(float adjustment) {
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mAutoBrightnessAdjustment = adjustment;
            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, adjustment,
                    UserHandle.USER_CURRENT);
        }
    }

    private boolean updateAutoBrightnessAdjustment() {
        if (Float.isNaN(mPendingAutoBrightnessAdjustment)) {
            return false;
        }
        if (mAutoBrightnessAdjustment == mPendingAutoBrightnessAdjustment) {
            mPendingAutoBrightnessAdjustment = Float.NaN;
            return false;
        }
        mAutoBrightnessAdjustment = mPendingAutoBrightnessAdjustment;
        mPendingAutoBrightnessAdjustment = Float.NaN;
        mTemporaryAutoBrightnessAdjustment = Float.NaN;
        return true;
    }

    // We want to return true if the user has set the screen brightness.
    // RBC on, off, and intensity changes will return false.
    // Slider interactions whilst in RBC will return true, just as when in non-rbc.
    private boolean updateUserSetScreenBrightness() {
        if ((Float.isNaN(mPendingScreenBrightnessSetting)
                || mPendingScreenBrightnessSetting < 0.0f)) {
            return false;
        }
        if (mCurrentScreenBrightnessSetting == mPendingScreenBrightnessSetting) {
            mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            return false;
        }
        setCurrentScreenBrightness(mPendingScreenBrightnessSetting);
        mLastUserSetScreenBrightness = mPendingScreenBrightnessSetting;
        mPendingScreenBrightnessSetting = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mTemporaryScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        return true;
    }

    private void notifyBrightnessTrackerChanged(float brightness, boolean userInitiated,
            boolean hadUserDataPoint) {
        final float brightnessInNits = convertToNits(brightness);
        if (mPowerRequest.useAutoBrightness && brightnessInNits >= 0.0f
                && mAutomaticBrightnessController != null && mBrightnessTracker != null) {
            // We only want to track changes on devices that can actually map the display backlight
            // values into a physical brightness unit since the value provided by the API is in
            // nits and not using the arbitrary backlight units.
            final float powerFactor = mPowerRequest.lowPowerMode
                    ? mPowerRequest.screenLowPowerBrightnessFactor
                    : 1.0f;
            mBrightnessTracker.notifyBrightnessChanged(brightnessInNits, userInitiated,
                    powerFactor, hadUserDataPoint,
                    mAutomaticBrightnessController.isDefaultConfig(), mUniqueDisplayId);
        }
    }

    private float convertToNits(float brightness) {
        if (mAutomaticBrightnessController == null) {
            return -1f;
        }
        return mAutomaticBrightnessController.convertToNits(brightness);
    }

    @Override
    public void dump(final PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Display Power Controller:");
            pw.println("  mDisplayId=" + mDisplayId);
            pw.println("  mLightSensor=" + mLightSensor);

            pw.println();
            pw.println("Display Power Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + mPendingRequestChangedLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + mPendingUpdatePowerStateLocked);
        }

        pw.println();
        pw.println("Display Power Controller Configuration:");
        pw.println("  mScreenBrightnessRangeDefault=" + mScreenBrightnessDefault);
        pw.println("  mScreenBrightnessDozeConfig=" + mScreenBrightnessDozeConfig);
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mScreenBrightnessForVrRangeMinimum=" + mScreenBrightnessForVrRangeMinimum);
        pw.println("  mScreenBrightnessForVrRangeMaximum=" + mScreenBrightnessForVrRangeMaximum);
        pw.println("  mScreenBrightnessForVrDefault=" + mScreenBrightnessForVrDefault);
        pw.println("  mUseSoftwareAutoBrightnessConfig=" + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mAllowAutoBrightnessWhileDozingConfig="
                + mAllowAutoBrightnessWhileDozingConfig);
        pw.println("  mSkipScreenOnBrightnessRamp=" + mSkipScreenOnBrightnessRamp);
        pw.println("  mColorFadeFadesConfig=" + mColorFadeFadesConfig);
        pw.println("  mColorFadeEnabled=" + mColorFadeEnabled);
        synchronized (mCachedBrightnessInfo) {
            pw.println("  mCachedBrightnessInfo.brightness="
                    + mCachedBrightnessInfo.brightness.value);
            pw.println("  mCachedBrightnessInfo.adjustedBrightness="
                    + mCachedBrightnessInfo.adjustedBrightness.value);
            pw.println("  mCachedBrightnessInfo.brightnessMin="
                    + mCachedBrightnessInfo.brightnessMin.value);
            pw.println("  mCachedBrightnessInfo.brightnessMax="
                    + mCachedBrightnessInfo.brightnessMax.value);
            pw.println("  mCachedBrightnessInfo.hbmMode=" + mCachedBrightnessInfo.hbmMode.value);
            pw.println("  mCachedBrightnessInfo.hbmTransitionPoint="
                    + mCachedBrightnessInfo.hbmTransitionPoint.value);
            pw.println("  mCachedBrightnessInfo.brightnessMaxReason ="
                    + mCachedBrightnessInfo.brightnessMaxReason.value);
        }
        pw.println("  mDisplayBlanksAfterDozeConfig=" + mDisplayBlanksAfterDozeConfig);
        pw.println("  mBrightnessBucketsInDozeConfig=" + mBrightnessBucketsInDozeConfig);
        mHandler.runWithScissors(() -> dumpLocal(pw), 1000);
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Power Controller Thread State:");
        pw.println("  mPowerRequest=" + mPowerRequest);
        pw.println("  mLastUserSetScreenBrightness=" + mLastUserSetScreenBrightness);
        pw.println("  mPendingScreenBrightnessSetting="
                + mPendingScreenBrightnessSetting);
        pw.println("  mTemporaryScreenBrightness=" + mTemporaryScreenBrightness);
        pw.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
        pw.println("  mBrightnessReason=" + mBrightnessReason);
        pw.println("  mTemporaryAutoBrightnessAdjustment=" + mTemporaryAutoBrightnessAdjustment);
        pw.println("  mPendingAutoBrightnessAdjustment=" + mPendingAutoBrightnessAdjustment);
        pw.println("  mScreenBrightnessForVrFloat=" + mScreenBrightnessForVr);
        pw.println("  mAppliedAutoBrightness=" + mAppliedAutoBrightness);
        pw.println("  mAppliedDimming=" + mAppliedDimming);
        pw.println("  mAppliedLowPower=" + mAppliedLowPower);
        pw.println("  mAppliedThrottling=" + mAppliedThrottling);
        pw.println("  mAppliedScreenBrightnessOverride=" + mAppliedScreenBrightnessOverride);
        pw.println("  mAppliedTemporaryBrightness=" + mAppliedTemporaryBrightness);
        pw.println("  mAppliedTemporaryAutoBrightnessAdjustment="
                + mAppliedTemporaryAutoBrightnessAdjustment);
        pw.println("  mAppliedBrightnessBoost=" + mAppliedBrightnessBoost);
        pw.println("  mDozing=" + mDozing);
        pw.println("  mSkipRampState=" + skipRampStateToString(mSkipRampState));
        pw.println("  mScreenOnBlockStartRealTime=" + mScreenOnBlockStartRealTime);
        pw.println("  mScreenOffBlockStartRealTime=" + mScreenOffBlockStartRealTime);
        pw.println("  mPendingScreenOnUnblocker=" + mPendingScreenOnUnblocker);
        pw.println("  mPendingScreenOffUnblocker=" + mPendingScreenOffUnblocker);
        pw.println("  mPendingScreenOff=" + mPendingScreenOff);
        pw.println("  mReportedToPolicy="
                + reportedToPolicyToString(mReportedScreenStateToPolicy));
        pw.println("  mIsRbcActive=" + mIsRbcActive);

        if (mScreenBrightnessRampAnimator != null) {
            pw.println("  mScreenBrightnessRampAnimator.isAnimating()="
                    + mScreenBrightnessRampAnimator.isAnimating());
        }

        if (mColorFadeOnAnimator != null) {
            pw.println("  mColorFadeOnAnimator.isStarted()="
                    + mColorFadeOnAnimator.isStarted());
        }
        if (mColorFadeOffAnimator != null) {
            pw.println("  mColorFadeOffAnimator.isStarted()="
                    + mColorFadeOffAnimator.isStarted());
        }

        if (mPowerState != null) {
            mPowerState.dump(pw);
        }

        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.dump(pw);
            dumpBrightnessEvents(pw);
        }

        if (mHbmController != null) {
            mHbmController.dump(pw);
        }

        if (mBrightnessThrottler != null) {
            mBrightnessThrottler.dump(pw);
        }

        pw.println();
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.dump(pw);
            mDisplayWhiteBalanceSettings.dump(pw);
        }

        pw.println();

        if (mWakelockController != null) {
            mWakelockController.dumpLocal(pw);
        }

        if (mDisplayPowerProximityStateController != null) {
            mDisplayPowerProximityStateController.dumpLocal(pw);
        }
    }


    private static String reportedToPolicyToString(int state) {
        switch (state) {
            case REPORTED_TO_POLICY_SCREEN_OFF:
                return "REPORTED_TO_POLICY_SCREEN_OFF";
            case REPORTED_TO_POLICY_SCREEN_TURNING_ON:
                return "REPORTED_TO_POLICY_SCREEN_TURNING_ON";
            case REPORTED_TO_POLICY_SCREEN_ON:
                return "REPORTED_TO_POLICY_SCREEN_ON";
            default:
                return Integer.toString(state);
        }
    }

    private static String skipRampStateToString(int state) {
        switch (state) {
            case RAMP_STATE_SKIP_NONE:
                return "RAMP_STATE_SKIP_NONE";
            case RAMP_STATE_SKIP_INITIAL:
                return "RAMP_STATE_SKIP_INITIAL";
            case RAMP_STATE_SKIP_AUTOBRIGHT:
                return "RAMP_STATE_SKIP_AUTOBRIGHT";
            default:
                return Integer.toString(state);
        }
    }

    private void dumpBrightnessEvents(PrintWriter pw) {
        int size = mBrightnessEventRingBuffer.size();
        if (size < 1) {
            pw.println("No Automatic Brightness Adjustments");
            return;
        }

        pw.println("Automatic Brightness Adjustments Last " + size + " Events: ");
        BrightnessEvent[] eventArray = mBrightnessEventRingBuffer.toArray();
        for (int i = 0; i < mBrightnessEventRingBuffer.size(); i++) {
            pw.println("  " + eventArray[i].toString());
        }
    }

    private static float clampAbsoluteBrightness(float value) {
        return MathUtils.constrain(value, PowerManager.BRIGHTNESS_MIN,
                PowerManager.BRIGHTNESS_MAX);
    }

    private static float clampAutoBrightnessAdjustment(float value) {
        return MathUtils.constrain(value, -1.0f, 1.0f);
    }

    private void noteScreenState(int screenState) {
        if (mBatteryStats != null) {
            try {
                // TODO(multi-display): make this multi-display
                mBatteryStats.noteScreenState(screenState);
            } catch (RemoteException e) {
                // same process
            }
        }
    }

    private void noteScreenBrightness(float brightness) {
        if (mBatteryStats != null) {
            try {
                // TODO(brightnessfloat): change BatteryStats to use float
                mBatteryStats.noteScreenBrightness(BrightnessSynchronizer.brightnessFloatToInt(
                        brightness));
            } catch (RemoteException e) {
                // same process
            }
        }
    }

    private void reportStats(float brightness) {
        if (mLastStatsBrightness == brightness) {
            return;
        }

        float hbmTransitionPoint = PowerManager.BRIGHTNESS_MAX;
        synchronized (mCachedBrightnessInfo) {
            if (mCachedBrightnessInfo.hbmTransitionPoint == null) {
                return;
            }
            hbmTransitionPoint = mCachedBrightnessInfo.hbmTransitionPoint.value;
        }

        final boolean aboveTransition = brightness > hbmTransitionPoint;
        final boolean oldAboveTransition = mLastStatsBrightness > hbmTransitionPoint;

        if (aboveTransition || oldAboveTransition) {
            mLastStatsBrightness = brightness;
            mHandler.removeMessages(MSG_STATSD_HBM_BRIGHTNESS);
            if (aboveTransition != oldAboveTransition) {
                // report immediately
                logHbmBrightnessStats(brightness, mDisplayStatsId);
            } else {
                // delay for rate limiting
                Message msg = mHandler.obtainMessage();
                msg.what = MSG_STATSD_HBM_BRIGHTNESS;
                msg.arg1 = Float.floatToIntBits(brightness);
                msg.arg2 = mDisplayStatsId;
                mHandler.sendMessageDelayed(msg, BRIGHTNESS_CHANGE_STATSD_REPORT_INTERVAL_MS);
            }
        }
    }

    private void logHbmBrightnessStats(float brightness, int displayStatsId) {
        synchronized (mHandler) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.DISPLAY_HBM_BRIGHTNESS_CHANGED, displayStatsId, brightness);
        }
    }

    private void logManualBrightnessEvent(BrightnessEvent event) {
        float appliedLowPowerMode = event.isLowPowerModeSet() ? event.getPowerFactor() : -1f;
        int appliedRbcStrength  = event.isRbcEnabled() ? event.getRbcStrength() : -1;
        float appliedHbmMaxNits =
                event.getHbmMode() == BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF
                ? -1f : convertToNits(event.getHbmMax());
        // thermalCapNits set to -1 if not currently capping max brightness
        float appliedThermalCapNits =
                event.getThermalMax() == PowerManager.BRIGHTNESS_MAX
                ? -1f : convertToNits(event.getThermalMax());

        FrameworkStatsLog.write(FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED,
                convertToNits(event.getInitialBrightness()),
                convertToNits(event.getBrightness()),
                event.getSlowAmbientLux(),
                event.getPhysicalDisplayId(),
                event.isShortTermModelActive(),
                appliedLowPowerMode,
                appliedRbcStrength,
                appliedHbmMaxNits,
                appliedThermalCapNits,
                event.isAutomaticBrightnessEnabled(),
                FrameworkStatsLog.DISPLAY_BRIGHTNESS_CHANGED__REASON__REASON_MANUAL);
    }

    private final class DisplayControllerHandler extends Handler {
        DisplayControllerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POWER_STATE:
                    updatePowerState();
                    break;

                case MSG_SCREEN_ON_UNBLOCKED:
                    if (mPendingScreenOnUnblocker == msg.obj) {
                        unblockScreenOn();
                        updatePowerState();
                    }
                    break;
                case MSG_SCREEN_OFF_UNBLOCKED:
                    if (mPendingScreenOffUnblocker == msg.obj) {
                        unblockScreenOff();
                        updatePowerState();
                    }
                    break;
                case MSG_CONFIGURE_BRIGHTNESS:
                    mBrightnessConfiguration = (BrightnessConfiguration) msg.obj;
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_BRIGHTNESS:
                    // TODO: Should we have a a timeout for the temporary brightness?
                    mTemporaryScreenBrightness = Float.intBitsToFloat(msg.arg1);
                    updatePowerState();
                    break;

                case MSG_SET_TEMPORARY_AUTO_BRIGHTNESS_ADJUSTMENT:
                    mTemporaryAutoBrightnessAdjustment = Float.intBitsToFloat(msg.arg1);
                    updatePowerState();
                    break;

                case MSG_STOP:
                    cleanupHandlerThreadAfterStop();
                    break;

                case MSG_UPDATE_BRIGHTNESS:
                    if (mStopped) {
                        return;
                    }
                    handleSettingsChange(false /*userSwitch*/);
                    break;

                case MSG_UPDATE_RBC:
                    handleRbcChanged();
                    break;

                case MSG_BRIGHTNESS_RAMP_DONE:
                    if (mPowerState != null) {
                        final float brightness = mPowerState.getScreenBrightness();
                        reportStats(brightness);
                    }
                    break;

                case MSG_STATSD_HBM_BRIGHTNESS:
                    logHbmBrightnessStats(Float.intBitsToFloat(msg.arg1), msg.arg2);
                    break;
            }
        }
    }


    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            handleSettingsChange(false /* userSwitch */);
        }
    }

    private final class ScreenOnUnblocker implements WindowManagerPolicy.ScreenOnListener {
        @Override
        public void onScreenOn() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_ON_UNBLOCKED, this);
            mHandler.sendMessage(msg);
        }
    }

    private final class ScreenOffUnblocker implements WindowManagerPolicy.ScreenOffListener {
        @Override
        public void onScreenOff() {
            Message msg = mHandler.obtainMessage(MSG_SCREEN_OFF_UNBLOCKED, this);
            mHandler.sendMessage(msg);
        }
    }

    @Override
    public void setAutoBrightnessLoggingEnabled(boolean enabled) {
        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.setLoggingEnabled(enabled);
        }
    }

    @Override // DisplayWhiteBalanceController.Callbacks
    public void updateWhiteBalance() {
        sendUpdatePowerState();
    }

    @Override
    public void setDisplayWhiteBalanceLoggingEnabled(boolean enabled) {
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setLoggingEnabled(enabled);
            mDisplayWhiteBalanceSettings.setLoggingEnabled(enabled);
        }
    }

    @Override
    public void setAmbientColorTemperatureOverride(float cct) {
        if (mDisplayWhiteBalanceController != null) {
            mDisplayWhiteBalanceController.setAmbientColorTemperatureOverride(cct);
            // The ambient color temperature override is only applied when the ambient color
            // temperature changes or is updated, so it doesn't necessarily change the screen color
            // temperature immediately. So, let's make it!
            sendUpdatePowerState();
        }
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    @VisibleForTesting
    static class Injector {
        Clock getClock() {
            return SystemClock::uptimeMillis;
        }

        DisplayPowerState getDisplayPowerState(DisplayBlanker blanker, ColorFade colorFade,
                int displayId, int displayState) {
            return new DisplayPowerState(blanker, colorFade, displayId, displayState);
        }

        DualRampAnimator<DisplayPowerState> getDualRampAnimator(DisplayPowerState dps,
                FloatProperty<DisplayPowerState> firstProperty,
                FloatProperty<DisplayPowerState> secondProperty) {
            return new DualRampAnimator(dps, firstProperty, secondProperty);
        }

        WakelockController getWakelockController(int displayId,
                DisplayPowerCallbacks displayPowerCallbacks) {
            return new WakelockController(displayId, displayPowerCallbacks);
        }

        DisplayPowerProximityStateController getDisplayPowerProximityStateController(
                WakelockController wakelockController, DisplayDeviceConfig displayDeviceConfig,
                Looper looper, Runnable nudgeUpdatePowerState,
                int displayId, SensorManager sensorManager) {
            return new DisplayPowerProximityStateController(wakelockController, displayDeviceConfig,
                    looper, nudgeUpdatePowerState,
                    displayId, sensorManager);
        }
    }

    static class CachedBrightnessInfo {
        public MutableFloat brightness = new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat adjustedBrightness =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat brightnessMin =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableFloat brightnessMax =
                new MutableFloat(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        public MutableInt hbmMode = new MutableInt(BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);
        public MutableFloat hbmTransitionPoint =
                new MutableFloat(HighBrightnessModeController.HBM_TRANSITION_POINT_INVALID);
        public MutableInt brightnessMaxReason =
                new MutableInt(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE);

        public boolean checkAndSetFloat(MutableFloat mf, float f) {
            if (mf.value != f) {
                mf.value = f;
                return true;
            }
            return false;
        }

        public boolean checkAndSetInt(MutableInt mi, int i) {
            if (mi.value != i) {
                mi.value = i;
                return true;
            }
            return false;
        }
    }
}
