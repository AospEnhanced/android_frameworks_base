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

package com.android.systemui.shade;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.TestScopeProvider;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.data.repository.ShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarStateControllerImpl;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.KeyguardBottomAreaView;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.user.domain.interactor.UserInteractor;
import com.android.systemui.util.kotlin.JavaAdapter;

import dagger.Lazy;

import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import kotlinx.coroutines.test.TestScope;

public class QuickSettingsControllerBaseTest extends SysuiTestCase {
    protected static final float QS_FRAME_START_X = 0f;
    protected static final int QS_FRAME_WIDTH = 1000;
    protected static final int QS_FRAME_TOP = 0;
    protected static final int QS_FRAME_BOTTOM = 1000;
    protected static final int DEFAULT_HEIGHT = 1000;
    // In split shade min = max
    protected static final int DEFAULT_MIN_HEIGHT_SPLIT_SHADE = DEFAULT_HEIGHT;
    protected static final int DEFAULT_MIN_HEIGHT = 300;

    protected QuickSettingsController mQsController;

    protected TestScope mTestScope = TestScopeProvider.getTestScope();

    @Mock
    protected Resources mResources;
    @Mock protected KeyguardBottomAreaView mQsFrame;
    @Mock protected KeyguardStatusBarView mKeyguardStatusBar;
    @Mock protected QS mQs;
    @Mock protected QSFragment mQSFragment;
    @Mock protected Lazy<NotificationPanelViewController> mPanelViewControllerLazy;
    @Mock protected NotificationPanelViewController mNotificationPanelViewController;
    @Mock protected NotificationPanelView mPanelView;
    @Mock protected ViewGroup mQsHeader;
    @Mock protected ViewParent mPanelViewParent;
    @Mock protected QsFrameTranslateController mQsFrameTranslateController;
    @Mock protected ShadeTransitionController mShadeTransitionController;
    @Mock protected PulseExpansionHandler mPulseExpansionHandler;
    @Mock protected NotificationRemoteInputManager mNotificationRemoteInputManager;
    @Mock protected StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock protected LightBarController mLightBarController;
    @Mock protected NotificationStackScrollLayoutController
            mNotificationStackScrollLayoutController;
    @Mock protected LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Mock protected NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock protected ShadeHeaderController mShadeHeaderController;
    @Mock protected StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    @Mock protected KeyguardStateController mKeyguardStateController;
    @Mock protected KeyguardBypassController mKeyguardBypassController;
    @Mock protected KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock protected ScrimController mScrimController;
    @Mock protected MediaDataManager mMediaDataManager;
    @Mock protected MediaHierarchyManager mMediaHierarchyManager;
    @Mock protected AmbientState mAmbientState;
    @Mock protected RecordingController mRecordingController;
    @Mock protected FalsingManager mFalsingManager;
    @Mock protected FalsingCollector mFalsingCollector;
    @Mock protected AccessibilityManager mAccessibilityManager;
    @Mock protected LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock protected MetricsLogger mMetricsLogger;
    @Mock protected FeatureFlags mFeatureFlags;
    @Mock protected InteractionJankMonitor mInteractionJankMonitor;
    @Mock protected ShadeLogger mShadeLogger;
    @Mock protected DumpManager mDumpManager;
    @Mock protected UiEventLogger mUiEventLogger;
    @Mock protected CastController mCastController;
    @Mock protected DeviceProvisionedController mDeviceProvisionedController;
    @Mock protected UserInteractor mUserInteractor;
    @Mock protected TunerService mTunerService;
    protected FakeDisableFlagsRepository mDisableFlagsRepository =
            new FakeDisableFlagsRepository();
    protected FakeKeyguardRepository mKeyguardRepository = new FakeKeyguardRepository();

    protected SysuiStatusBarStateController mStatusBarStateController;
    protected ShadeInteractor mShadeInteractor;

    protected Handler mMainHandler;
    protected LockscreenShadeTransitionController.Callback mLockscreenShadeTransitionCallback;

    protected final ShadeExpansionStateManager mShadeExpansionStateManager =
            new ShadeExpansionStateManager();

