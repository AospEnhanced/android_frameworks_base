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

package com.android.systemui.globalactions;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Handler;
import android.os.UserManager;
import android.service.dreams.IDreamManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManagerPolicyConstants;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.widget.LockPatternUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class GlobalActionsDialogLiteTest extends SysuiTestCase {
    private GlobalActionsDialogLite mGlobalActionsDialogLite;

    @Mock private GlobalActions.GlobalActionsManager mWindowManagerFuncs;
    @Mock private AudioManager mAudioManager;
    @Mock private IDreamManager mDreamManager;
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private BroadcastDispatcher mBroadcastDispatcher;
    @Mock private TelephonyListenerManager mTelephonyListenerManager;
    @Mock private GlobalSettings mGlobalSettings;
    @Mock private SecureSettings mSecureSettings;
    @Mock private Resources mResources;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private UserManager mUserManager;
    @Mock private TrustManager mTrustManager;
    @Mock private IActivityManager mActivityManager;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private NotificationShadeDepthController mDepthController;
    @Mock private SysuiColorExtractor mColorExtractor;
    @Mock private IStatusBarService mStatusBarService;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private IWindowManager mWindowManager;
    @Mock private Executor mBackgroundExecutor;
    @Mock private UiEventLogger mUiEventLogger;
    @Mock private RingerModeTracker mRingerModeTracker;
    @Mock private RingerModeLiveData mRingerModeLiveData;
    @Mock private SysUiState mSysUiState;
    @Mock private Handler mHandler;
    @Mock private UserContextProvider mUserContextProvider;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mUserContextProvider.getUserContext()).thenReturn(mContext);

        mGlobalActionsDialogLite = new GlobalActionsDialogLite(mContext,
                mWindowManagerFuncs,
                mAudioManager,
                mDreamManager,
                mDevicePolicyManager,
                mLockPatternUtils,
                mBroadcastDispatcher,
                mTelephonyListenerManager,
                mGlobalSettings,
                mSecureSettings,
                null,
                mResources,
                mConfigurationController,
                mKeyguardStateController,
                mUserManager,
                mTrustManager,
                mActivityManager,
                null,
                mMetricsLogger,
                mDepthController,
                mColorExtractor,
                mStatusBarService,
                mNotificationShadeWindowController,
                mWindowManager,
                mBackgroundExecutor,
                mUiEventLogger,
                mRingerModeTracker,
                mSysUiState,
                mHandler
        );
        mGlobalActionsDialogLite.setZeroDialogPressDelayForTesting();

        ColorExtractor.GradientColors backdropColors = new ColorExtractor.GradientColors();
        backdropColors.setMainColor(Color.BLACK);
        when(mColorExtractor.getNeutralColors()).thenReturn(backdropColors);
        when(mSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mSysUiState);
    }

    @Test
    public void testShouldLogShow() {
        mGlobalActionsDialogLite.onShow(null);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_POWER_MENU_OPEN);
    }

    @Test
    public void testShouldLogDismiss() {
        mGlobalActionsDialogLite.onDismiss(mGlobalActionsDialogLite.mDialog);
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_POWER_MENU_CLOSE);
    }

    @Test
    public void testShouldLogClose_backButton() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();
        dialog.onBackPressed();
        mTestableLooper.processAllMessages();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_CLOSE_BACK);
    }

    @Test
    public void testShouldLogOnTapOutside() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        GlobalActionsDialogLite.ActionsDialogLite dialog = mGlobalActionsDialogLite.createDialog();
        View container = dialog.findViewById(com.android.systemui.R.id.global_actions_container);
        container.callOnClick();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_CLOSE_TAP_OUTSIDE);
    }

    @Test
    public void testShouldLogBugreportPress() throws InterruptedException {
        GlobalActionsDialog.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        bugReportAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_BUGREPORT_PRESS);
    }

    @Test
    public void testShouldLogBugreportLongPress() {
        GlobalActionsDialog.BugReportAction bugReportAction =
                mGlobalActionsDialogLite.makeBugReportActionForTesting();
        bugReportAction.onLongPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_BUGREPORT_LONG_PRESS);
    }

    @Test
    public void testShouldLogEmergencyDialerPress() {
        GlobalActionsDialog.EmergencyDialerAction emergencyDialerAction =
                mGlobalActionsDialogLite.makeEmergencyDialerActionForTesting();
        emergencyDialerAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_EMERGENCY_DIALER_PRESS);
    }

    @Test
    public void testShouldLogScreenshotPress() {
        GlobalActionsDialog.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        screenshotAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_SCREENSHOT_PRESS);
    }

    @Test
    public void testShouldShowScreenshot() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_navBarInteractionMode,
                WindowManagerPolicyConstants.NAV_BAR_MODE_2BUTTON);

        GlobalActionsDialog.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        assertThat(screenshotAction.shouldShow()).isTrue();
    }

    @Test
    public void testShouldNotShowScreenshot() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.integer.config_navBarInteractionMode,
                WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON);

        GlobalActionsDialog.ScreenshotAction screenshotAction =
                mGlobalActionsDialogLite.makeScreenshotActionForTesting();
        assertThat(screenshotAction.shouldShow()).isFalse();
    }

    private void verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent event) {
        mTestableLooper.processAllMessages();
        verify(mUiEventLogger, times(1))
                .log(event);
    }

    @SafeVarargs
    private static <T> void assertItemsOfType(List<T> stuff, Class<? extends T>... classes) {
        assertThat(stuff).hasSize(classes.length);
        for (int i = 0; i < stuff.size(); i++) {
            assertThat(stuff.get(i)).isInstanceOf(classes[i]);
        }
    }

    @Test
    public void testCreateActionItems_lockdownEnabled_doesShowLockdown() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        doReturn(true).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        doReturn(true).when(mGlobalActionsDialogLite).shouldShowAction(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialog.EmergencyAction.class,
                GlobalActionsDialog.LockDownAction.class,
                GlobalActionsDialog.ShutDownAction.class,
                GlobalActionsDialog.RestartAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testCreateActionItems_lockdownDisabled_doesNotShowLockdown() {
        mGlobalActionsDialogLite = spy(mGlobalActionsDialogLite);
        doReturn(4).when(mGlobalActionsDialogLite).getMaxShownPowerItems();
        // make sure lockdown action will NOT be shown
        doReturn(false).when(mGlobalActionsDialogLite).shouldDisplayLockdown(any());
        String[] actions = {
                GlobalActionsDialog.GLOBAL_ACTION_KEY_EMERGENCY,
                // lockdown action not allowed
                GlobalActionsDialog.GLOBAL_ACTION_KEY_LOCKDOWN,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_POWER,
                GlobalActionsDialog.GLOBAL_ACTION_KEY_RESTART,
        };
        doReturn(actions).when(mGlobalActionsDialogLite).getDefaultActions();
        mGlobalActionsDialogLite.createActionItems();

        assertItemsOfType(mGlobalActionsDialogLite.mItems,
                GlobalActionsDialog.EmergencyAction.class,
                GlobalActionsDialog.ShutDownAction.class,
                GlobalActionsDialog.RestartAction.class);
        assertThat(mGlobalActionsDialogLite.mOverflowItems).isEmpty();
        assertThat(mGlobalActionsDialogLite.mPowerItems).isEmpty();
    }

    @Test
    public void testShouldLogLockdownPress() {
        GlobalActionsDialogLite.LockDownAction lockDownAction =
                mGlobalActionsDialogLite.new LockDownAction();
        lockDownAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_LOCKDOWN_PRESS);
    }

    @Test
    public void testShouldLogShutdownPress() {
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                mGlobalActionsDialogLite.new ShutDownAction();
        shutDownAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_SHUTDOWN_PRESS);
    }

    @Test
    public void testShouldLogShutdownLongPress() {
        GlobalActionsDialogLite.ShutDownAction shutDownAction =
                mGlobalActionsDialogLite.new ShutDownAction();
        shutDownAction.onLongPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_SHUTDOWN_LONG_PRESS);
    }

    @Test
    public void testShouldLogRebootPress() {
        GlobalActionsDialogLite.RestartAction restartAction =
                mGlobalActionsDialogLite.new RestartAction();
        restartAction.onPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_REBOOT_PRESS);
    }

    @Test
    public void testShouldLogRebootLongPress() {
        GlobalActionsDialogLite.RestartAction restartAction =
                mGlobalActionsDialogLite.new RestartAction();
        restartAction.onLongPress();
        verifyLogPosted(GlobalActionsDialog.GlobalActionsEvent.GA_REBOOT_LONG_PRESS);
    }
}
