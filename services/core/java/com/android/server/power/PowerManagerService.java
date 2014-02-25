/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.power;

import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IBatteryStats;
import com.android.server.BatteryService;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.twilight.TwilightManager;
import com.android.server.Watchdog;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.hardware.SystemSensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.SystemService;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.WindowManagerPolicy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import libcore.util.Objects;

/**
 * The power manager service is responsible for coordinating power management
 * functions on the device.
 */
public final class PowerManagerService extends com.android.server.SystemService
        implements Watchdog.Monitor {
    private static final String TAG = "PowerManagerService";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SPEW = DEBUG && true;

    // Message: Sent when a user activity timeout occurs to update the power state.
    private static final int MSG_USER_ACTIVITY_TIMEOUT = 1;
    // Message: Sent when the device enters or exits a dreaming or dozing state.
    private static final int MSG_SANDMAN = 2;
    // Message: Sent when the screen on blocker is released.
    private static final int MSG_SCREEN_ON_BLOCKER_RELEASED = 3;
    // Message: Sent to poll whether the boot animation has terminated.
    private static final int MSG_CHECK_IF_BOOT_ANIMATION_FINISHED = 4;

    // Dirty bit: mWakeLocks changed
    private static final int DIRTY_WAKE_LOCKS = 1 << 0;
    // Dirty bit: mWakefulness changed
    private static final int DIRTY_WAKEFULNESS = 1 << 1;
    // Dirty bit: user activity was poked or may have timed out
    private static final int DIRTY_USER_ACTIVITY = 1 << 2;
    // Dirty bit: actual display power state was updated asynchronously
    private static final int DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED = 1 << 3;
    // Dirty bit: mBootCompleted changed
    private static final int DIRTY_BOOT_COMPLETED = 1 << 4;
    // Dirty bit: settings changed
    private static final int DIRTY_SETTINGS = 1 << 5;
    // Dirty bit: mIsPowered changed
    private static final int DIRTY_IS_POWERED = 1 << 6;
    // Dirty bit: mStayOn changed
    private static final int DIRTY_STAY_ON = 1 << 7;
    // Dirty bit: battery state changed
    private static final int DIRTY_BATTERY_STATE = 1 << 8;
    // Dirty bit: proximity state changed
    private static final int DIRTY_PROXIMITY_POSITIVE = 1 << 9;
    // Dirty bit: screen on blocker state became held or unheld
    private static final int DIRTY_SCREEN_ON_BLOCKER_RELEASED = 1 << 10;
    // Dirty bit: dock state changed
    private static final int DIRTY_DOCK_STATE = 1 << 11;

    // Wakefulness: The device is asleep and can only be awoken by a call to wakeUp().
    // The screen should be off or in the process of being turned off by the display controller.
    // The device typically passes through the dozing state first.
    private static final int WAKEFULNESS_ASLEEP = 0;
    // Wakefulness: The device is fully awake.  It can be put to sleep by a call to goToSleep().
    // When the user activity timeout expires, the device may start dreaming or go to sleep.
    private static final int WAKEFULNESS_AWAKE = 1;
    // Wakefulness: The device is dreaming.  It can be awoken by a call to wakeUp(),
    // which ends the dream.  The device goes to sleep when goToSleep() is called, when
    // the dream ends or when unplugged.
    // User activity may brighten the screen but does not end the dream.
    private static final int WAKEFULNESS_DREAMING = 2;
    // Wakefulness: The device is dozing.  It is almost asleep but is allowing a special
    // low-power "doze" dream to run which keeps the display on but lets the application
    // processor be suspended.  It can be awoken by a call to wakeUp() which ends the dream.
    // The device fully goes to sleep if the dream cannot be started or ends on its own.
    private static final int WAKEFULNESS_DOZING = 3;

    // Summarizes the state of all active wakelocks.
    private static final int WAKE_LOCK_CPU = 1 << 0;
    private static final int WAKE_LOCK_SCREEN_BRIGHT = 1 << 1;
    private static final int WAKE_LOCK_SCREEN_DIM = 1 << 2;
    private static final int WAKE_LOCK_BUTTON_BRIGHT = 1 << 3;
    private static final int WAKE_LOCK_PROXIMITY_SCREEN_OFF = 1 << 4;
    private static final int WAKE_LOCK_STAY_AWAKE = 1 << 5; // only set if already awake
    private static final int WAKE_LOCK_DOZE = 1 << 6;

    // Summarizes the user activity state.
    private static final int USER_ACTIVITY_SCREEN_BRIGHT = 1 << 0;
    private static final int USER_ACTIVITY_SCREEN_DIM = 1 << 1;

    // Default and minimum screen off timeout in milliseconds.
    private static final int DEFAULT_SCREEN_OFF_TIMEOUT = 15 * 1000;
    private static final int MINIMUM_SCREEN_OFF_TIMEOUT = 10 * 1000;

    // The screen dim duration, in milliseconds.
    // This is subtracted from the end of the screen off timeout so the
    // minimum screen off timeout should be longer than this.
    private static final int SCREEN_DIM_DURATION = 7 * 1000;

    // The maximum screen dim time expressed as a ratio relative to the screen
    // off timeout.  If the screen off timeout is very short then we want the
    // dim timeout to also be quite short so that most of the time is spent on.
    // Otherwise the user won't get much screen on time before dimming occurs.
    private static final float MAXIMUM_SCREEN_DIM_RATIO = 0.2f;

    // The name of the boot animation service in init.rc.
    private static final String BOOT_ANIMATION_SERVICE = "bootanim";

    // Poll interval in milliseconds for watching boot animation finished.
    private static final int BOOT_ANIMATION_POLL_INTERVAL = 200;

    private final Context mContext;
    private LightsManager mLightsManager;
    private BatteryService mBatteryService;
    private DisplayManagerInternal mDisplayManagerInternal;
    private IBatteryStats mBatteryStats;
    private IAppOpsService mAppOps;
    private ServiceThread mHandlerThread;
    private PowerManagerHandler mHandler;
    private WindowManagerPolicy mPolicy;
    private Notifier mNotifier;
    private DisplayPowerController mDisplayPowerController;
    private WirelessChargerDetector mWirelessChargerDetector;
    private SettingsObserver mSettingsObserver;
    private DreamManagerInternal mDreamManager;
    private Light mAttentionLight;

    private final Object mLock = new Object();

    // A bitfield that indicates what parts of the power state have
    // changed and need to be recalculated.
    private int mDirty;

    // Indicates whether the device is awake or asleep or somewhere in between.
    // This is distinct from the screen power state, which is managed separately.
    private int mWakefulness;

    // True if the sandman has just been summoned for the first time since entering the
    // dreaming or dozing state.  Indicates whether a new dream should begin.
    private boolean mSandmanSummoned;

    // True if MSG_SANDMAN has been scheduled.
    private boolean mSandmanScheduled;

    // Table of all suspend blockers.
    // There should only be a few of these.
    private final ArrayList<SuspendBlocker> mSuspendBlockers = new ArrayList<SuspendBlocker>();

    // Table of all wake locks acquired by applications.
    private final ArrayList<WakeLock> mWakeLocks = new ArrayList<WakeLock>();

    // A bitfield that summarizes the state of all active wakelocks.
    private int mWakeLockSummary;

    // If true, instructs the display controller to wait for the proximity sensor to
    // go negative before turning the screen on.
    private boolean mRequestWaitForNegativeProximity;

    // Timestamp of the last time the device was awoken or put to sleep.
    private long mLastWakeTime;
    private long mLastSleepTime;

    // True if we need to send a wake up or go to sleep finished notification
    // when the display is ready.
    private boolean mSendWakeUpFinishedNotificationWhenReady;
    private boolean mSendGoToSleepFinishedNotificationWhenReady;

    // Timestamp of the last call to user activity.
    private long mLastUserActivityTime;
    private long mLastUserActivityTimeNoChangeLights;

    // A bitfield that summarizes the effect of the user activity timer.
    // A zero value indicates that the user activity timer has expired.
    private int mUserActivitySummary;

    // The desired display power state.  The actual state may lag behind the
    // requested because it is updated asynchronously by the display power controller.
    private final DisplayPowerRequest mDisplayPowerRequest = new DisplayPowerRequest();

    // True if the display power state has been fully applied, which means the display
    // is actually on or actually off or whatever was requested.
    private boolean mDisplayReady;

    // The suspend blocker used to keep the CPU alive when an application has acquired
    // a wake lock.
    private final SuspendBlocker mWakeLockSuspendBlocker;

    // True if the wake lock suspend blocker has been acquired.
    private boolean mHoldingWakeLockSuspendBlocker;

    // The suspend blocker used to keep the CPU alive when the display is on, the
    // display is getting ready or there is user activity (in which case the display
    // must be on).
    private final SuspendBlocker mDisplaySuspendBlocker;

    // True if the display suspend blocker has been acquired.
    private boolean mHoldingDisplaySuspendBlocker;

    // The screen on blocker used to keep the screen from turning on while the lock
    // screen is coming up.
    private final ScreenOnBlockerImpl mScreenOnBlocker;

    // The display blanker used to turn the screen on or off.
    private final DisplayBlankerImpl mDisplayBlanker;

    // True if systemReady() has been called.
    private boolean mSystemReady;

    // True if boot completed occurred.  We keep the screen on until this happens.
    private boolean mBootCompleted;

    // True if auto-suspend mode is enabled.
    // Refer to autosuspend.h.
    private boolean mAutoSuspendModeEnabled;

    // True if interactive mode is enabled.
    // Refer to power.h.
    private boolean mInteractiveModeEnabled;

    // True if the device is plugged into a power source.
    private boolean mIsPowered;

    // The current plug type, such as BatteryManager.BATTERY_PLUGGED_WIRELESS.
    private int mPlugType;

    // The current battery level percentage.
    private int mBatteryLevel;

    // The battery level percentage at the time the dream started.
    // This is used to terminate a dream and go to sleep if the battery is
    // draining faster than it is charging and the user activity timeout has expired.
    private int mBatteryLevelWhenDreamStarted;

    // The current dock state.
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    // True to decouple auto-suspend mode from the display state.
    private boolean mDecoupleAutoSuspendModeFromDisplayConfig;

    // True to decouple interactive mode from the display state.
    private boolean mDecoupleInteractiveModeFromDisplayConfig;

    // True if the device should wake up when plugged or unplugged.
    private boolean mWakeUpWhenPluggedOrUnpluggedConfig;

    // True if the device should suspend when the screen is off due to proximity.
    private boolean mSuspendWhenScreenOffDueToProximityConfig;

    // True if dreams are supported on this device.
    private boolean mDreamsSupportedConfig;

    // Default value for dreams enabled
    private boolean mDreamsEnabledByDefaultConfig;

    // Default value for dreams activate-on-sleep
    private boolean mDreamsActivatedOnSleepByDefaultConfig;

    // Default value for dreams activate-on-dock
    private boolean mDreamsActivatedOnDockByDefaultConfig;

    // True if dreams can run while not plugged in.
    private boolean mDreamsEnabledOnBatteryConfig;

    // Minimum battery level to allow dreaming when powered.
    // Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelMinimumWhenPoweredConfig;

    // Minimum battery level to allow dreaming when not powered.
    // Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelMinimumWhenNotPoweredConfig;

    // If the battery level drops by this percentage and the user activity timeout
    // has expired, then assume the device is receiving insufficient current to charge
    // effectively and terminate the dream.  Use -1 to disable this safety feature.
    private int mDreamsBatteryLevelDrainCutoffConfig;

    // True if dreams are enabled by the user.
    private boolean mDreamsEnabledSetting;

    // True if dreams should be activated on sleep.
    private boolean mDreamsActivateOnSleepSetting;

    // True if dreams should be activated on dock.
    private boolean mDreamsActivateOnDockSetting;

    // The screen off timeout setting value in milliseconds.
    private int mScreenOffTimeoutSetting;

    // The maximum allowable screen off timeout according to the device
    // administration policy.  Overrides other settings.
    private int mMaximumScreenOffTimeoutFromDeviceAdmin = Integer.MAX_VALUE;

    // The stay on while plugged in setting.
    // A bitfield of battery conditions under which to make the screen stay on.
    private int mStayOnWhilePluggedInSetting;

    // True if the device should stay on.
    private boolean mStayOn;

    // True if the proximity sensor reads a positive result.
    private boolean mProximityPositive;

    // Screen brightness setting limits.
    private int mScreenBrightnessSettingMinimum;
    private int mScreenBrightnessSettingMaximum;
    private int mScreenBrightnessSettingDefault;

    // The screen brightness setting, from 0 to 255.
    // Use -1 if no value has been set.
    private int mScreenBrightnessSetting;

    // The screen auto-brightness adjustment setting, from -1 to 1.
    // Use 0 if there is no adjustment.
    private float mScreenAutoBrightnessAdjustmentSetting;

    // The screen brightness mode.
    // One of the Settings.System.SCREEN_BRIGHTNESS_MODE_* constants.
    private int mScreenBrightnessModeSetting;

    // The screen brightness setting override from the window manager
    // to allow the current foreground activity to override the brightness.
    // Use -1 to disable.
    private int mScreenBrightnessOverrideFromWindowManager = -1;

    // The user activity timeout override from the window manager
    // to allow the current foreground activity to override the user activity timeout.
    // Use -1 to disable.
    private long mUserActivityTimeoutOverrideFromWindowManager = -1;

    // The screen brightness setting override from the settings application
    // to temporarily adjust the brightness until next updated,
    // Use -1 to disable.
    private int mTemporaryScreenBrightnessSettingOverride = -1;

    // The screen brightness adjustment setting override from the settings
    // application to temporarily adjust the auto-brightness adjustment factor
    // until next updated, in the range -1..1.
    // Use NaN to disable.
    private float mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;

    // Time when we last logged a warning about calling userActivity() without permission.
    private long mLastWarningAboutUserActivityPermission = Long.MIN_VALUE;

    private native void nativeInit();

    private static native void nativeSetPowerState(boolean screenOn, boolean screenBright);
    private static native void nativeAcquireSuspendBlocker(String name);
    private static native void nativeReleaseSuspendBlocker(String name);
    private static native void nativeSetInteractive(boolean enable);
    private static native void nativeSetAutoSuspend(boolean enable);

    public PowerManagerService(Context context) {
        super(context);
        mContext = context;
        synchronized (mLock) {
            mWakeLockSuspendBlocker = createSuspendBlockerLocked("PowerManagerService.WakeLocks");
            mDisplaySuspendBlocker = createSuspendBlockerLocked("PowerManagerService.Display");
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;

            mScreenOnBlocker = new ScreenOnBlockerImpl();
            mDisplayBlanker = new DisplayBlankerImpl();
            mWakefulness = WAKEFULNESS_AWAKE;
        }

        nativeInit();
        nativeSetPowerState(true, true);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.POWER_SERVICE, new BinderService());
        publishLocalService(PowerManagerInternal.class, new LocalService());
    }

    /**
     * Initialize the power manager.
     * Must be called before any other functions within the power manager are called.
     */
    public void init(LightsManager ls,
            BatteryService bs, IBatteryStats bss,
            IAppOpsService appOps) {
        mLightsManager = ls;
        mBatteryService = bs;
        mBatteryStats = bss;
        mAppOps = appOps;
        mDisplayManagerInternal = getLocalService(DisplayManagerInternal.class);
        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DISPLAY, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new PowerManagerHandler(mHandlerThread.getLooper());

        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);

        // Forcibly turn the screen on at boot so that it is in a known power state.
        // We do this in init() rather than in the constructor because setting the
        // screen state requires a call into surface flinger which then needs to call back
        // into the activity manager to check permissions.  Unfortunately the
        // activity manager is not running when the constructor is called, so we
        // have to defer setting the screen state until this point.
        mDisplayBlanker.unblankAllDisplays();
    }

    void setPolicy(WindowManagerPolicy policy) {
        synchronized (mLock) {
            mPolicy = policy;
        }
    }

    public void systemReady() {
        synchronized (mLock) {
            mSystemReady = true;
            mDreamManager = LocalServices.getService(DreamManagerInternal.class);

            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            mScreenBrightnessSettingMinimum = pm.getMinimumScreenBrightnessSetting();
            mScreenBrightnessSettingMaximum = pm.getMaximumScreenBrightnessSetting();
            mScreenBrightnessSettingDefault = pm.getDefaultScreenBrightnessSetting();

            SensorManager sensorManager = new SystemSensorManager(mContext, mHandler.getLooper());

            // The notifier runs on the system server's main looper so as not to interfere
            // with the animations and other critical functions of the power manager.
            mNotifier = new Notifier(Looper.getMainLooper(), mContext, mBatteryStats,
                    mAppOps, createSuspendBlockerLocked("PowerManagerService.Broadcasts"),
                    mScreenOnBlocker, mPolicy);

            // The display power controller runs on the power manager service's
            // own handler thread to ensure timely operation.
            mDisplayPowerController = new DisplayPowerController(mHandler.getLooper(),
                    mContext, mNotifier, mLightsManager,
                    LocalServices.getService(TwilightManager.class), sensorManager,
                    mDisplaySuspendBlocker, mDisplayBlanker,
                    mDisplayPowerControllerCallbacks, mHandler);

            mWirelessChargerDetector = new WirelessChargerDetector(sensorManager,
                    createSuspendBlockerLocked("PowerManagerService.WirelessChargerDetector"),
                    mHandler);
            mSettingsObserver = new SettingsObserver(mHandler);
            mAttentionLight = mLightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

            // Register for broadcasts from other components of the system.
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mContext.registerReceiver(new BatteryReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            mContext.registerReceiver(new BootCompletedReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DREAMING_STARTED);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            mContext.registerReceiver(new DreamReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            mContext.registerReceiver(new UserSwitchedReceiver(), filter, null, mHandler);

            filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DOCK_EVENT);
            mContext.registerReceiver(new DockReceiver(), filter, null, mHandler);

            // Register for settings changes.
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.STAY_ON_WHILE_PLUGGED_IN),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            // Go.
            readConfigurationLocked();
            updateSettingsLocked();
            mDirty |= DIRTY_BATTERY_STATE;
            updatePowerStateLocked();
        }
    }

    private void readConfigurationLocked() {
        final Resources resources = mContext.getResources();

        mDecoupleAutoSuspendModeFromDisplayConfig = resources.getBoolean(
                com.android.internal.R.bool.config_powerDecoupleAutoSuspendModeFromDisplay);
        mDecoupleInteractiveModeFromDisplayConfig = resources.getBoolean(
                com.android.internal.R.bool.config_powerDecoupleInteractiveModeFromDisplay);
        mWakeUpWhenPluggedOrUnpluggedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_unplugTurnsOnScreen);
        mSuspendWhenScreenOffDueToProximityConfig = resources.getBoolean(
                com.android.internal.R.bool.config_suspendWhenScreenOffDueToProximity);
        mDreamsSupportedConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsSupported);
        mDreamsEnabledByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnSleepByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefaultConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
        mDreamsEnabledOnBatteryConfig = resources.getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledOnBattery);
        mDreamsBatteryLevelMinimumWhenPoweredConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelMinimumWhenPowered);
        mDreamsBatteryLevelMinimumWhenNotPoweredConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelMinimumWhenNotPowered);
        mDreamsBatteryLevelDrainCutoffConfig = resources.getInteger(
                com.android.internal.R.integer.config_dreamsBatteryLevelDrainCutoff);
    }

    private void updateSettingsLocked() {
        final ContentResolver resolver = mContext.getContentResolver();

        mDreamsEnabledSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ENABLED,
                mDreamsEnabledByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnSleepSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                mDreamsActivatedOnSleepByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mDreamsActivateOnDockSetting = (Settings.Secure.getIntForUser(resolver,
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                mDreamsActivatedOnDockByDefaultConfig ? 1 : 0,
                UserHandle.USER_CURRENT) != 0);
        mScreenOffTimeoutSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_TIMEOUT,
                UserHandle.USER_CURRENT);
        mStayOnWhilePluggedInSetting = Settings.Global.getInt(resolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, BatteryManager.BATTERY_PLUGGED_AC);

        final int oldScreenBrightnessSetting = mScreenBrightnessSetting;
        mScreenBrightnessSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS, mScreenBrightnessSettingDefault,
                UserHandle.USER_CURRENT);
        if (oldScreenBrightnessSetting != mScreenBrightnessSetting) {
            mTemporaryScreenBrightnessSettingOverride = -1;
        }

        final float oldScreenAutoBrightnessAdjustmentSetting =
                mScreenAutoBrightnessAdjustmentSetting;
        mScreenAutoBrightnessAdjustmentSetting = Settings.System.getFloatForUser(resolver,
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f,
                UserHandle.USER_CURRENT);
        if (oldScreenAutoBrightnessAdjustmentSetting != mScreenAutoBrightnessAdjustmentSetting) {
            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = Float.NaN;
        }

        mScreenBrightnessModeSetting = Settings.System.getIntForUser(resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT);

        mDirty |= DIRTY_SETTINGS;
    }

    private void handleSettingsChangedLocked() {
        updateSettingsLocked();
        updatePowerStateLocked();
    }

    private void acquireWakeLockInternal(IBinder lock, int flags, String tag, String packageName,
            WorkSource ws, int uid, int pid) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "acquireWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + ", flags=0x" + Integer.toHexString(flags)
                        + ", tag=\"" + tag + "\", ws=" + ws + ", uid=" + uid + ", pid=" + pid);
            }

            WakeLock wakeLock;
            int index = findWakeLockIndexLocked(lock);
            if (index >= 0) {
                wakeLock = mWakeLocks.get(index);
                if (!wakeLock.hasSameProperties(flags, tag, ws, uid, pid)) {
                    // Update existing wake lock.  This shouldn't happen but is harmless.
                    notifyWakeLockReleasedLocked(wakeLock);
                    wakeLock.updateProperties(flags, tag, packageName, ws, uid, pid);
                    notifyWakeLockAcquiredLocked(wakeLock);
                }
            } else {
                wakeLock = new WakeLock(lock, flags, tag, packageName, ws, uid, pid);
                try {
                    lock.linkToDeath(wakeLock, 0);
                } catch (RemoteException ex) {
                    throw new IllegalArgumentException("Wake lock is already dead.");
                }
                notifyWakeLockAcquiredLocked(wakeLock);
                mWakeLocks.add(wakeLock);
            }

            applyWakeLockFlagsOnAcquireLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isScreenLock(final WakeLock wakeLock) {
        switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
            case PowerManager.FULL_WAKE_LOCK:
            case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
            case PowerManager.SCREEN_DIM_WAKE_LOCK:
                return true;
        }
        return false;
    }

    private void applyWakeLockFlagsOnAcquireLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0
                && isScreenLock(wakeLock)) {
            wakeUpNoUpdateLocked(SystemClock.uptimeMillis());
        }
    }

    private void releaseWakeLockInternal(IBinder lock, int flags) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], flags=0x" + Integer.toHexString(flags));
                }
                return;
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "releaseWakeLockInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], flags=0x" + Integer.toHexString(flags));
            }

            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);
            wakeLock.mLock.unlinkToDeath(wakeLock, 0);

            if ((flags & PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE) != 0) {
                mRequestWaitForNegativeProximity = true;
            }

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void handleWakeLockDeath(WakeLock wakeLock) {
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleWakeLockDeath: lock=" + Objects.hashCode(wakeLock.mLock)
                        + " [" + wakeLock.mTag + "]");
            }

            int index = mWakeLocks.indexOf(wakeLock);
            if (index < 0) {
                return;
            }

            mWakeLocks.remove(index);
            notifyWakeLockReleasedLocked(wakeLock);

            applyWakeLockFlagsOnReleaseLocked(wakeLock);
            mDirty |= DIRTY_WAKE_LOCKS;
            updatePowerStateLocked();
        }
    }

    private void applyWakeLockFlagsOnReleaseLocked(WakeLock wakeLock) {
        if ((wakeLock.mFlags & PowerManager.ON_AFTER_RELEASE) != 0
                && isScreenLock(wakeLock)) {
            userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_OTHER,
                    PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS,
                    wakeLock.mOwnerUid);
        }
    }

    private void updateWakeLockWorkSourceInternal(IBinder lock, WorkSource ws) {
        synchronized (mLock) {
            int index = findWakeLockIndexLocked(lock);
            if (index < 0) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                            + " [not found], ws=" + ws);
                }
                throw new IllegalArgumentException("Wake lock not active");
            }

            WakeLock wakeLock = mWakeLocks.get(index);
            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockWorkSourceInternal: lock=" + Objects.hashCode(lock)
                        + " [" + wakeLock.mTag + "], ws=" + ws);
            }

            if (!wakeLock.hasSameWorkSource(ws)) {
                notifyWakeLockReleasedLocked(wakeLock);
                wakeLock.updateWorkSource(ws);
                notifyWakeLockAcquiredLocked(wakeLock);
            }
        }
    }

    private int findWakeLockIndexLocked(IBinder lock) {
        final int count = mWakeLocks.size();
        for (int i = 0; i < count; i++) {
            if (mWakeLocks.get(i).mLock == lock) {
                return i;
            }
        }
        return -1;
    }

    private void notifyWakeLockAcquiredLocked(WakeLock wakeLock) {
        if (mSystemReady) {
            wakeLock.mNotifiedAcquired = true;
            mNotifier.onWakeLockAcquired(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    private void notifyWakeLockReleasedLocked(WakeLock wakeLock) {
        if (mSystemReady && wakeLock.mNotifiedAcquired) {
            wakeLock.mNotifiedAcquired = false;
            mNotifier.onWakeLockReleased(wakeLock.mFlags, wakeLock.mTag, wakeLock.mPackageName,
                    wakeLock.mOwnerUid, wakeLock.mOwnerPid, wakeLock.mWorkSource);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isWakeLockLevelSupportedInternal(int level) {
        synchronized (mLock) {
            switch (level) {
                case PowerManager.PARTIAL_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.FULL_WAKE_LOCK:
                case PowerManager.DOZE_WAKE_LOCK:
                    return true;

                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return mSystemReady && mDisplayPowerController.isProximitySensorAvailable();

                default:
                    return false;
            }
        }
    }

    // Called from native code.
    private void userActivityFromNative(long eventTime, int event, int flags) {
        userActivityInternal(eventTime, event, flags, Process.SYSTEM_UID);
    }

    private void userActivityInternal(long eventTime, int event, int flags, int uid) {
        synchronized (mLock) {
            if (userActivityNoUpdateLocked(eventTime, event, flags, uid)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean userActivityNoUpdateLocked(long eventTime, int event, int flags, int uid) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "userActivityNoUpdateLocked: eventTime=" + eventTime
                    + ", event=" + event + ", flags=0x" + Integer.toHexString(flags)
                    + ", uid=" + uid);
        }

        if (eventTime < mLastSleepTime || eventTime < mLastWakeTime
                || mWakefulness == WAKEFULNESS_ASLEEP || mWakefulness == WAKEFULNESS_DOZING
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        mNotifier.onUserActivity(event, uid);

        if ((flags & PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS) != 0) {
            if (eventTime > mLastUserActivityTimeNoChangeLights
                    && eventTime > mLastUserActivityTime) {
                mLastUserActivityTimeNoChangeLights = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        } else {
            if (eventTime > mLastUserActivityTime) {
                mLastUserActivityTime = eventTime;
                mDirty |= DIRTY_USER_ACTIVITY;
                return true;
            }
        }
        return false;
    }

    // Called from native code.
    private void wakeUpFromNative(long eventTime) {
        wakeUpInternal(eventTime);
    }

    private void wakeUpInternal(long eventTime) {
        synchronized (mLock) {
            if (wakeUpNoUpdateLocked(eventTime)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean wakeUpNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "wakeUpNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastSleepTime || mWakefulness == WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (mWakefulness) {
            case WAKEFULNESS_ASLEEP:
                Slog.i(TAG, "Waking up from sleep...");
                break;
            case WAKEFULNESS_DREAMING:
                Slog.i(TAG, "Waking up from dream...");
                break;
            case WAKEFULNESS_DOZING:
                Slog.i(TAG, "Waking up from dozing...");
                break;
        }

        if (mWakefulness != WAKEFULNESS_DREAMING) {
            sendPendingNotificationsLocked();
            mNotifier.onWakeUpStarted();
            mSendWakeUpFinishedNotificationWhenReady = true;
        }

        mLastWakeTime = eventTime;
        mWakefulness = WAKEFULNESS_AWAKE;
        mDirty |= DIRTY_WAKEFULNESS;

        userActivityNoUpdateLocked(
                eventTime, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        return true;
    }

    // Called from native code.
    private void goToSleepFromNative(long eventTime, int reason) {
        goToSleepInternal(eventTime, reason);
    }

    private void goToSleepInternal(long eventTime, int reason) {
        synchronized (mLock) {
            if (goToSleepNoUpdateLocked(eventTime, reason)) {
                updatePowerStateLocked();
            }
        }
    }

    // This method is called goToSleep for historical reasons but we actually start
    // dozing before really going to sleep.
    @SuppressWarnings("deprecation")
    private boolean goToSleepNoUpdateLocked(long eventTime, int reason) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "goToSleepNoUpdateLocked: eventTime=" + eventTime + ", reason=" + reason);
        }

        if (eventTime < mLastWakeTime
                || mWakefulness == WAKEFULNESS_ASLEEP
                || mWakefulness == WAKEFULNESS_DOZING
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        switch (reason) {
            case PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN:
                Slog.i(TAG, "Going to sleep due to device administration policy...");
                break;
            case PowerManager.GO_TO_SLEEP_REASON_TIMEOUT:
                Slog.i(TAG, "Going to sleep due to screen timeout...");
                break;
            default:
                Slog.i(TAG, "Going to sleep by user request...");
                reason = PowerManager.GO_TO_SLEEP_REASON_USER;
                break;
        }

        sendPendingNotificationsLocked();
        mNotifier.onGoToSleepStarted(reason);
        mSendGoToSleepFinishedNotificationWhenReady = true;

        mLastSleepTime = eventTime;
        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_DOZING;
        mSandmanSummoned = true;

        // Report the number of wake locks that will be cleared by going to sleep.
        int numWakeLocksCleared = 0;
        final int numWakeLocks = mWakeLocks.size();
        for (int i = 0; i < numWakeLocks; i++) {
            final WakeLock wakeLock = mWakeLocks.get(i);
            switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    numWakeLocksCleared += 1;
                    break;
            }
        }
        EventLog.writeEvent(EventLogTags.POWER_SLEEP_REQUESTED, numWakeLocksCleared);
        return true;
    }

    private void napInternal(long eventTime) {
        synchronized (mLock) {
            if (napNoUpdateLocked(eventTime)) {
                updatePowerStateLocked();
            }
        }
    }

    private boolean napNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "napNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastWakeTime || mWakefulness != WAKEFULNESS_AWAKE
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Slog.i(TAG, "Nap time...");

        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_DREAMING;
        mSandmanSummoned = true;
        return true;
    }

    // Done dozing, drop everything and go to sleep.
    private boolean reallyGoToSleepNoUpdateLocked(long eventTime) {
        if (DEBUG_SPEW) {
            Slog.d(TAG, "reallyGoToSleepNoUpdateLocked: eventTime=" + eventTime);
        }

        if (eventTime < mLastWakeTime || mWakefulness == WAKEFULNESS_ASLEEP
                || !mBootCompleted || !mSystemReady) {
            return false;
        }

        Slog.i(TAG, "Sleeping...");

        mDirty |= DIRTY_WAKEFULNESS;
        mWakefulness = WAKEFULNESS_ASLEEP;
        return true;
    }

    /**
     * Updates the global power state based on dirty bits recorded in mDirty.
     *
     * This is the main function that performs power state transitions.
     * We centralize them here so that we can recompute the power state completely
     * each time something important changes, and ensure that we do it the same
     * way each time.  The point is to gather all of the transition logic here.
     */
    private void updatePowerStateLocked() {
        if (!mSystemReady || mDirty == 0) {
            return;
        }

        // Phase 0: Basic state updates.
        updateIsPoweredLocked(mDirty);
        updateStayOnLocked(mDirty);

        // Phase 1: Update wakefulness.
        // Loop because the wake lock and user activity computations are influenced
        // by changes in wakefulness.
        final long now = SystemClock.uptimeMillis();
        int dirtyPhase2 = 0;
        for (;;) {
            int dirtyPhase1 = mDirty;
            dirtyPhase2 |= dirtyPhase1;
            mDirty = 0;

            updateWakeLockSummaryLocked(dirtyPhase1);
            updateUserActivitySummaryLocked(now, dirtyPhase1);
            if (!updateWakefulnessLocked(dirtyPhase1)) {
                break;
            }
        }

        // Phase 2: Update dreams and display power state.
        updateDreamLocked(dirtyPhase2);
        updateDisplayPowerStateLocked(dirtyPhase2);

        // Phase 3: Send notifications, if needed.
        if (mDisplayReady) {
            sendPendingNotificationsLocked();
        }

        // Phase 4: Update suspend blocker.
        // Because we might release the last suspend blocker here, we need to make sure
        // we finished everything else first!
        updateSuspendBlockerLocked();
    }

    private void sendPendingNotificationsLocked() {
        if (mSendWakeUpFinishedNotificationWhenReady) {
            mSendWakeUpFinishedNotificationWhenReady = false;
            mNotifier.onWakeUpFinished();
        }
        if (mSendGoToSleepFinishedNotificationWhenReady) {
            mSendGoToSleepFinishedNotificationWhenReady = false;
            mNotifier.onGoToSleepFinished();
        }
    }

    /**
     * Updates the value of mIsPowered.
     * Sets DIRTY_IS_POWERED if a change occurred.
     */
    private void updateIsPoweredLocked(int dirty) {
        if ((dirty & DIRTY_BATTERY_STATE) != 0) {
            final boolean wasPowered = mIsPowered;
            final int oldPlugType = mPlugType;
            mIsPowered = mBatteryService.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
            mPlugType = mBatteryService.getPlugType();
            mBatteryLevel = mBatteryService.getBatteryLevel();

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateIsPoweredLocked: wasPowered=" + wasPowered
                        + ", mIsPowered=" + mIsPowered
                        + ", oldPlugType=" + oldPlugType
                        + ", mPlugType=" + mPlugType
                        + ", mBatteryLevel=" + mBatteryLevel);
            }

            if (wasPowered != mIsPowered || oldPlugType != mPlugType) {
                mDirty |= DIRTY_IS_POWERED;

                // Update wireless dock detection state.
                final boolean dockedOnWirelessCharger = mWirelessChargerDetector.update(
                        mIsPowered, mPlugType, mBatteryLevel);

                // Treat plugging and unplugging the devices as a user activity.
                // Users find it disconcerting when they plug or unplug the device
                // and it shuts off right away.
                // Some devices also wake the device when plugged or unplugged because
                // they don't have a charging LED.
                final long now = SystemClock.uptimeMillis();
                if (shouldWakeUpWhenPluggedOrUnpluggedLocked(wasPowered, oldPlugType,
                        dockedOnWirelessCharger)) {
                    wakeUpNoUpdateLocked(now);
                }
                userActivityNoUpdateLocked(
                        now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);

                // Tell the notifier whether wireless charging has started so that
                // it can provide feedback to the user.
                if (dockedOnWirelessCharger) {
                    mNotifier.onWirelessChargingStarted();
                }
            }
        }
    }

    private boolean shouldWakeUpWhenPluggedOrUnpluggedLocked(
            boolean wasPowered, int oldPlugType, boolean dockedOnWirelessCharger) {
        // Don't wake when powered unless configured to do so.
        if (!mWakeUpWhenPluggedOrUnpluggedConfig) {
            return false;
        }

        // Don't wake when undocked from wireless charger.
        // See WirelessChargerDetector for justification.
        if (wasPowered && !mIsPowered
                && oldPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            return false;
        }

        // Don't wake when docked on wireless charger unless we are certain of it.
        // See WirelessChargerDetector for justification.
        if (!wasPowered && mIsPowered
                && mPlugType == BatteryManager.BATTERY_PLUGGED_WIRELESS
                && !dockedOnWirelessCharger) {
            return false;
        }

        // If already dreaming and becoming powered, then don't wake.
        if (mIsPowered && mWakefulness == WAKEFULNESS_DREAMING) {
            return false;
        }

        // Otherwise wake up!
        return true;
    }

    /**
     * Updates the value of mStayOn.
     * Sets DIRTY_STAY_ON if a change occurred.
     */
    private void updateStayOnLocked(int dirty) {
        if ((dirty & (DIRTY_BATTERY_STATE | DIRTY_SETTINGS)) != 0) {
            final boolean wasStayOn = mStayOn;
            if (mStayOnWhilePluggedInSetting != 0
                    && !isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
                mStayOn = mBatteryService.isPowered(mStayOnWhilePluggedInSetting);
            } else {
                mStayOn = false;
            }

            if (mStayOn != wasStayOn) {
                mDirty |= DIRTY_STAY_ON;
            }
        }
    }

    /**
     * Updates the value of mWakeLockSummary to summarize the state of all active wake locks.
     * Note that most wake-locks are ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    @SuppressWarnings("deprecation")
    private void updateWakeLockSummaryLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_WAKEFULNESS)) != 0) {
            mWakeLockSummary = 0;

            final int numWakeLocks = mWakeLocks.size();
            for (int i = 0; i < numWakeLocks; i++) {
                final WakeLock wakeLock = mWakeLocks.get(i);
                switch (wakeLock.mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                    case PowerManager.PARTIAL_WAKE_LOCK:
                        mWakeLockSummary |= WAKE_LOCK_CPU;
                        break;
                    case PowerManager.FULL_WAKE_LOCK:
                        if (mWakefulness == WAKEFULNESS_AWAKE
                                || mWakefulness == WAKEFULNESS_DREAMING) {
                            mWakeLockSummary |= WAKE_LOCK_CPU
                                    | WAKE_LOCK_SCREEN_BRIGHT | WAKE_LOCK_BUTTON_BRIGHT;
                        }
                        if (mWakefulness == WAKEFULNESS_AWAKE) {
                            mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                        }
                        break;
                    case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                        if (mWakefulness == WAKEFULNESS_AWAKE
                                || mWakefulness == WAKEFULNESS_DREAMING) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_BRIGHT;
                        }
                        if (mWakefulness == WAKEFULNESS_AWAKE) {
                            mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                        }
                        break;
                    case PowerManager.SCREEN_DIM_WAKE_LOCK:
                        if (mWakefulness == WAKEFULNESS_AWAKE
                                || mWakefulness == WAKEFULNESS_DREAMING) {
                            mWakeLockSummary |= WAKE_LOCK_CPU | WAKE_LOCK_SCREEN_DIM;
                        }
                        if (mWakefulness == WAKEFULNESS_AWAKE) {
                            mWakeLockSummary |= WAKE_LOCK_STAY_AWAKE;
                        }
                        break;
                    case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                        if (mWakefulness == WAKEFULNESS_AWAKE
                                || mWakefulness == WAKEFULNESS_DREAMING
                                || mWakefulness == WAKEFULNESS_DOZING) {
                            mWakeLockSummary |= WAKE_LOCK_PROXIMITY_SCREEN_OFF;
                        }
                        break;
                    case PowerManager.DOZE_WAKE_LOCK:
                        if (mWakefulness == WAKEFULNESS_DOZING) {
                            mWakeLockSummary |= WAKE_LOCK_DOZE;
                        }
                        break;
                }
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateWakeLockSummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            }
        }
    }

    /**
     * Updates the value of mUserActivitySummary to summarize the user requested
     * state of the system such as whether the screen should be bright or dim.
     * Note that user activity is ignored when the system is asleep.
     *
     * This function must have no other side-effects.
     */
    private void updateUserActivitySummaryLocked(long now, int dirty) {
        // Update the status of the user activity timeout timer.
        if ((dirty & (DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS | DIRTY_SETTINGS)) != 0) {
            mHandler.removeMessages(MSG_USER_ACTIVITY_TIMEOUT);

            long nextTimeout = 0;
            if (mWakefulness == WAKEFULNESS_AWAKE
                    || mWakefulness == WAKEFULNESS_DREAMING) {
                final int screenOffTimeout = getScreenOffTimeoutLocked();
                final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);

                mUserActivitySummary = 0;
                if (mLastUserActivityTime >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTime
                            + screenOffTimeout - screenDimDuration;
                    if (now < nextTimeout) {
                        mUserActivitySummary |= USER_ACTIVITY_SCREEN_BRIGHT;
                    } else {
                        nextTimeout = mLastUserActivityTime + screenOffTimeout;
                        if (now < nextTimeout) {
                            mUserActivitySummary |= USER_ACTIVITY_SCREEN_DIM;
                        }
                    }
                }
                if (mUserActivitySummary == 0
                        && mLastUserActivityTimeNoChangeLights >= mLastWakeTime) {
                    nextTimeout = mLastUserActivityTimeNoChangeLights + screenOffTimeout;
                    if (now < nextTimeout
                            && mDisplayPowerRequest.wantScreenOnNormal()) {
                        mUserActivitySummary = mDisplayPowerRequest.screenState
                                == DisplayPowerRequest.SCREEN_STATE_BRIGHT ?
                                USER_ACTIVITY_SCREEN_BRIGHT : USER_ACTIVITY_SCREEN_DIM;
                    }
                }
                if (mUserActivitySummary != 0) {
                    Message msg = mHandler.obtainMessage(MSG_USER_ACTIVITY_TIMEOUT);
                    msg.setAsynchronous(true);
                    mHandler.sendMessageAtTime(msg, nextTimeout);
                }
            } else {
                mUserActivitySummary = 0;
            }

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateUserActivitySummaryLocked: mWakefulness="
                        + wakefulnessToString(mWakefulness)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", nextTimeout=" + TimeUtils.formatUptime(nextTimeout));
            }
        }
    }

    /**
     * Called when a user activity timeout has occurred.
     * Simply indicates that something about user activity has changed so that the new
     * state can be recomputed when the power state is updated.
     *
     * This function must have no other side-effects besides setting the dirty
     * bit and calling update power state.  Wakefulness transitions are handled elsewhere.
     */
    private void handleUserActivityTimeout() { // runs on handler thread
        synchronized (mLock) {
            if (DEBUG_SPEW) {
                Slog.d(TAG, "handleUserActivityTimeout");
            }

            mDirty |= DIRTY_USER_ACTIVITY;
            updatePowerStateLocked();
        }
    }

    private int getScreenOffTimeoutLocked() {
        int timeout = mScreenOffTimeoutSetting;
        if (isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()) {
            timeout = Math.min(timeout, mMaximumScreenOffTimeoutFromDeviceAdmin);
        }
        if (mUserActivityTimeoutOverrideFromWindowManager >= 0) {
            timeout = (int)Math.min(timeout, mUserActivityTimeoutOverrideFromWindowManager);
        }
        return Math.max(timeout, MINIMUM_SCREEN_OFF_TIMEOUT);
    }

    private int getScreenDimDurationLocked(int screenOffTimeout) {
        return Math.min(SCREEN_DIM_DURATION,
                (int)(screenOffTimeout * MAXIMUM_SCREEN_DIM_RATIO));
    }

    /**
     * Updates the wakefulness of the device.
     *
     * This is the function that decides whether the device should start dreaming
     * based on the current wake locks and user activity state.  It may modify mDirty
     * if the wakefulness changes.
     *
     * Returns true if the wakefulness changed and we need to restart power state calculation.
     */
    private boolean updateWakefulnessLocked(int dirty) {
        boolean changed = false;
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_BOOT_COMPLETED
                | DIRTY_WAKEFULNESS | DIRTY_STAY_ON | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_DOCK_STATE)) != 0) {
            if (mWakefulness == WAKEFULNESS_AWAKE && isItBedTimeYetLocked()) {
                if (DEBUG_SPEW) {
                    Slog.d(TAG, "updateWakefulnessLocked: Bed time...");
                }
                final long time = SystemClock.uptimeMillis();
                if (shouldNapAtBedTimeLocked()) {
                    changed = napNoUpdateLocked(time);
                } else {
                    changed = goToSleepNoUpdateLocked(time,
                            PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
                }
            }
        }
        return changed;
    }

    /**
     * Returns true if the device should automatically nap and start dreaming when the user
     * activity timeout has expired and it's bedtime.
     */
    private boolean shouldNapAtBedTimeLocked() {
        return mDreamsActivateOnSleepSetting
                || (mDreamsActivateOnDockSetting
                        && mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED);
    }

    /**
     * Returns true if the device should go to sleep now.
     * Also used when exiting a dream to determine whether we should go back
     * to being fully awake or else go to sleep for good.
     */
    private boolean isItBedTimeYetLocked() {
        return mBootCompleted && !isBeingKeptAwakeLocked();
    }

    /**
     * Returns true if the device is being kept awake by a wake lock, user activity
     * or the stay on while powered setting.  We also keep the phone awake when
     * the proximity sensor returns a positive result so that the device does not
     * lock while in a phone call.  This function only controls whether the device
     * will go to sleep or dream which is independent of whether it will be allowed
     * to suspend.
     */
    private boolean isBeingKeptAwakeLocked() {
        return mStayOn
                || mProximityPositive
                || (mWakeLockSummary & WAKE_LOCK_STAY_AWAKE) != 0
                || (mUserActivitySummary & (USER_ACTIVITY_SCREEN_BRIGHT
                        | USER_ACTIVITY_SCREEN_DIM)) != 0;
    }

    /**
     * Determines whether to post a message to the sandman to update the dream state.
     */
    private void updateDreamLocked(int dirty) {
        if ((dirty & (DIRTY_WAKEFULNESS
                | DIRTY_USER_ACTIVITY
                | DIRTY_WAKE_LOCKS
                | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS
                | DIRTY_IS_POWERED
                | DIRTY_STAY_ON
                | DIRTY_PROXIMITY_POSITIVE
                | DIRTY_BATTERY_STATE)) != 0) {
            scheduleSandmanLocked();
        }
    }

    private void scheduleSandmanLocked() {
        if (!mSandmanScheduled) {
            mSandmanScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_SANDMAN);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Called when the device enters or exits a dreaming or dozing state.
     *
     * We do this asynchronously because we must call out of the power manager to start
     * the dream and we don't want to hold our lock while doing so.  There is a risk that
     * the device will wake or go to sleep in the meantime so we have to handle that case.
     */
    private void handleSandman() { // runs on handler thread
        // Handle preconditions.
        final boolean startDreaming;
        final int wakefulness;
        synchronized (mLock) {
            mSandmanScheduled = false;
            wakefulness = mWakefulness;
            if (mSandmanSummoned) {
                startDreaming = ((wakefulness == WAKEFULNESS_DREAMING && canDreamLocked())
                        || wakefulness == WAKEFULNESS_DOZING);
                mSandmanSummoned = false;
            } else {
                startDreaming = false;
            }
        }

        // Start dreaming if needed.
        // We only control the dream on the handler thread, so we don't need to worry about
        // concurrent attempts to start or stop the dream.
        final boolean isDreaming;
        if (mDreamManager != null) {
            // Restart the dream whenever the sandman is summoned.
            if (startDreaming) {
                mDreamManager.stopDream();
                mDreamManager.startDream(wakefulness == WAKEFULNESS_DOZING);
            }
            isDreaming = mDreamManager.isDreaming();
        } else {
            isDreaming = false;
        }

        // Update dream state.
        synchronized (mLock) {
            // Remember the initial battery level when the dream started.
            if (startDreaming && isDreaming) {
                mBatteryLevelWhenDreamStarted = mBatteryLevel;
                if (wakefulness == WAKEFULNESS_DOZING) {
                    Slog.i(TAG, "Dozing...");
                } else {
                    Slog.i(TAG, "Dreaming...");
                }
            }

            // If preconditions changed, wait for the next iteration to determine
            // whether the dream should continue (or be restarted).
            if (mSandmanSummoned || mWakefulness != wakefulness) {
                return; // wait for next cycle
            }

            // Determine whether the dream should continue.
            if (wakefulness == WAKEFULNESS_DREAMING) {
                if (isDreaming && canDreamLocked()) {
                    if (mDreamsBatteryLevelDrainCutoffConfig >= 0
                            && mBatteryLevel < mBatteryLevelWhenDreamStarted
                                    - mDreamsBatteryLevelDrainCutoffConfig
                            && !isBeingKeptAwakeLocked()) {
                        // If the user activity timeout expired and the battery appears
                        // to be draining faster than it is charging then stop dreaming
                        // and go to sleep.
                        Slog.i(TAG, "Stopping dream because the battery appears to "
                                + "be draining faster than it is charging.  "
                                + "Battery level when dream started: "
                                + mBatteryLevelWhenDreamStarted + "%.  "
                                + "Battery level now: " + mBatteryLevel + "%.");
                    } else {
                        return; // continue dreaming
                    }
                }

                // Dream has ended or will be stopped.  Update the power state.
                if (isItBedTimeYetLocked()) {
                    goToSleepNoUpdateLocked(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_TIMEOUT);
                    updatePowerStateLocked();
                } else {
                    wakeUpNoUpdateLocked(SystemClock.uptimeMillis());
                    updatePowerStateLocked();
                }
            } else if (wakefulness == WAKEFULNESS_DOZING) {
                if (isDreaming) {
                    return; // continue dozing
                }

                // Doze has ended or will be stopped.  Update the power state.
                reallyGoToSleepNoUpdateLocked(SystemClock.uptimeMillis());
                updatePowerStateLocked();
            }
        }

        // Stop dream.
        if (isDreaming) {
            mDreamManager.stopDream();
        }
    }

    /**
     * Returns true if the device is allowed to dream in its current state.
     * This function is not called when dozing.
     */
    private boolean canDreamLocked() {
        if (mWakefulness != WAKEFULNESS_DREAMING
                || !mDreamsSupportedConfig
                || !mDreamsEnabledSetting
                || !mDisplayPowerRequest.wantScreenOnNormal()
                || !mBootCompleted) {
            return false;
        }
        if (!isBeingKeptAwakeLocked()) {
            if (!mIsPowered && !mDreamsEnabledOnBatteryConfig) {
                return false;
            }
            if (!mIsPowered
                    && mDreamsBatteryLevelMinimumWhenNotPoweredConfig >= 0
                    && mBatteryLevel < mDreamsBatteryLevelMinimumWhenNotPoweredConfig) {
                return false;
            }
            if (mIsPowered
                    && mDreamsBatteryLevelMinimumWhenPoweredConfig >= 0
                    && mBatteryLevel < mDreamsBatteryLevelMinimumWhenPoweredConfig) {
                return false;
            }
        }
        return true;
    }

    private void handleScreenOnBlockerReleased() {
        synchronized (mLock) {
            mDirty |= DIRTY_SCREEN_ON_BLOCKER_RELEASED;
            updatePowerStateLocked();
        }
    }

    /**
     * Updates the display power state asynchronously.
     * When the update is finished, mDisplayReady will be set to true.  The display
     * controller posts a message to tell us when the actual display power state
     * has been updated so we come back here to double-check and finish up.
     *
     * This function recalculates the display power state each time.
     */
    private void updateDisplayPowerStateLocked(int dirty) {
        if ((dirty & (DIRTY_WAKE_LOCKS | DIRTY_USER_ACTIVITY | DIRTY_WAKEFULNESS
                | DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED | DIRTY_BOOT_COMPLETED
                | DIRTY_SETTINGS | DIRTY_SCREEN_ON_BLOCKER_RELEASED)) != 0) {
            final int newScreenState = getDesiredScreenPowerStateLocked();
            if (newScreenState != mDisplayPowerRequest.screenState) {
                mDisplayPowerRequest.screenState = newScreenState;
                nativeSetPowerState(
                        mDisplayPowerRequest.wantScreenOnNormal(),
                        newScreenState == DisplayPowerRequest.SCREEN_STATE_BRIGHT);
            }

            int screenBrightness = mScreenBrightnessSettingDefault;
            float screenAutoBrightnessAdjustment = 0.0f;
            boolean autoBrightness = (mScreenBrightnessModeSetting ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            if (isValidBrightness(mScreenBrightnessOverrideFromWindowManager)) {
                screenBrightness = mScreenBrightnessOverrideFromWindowManager;
                autoBrightness = false;
            } else if (isValidBrightness(mTemporaryScreenBrightnessSettingOverride)) {
                screenBrightness = mTemporaryScreenBrightnessSettingOverride;
            } else if (isValidBrightness(mScreenBrightnessSetting)) {
                screenBrightness = mScreenBrightnessSetting;
            }
            if (autoBrightness) {
                screenBrightness = mScreenBrightnessSettingDefault;
                if (isValidAutoBrightnessAdjustment(
                        mTemporaryScreenAutoBrightnessAdjustmentSettingOverride)) {
                    screenAutoBrightnessAdjustment =
                            mTemporaryScreenAutoBrightnessAdjustmentSettingOverride;
                } else if (isValidAutoBrightnessAdjustment(
                        mScreenAutoBrightnessAdjustmentSetting)) {
                    screenAutoBrightnessAdjustment = mScreenAutoBrightnessAdjustmentSetting;
                }
            }
            screenBrightness = Math.max(Math.min(screenBrightness,
                    mScreenBrightnessSettingMaximum), mScreenBrightnessSettingMinimum);
            screenAutoBrightnessAdjustment = Math.max(Math.min(
                    screenAutoBrightnessAdjustment, 1.0f), -1.0f);
            mDisplayPowerRequest.screenBrightness = screenBrightness;
            mDisplayPowerRequest.screenAutoBrightnessAdjustment =
                    screenAutoBrightnessAdjustment;
            mDisplayPowerRequest.useAutoBrightness = autoBrightness;

            mDisplayPowerRequest.useProximitySensor = shouldUseProximitySensorLocked();

            mDisplayPowerRequest.blockScreenOn = mScreenOnBlocker.isHeld();

            mDisplayReady = mDisplayPowerController.requestPowerState(mDisplayPowerRequest,
                    mRequestWaitForNegativeProximity);
            mRequestWaitForNegativeProximity = false;

            if (DEBUG_SPEW) {
                Slog.d(TAG, "updateScreenStateLocked: mDisplayReady=" + mDisplayReady
                        + ", newScreenState=" + newScreenState
                        + ", mWakefulness=" + mWakefulness
                        + ", mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary)
                        + ", mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary)
                        + ", mBootCompleted=" + mBootCompleted);
            }
        }
    }

    private static boolean isValidBrightness(int value) {
        return value >= 0 && value <= 255;
    }

    private static boolean isValidAutoBrightnessAdjustment(float value) {
        // Handles NaN by always returning false.
        return value >= -1.0f && value <= 1.0f;
    }

    private int getDesiredScreenPowerStateLocked() {
        if (mWakefulness == WAKEFULNESS_ASLEEP) {
            return DisplayPowerRequest.SCREEN_STATE_OFF;
        }

        if ((mWakeLockSummary & WAKE_LOCK_DOZE) != 0) {
            return DisplayPowerRequest.SCREEN_STATE_DOZE;
        }

        if ((mWakeLockSummary & WAKE_LOCK_SCREEN_BRIGHT) != 0
                || (mUserActivitySummary & USER_ACTIVITY_SCREEN_BRIGHT) != 0
                || !mBootCompleted) {
            return DisplayPowerRequest.SCREEN_STATE_BRIGHT;
        }

        return DisplayPowerRequest.SCREEN_STATE_DIM;
    }

    private final DisplayPowerController.Callbacks mDisplayPowerControllerCallbacks =
            new DisplayPowerController.Callbacks() {
        @Override
        public void onStateChanged() {
            synchronized (mLock) {
                mDirty |= DIRTY_ACTUAL_DISPLAY_POWER_STATE_UPDATED;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityPositive() {
            synchronized (mLock) {
                mProximityPositive = true;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                updatePowerStateLocked();
            }
        }

        @Override
        public void onProximityNegative() {
            synchronized (mLock) {
                mProximityPositive = false;
                mDirty |= DIRTY_PROXIMITY_POSITIVE;
                userActivityNoUpdateLocked(SystemClock.uptimeMillis(),
                        PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
                updatePowerStateLocked();
            }
        }
    };

    private boolean shouldUseProximitySensorLocked() {
        return (mWakeLockSummary & WAKE_LOCK_PROXIMITY_SCREEN_OFF) != 0;
    }

    /**
     * Updates the suspend blocker that keeps the CPU alive.
     *
     * This function must have no other side-effects.
     */
    private void updateSuspendBlockerLocked() {
        final boolean needWakeLockSuspendBlocker = ((mWakeLockSummary & WAKE_LOCK_CPU) != 0);
        final boolean needDisplaySuspendBlocker = needDisplaySuspendBlockerLocked();
        final boolean autoSuspend = !needDisplaySuspendBlocker;

        // Disable auto-suspend if needed.
        if (!autoSuspend) {
            if (mDecoupleAutoSuspendModeFromDisplayConfig) {
                setAutoSuspendModeLocked(false);
            }
            if (mDecoupleInteractiveModeFromDisplayConfig) {
                setInteractiveModeLocked(true);
            }
        }

        // First acquire suspend blockers if needed.
        if (needWakeLockSuspendBlocker && !mHoldingWakeLockSuspendBlocker) {
            mWakeLockSuspendBlocker.acquire();
            mHoldingWakeLockSuspendBlocker = true;
        }
        if (needDisplaySuspendBlocker && !mHoldingDisplaySuspendBlocker) {
            mDisplaySuspendBlocker.acquire();
            mHoldingDisplaySuspendBlocker = true;
        }

        // Then release suspend blockers if needed.
        if (!needWakeLockSuspendBlocker && mHoldingWakeLockSuspendBlocker) {
            mWakeLockSuspendBlocker.release();
            mHoldingWakeLockSuspendBlocker = false;
        }
        if (!needDisplaySuspendBlocker && mHoldingDisplaySuspendBlocker) {
            mDisplaySuspendBlocker.release();
            mHoldingDisplaySuspendBlocker = false;
        }

        // Enable auto-suspend if needed.
        if (autoSuspend) {
            if (mDecoupleInteractiveModeFromDisplayConfig) {
                setInteractiveModeLocked(false);
            }
            if (mDecoupleAutoSuspendModeFromDisplayConfig) {
                setAutoSuspendModeLocked(true);
            }
        }
    }

    /**
     * Return true if we must keep a suspend blocker active on behalf of the display.
     * We do so if the screen is on or is in transition between states.
     */
    private boolean needDisplaySuspendBlockerLocked() {
        if (!mDisplayReady) {
            return true;
        }
        if (mDisplayPowerRequest.wantScreenOnNormal()) {
            // If we asked for the screen to be on but it is off due to the proximity
            // sensor then we may suspend but only if the configuration allows it.
            // On some hardware it may not be safe to suspend because the proximity
            // sensor may not be correctly configured as a wake-up source.
            if (!mDisplayPowerRequest.useProximitySensor || !mProximityPositive
                    || !mSuspendWhenScreenOffDueToProximityConfig) {
                return true;
            }
        }
        // Let the system suspend if the screen is off or dozing.
        return false;
    }

    private void setAutoSuspendModeLocked(boolean enable) {
        if (enable != mAutoSuspendModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting auto-suspend mode to " + enable);
            }
            mAutoSuspendModeEnabled = enable;
            nativeSetAutoSuspend(enable);
        }
    }

    private void setInteractiveModeLocked(boolean enable) {
        if (enable != mInteractiveModeEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "Setting interactive mode to " + enable);
            }
            mInteractiveModeEnabled = enable;
            nativeSetInteractive(enable);
        }
    }

    private boolean isScreenOnInternal() {
        synchronized (mLock) {
            return !mSystemReady
                    || mDisplayPowerRequest.wantScreenOnNormal();
        }
    }

    private void handleBatteryStateChangedLocked() {
        mDirty |= DIRTY_BATTERY_STATE;
        updatePowerStateLocked();
    }

    private void startWatchingForBootAnimationFinished() {
        mHandler.sendEmptyMessage(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED);
    }

    private void checkIfBootAnimationFinished() {
        if (DEBUG) {
            Slog.d(TAG, "Check if boot animation finished...");
        }

        if (SystemService.isRunning(BOOT_ANIMATION_SERVICE)) {
            mHandler.sendEmptyMessageDelayed(MSG_CHECK_IF_BOOT_ANIMATION_FINISHED,
                    BOOT_ANIMATION_POLL_INTERVAL);
            return;
        }

        synchronized (mLock) {
            if (!mBootCompleted) {
                Slog.i(TAG, "Boot animation finished.");
                handleBootCompletedLocked();
            }
        }
    }

    private void handleBootCompletedLocked() {
        final long now = SystemClock.uptimeMillis();
        mBootCompleted = true;
        mDirty |= DIRTY_BOOT_COMPLETED;
        userActivityNoUpdateLocked(
                now, PowerManager.USER_ACTIVITY_EVENT_OTHER, 0, Process.SYSTEM_UID);
        updatePowerStateLocked();
    }

    private void shutdownOrRebootInternal(final boolean shutdown, final boolean confirm,
            final String reason, boolean wait) {
        if (mHandler == null || !mSystemReady) {
            throw new IllegalStateException("Too early to call shutdown() or reboot()");
        }

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                synchronized (this) {
                    if (shutdown) {
                        ShutdownThread.shutdown(mContext, confirm);
                    } else {
                        ShutdownThread.reboot(mContext, reason, confirm);
                    }
                }
            }
        };

        // ShutdownThread must run on a looper capable of displaying the UI.
        Message msg = Message.obtain(mHandler, runnable);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);

        // PowerManager.reboot() is documented not to return so just wait for the inevitable.
        if (wait) {
            synchronized (runnable) {
                while (true) {
                    try {
                        runnable.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }

    private void crashInternal(final String message) {
        Thread t = new Thread("PowerManagerService.crash()") {
            @Override
            public void run() {
                throw new RuntimeException(message);
            }
        };
        try {
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        }
    }

    private void setStayOnSettingInternal(int val) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, val);
    }

    private void setMaximumScreenOffTimeoutFromDeviceAdminInternal(int timeMs) {
        synchronized (mLock) {
            mMaximumScreenOffTimeoutFromDeviceAdmin = timeMs;
            mDirty |= DIRTY_SETTINGS;
            updatePowerStateLocked();
        }
    }

    private boolean isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() {
        return mMaximumScreenOffTimeoutFromDeviceAdmin >= 0
                && mMaximumScreenOffTimeoutFromDeviceAdmin < Integer.MAX_VALUE;
    }

    private void setAttentionLightInternal(boolean on, int color) {
        Light light;
        synchronized (mLock) {
            if (!mSystemReady) {
                return;
            }
            light = mAttentionLight;
        }

        // Control light outside of lock.
        light.setFlashing(color, Light.LIGHT_FLASH_HARDWARE, (on ? 3 : 0), 0);
    }

    private void setScreenBrightnessOverrideFromWindowManagerInternal(int brightness) {
        synchronized (mLock) {
            if (mScreenBrightnessOverrideFromWindowManager != brightness) {
                mScreenBrightnessOverrideFromWindowManager = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setUserActivityTimeoutOverrideFromWindowManagerInternal(long timeoutMillis) {
        synchronized (mLock) {
            if (mUserActivityTimeoutOverrideFromWindowManager != timeoutMillis) {
                mUserActivityTimeoutOverrideFromWindowManager = timeoutMillis;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setTemporaryScreenBrightnessSettingOverrideInternal(int brightness) {
        synchronized (mLock) {
            if (mTemporaryScreenBrightnessSettingOverride != brightness) {
                mTemporaryScreenBrightnessSettingOverride = brightness;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    private void setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(float adj) {
        synchronized (mLock) {
            // Note: This condition handles NaN because NaN is not equal to any other
            // value, including itself.
            if (mTemporaryScreenAutoBrightnessAdjustmentSettingOverride != adj) {
                mTemporaryScreenAutoBrightnessAdjustmentSettingOverride = adj;
                mDirty |= DIRTY_SETTINGS;
                updatePowerStateLocked();
            }
        }
    }

    /**
     * Low-level function turn the device off immediately, without trying
     * to be clean.  Most people should use {@link ShutdownThread} for a clean shutdown.
     */
    public static void lowLevelShutdown() {
        SystemProperties.set("sys.powerctl", "shutdown");
    }

    /**
     * Low-level function to reboot the device. On success, this
     * function doesn't return. If more than 20 seconds passes from
     * the time a reboot is requested (120 seconds for reboot to
     * recovery), this method returns.
     *
     * @param reason code to pass to the kernel (e.g. "recovery"), or null.
     */
    public static void lowLevelReboot(String reason) {
        if (reason == null) {
            reason = "";
        }
        long duration;
        if (reason.equals(PowerManager.REBOOT_RECOVERY)) {
            // If we are rebooting to go into recovery, instead of
            // setting sys.powerctl directly we'll start the
            // pre-recovery service which will do some preparation for
            // recovery and then reboot for us.
            //
            // This preparation can take more than 20 seconds if
            // there's a very large update package, so lengthen the
            // timeout.
            SystemProperties.set("ctl.start", "pre-recovery");
            duration = 120 * 1000L;
        } else {
            SystemProperties.set("sys.powerctl", "reboot," + reason);
            duration = 20 * 1000L;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override // Watchdog.Monitor implementation
    public void monitor() {
        // Grab and release lock for watchdog monitor to detect deadlocks.
        synchronized (mLock) {
        }
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("POWER MANAGER (dumpsys power)\n");

        final DisplayPowerController dpc;
        final WirelessChargerDetector wcd;
        synchronized (mLock) {
            pw.println("Power Manager State:");
            pw.println("  mDirty=0x" + Integer.toHexString(mDirty));
            pw.println("  mWakefulness=" + wakefulnessToString(mWakefulness));
            pw.println("  mIsPowered=" + mIsPowered);
            pw.println("  mPlugType=" + mPlugType);
            pw.println("  mBatteryLevel=" + mBatteryLevel);
            pw.println("  mBatteryLevelWhenDreamStarted=" + mBatteryLevelWhenDreamStarted);
            pw.println("  mDockState=" + mDockState);
            pw.println("  mStayOn=" + mStayOn);
            pw.println("  mProximityPositive=" + mProximityPositive);
            pw.println("  mBootCompleted=" + mBootCompleted);
            pw.println("  mSystemReady=" + mSystemReady);
            pw.println("  mAutoSuspendModeEnabled=" + mAutoSuspendModeEnabled);
            pw.println("  mInteactiveModeEnabled=" + mInteractiveModeEnabled);
            pw.println("  mWakeLockSummary=0x" + Integer.toHexString(mWakeLockSummary));
            pw.println("  mUserActivitySummary=0x" + Integer.toHexString(mUserActivitySummary));
            pw.println("  mRequestWaitForNegativeProximity=" + mRequestWaitForNegativeProximity);
            pw.println("  mSandmanScheduled=" + mSandmanScheduled);
            pw.println("  mSandmanSummoned=" + mSandmanSummoned);
            pw.println("  mLastWakeTime=" + TimeUtils.formatUptime(mLastWakeTime));
            pw.println("  mLastSleepTime=" + TimeUtils.formatUptime(mLastSleepTime));
            pw.println("  mSendWakeUpFinishedNotificationWhenReady="
                    + mSendWakeUpFinishedNotificationWhenReady);
            pw.println("  mSendGoToSleepFinishedNotificationWhenReady="
                    + mSendGoToSleepFinishedNotificationWhenReady);
            pw.println("  mLastUserActivityTime=" + TimeUtils.formatUptime(mLastUserActivityTime));
            pw.println("  mLastUserActivityTimeNoChangeLights="
                    + TimeUtils.formatUptime(mLastUserActivityTimeNoChangeLights));
            pw.println("  mDisplayReady=" + mDisplayReady);
            pw.println("  mHoldingWakeLockSuspendBlocker=" + mHoldingWakeLockSuspendBlocker);
            pw.println("  mHoldingDisplaySuspendBlocker=" + mHoldingDisplaySuspendBlocker);

            pw.println();
            pw.println("Settings and Configuration:");
            pw.println("  mDecoupleAutoSuspendModeFromDisplayConfig="
                    + mDecoupleAutoSuspendModeFromDisplayConfig);
            pw.println("  mDecoupleInteractiveModeFromDisplayConfig="
                    + mDecoupleInteractiveModeFromDisplayConfig);
            pw.println("  mWakeUpWhenPluggedOrUnpluggedConfig="
                    + mWakeUpWhenPluggedOrUnpluggedConfig);
            pw.println("  mSuspendWhenScreenOffDueToProximityConfig="
                    + mSuspendWhenScreenOffDueToProximityConfig);
            pw.println("  mDreamsSupportedConfig=" + mDreamsSupportedConfig);
            pw.println("  mDreamsEnabledByDefaultConfig=" + mDreamsEnabledByDefaultConfig);
            pw.println("  mDreamsActivatedOnSleepByDefaultConfig="
                    + mDreamsActivatedOnSleepByDefaultConfig);
            pw.println("  mDreamsActivatedOnDockByDefaultConfig="
                    + mDreamsActivatedOnDockByDefaultConfig);
            pw.println("  mDreamsEnabledOnBatteryConfig="
                    + mDreamsEnabledOnBatteryConfig);
            pw.println("  mDreamsBatteryLevelMinimumWhenPoweredConfig="
                    + mDreamsBatteryLevelMinimumWhenPoweredConfig);
            pw.println("  mDreamsBatteryLevelMinimumWhenNotPoweredConfig="
                    + mDreamsBatteryLevelMinimumWhenNotPoweredConfig);
            pw.println("  mDreamsBatteryLevelDrainCutoffConfig="
                    + mDreamsBatteryLevelDrainCutoffConfig);
            pw.println("  mDreamsEnabledSetting=" + mDreamsEnabledSetting);
            pw.println("  mDreamsActivateOnSleepSetting=" + mDreamsActivateOnSleepSetting);
            pw.println("  mDreamsActivateOnDockSetting=" + mDreamsActivateOnDockSetting);
            pw.println("  mScreenOffTimeoutSetting=" + mScreenOffTimeoutSetting);
            pw.println("  mMaximumScreenOffTimeoutFromDeviceAdmin="
                    + mMaximumScreenOffTimeoutFromDeviceAdmin + " (enforced="
                    + isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked() + ")");
            pw.println("  mStayOnWhilePluggedInSetting=" + mStayOnWhilePluggedInSetting);
            pw.println("  mScreenBrightnessSetting=" + mScreenBrightnessSetting);
            pw.println("  mScreenAutoBrightnessAdjustmentSetting="
                    + mScreenAutoBrightnessAdjustmentSetting);
            pw.println("  mScreenBrightnessModeSetting=" + mScreenBrightnessModeSetting);
            pw.println("  mScreenBrightnessOverrideFromWindowManager="
                    + mScreenBrightnessOverrideFromWindowManager);
            pw.println("  mUserActivityTimeoutOverrideFromWindowManager="
                    + mUserActivityTimeoutOverrideFromWindowManager);
            pw.println("  mTemporaryScreenBrightnessSettingOverride="
                    + mTemporaryScreenBrightnessSettingOverride);
            pw.println("  mTemporaryScreenAutoBrightnessAdjustmentSettingOverride="
                    + mTemporaryScreenAutoBrightnessAdjustmentSettingOverride);
            pw.println("  mScreenBrightnessSettingMinimum=" + mScreenBrightnessSettingMinimum);
            pw.println("  mScreenBrightnessSettingMaximum=" + mScreenBrightnessSettingMaximum);
            pw.println("  mScreenBrightnessSettingDefault=" + mScreenBrightnessSettingDefault);

            final int screenOffTimeout = getScreenOffTimeoutLocked();
            final int screenDimDuration = getScreenDimDurationLocked(screenOffTimeout);
            pw.println();
            pw.println("Screen off timeout: " + screenOffTimeout + " ms");
            pw.println("Screen dim duration: " + screenDimDuration + " ms");

            pw.println();
            pw.println("Wake Locks: size=" + mWakeLocks.size());
            for (WakeLock wl : mWakeLocks) {
                pw.println("  " + wl);
            }

            pw.println();
            pw.println("Suspend Blockers: size=" + mSuspendBlockers.size());
            for (SuspendBlocker sb : mSuspendBlockers) {
                pw.println("  " + sb);
            }

            pw.println();
            pw.println("Screen On Blocker: " + mScreenOnBlocker);

            pw.println();
            pw.println("Display Blanker: " + mDisplayBlanker);

            dpc = mDisplayPowerController;
            wcd = mWirelessChargerDetector;
        }

        if (dpc != null) {
            dpc.dump(pw);
        }

        if (wcd != null) {
            wcd.dump(pw);
        }
    }

    private SuspendBlocker createSuspendBlockerLocked(String name) {
        SuspendBlocker suspendBlocker = new SuspendBlockerImpl(name);
        mSuspendBlockers.add(suspendBlocker);
        return suspendBlocker;
    }

    private static String wakefulnessToString(int wakefulness) {
        switch (wakefulness) {
            case WAKEFULNESS_ASLEEP:
                return "Asleep";
            case WAKEFULNESS_AWAKE:
                return "Awake";
            case WAKEFULNESS_DREAMING:
                return "Dreaming";
            case WAKEFULNESS_DOZING:
                return "Dozing";
            default:
                return Integer.toString(wakefulness);
        }
    }

    private static WorkSource copyWorkSource(WorkSource workSource) {
        return workSource != null ? new WorkSource(workSource) : null;
    }

    private final class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleBatteryStateChangedLocked();
            }
        }
    }

    private final class BootCompletedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // This is our early signal that the system thinks it has finished booting.
            // However, the boot animation may still be running for a few more seconds
            // since it is ultimately in charge of when it terminates.
            // Defer transitioning into the boot completed state until the animation exits.
            // We do this so that the screen does not start to dim prematurely before
            // the user has actually had a chance to interact with the device.
            startWatchingForBootAnimationFinished();
        }
    }

    private final class DreamReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                scheduleSandmanLocked();
            }
        }
    }

    private final class UserSwitchedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    private final class DockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                if (mDockState != dockState) {
                    mDockState = dockState;
                    mDirty |= DIRTY_DOCK_STATE;
                    updatePowerStateLocked();
                }
            }
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                handleSettingsChangedLocked();
            }
        }
    }

    /**
     * Handler for asynchronous operations performed by the power manager.
     */
    private final class PowerManagerHandler extends Handler {
        public PowerManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_USER_ACTIVITY_TIMEOUT:
                    handleUserActivityTimeout();
                    break;
                case MSG_SANDMAN:
                    handleSandman();
                    break;
                case MSG_SCREEN_ON_BLOCKER_RELEASED:
                    handleScreenOnBlockerReleased();
                    break;
                case MSG_CHECK_IF_BOOT_ANIMATION_FINISHED:
                    checkIfBootAnimationFinished();
                    break;
            }
        }
    }

    /**
     * Represents a wake lock that has been acquired by an application.
     */
    private final class WakeLock implements IBinder.DeathRecipient {
        public final IBinder mLock;
        public int mFlags;
        public String mTag;
        public final String mPackageName;
        public WorkSource mWorkSource;
        public final int mOwnerUid;
        public final int mOwnerPid;
        public boolean mNotifiedAcquired;

        public WakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource workSource, int ownerUid, int ownerPid) {
            mLock = lock;
            mFlags = flags;
            mTag = tag;
            mPackageName = packageName;
            mWorkSource = copyWorkSource(workSource);
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
        }

        @Override
        public void binderDied() {
            PowerManagerService.this.handleWakeLockDeath(this);
        }

        public boolean hasSameProperties(int flags, String tag, WorkSource workSource,
                int ownerUid, int ownerPid) {
            return mFlags == flags
                    && mTag.equals(tag)
                    && hasSameWorkSource(workSource)
                    && mOwnerUid == ownerUid
                    && mOwnerPid == ownerPid;
        }

        public void updateProperties(int flags, String tag, String packageName,
                WorkSource workSource, int ownerUid, int ownerPid) {
            if (!mPackageName.equals(packageName)) {
                throw new IllegalStateException("Existing wake lock package name changed: "
                        + mPackageName + " to " + packageName);
            }
            if (mOwnerUid != ownerUid) {
                throw new IllegalStateException("Existing wake lock uid changed: "
                        + mOwnerUid + " to " + ownerUid);
            }
            if (mOwnerPid != ownerPid) {
                throw new IllegalStateException("Existing wake lock pid changed: "
                        + mOwnerPid + " to " + ownerPid);
            }
            mFlags = flags;
            mTag = tag;
            updateWorkSource(workSource);
        }

        public boolean hasSameWorkSource(WorkSource workSource) {
            return Objects.equal(mWorkSource, workSource);
        }

        public void updateWorkSource(WorkSource workSource) {
            mWorkSource = copyWorkSource(workSource);
        }

        @Override
        public String toString() {
            return getLockLevelString()
                    + " '" + mTag + "'" + getLockFlagsString()
                    + " (uid=" + mOwnerUid + ", pid=" + mOwnerPid + ", ws=" + mWorkSource + ")";
        }

        @SuppressWarnings("deprecation")
        private String getLockLevelString() {
            switch (mFlags & PowerManager.WAKE_LOCK_LEVEL_MASK) {
                case PowerManager.FULL_WAKE_LOCK:
                    return "FULL_WAKE_LOCK                ";
                case PowerManager.SCREEN_BRIGHT_WAKE_LOCK:
                    return "SCREEN_BRIGHT_WAKE_LOCK       ";
                case PowerManager.SCREEN_DIM_WAKE_LOCK:
                    return "SCREEN_DIM_WAKE_LOCK          ";
                case PowerManager.PARTIAL_WAKE_LOCK:
                    return "PARTIAL_WAKE_LOCK             ";
                case PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK:
                    return "PROXIMITY_SCREEN_OFF_WAKE_LOCK";
                case PowerManager.DOZE_WAKE_LOCK:
                    return "DOZE_WAKE_LOCK                ";
                default:
                    return "???                           ";
            }
        }

        private String getLockFlagsString() {
            String result = "";
            if ((mFlags & PowerManager.ACQUIRE_CAUSES_WAKEUP) != 0) {
                result += " ACQUIRE_CAUSES_WAKEUP";
            }
            if ((mFlags & PowerManager.ON_AFTER_RELEASE) != 0) {
                result += " ON_AFTER_RELEASE";
            }
            return result;
        }
    }

    private final class SuspendBlockerImpl implements SuspendBlocker {
        private final String mName;
        private int mReferenceCount;

        public SuspendBlockerImpl(String name) {
            mName = name;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (mReferenceCount != 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was finalized without being released!");
                    mReferenceCount = 0;
                    nativeReleaseSuspendBlocker(mName);
                }
            } finally {
                super.finalize();
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mReferenceCount += 1;
                if (mReferenceCount == 1) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Acquiring suspend blocker \"" + mName + "\".");
                    }
                    nativeAcquireSuspendBlocker(mName);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mReferenceCount -= 1;
                if (mReferenceCount == 0) {
                    if (DEBUG_SPEW) {
                        Slog.d(TAG, "Releasing suspend blocker \"" + mName + "\".");
                    }
                    nativeReleaseSuspendBlocker(mName);
                } else if (mReferenceCount < 0) {
                    Log.wtf(TAG, "Suspend blocker \"" + mName
                            + "\" was released without being acquired!", new Throwable());
                    mReferenceCount = 0;
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return mName + ": ref count=" + mReferenceCount;
            }
        }
    }

    private final class ScreenOnBlockerImpl implements ScreenOnBlocker {
        private int mNestCount;

        public boolean isHeld() {
            synchronized (this) {
                return mNestCount != 0;
            }
        }

        @Override
        public void acquire() {
            synchronized (this) {
                mNestCount += 1;
                if (DEBUG) {
                    Slog.d(TAG, "Screen on blocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public void release() {
            synchronized (this) {
                mNestCount -= 1;
                if (mNestCount < 0) {
                    Log.wtf(TAG, "Screen on blocker was released without being acquired!",
                            new Throwable());
                    mNestCount = 0;
                }
                if (mNestCount == 0) {
                    mHandler.sendEmptyMessage(MSG_SCREEN_ON_BLOCKER_RELEASED);
                }
                if (DEBUG) {
                    Slog.d(TAG, "Screen on unblocked: mNestCount=" + mNestCount);
                }
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "held=" + (mNestCount != 0) + ", mNestCount=" + mNestCount;
            }
        }
    }

    private final class DisplayBlankerImpl implements DisplayBlanker {
        private boolean mBlanked;

        @Override
        public void blankAllDisplays() {
            synchronized (this) {
                mBlanked = true;
                mDisplayManagerInternal.blankAllDisplaysFromPowerManager();
                if (!mDecoupleInteractiveModeFromDisplayConfig) {
                    setInteractiveModeLocked(false);
                }
                if (!mDecoupleAutoSuspendModeFromDisplayConfig) {
                    setAutoSuspendModeLocked(true);
                }
            }
        }

        @Override
        public void unblankAllDisplays() {
            synchronized (this) {
                if (!mDecoupleAutoSuspendModeFromDisplayConfig) {
                    setAutoSuspendModeLocked(false);
                }
                if (!mDecoupleInteractiveModeFromDisplayConfig) {
                    setInteractiveModeLocked(true);
                }
                mDisplayManagerInternal.unblankAllDisplaysFromPowerManager();
                mBlanked = false;
            }
        }

        @Override
        public String toString() {
            synchronized (this) {
                return "blanked=" + mBlanked;
            }
        }
    }

    private final class BinderService extends IPowerManager.Stub {
        @Override // Binder call
        public void acquireWakeLockWithUid(IBinder lock, int flags, String tag,
                String packageName, int uid) {
            acquireWakeLock(lock, flags, tag, packageName, new WorkSource(uid));
        }

        @Override // Binder call
        public void acquireWakeLock(IBinder lock, int flags, String tag, String packageName,
                WorkSource ws) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }
            if (packageName == null) {
                throw new IllegalArgumentException("packageName must not be null");
            }
            PowerManager.validateWakeLockParameters(flags, tag);

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
            if (ws != null && ws.size() != 0) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS, null);
            } else {
                ws = null;
            }

            final int uid = Binder.getCallingUid();
            final int pid = Binder.getCallingPid();
            final long ident = Binder.clearCallingIdentity();
            try {
                acquireWakeLockInternal(lock, flags, tag, packageName, ws, uid, pid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void releaseWakeLock(IBinder lock, int flags) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                releaseWakeLockInternal(lock, flags);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void updateWakeLockUids(IBinder lock, int[] uids) {
            WorkSource ws = null;

            if (uids != null) {
                ws = new WorkSource();
                // XXX should WorkSource have a way to set uids as an int[] instead of adding them
                // one at a time?
                for (int i = 0; i < uids.length; i++) {
                    ws.add(uids[i]);
                }
            }
            updateWakeLockWorkSource(lock, ws);
        }

        @Override // Binder call
        public void updateWakeLockWorkSource(IBinder lock, WorkSource ws) {
            if (lock == null) {
                throw new IllegalArgumentException("lock must not be null");
            }

            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
            if (ws != null && ws.size() != 0) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.UPDATE_DEVICE_STATS, null);
            } else {
                ws = null;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                updateWakeLockWorkSourceInternal(lock, ws);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isWakeLockLevelSupported(int level) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isWakeLockLevelSupportedInternal(level);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void userActivity(long eventTime, int event, int flags) {
            final long now = SystemClock.uptimeMillis();
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DEVICE_POWER)
                    != PackageManager.PERMISSION_GRANTED) {
                // Once upon a time applications could call userActivity().
                // Now we require the DEVICE_POWER permission.  Log a warning and ignore the
                // request instead of throwing a SecurityException so we don't break old apps.
                synchronized (mLock) {
                    if (now >= mLastWarningAboutUserActivityPermission + (5 * 60 * 1000)) {
                        mLastWarningAboutUserActivityPermission = now;
                        Slog.w(TAG, "Ignoring call to PowerManager.userActivity() because the "
                                + "caller does not have DEVICE_POWER permission.  "
                                + "Please fix your app!  "
                                + " pid=" + Binder.getCallingPid()
                                + " uid=" + Binder.getCallingUid());
                    }
                }
                return;
            }

            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            final int uid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                userActivityInternal(eventTime, event, flags, uid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void wakeUp(long eventTime) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                wakeUpInternal(eventTime);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void goToSleep(long eventTime, int reason) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                goToSleepInternal(eventTime, reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void nap(long eventTime) {
            if (eventTime > SystemClock.uptimeMillis()) {
                throw new IllegalArgumentException("event time must not be in the future");
            }

            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                napInternal(eventTime);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isScreenOn() {
            final long ident = Binder.clearCallingIdentity();
            try {
                return isScreenOnInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Reboots the device.
         *
         * @param confirm If true, shows a reboot confirmation dialog.
         * @param reason The reason for the reboot, or null if none.
         * @param wait If true, this call waits for the reboot to complete and does not return.
         */
        @Override // Binder call
        public void reboot(boolean confirm, String reason, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);
            if (PowerManager.REBOOT_RECOVERY.equals(reason)) {
                mContext.enforceCallingOrSelfPermission(android.Manifest.permission.RECOVERY, null);
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(false, confirm, reason, wait);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Shuts down the device.
         *
         * @param confirm If true, shows a shutdown confirmation dialog.
         * @param wait If true, this call waits for the shutdown to complete and does not return.
         */
        @Override // Binder call
        public void shutdown(boolean confirm, boolean wait) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                shutdownOrRebootInternal(true, confirm, null, wait);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Crash the runtime (causing a complete restart of the Android framework).
         * Requires REBOOT permission.  Mostly for testing.  Should not return.
         */
        @Override // Binder call
        public void crash(String message) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.REBOOT, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                crashInternal(message);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Set the setting that determines whether the device stays on when plugged in.
         * The argument is a bit string, with each bit specifying a power source that,
         * when the device is connected to that source, causes the device to stay on.
         * See {@link android.os.BatteryManager} for the list of power sources that
         * can be specified. Current values include
         * {@link android.os.BatteryManager#BATTERY_PLUGGED_AC}
         * and {@link android.os.BatteryManager#BATTERY_PLUGGED_USB}
         *
         * Used by "adb shell svc power stayon ..."
         *
         * @param val an {@code int} containing the bits that specify which power sources
         * should cause the device to stay on.
         */
        @Override // Binder call
        public void setStayOnSetting(int val) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SETTINGS, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setStayOnSettingInternal(val);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by device administration to set the maximum screen off timeout.
         *
         * This method must only be called by the device administration policy manager.
         */
        @Override // Binder call
        public void setMaximumScreenOffTimeoutFromDeviceAdmin(int timeMs) {
            final long ident = Binder.clearCallingIdentity();
            try {
                setMaximumScreenOffTimeoutFromDeviceAdminInternal(timeMs);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by the settings application and brightness control widgets to
         * temporarily override the current screen brightness setting so that the
         * user can observe the effect of an intended settings change without applying
         * it immediately.
         *
         * The override will be canceled when the setting value is next updated.
         *
         * @param brightness The overridden brightness.
         *
         * @see android.provider.Settings.System#SCREEN_BRIGHTNESS
         */
        @Override // Binder call
        public void setTemporaryScreenBrightnessSettingOverride(int brightness) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setTemporaryScreenBrightnessSettingOverrideInternal(brightness);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by the settings application and brightness control widgets to
         * temporarily override the current screen auto-brightness adjustment setting so that the
         * user can observe the effect of an intended settings change without applying
         * it immediately.
         *
         * The override will be canceled when the setting value is next updated.
         *
         * @param adj The overridden brightness, or Float.NaN to disable the override.
         *
         * @see Settings.System#SCREEN_AUTO_BRIGHTNESS_ADJ
         */
        @Override // Binder call
        public void setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(float adj) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setTemporaryScreenAutoBrightnessAdjustmentSettingOverrideInternal(adj);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by the phone application to make the attention LED flash when ringing.
         */
        @Override // Binder call
        public void setAttentionLight(boolean on, int color) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setAttentionLightInternal(on, color);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump PowerManager from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private final class LocalService extends PowerManagerInternal {
        /**
         * Used by the window manager to override the screen brightness based on the
         * current foreground activity.
         *
         * This method must only be called by the window manager.
         *
         * @param brightness The overridden brightness, or -1 to disable the override.
         */
        @Override
        public void setScreenBrightnessOverrideFromWindowManager(int brightness) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setScreenBrightnessOverrideFromWindowManagerInternal(brightness);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * Used by the window manager to override the button brightness based on the
         * current foreground activity.
         *
         * This method must only be called by the window manager.
         *
         * @param brightness The overridden brightness, or -1 to disable the override.
         */
        @Override
        public void setButtonBrightnessOverrideFromWindowManager(int brightness) {
            // Do nothing.
            // Button lights are not currently supported in the new implementation.
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);
        }

        /**
         * Used by the window manager to override the user activity timeout based on the
         * current foreground activity.  It can only be used to make the timeout shorter
         * than usual, not longer.
         *
         * This method must only be called by the window manager.
         *
         * @param timeoutMillis The overridden timeout, or -1 to disable the override.
         */
        @Override
        public void setUserActivityTimeoutOverrideFromWindowManager(long timeoutMillis) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.DEVICE_POWER, null);

            final long ident = Binder.clearCallingIdentity();
            try {
                setUserActivityTimeoutOverrideFromWindowManagerInternal(timeoutMillis);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setPolicy(WindowManagerPolicy policy) {
            PowerManagerService.this.setPolicy(policy);
        }
    }
}