    protected FragmentHostManager.FragmentListener mFragmentListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mPanelViewControllerLazy.get()).thenReturn(mNotificationPanelViewController);
        mStatusBarStateController = new StatusBarStateControllerImpl(mUiEventLogger, mDumpManager,
                mInteractionJankMonitor, mShadeExpansionStateManager);

        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        mShadeInteractor =
                new ShadeInteractor(
                        mTestScope.getBackgroundScope(),
                        mDisableFlagsRepository,
                        mKeyguardRepository,
                        new FakeUserSetupRepository(),
                        mDeviceProvisionedController,
                        mUserInteractor
                );

        KeyguardStatusView keyguardStatusView = new KeyguardStatusView(mContext);
        keyguardStatusView.setId(R.id.keyguard_status_view);

        when(mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance)).thenReturn(DEFAULT_HEIGHT);
        when(mPanelView.getResources()).thenReturn(mResources);
        when(mPanelView.getContext()).thenReturn(getContext());
        when(mPanelView.findViewById(R.id.keyguard_header)).thenReturn(mKeyguardStatusBar);
        when(mNotificationStackScrollLayoutController.getHeight()).thenReturn(1000);
        when(mPanelView.findViewById(R.id.qs_frame)).thenReturn(mQsFrame);
        when(mQsFrame.getX()).thenReturn(QS_FRAME_START_X);
        when(mQsFrame.getWidth()).thenReturn(QS_FRAME_WIDTH);
        when(mQsHeader.getTop()).thenReturn(QS_FRAME_TOP);
        when(mQsHeader.getBottom()).thenReturn(QS_FRAME_BOTTOM);
        when(mPanelView.getY()).thenReturn((float) QS_FRAME_TOP);
        when(mPanelView.getHeight()).thenReturn(QS_FRAME_BOTTOM);
        when(mPanelView.findViewById(R.id.keyguard_status_view))
                .thenReturn(mock(KeyguardStatusView.class));
        when(mQs.getView()).thenReturn(mPanelView);
        when(mQSFragment.getView()).thenReturn(mPanelView);

        when(mNotificationRemoteInputManager.isRemoteInputActive())
                .thenReturn(false);
        when(mInteractionJankMonitor.begin(any(), anyInt()))
                .thenReturn(true);
        when(mInteractionJankMonitor.end(anyInt()))
                .thenReturn(true);

        when(mPanelView.getParent()).thenReturn(mPanelViewParent);
        when(mQs.getHeader()).thenReturn(mQsHeader);

        doAnswer(invocation -> {
            mLockscreenShadeTransitionCallback = invocation.getArgument(0);
            return null;
        }).when(mLockscreenShadeTransitionController).addCallback(any());


        mMainHandler = new Handler(Looper.getMainLooper());

        mQsController = new QuickSettingsController(
                mPanelViewControllerLazy,
                mPanelView,
                mQsFrameTranslateController,
                mShadeTransitionController,
                mPulseExpansionHandler,
                mNotificationRemoteInputManager,
                mShadeExpansionStateManager,
                mStatusBarKeyguardViewManager,
                mLightBarController,
                mNotificationStackScrollLayoutController,
                mLockscreenShadeTransitionController,
                mNotificationShadeDepthController,
                mShadeHeaderController,
                mStatusBarTouchableRegionManager,
                mKeyguardStateController,
                mKeyguardBypassController,
                mKeyguardUpdateMonitor,
                mScrimController,
                mMediaDataManager,
                mMediaHierarchyManager,
                mAmbientState,
                mRecordingController,
                mFalsingManager,
                mFalsingCollector,
                mAccessibilityManager,
                mLockscreenGestureLogger,
                mMetricsLogger,
                mFeatureFlags,
                mInteractionJankMonitor,
                mShadeLogger,
                mDumpManager,
                mock(KeyguardFaceAuthInteractor.class),
                mock(ShadeRepository.class),
                mShadeInteractor,
                new JavaAdapter(mTestScope.getBackgroundScope()),
                mCastController,
                mTunerService
        );
        mQsController.init();

        mFragmentListener = mQsController.getQsFragmentListener();
    }

    @After
    public void tearDown() {
        mMainHandler.removeCallbacksAndMessages(null);
    }
}
