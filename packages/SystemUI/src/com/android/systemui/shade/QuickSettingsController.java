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


package com.android.systemui.shade;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE;
import static com.android.systemui.classifier.Classifier.QS_COLLAPSE;
import static com.android.systemui.shade.NotificationPanelViewController.COUNTER_PANEL_OPEN_QS;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_COLLAPSE;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_EXPAND;
import static com.android.systemui.shade.NotificationPanelViewController.FLING_HIDE;
import static com.android.systemui.shade.NotificationPanelViewController.QS_PARALLAX_AMOUNT;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Fragment;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.keyguard.FaceAuthApiRequestReason;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.MediaHierarchyManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.shade.transition.ShadeTransitionController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.QsFrameTranslateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarTouchableRegionManager;
import com.android.systemui.statusbar.phone.dagger.CentralSurfacesComponent;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.LargeScreenUtils;

import javax.inject.Inject;

import dagger.Lazy;

/** Handles QuickSettings touch handling, expansion and animation state
 * TODO (b/264460656) make this dumpable
 */
@CentralSurfacesComponent.CentralSurfacesScope
public class QuickSettingsController {
    public static final String TAG = "QuickSettingsController";

    private QS mQs;
    private final Lazy<NotificationPanelViewController> mPanelViewControllerLazy;

    private final NotificationPanelView mPanelView;
    private final KeyguardStatusBarView mKeyguardStatusBar;
    private final FrameLayout mQsFrame;

    private final QsFrameTranslateController mQsFrameTranslateController;
    private final ShadeTransitionController mShadeTransitionController;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final NotificationShadeDepthController mDepthController;
    private final ShadeHeaderController mShadeHeaderController;
    private final StatusBarTouchableRegionManager mStatusBarTouchableRegionManager;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardBypassController mKeyguardBypassController;
    private final NotificationRemoteInputManager mRemoteInputManager;
    private VelocityTracker mQsVelocityTracker;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ScrimController mScrimController;
    private final MediaDataManager mMediaDataManager;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final AmbientState mAmbientState;
    private final RecordingController mRecordingController;
    private final FalsingCollector mFalsingCollector;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final ShadeLogger mShadeLog;
    private final FeatureFlags mFeatureFlags;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private final FalsingManager mFalsingManager;
    private final AccessibilityManager mAccessibilityManager;
    private final MetricsLogger mMetricsLogger;
    private final Resources mResources;

    /** Whether the notifications are displayed full width (no margins on the side). */
    private boolean mIsFullWidth;
    private int mTouchSlop;
    private float mSlopMultiplier;
    /** the current {@link StatusBarState} */
    private int mBarState;
    private int mStatusBarMinHeight;
    private boolean mScrimEnabled = true;
    private int mScrimCornerRadius;
    private int mScreenCornerRadius;
    private boolean mUseLargeScreenShadeHeader;
    private int mLargeScreenShadeHeaderHeight;
    private int mDisplayRightInset = 0; // in pixels
    private int mDisplayLeftInset = 0; // in pixels
    private boolean mSplitShadeEnabled;
    /**
     * The padding between the start of notifications and the qs boundary on the lockscreen.
     * On lockscreen, notifications aren't inset this extra amount, but we still want the
     * qs boundary to be padded.
     */
    private int mLockscreenNotificationPadding;
    private int mSplitShadeNotificationsScrimMarginBottom;
    private boolean mDozing;
    private boolean mEnableClipping;
    private int mFalsingThreshold;
    /**
     * Position of the qs bottom during the full shade transition. This is needed as the toppadding
     * can change during state changes, which makes it much harder to do animations
     */
    private int mTransitionToFullShadePosition;
    private boolean mCollapsedOnDown;
    private float mShadeExpandedHeight = 0;
    private boolean mLastShadeFlingWasExpanding;

    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    /** whether current touch Y delta is above falsing threshold */
    private boolean mTouchAboveFalsingThreshold;
    /** whether we are tracking a touch on QS container */
    private boolean mTracking;
    /** pointerId of the pointer we're currently tracking */
    private int mTrackingPointer;

    /**
     * Indicates that QS is in expanded state which can happen by:
     * - single pane shade: expanding shade and then expanding QS
     * - split shade: just expanding shade (QS are expanded automatically)
     */
    private boolean mExpanded;
    /** Indicates QS is at its max height */
    private boolean mFullyExpanded;
    /**
     * Determines if QS should be already expanded when expanding shade.
     * Used for split shade, two finger gesture as well as accessibility shortcut to QS.
     * It needs to be set when movement starts as it resets at the end of expansion/collapse.
     */
    private boolean mExpandImmediate;
    private boolean mExpandedWhenExpandingStarted;
    private boolean mAnimatingHiddenFromCollapsed;
    private boolean mVisible;
    private float mExpansionHeight;
    /**
     * QS height when QS expansion fraction is 0 so when QS is collapsed. That state doesn't really
     * exist for split shade so currently this value is always 0 then.
     */
    private int mMinExpansionHeight;
    /** QS height when QS expansion fraction is 1 so qs is fully expanded */
    private int mMaxExpansionHeight;
    /** Expansion fraction of the notification shade */
    private float mShadeExpandedFraction;
    private int mPeekHeight;
    private float mLastOverscroll;
    private boolean mExpansionFromOverscroll;
    private boolean mExpansionEnabledPolicy = true;
    private boolean mExpansionEnabledAmbient = true;
    private float mQuickQsHeaderHeight;
    /**
     * Determines if QS should be already expanded when expanding shade.
     * Used for split shade, two finger gesture as well as accessibility shortcut to QS.
     * It needs to be set when movement starts as it resets at the end of expansion/collapse.
     */
    private boolean mTwoFingerExpandPossible;
    /** Whether the ongoing gesture might both trigger the expansion in both the view and QS. */
    private boolean mConflictingExpansionGesture;
    /**
     * If we are in a panel collapsing motion, we reset scrollY of our scroll view but still
     * need to take this into account in our panel height calculation.
     */
    private boolean mAnimatorExpand;

    /**
     * The amount of progress we are currently in if we're transitioning to the full shade.
     * 0.0f means we're not transitioning yet, while 1 means we're all the way in the full
     * shade. This value can also go beyond 1.1 when we're overshooting!
     */
    private float mTransitioningToFullShadeProgress;
    /** Distance a full shade transition takes in order for qs to fully transition to the shade. */
    private int mDistanceForFullShadeTransition;
    private boolean mStackScrollerOverscrolling;
    /** Indicates QS is animating - set by flingQs */
    private boolean mAnimating;
    /** Whether the current animator is resetting the qs translation. */
    private boolean mIsTranslationResettingAnimator;
    /** Whether the current animator is resetting the pulse expansion after a drag down. */
    private boolean mIsPulseExpansionResettingAnimator;
    /** The translation amount for QS for the full shade transition. */
    private float mTranslationForFullShadeTransition;
    /** Should we animate the next bounds update. */
    private boolean mAnimateNextNotificationBounds;
    /** The delay for the next bounds animation. */
    private long mNotificationBoundsAnimationDelay;
    /** The duration of the notification bounds animation. */
    private long mNotificationBoundsAnimationDuration;

    private final Region mInterceptRegion = new Region();
    /** The end bounds of a clipping animation. */
    private final Rect mClippingAnimationEndBounds = new Rect();
    private final Rect mLastClipBounds = new Rect();

    /** The animator for the qs clipping bounds. */
    private ValueAnimator mClippingAnimator = null;
    /** The main animator for QS expansion */
    private ValueAnimator mExpansionAnimator;
    /** The animator for QS size change */
    private ValueAnimator mSizeChangeAnimator;

    private ExpansionHeightListener mExpansionHeightListener;
    private QsStateUpdateListener mQsStateUpdateListener;
    private ApplyClippingImmediatelyListener mApplyClippingImmediatelyListener;
    private FlingQsWithoutClickListener mFlingQsWithoutClickListener;
    private ExpansionHeightSetToMaxListener mExpansionHeightSetToMaxListener;
    private final QS.HeightListener mQsHeightListener = this::onHeightChanged;
    private final Runnable mQsCollapseExpandAction = this::collapseOrExpandQs;
    private final QS.ScrollListener mQsScrollListener = this::onScroll;

    @Inject
    public QuickSettingsController(
            Lazy<NotificationPanelViewController> panelViewControllerLazy,
            NotificationPanelView panelView,
            QsFrameTranslateController qsFrameTranslateController,
            ShadeTransitionController shadeTransitionController,
            PulseExpansionHandler pulseExpansionHandler,
            NotificationRemoteInputManager remoteInputManager,
            ShadeExpansionStateManager shadeExpansionStateManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            NotificationShadeDepthController notificationShadeDepthController,
            ShadeHeaderController shadeHeaderController,
            StatusBarTouchableRegionManager statusBarTouchableRegionManager,
            KeyguardStateController keyguardStateController,
            KeyguardBypassController keyguardBypassController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ScrimController scrimController,
            MediaDataManager mediaDataManager,
            MediaHierarchyManager mediaHierarchyManager,
            AmbientState ambientState,
            RecordingController recordingController,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            AccessibilityManager accessibilityManager,
            LockscreenGestureLogger lockscreenGestureLogger,
            MetricsLogger metricsLogger,
            FeatureFlags featureFlags,
            InteractionJankMonitor interactionJankMonitor,
            ShadeLogger shadeLog
    ) {
        mPanelViewControllerLazy = panelViewControllerLazy;
        mPanelView = panelView;
        mQsFrame = mPanelView.findViewById(R.id.qs_frame);
        mKeyguardStatusBar = mPanelView.findViewById(R.id.keyguard_header);
        mResources = mPanelView.getResources();
        mSplitShadeEnabled = LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        mQsFrameTranslateController = qsFrameTranslateController;
        mShadeTransitionController = shadeTransitionController;
        mPulseExpansionHandler = pulseExpansionHandler;
        pulseExpansionHandler.setPulseExpandAbortListener(() -> {
            if (mQs != null) {
                mQs.animateHeaderSlidingOut();
            }
        });
        mRemoteInputManager = remoteInputManager;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mDepthController = notificationShadeDepthController;
        mShadeHeaderController = shadeHeaderController;
        mStatusBarTouchableRegionManager = statusBarTouchableRegionManager;
        mKeyguardStateController = keyguardStateController;
        mKeyguardBypassController = keyguardBypassController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mScrimController = scrimController;
        mMediaDataManager = mediaDataManager;
        mMediaHierarchyManager = mediaHierarchyManager;
        mAmbientState = ambientState;
        mRecordingController = recordingController;
        mFalsingManager = falsingManager;
        mFalsingCollector = falsingCollector;
        mAccessibilityManager = accessibilityManager;

        mLockscreenGestureLogger = lockscreenGestureLogger;
        mMetricsLogger = metricsLogger;
        mShadeLog = shadeLog;
        mFeatureFlags = featureFlags;
        mInteractionJankMonitor = interactionJankMonitor;

        mShadeExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged);
        mLockscreenShadeTransitionController.addCallback(new LockscreenShadeTransitionCallback());
    }

    @VisibleForTesting
    void setQs(QS qs) {
        mQs = qs;
    }

    public void setExpansionHeightListener(ExpansionHeightListener listener) {
        mExpansionHeightListener = listener;
    }

    public void setQsStateUpdateListener(QsStateUpdateListener listener) {
        mQsStateUpdateListener = listener;
    }

    public void setApplyClippingImmediatelyListener(ApplyClippingImmediatelyListener listener) {
        mApplyClippingImmediatelyListener = listener;
    }

    public void setFlingQsWithoutClickListener(FlingQsWithoutClickListener listener) {
        mFlingQsWithoutClickListener = listener;
    }

    public void setExpansionHeightSetToMaxListener(ExpansionHeightSetToMaxListener callback) {
        mExpansionHeightSetToMaxListener = callback;
    }

    void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(this.mPanelView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mPeekHeight = mResources.getDimensionPixelSize(R.dimen.qs_peek_height);
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mPanelView.getContext());
        mScrimCornerRadius = mResources.getDimensionPixelSize(
                R.dimen.notification_scrim_corner_radius);
        mScreenCornerRadius = (int) ScreenDecorationsUtils.getWindowCornerRadius(
                mPanelView.getContext());
        mFalsingThreshold = mResources.getDimensionPixelSize(R.dimen.qs_falsing_threshold);
        mLockscreenNotificationPadding = mResources.getDimensionPixelSize(
                R.dimen.notification_side_paddings);
        mDistanceForFullShadeTransition = mResources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_transition_distance);
    }

    void updateResources() {
        mSplitShadeEnabled = LargeScreenUtils.shouldUseSplitNotificationShade(mResources);
        if (mQs != null) {
            mQs.setInSplitShade(mSplitShadeEnabled);
        }
        mSplitShadeNotificationsScrimMarginBottom =
                mResources.getDimensionPixelSize(
                        R.dimen.split_shade_notifications_scrim_margin_bottom);

        mUseLargeScreenShadeHeader =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(mPanelView.getResources());
        mLargeScreenShadeHeaderHeight =
                mResources.getDimensionPixelSize(R.dimen.large_screen_shade_header_height);
        int topMargin = mUseLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight :
                mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_top);
        mShadeHeaderController.setLargeScreenActive(mUseLargeScreenShadeHeader);
        mAmbientState.setStackTopMargin(topMargin);

        mQuickQsHeaderHeight = mLargeScreenShadeHeaderHeight;

        mEnableClipping = mResources.getBoolean(R.bool.qs_enable_clipping);
    }

    // TODO (b/265054088): move this and others to a CoreStartable
    void initNotificationStackScrollLayoutController() {
        mNotificationStackScrollLayoutController.setOverscrollTopChangedListener(
                new NsslOverscrollTopChangedListener());
        mNotificationStackScrollLayoutController.setOnStackYChanged(this::onStackYChanged);
        mNotificationStackScrollLayoutController.setOnScrollListener(this::onNotificationScrolled);
    }

    private void onStackYChanged(boolean shouldAnimate) {
        if (isQsFragmentCreated()) {
            if (shouldAnimate) {
                setAnimateNextNotificationBounds(StackStateAnimator.ANIMATION_DURATION_STANDARD,
                        0 /* delay */);
            }
            setClippingBounds();
        }
    }

    private void onNotificationScrolled(int newScrollPosition) {
        updateExpansionEnabledAmbient();
    }

    @VisibleForTesting
    void setStatusBarMinHeight(int height) {
        mStatusBarMinHeight = height;
    }

    int getHeaderHeight() {
        return mQs.getHeader().getHeight();
    }

    /** Returns the padding of the stackscroller when unlocked */
    int getUnlockedStackScrollerPadding() {
        return (mQs != null ? mQs.getHeader().getHeight() : 0) + mPeekHeight;
    }

    public boolean isExpansionEnabled() {
        return mExpansionEnabledPolicy && mExpansionEnabledAmbient
                && !mRemoteInputManager.isRemoteInputActive();
    }

    public float getTransitioningToFullShadeProgress() {
        return mTransitioningToFullShadeProgress;
    }

    /** */
    @VisibleForTesting
    boolean isExpandImmediate() {
        return mExpandImmediate;
    }

    float getInitialTouchY() {
        return mInitialTouchY;
    }

    /** Returns whether split shade is enabled and an x coordinate is outside of the QS frame. */
    private boolean isSplitShadeAndTouchXOutsideQs(float touchX) {
        return mSplitShadeEnabled && touchX < mQsFrame.getX()
                || touchX > mQsFrame.getX() + mQsFrame.getWidth();
    }

    /** Returns whether touch is within QS area */
    private boolean isTouchInQsArea(float x, float y) {
        if (isSplitShadeAndTouchXOutsideQs(x)) {
            return false;
        }
        // TODO (b/265193930): remove dependency on NPVC
        // Let's reject anything at the very bottom around the home handle in gesture nav
        if (mPanelViewControllerLazy.get().isInGestureNavHomeHandleArea(x, y)) {
            return false;
        }
        return y <= mNotificationStackScrollLayoutController.getBottomMostNotificationBottom()
                || y <= mQs.getView().getY() + mQs.getView().getHeight();
    }

    /** Returns whether or not event should open QS */
    @VisibleForTesting
    boolean isOpenQsEvent(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        final int action = event.getActionMasked();

        final boolean
                twoFingerDrag =
                action == MotionEvent.ACTION_POINTER_DOWN && pointerCount == 2;

        final boolean
                stylusButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_PRIMARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_STYLUS_SECONDARY));

        final boolean
                mouseButtonClickDrag =
                action == MotionEvent.ACTION_DOWN && (event.isButtonPressed(
                        MotionEvent.BUTTON_SECONDARY) || event.isButtonPressed(
                        MotionEvent.BUTTON_TERTIARY));

        return twoFingerDrag || stylusButtonClickDrag || mouseButtonClickDrag;
    }


    public boolean getExpanded() {
        return mExpanded;
    }

    @VisibleForTesting
    boolean isTracking() {
        return mTracking;
    }

    public boolean getFullyExpanded() {
        return mFullyExpanded;
    }

    boolean isGoingBetweenClosedShadeAndExpandedQs() {
        // Below is true when QS are expanded and we swipe up from the same bottom of panel to
        // close the whole shade with one motion. Also this will be always true when closing
        // split shade as there QS are always expanded so every collapsing motion is motion from
        // expanded QS to closed panel
        return mExpandImmediate || (mExpanded
                && !mTracking && !isExpansionAnimating()
                && !mExpansionFromOverscroll);
    }

    private boolean isQsFragmentCreated() {
        return mQs != null;
    }

    public boolean isCustomizing() {
        return isQsFragmentCreated() && mQs.isCustomizing();
    }

    public float getExpansionHeight() {
        return mExpansionHeight;
    }

    public boolean getExpandedWhenExpandingStarted() {
        return mExpandedWhenExpandingStarted;
    }

    public int getMinExpansionHeight() {
        return mMinExpansionHeight;
    }

    public boolean isFullyExpandedAndTouchesDisallowed() {
        return isQsFragmentCreated() && getFullyExpanded() && disallowTouches();
    }

    public int getMaxExpansionHeight() {
        return mMaxExpansionHeight;
    }

    private boolean isQsFalseTouch() {
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(Classifier.QUICK_SETTINGS);
        }
        return !mTouchAboveFalsingThreshold;
    }

    public int getFalsingThreshold() {
        return mFalsingThreshold;
    }

    /**
     * Returns Whether we should intercept a gesture to open Quick Settings.
     */
    public boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        boolean keyguardShowing = mBarState == KEYGUARD;
        if (!isExpansionEnabled() || mCollapsedOnDown || (keyguardShowing
                && mKeyguardBypassController.getBypassEnabled()) || mSplitShadeEnabled) {
            return false;
        }
        View header = keyguardShowing || mQs == null ? mKeyguardStatusBar : mQs.getHeader();
        int frameTop = keyguardShowing
                || mQs == null ? 0 : mQsFrame.getTop();
        mInterceptRegion.set(
                /* left= */ (int) mQsFrame.getX(),
                /* top= */ header.getTop() + frameTop,
                /* right= */ (int) mQsFrame.getX() + mQsFrame.getWidth(),
                /* bottom= */ header.getBottom() + frameTop);
        // Also allow QS to intercept if the touch is near the notch.
        mStatusBarTouchableRegionManager.updateRegionForNotch(mInterceptRegion);
        final boolean onHeader = mInterceptRegion.contains((int) x, (int) y);

        if (getExpanded()) {
            return onHeader || (yDiff < 0 && isTouchInQsArea(x, y));
        } else {
            return onHeader;
        }
    }

    /** Returns amount header should be translated */
    private float getHeaderTranslation() {
        if (mSplitShadeEnabled) {
            // in split shade QS don't translate, just (un)squish and overshoot
            return 0;
        }
        if (mBarState == KEYGUARD && !mKeyguardBypassController.getBypassEnabled()) {
            return -mQs.getQsMinExpansionHeight();
        }
        float appearAmount = mNotificationStackScrollLayoutController
                .calculateAppearFraction(mShadeExpandedHeight);
        float startHeight = -getExpansionHeight();
        if (mBarState == StatusBarState.SHADE) {
            // Small parallax as we pull down and clip QS
            startHeight = -getExpansionHeight() * QS_PARALLAX_AMOUNT;
        }
        if (mKeyguardBypassController.getBypassEnabled() && mBarState == KEYGUARD) {
            appearAmount = mNotificationStackScrollLayoutController.calculateAppearFractionBypass();
            startHeight = -mQs.getQsMinExpansionHeight();
        }
        float translation = MathUtils.lerp(startHeight, 0, Math.min(1.0f, appearAmount));
        return Math.min(0, translation);
    }

    /**
     * Can the panel collapse in this motion because it was started on QQS?
     *
     * @param downX the x location where the touch started
     * @param downY the y location where the touch started
     * Returns true if the panel could be collapsed because it stared on QQS
     */
    public boolean canPanelCollapseOnQQS(float downX, float downY) {
        if (mCollapsedOnDown || mBarState == KEYGUARD || getExpanded()) {
            return false;
        }
        View header = mQs == null ? mKeyguardStatusBar : mQs.getHeader();
        return downX >= mQsFrame.getX() && downX <= mQsFrame.getX() + mQsFrame.getWidth()
                && downY <= header.getBottom();
    }

    /** Closes the Qs customizer. */
    public void closeQsCustomizer() {
        mQs.closeCustomizer();
    }

    /** Returns whether touches from the notification panel should be disallowed */
    public boolean disallowTouches() {
        return mQs.disallowPanelTouches();
    }

    void setListening(boolean listening) {
        if (mQs != null) {
            mQs.setListening(listening);
        }
    }

    void hideQsImmediately() {
        if (mQs != null) {
            mQs.hideImmediately();
        }
    }

    public void setDozing(boolean dozing) {
        mDozing = dozing;
    }

    /**
     * This method closes QS but in split shade it should be used only in special cases: to make
     * sure QS closes when shade is closed as well. Otherwise it will result in QS disappearing
     * from split shade
     */
    public void closeQs() {
        if (mSplitShadeEnabled) {
            mShadeLog.d("Closing QS while in split shade");
        }
        cancelExpansionAnimation();
        setExpansionHeight(getMinExpansionHeight());
        // qsExpandImmediate is a safety latch in case we're calling closeQS while we're in the
        // middle of animation - we need to make sure that value is always false when shade if
        // fully collapsed or expanded
        setExpandImmediate(false);
    }

    @VisibleForTesting
    void setExpanded(boolean expanded) {
        boolean changed = mExpanded != expanded;
        if (changed) {
            mExpanded = expanded;
            updateQsState();
            mShadeExpansionStateManager.onQsExpansionChanged(expanded);
            mShadeLog.logQsExpansionChanged("QS Expansion Changed.", expanded,
                    getMinExpansionHeight(), getMaxExpansionHeight(),
                    mStackScrollerOverscrolling, mAnimatorExpand, mAnimating);
        }
    }

    void setLastShadeFlingWasExpanding(boolean expanding) {
        mLastShadeFlingWasExpanding = expanding;
        mShadeLog.logLastFlingWasExpanding(expanding);
    }

    /** update Qs height state */
    public void setExpansionHeight(float height) {
        checkCorrectSplitShadeState(height);
        int maxHeight = getMaxExpansionHeight();
        height = Math.min(Math.max(
                height, getMinExpansionHeight()), maxHeight);
        mFullyExpanded = height == maxHeight && maxHeight != 0;
        boolean qsAnimatingAway = !mAnimatorExpand && mAnimating;
        if (height > getMinExpansionHeight() && !getExpanded()
                && !mStackScrollerOverscrolling
                && !mDozing && !qsAnimatingAway) {
            setExpanded(true);
        } else if (height <= getMinExpansionHeight()
                && getExpanded()) {
            setExpanded(false);
        }
        mExpansionHeight = height;
        updateExpansion();

        if (mExpansionHeightListener != null) {
            mExpansionHeightListener.onQsSetExpansionHeightCalled(getFullyExpanded());
        }
    }

    /** TODO(b/269742565) Remove this logging */
    private void checkCorrectSplitShadeState(float height) {
        if (mSplitShadeEnabled && height == 0
                && mPanelViewControllerLazy.get().isShadeFullyOpen()) {
            Log.wtfStack(TAG, "qsExpansion set to 0 while split shade is expanding or open");
        }
    }

    /** */
    public void setHeightOverrideToDesiredHeight() {
        if (isSizeChangeAnimationRunning() && isQsFragmentCreated()) {
            mQs.setHeightOverride(mQs.getDesiredHeight());
        }
    }

    /** Updates quick setting heights and returns old max height. */
    int updateHeightsOnShadeLayoutChange() {
        int oldMaxHeight = getMaxExpansionHeight();
        if (isQsFragmentCreated()) {
            updateMinHeight();
            mMaxExpansionHeight = mQs.getDesiredHeight();
            mNotificationStackScrollLayoutController.setMaxTopPadding(
                    getMaxExpansionHeight());
        }
        return oldMaxHeight;
    }

    /** Called when Shade view layout changed. Updates QS expansion or
     * starts size change animation if height has changed. */
    void handleShadeLayoutChanged(int oldMaxHeight) {
        if (mExpanded && mFullyExpanded) {
            mExpansionHeight = mMaxExpansionHeight;
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }

            // Size has changed, start an animation.
            if (getMaxExpansionHeight() != oldMaxHeight) {
                startSizeChangeAnimation(oldMaxHeight,
                        getMaxExpansionHeight());
            }
        } else if (!getExpanded()
                && !isExpansionAnimating()) {
            setExpansionHeight(getMinExpansionHeight() + mLastOverscroll);
        } else {
            mShadeLog.v("onLayoutChange: qs expansion not set");
        }
    }

    private boolean isSizeChangeAnimationRunning() {
        return mSizeChangeAnimator != null;
    }

    private void startSizeChangeAnimation(int oldHeight, final int newHeight) {
        if (mSizeChangeAnimator != null) {
            oldHeight = (int) mSizeChangeAnimator.getAnimatedValue();
            mSizeChangeAnimator.cancel();
        }
        mSizeChangeAnimator = ValueAnimator.ofInt(oldHeight, newHeight);
        mSizeChangeAnimator.setDuration(300);
        mSizeChangeAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mSizeChangeAnimator.addUpdateListener(animation -> {
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }

            int height = (int) mSizeChangeAnimator.getAnimatedValue();
            mQs.setHeightOverride(height);
        });
        mSizeChangeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mSizeChangeAnimator = null;
            }
        });
        mSizeChangeAnimator.start();
    }

    void setNotificationPanelFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        if (mQs != null) {
            mQs.setIsNotificationPanelFullWidth(isFullWidth);
        }
    }

    void setBarState(int barState) {
        mBarState = barState;
    }

    /** */
    public void setExpansionEnabledPolicy(boolean expansionEnabledPolicy) {
        mExpansionEnabledPolicy = expansionEnabledPolicy;
        if (mQs != null) {
            mQs.setHeaderClickable(isExpansionEnabled());
        }
    }

    void setOverScrollAmount(int overExpansion) {
        mQs.setOverScrollAmount(overExpansion);
    }

    private void setOverScrolling(boolean overscrolling) {
        mStackScrollerOverscrolling = overscrolling;
        if (mQs != null) {
            mQs.setOverscrolling(overscrolling);
        }
    }

    /** Sets Qs ScrimEnabled and updates QS state. */
    public void setScrimEnabled(boolean scrimEnabled) {
        boolean changed = mScrimEnabled != scrimEnabled;
        mScrimEnabled = scrimEnabled;
        if (changed) {
            updateQsState();
        }
    }

    void setCollapsedOnDown(boolean collapsedOnDown) {
        mCollapsedOnDown = collapsedOnDown;
    }

    void setShadeExpandedHeight(float shadeExpandedHeight) {
        mShadeExpandedHeight = shadeExpandedHeight;
    }

    @VisibleForTesting
    float getShadeExpandedHeight() {
        return mShadeExpandedHeight;
    }

    @VisibleForTesting
    void setExpandImmediate(boolean expandImmediate) {
        if (expandImmediate != mExpandImmediate) {
            mShadeLog.logQsExpandImmediateChanged(expandImmediate);
            mExpandImmediate = expandImmediate;
            mShadeExpansionStateManager.notifyExpandImmediateChange(expandImmediate);
        }
    }

    void setTwoFingerExpandPossible(boolean expandPossible) {
        mTwoFingerExpandPossible = expandPossible;
    }

    @VisibleForTesting
    boolean isTwoFingerExpandPossible() {
        return mTwoFingerExpandPossible;
    }

    /** Called when Qs starts expanding */
    private void onExpansionStarted() {
        cancelExpansionAnimation();
        // TODO (b/265193930): remove dependency on NPVC
        mPanelViewControllerLazy.get().cancelHeightAnimator();
        // end

        // Reset scroll position and apply that position to the expanded height.
        float height = mExpansionHeight;
        setExpansionHeight(height);
        mNotificationStackScrollLayoutController.checkSnoozeLeavebehind();

        // When expanding QS, let's authenticate the user if possible,
        // this will speed up notification actions.
        if (height == 0 && !mKeyguardStateController.canDismissLockScreen()) {
            mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.QS_EXPANDED);
        }
    }

    void updateQsState() {
        boolean qsFullScreen = mExpanded && !mSplitShadeEnabled;
        mNotificationStackScrollLayoutController.setQsFullScreen(qsFullScreen);
        mNotificationStackScrollLayoutController.setScrollingEnabled(
                mBarState != KEYGUARD && (!qsFullScreen || mExpansionFromOverscroll));

        if (mQsStateUpdateListener != null) {
            mQsStateUpdateListener.onQsStateUpdated(mExpanded, mStackScrollerOverscrolling);
        }

        if (mQs == null) return;
        mQs.setExpanded(mExpanded);
    }

    /** update expanded state of QS */
    public void updateExpansion() {
        if (mQs == null) return;
        final float squishiness;
        if ((mExpandImmediate || mExpanded) && !mSplitShadeEnabled) {
            squishiness = 1;
        } else if (mTransitioningToFullShadeProgress > 0.0f) {
            squishiness = mLockscreenShadeTransitionController.getQsSquishTransitionFraction();
        } else {
            squishiness = mNotificationStackScrollLayoutController
                    .getNotificationSquishinessFraction();
        }
        final float qsExpansionFraction = computeExpansionFraction();
        final float adjustedExpansionFraction = mSplitShadeEnabled
                ? 1f : computeExpansionFraction();
        mQs.setQsExpansion(
                adjustedExpansionFraction,
                mShadeExpandedFraction,
                getHeaderTranslation(),
                squishiness
        );
        mMediaHierarchyManager.setQsExpansion(qsExpansionFraction);
        int qsPanelBottomY = calculateBottomPosition(qsExpansionFraction);
        mScrimController.setQsPosition(qsExpansionFraction, qsPanelBottomY);
        setClippingBounds();

        if (mSplitShadeEnabled) {
            // In split shade we want to pretend that QS are always collapsed so their behaviour and
            // interactions don't influence notifications as they do in portrait. But we want to set
            // 0 explicitly in case we're rotating from non-split shade with QS expansion of 1.
            mNotificationStackScrollLayoutController.setQsExpansionFraction(0);
        } else {
            mNotificationStackScrollLayoutController.setQsExpansionFraction(qsExpansionFraction);
        }

        mDepthController.setQsPanelExpansion(qsExpansionFraction);
        mStatusBarKeyguardViewManager.setQsExpansion(qsExpansionFraction);

        // TODO (b/265193930): remove dependency on NPVC
        float shadeExpandedFraction = mBarState == KEYGUARD
                ? mPanelViewControllerLazy.get().getLockscreenShadeDragProgress()
                : mShadeExpandedFraction;
        mShadeHeaderController.setShadeExpandedFraction(shadeExpandedFraction);
        mShadeHeaderController.setQsExpandedFraction(qsExpansionFraction);
        mShadeHeaderController.setQsVisible(mVisible);
    }

    /** */
    public void updateExpansionEnabledAmbient() {
        final float scrollRangeToTop = mAmbientState.getTopPadding() - mQuickQsHeaderHeight;
        mExpansionEnabledAmbient = mSplitShadeEnabled
                || (mAmbientState.getScrollY() <= scrollRangeToTop);
        if (mQs != null) {
            mQs.setHeaderClickable(isExpansionEnabled());
        }
    }

    /** Calculate y value of bottom of QS */
    private int calculateBottomPosition(float qsExpansionFraction) {
        if (mTransitioningToFullShadeProgress > 0.0f) {
            return mTransitionToFullShadePosition;
        } else {
            int qsBottomYFrom = (int) getHeaderTranslation() + mQs.getQsMinExpansionHeight();
            int expandedTopMargin = mUseLargeScreenShadeHeader ? mLargeScreenShadeHeaderHeight : 0;
            int qsBottomYTo = mQs.getDesiredHeight() + expandedTopMargin;
            return (int) MathUtils.lerp(qsBottomYFrom, qsBottomYTo, qsExpansionFraction);
        }
    }

    /** Calculate fraction of current QS expansion state */
    public float computeExpansionFraction() {
        if (mAnimatingHiddenFromCollapsed) {
            // When hiding QS from collapsed state, the expansion can sometimes temporarily
            // be larger than 0 because of the timing, leading to flickers.
            return 0.0f;
        }
        return Math.min(
                1f, (mExpansionHeight - mMinExpansionHeight) / (mMaxExpansionHeight
                        - mMinExpansionHeight));
    }

    void updateMinHeight() {
        float previousMin = mMinExpansionHeight;
        if (mBarState == KEYGUARD || mSplitShadeEnabled) {
            mMinExpansionHeight = 0;
        } else {
            mMinExpansionHeight = mQs.getQsMinExpansionHeight();
        }
        if (mExpansionHeight == previousMin) {
            mExpansionHeight = mMinExpansionHeight;
        }
    }

    void updateQsFrameTranslation() {
        // TODO (b/265193930): remove dependency on NPVC
        mQsFrameTranslateController.translateQsFrame(mQsFrame, mQs,
                mPanelViewControllerLazy.get().getNavigationBarBottomHeight()
                        + mAmbientState.getStackTopMargin());
    }

    /** Called when shade starts expanding. */
    public void onExpandingStarted(boolean qsFullyExpanded) {
        mNotificationStackScrollLayoutController.onExpansionStarted();
        mExpandedWhenExpandingStarted = qsFullyExpanded;
        mMediaHierarchyManager.setCollapsingShadeFromQS(mExpandedWhenExpandingStarted
                /* We also start expanding when flinging closed Qs. Let's exclude that */
                && !mAnimating);
        if (mExpanded) {
            onExpansionStarted();
        }
        // Since there are QS tiles in the header now, we need to make sure we start listening
        // immediately so they can be up to date.
        if (mQs == null) return;
        mQs.setHeaderListening(true);
    }

    /** Set animate next notification bounds. */
    private void setAnimateNextNotificationBounds(long duration, long delay) {
        mAnimateNextNotificationBounds = true;
        mNotificationBoundsAnimationDuration = duration;
        mNotificationBoundsAnimationDelay = delay;
    }

    /**
     * Updates scrim bounds, QS clipping, notifications clipping and keyguard status view clipping
     * as well based on the bounds of the shade and QS state.
     */
    private void setClippingBounds() {
        float qsExpansionFraction = computeExpansionFraction();
        final int qsPanelBottomY = calculateBottomPosition(qsExpansionFraction);
        final boolean qsVisible = (qsExpansionFraction > 0 || qsPanelBottomY > 0);
        checkCorrectScrimVisibility(qsExpansionFraction);

        int top = calculateTopClippingBound(qsPanelBottomY);
        int bottom = calculateBottomClippingBound(top);
        int left = calculateLeftClippingBound();
        int right = calculateRightClippingBound();
        // top should never be lower than bottom, otherwise it will be invisible.
        top = Math.min(top, bottom);
        applyClippingBounds(left, top, right, bottom, qsVisible);
    }

    /**
     * Applies clipping to quick settings, notifications layout and
     * updates bounds of the notifications background (notifications scrim).
     *
     * The parameters are bounds of the notifications area rectangle, this function
     * calculates bounds for the QS clipping based on the notifications bounds.
     */
    private void applyClippingBounds(int left, int top, int right, int bottom,
            boolean qsVisible) {
        if (!mAnimateNextNotificationBounds || mLastClipBounds.isEmpty()) {
            if (mClippingAnimator != null) {
                // update the end position of the animator
                mClippingAnimationEndBounds.set(left, top, right, bottom);
            } else {
                applyClippingImmediately(left, top, right, bottom, qsVisible);
            }
        } else {
            mClippingAnimationEndBounds.set(left, top, right, bottom);
            final int startLeft = mLastClipBounds.left;
            final int startTop = mLastClipBounds.top;
            final int startRight = mLastClipBounds.right;
            final int startBottom = mLastClipBounds.bottom;
            if (mClippingAnimator != null) {
                mClippingAnimator.cancel();
            }
            mClippingAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
            mClippingAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            mClippingAnimator.setDuration(mNotificationBoundsAnimationDuration);
            mClippingAnimator.setStartDelay(mNotificationBoundsAnimationDelay);
            mClippingAnimator.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                int animLeft = (int) MathUtils.lerp(startLeft,
                        mClippingAnimationEndBounds.left, fraction);
                int animTop = (int) MathUtils.lerp(startTop,
                        mClippingAnimationEndBounds.top, fraction);
                int animRight = (int) MathUtils.lerp(startRight,
                        mClippingAnimationEndBounds.right, fraction);
                int animBottom = (int) MathUtils.lerp(startBottom,
                        mClippingAnimationEndBounds.bottom, fraction);
                applyClippingImmediately(animLeft, animTop, animRight, animBottom,
                        qsVisible /* qsVisible */);
            });
            mClippingAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mClippingAnimator = null;
                    mIsTranslationResettingAnimator = false;
                    mIsPulseExpansionResettingAnimator = false;
                }
            });
            mClippingAnimator.start();
        }
        mAnimateNextNotificationBounds = false;
        mNotificationBoundsAnimationDelay = 0;
    }

    private void applyClippingImmediately(int left, int top, int right, int bottom,
            boolean qsVisible) {
        int radius = mScrimCornerRadius;
        boolean clipStatusView = false;
        mLastClipBounds.set(left, top, right, bottom);
        if (mIsFullWidth) {
            clipStatusView = qsVisible;
            float screenCornerRadius = mRecordingController.isRecording() ? 0 : mScreenCornerRadius;
            radius = (int) MathUtils.lerp(screenCornerRadius, mScrimCornerRadius,
                    Math.min(top / (float) mScrimCornerRadius, 1f));
        }
        if (isQsFragmentCreated()) {
            float qsTranslation = 0;
            boolean pulseExpanding = mPulseExpansionHandler.isExpanding();
            if (mTransitioningToFullShadeProgress > 0.0f
                    || pulseExpanding || (mClippingAnimator != null
                    && (mIsTranslationResettingAnimator || mIsPulseExpansionResettingAnimator))) {
                if (pulseExpanding || mIsPulseExpansionResettingAnimator) {
                    // qsTranslation should only be positive during pulse expansion because it's
                    // already translating in from the top
                    qsTranslation = Math.max(0, (top - getHeaderHeight()) / 2.0f);
                } else if (!mSplitShadeEnabled) {
                    qsTranslation = (top - getHeaderHeight()) * QS_PARALLAX_AMOUNT;
                }
            }
            mTranslationForFullShadeTransition = qsTranslation;
            updateQsFrameTranslation();
            float currentTranslation = mQsFrame.getTranslationY();
            int clipTop = mEnableClipping
                    ? (int) (top - currentTranslation - mQsFrame.getTop()) : 0;
            int clipBottom = mEnableClipping
                    ? (int) (bottom - currentTranslation - mQsFrame.getTop()) : 0;
            mVisible = qsVisible;
            mQs.setQsVisible(qsVisible);
            mQs.setFancyClipping(
                    clipTop,
                    clipBottom,
                    radius,
                    qsVisible && !mSplitShadeEnabled);

        }

        // Increase the height of the notifications scrim when not in split shade
        // (e.g. portrait tablet) so the rounded corners are not visible at the bottom,
        // in this case they are rendered off-screen
        final int notificationsScrimBottom = mSplitShadeEnabled ? bottom : bottom + radius;
        mScrimController.setNotificationsBounds(left, top, right, notificationsScrimBottom);

        if (mApplyClippingImmediatelyListener != null) {
            mApplyClippingImmediatelyListener.onQsClippingImmediatelyApplied(clipStatusView,
                    mLastClipBounds, top, isQsFragmentCreated(), mVisible);
        }

        mScrimController.setScrimCornerRadius(radius);

        // Convert global clipping coordinates to local ones,
        // relative to NotificationStackScrollLayout
        int nsslLeft = calculateNsslLeft(left);
        int nsslRight = calculateNsslRight(right);
        int nsslTop = getNotificationsClippingTopBounds(top);
        int nsslBottom = bottom - mNotificationStackScrollLayoutController.getTop();
        int bottomRadius = mSplitShadeEnabled ? radius : 0;
        // TODO (b/265193930): remove dependency on NPVC
        int topRadius = mSplitShadeEnabled
                && mPanelViewControllerLazy.get().isExpandingFromHeadsUp() ? 0 : radius;
        mNotificationStackScrollLayoutController.setRoundedClippingBounds(
                nsslLeft, nsslTop, nsslRight, nsslBottom, topRadius, bottomRadius);
    }

    void setDisplayInsets(int leftInset, int rightInset) {
        mDisplayLeftInset = leftInset;
        mDisplayRightInset = rightInset;
    }

    private int calculateNsslLeft(int nsslLeftAbsolute) {
        int left = nsslLeftAbsolute - mNotificationStackScrollLayoutController.getLeft();
        if (mIsFullWidth) {
            return left;
        }
        return left - mDisplayLeftInset;
    }

    private int calculateNsslRight(int nsslRightAbsolute) {
        int right = nsslRightAbsolute - mNotificationStackScrollLayoutController.getLeft();
        if (mIsFullWidth) {
            return right;
        }
        return right - mDisplayLeftInset;
    }

    private int getNotificationsClippingTopBounds(int qsTop) {
        // TODO (b/265193930): remove dependency on NPVC
        if (mSplitShadeEnabled && mPanelViewControllerLazy.get().isExpandingFromHeadsUp()) {
            // in split shade nssl has extra top margin so clipping at top 0 is not enough, we need
            // to set top clipping bound to negative value to allow HUN to go up to the top edge of
            // the screen without clipping.
            return -mAmbientState.getStackTopMargin();
        } else {
            return qsTop - mNotificationStackScrollLayoutController.getTop();
        }
    }

    private void checkCorrectScrimVisibility(float expansionFraction) {
        // issues with scrims visible on keyguard occur only in split shade
        if (mSplitShadeEnabled) {
            // TODO (b/265193930): remove dependency on NPVC
            boolean keyguardViewsVisible = mBarState == KEYGUARD
                            && mPanelViewControllerLazy.get().getKeyguardOnlyContentAlpha() == 1;
            // expansionFraction == 1 means scrims are fully visible as their size/visibility depend
            // on QS expansion
            if (expansionFraction == 1 && keyguardViewsVisible) {
                Log.wtf(TAG,
                        "Incorrect state, scrim is visible at the same time when clock is visible");
            }
        }
    }

    /** Calculate top padding for notifications */
    public float calculateNotificationsTopPadding(boolean isShadeExpanding,
            int keyguardNotificationStaticPadding, float expandedFraction) {
        boolean keyguardShowing = mBarState == KEYGUARD;
        if (mSplitShadeEnabled) {
            return keyguardShowing
                    ? keyguardNotificationStaticPadding : 0;
        }
        if (keyguardShowing && (isExpandImmediate()
                || isShadeExpanding && getExpandedWhenExpandingStarted())) {

            // Either QS pushes the notifications down when fully expanded, or QS is fully above the
            // notifications (mostly on tablets). maxNotificationPadding denotes the normal top
            // padding on Keyguard, maxQsPadding denotes the top padding from the quick settings
            // panel. We need to take the maximum and linearly interpolate with the panel expansion
            // for a nice motion.
            int maxQsPadding = getMaxExpansionHeight();
            int max = keyguardShowing ? Math.max(
                    keyguardNotificationStaticPadding, maxQsPadding) : maxQsPadding;
            return (int) MathUtils.lerp((float) getMinExpansionHeight(),
                    (float) max, expandedFraction);
        } else if (isSizeChangeAnimationRunning()) {
            return Math.max((int) mSizeChangeAnimator.getAnimatedValue(),
                    keyguardNotificationStaticPadding);
        } else if (keyguardShowing) {
            // We can only do the smoother transition on Keyguard when we also are not collapsing
            // from a scrolled quick settings.
            return MathUtils.lerp((float) keyguardNotificationStaticPadding,
                    (float) (getMaxExpansionHeight()), computeExpansionFraction());
        } else {
            return mQsFrameTranslateController.getNotificationsTopPadding(
                    mExpansionHeight, mNotificationStackScrollLayoutController);
        }
    }

    /** Calculate height of QS panel */
    public int calculatePanelHeightExpanded(int stackScrollerPadding) {
        float
                notificationHeight =
                mNotificationStackScrollLayoutController.getHeight()
                        - mNotificationStackScrollLayoutController.getEmptyBottomMargin()
                        - mNotificationStackScrollLayoutController.getTopPadding();

        // When only empty shade view is visible in QS collapsed state, simulate that we would have
        // it in expanded QS state as well so we don't run into troubles when fading the view in/out
        // and expanding/collapsing the whole panel from/to quick settings.
        if (mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0
                && mNotificationStackScrollLayoutController.isShowingEmptyShadeView()) {
            notificationHeight = mNotificationStackScrollLayoutController.getEmptyShadeViewHeight();
        }
        int maxQsHeight = mMaxExpansionHeight;

        // If an animation is changing the size of the QS panel, take the animated value.
        if (mSizeChangeAnimator != null) {
            maxQsHeight = (int) mSizeChangeAnimator.getAnimatedValue();
        }
        float totalHeight = Math.max(maxQsHeight, mBarState == KEYGUARD ? stackScrollerPadding : 0)
                + notificationHeight
                + mNotificationStackScrollLayoutController.getTopPaddingOverflow();
        if (totalHeight > mNotificationStackScrollLayoutController.getHeight()) {
            float
                    fullyCollapsedHeight =
                    maxQsHeight + mNotificationStackScrollLayoutController.getLayoutMinHeight();
            totalHeight = Math.max(fullyCollapsedHeight,
                    mNotificationStackScrollLayoutController.getHeight());
        }
        return (int) totalHeight;
    }

    private float getEdgePosition() {
        // TODO: replace StackY with unified calculation
        return Math.max(mQuickQsHeaderHeight * mAmbientState.getExpansionFraction(),
                mAmbientState.getStackY()
                        // need to adjust for extra margin introduced by large screen shade header
                        + mAmbientState.getStackTopMargin() * mAmbientState.getExpansionFraction()
                        - mAmbientState.getScrollY());
    }

    private int calculateTopClippingBound(int qsPanelBottomY) {
        int top;
        if (mSplitShadeEnabled) {
            top = Math.min(qsPanelBottomY, mLargeScreenShadeHeaderHeight);
        } else {
            if (mTransitioningToFullShadeProgress > 0.0f) {
                // If we're transitioning, let's use the actual value. The else case
                // can be wrong during transitions when waiting for the keyguard to unlock
                top = mTransitionToFullShadePosition;
            } else {
                final float notificationTop = getEdgePosition();
                if (mBarState == KEYGUARD) {
                    if (mKeyguardBypassController.getBypassEnabled()) {
                        // When bypassing on the keyguard, let's use the panel bottom.
                        // this should go away once we unify the stackY position and don't have
                        // to do this min anymore below.
                        top = qsPanelBottomY;
                    } else {
                        top = (int) Math.min(qsPanelBottomY, notificationTop);
                    }
                } else {
                    top = (int) notificationTop;
                }
            }
            // TODO (b/265193930): remove dependency on NPVC
            top += mPanelViewControllerLazy.get().getOverStretchAmount();
            // Correction for instant expansion caused by HUN pull down/
            float minFraction = mPanelViewControllerLazy.get().getMinFraction();
            if (minFraction > 0f && minFraction < 1f) {
                float realFraction = (mShadeExpandedFraction
                        - minFraction) / (1f - minFraction);
                top *= MathUtils.saturate(realFraction / minFraction);
            }
        }
        return top;
    }

    private int calculateBottomClippingBound(int top) {
        if (mSplitShadeEnabled) {
            return top + mNotificationStackScrollLayoutController.getHeight()
                    + mSplitShadeNotificationsScrimMarginBottom;
        } else {
            return mPanelView.getBottom();
        }
    }

    private int calculateLeftClippingBound() {
        if (mIsFullWidth) {
            // left bounds can ignore insets, it should always reach the edge of the screen
            return 0;
        } else {
            return mNotificationStackScrollLayoutController.getLeft()
                    + mDisplayLeftInset;
        }
    }

    private int calculateRightClippingBound() {
        if (mIsFullWidth) {
            return mPanelView.getRight()
                    + mDisplayRightInset;
        } else {
            return mNotificationStackScrollLayoutController.getRight()
                    + mDisplayLeftInset;
        }
    }

    private void trackMovement(MotionEvent event) {
        if (mQsVelocityTracker != null) mQsVelocityTracker.addMovement(event);
    }

    private void initVelocityTracker() {
        if (mQsVelocityTracker != null) {
            mQsVelocityTracker.recycle();
        }
        mQsVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentVelocity() {
        if (mQsVelocityTracker == null) {
            return 0;
        }
        mQsVelocityTracker.computeCurrentVelocity(1000);
        return mQsVelocityTracker.getYVelocity();
    }

    boolean updateAndGetTouchAboveFalsingThreshold() {
        mTouchAboveFalsingThreshold = mFullyExpanded;
        return mTouchAboveFalsingThreshold;
    }

    @VisibleForTesting
    void onHeightChanged() {
        mMaxExpansionHeight = isQsFragmentCreated() ? mQs.getDesiredHeight() : 0;
        if (mExpanded && mFullyExpanded) {
            mExpansionHeight = mMaxExpansionHeight;
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(true);
            }
        }
        if (mAccessibilityManager.isEnabled()) {
            // TODO (b/265193930): remove dependency on NPVC
            mPanelView.setAccessibilityPaneTitle(
                    mPanelViewControllerLazy.get().determineAccessibilityPaneTitle());
        }
        mNotificationStackScrollLayoutController.setMaxTopPadding(mMaxExpansionHeight);
    }

    private void collapseOrExpandQs() {
        if (mSplitShadeEnabled) {
            return; // QS is always expanded in split shade
        }
        onExpansionStarted();
        if (getExpanded()) {
            flingQs(0, FLING_COLLAPSE, null, true);
        } else if (isExpansionEnabled()) {
            mLockscreenGestureLogger.write(MetricsProto.MetricsEvent.ACTION_SHADE_QS_TAP, 0, 0);
            flingQs(0, FLING_EXPAND, null, true);
        }
    }

    private void onScroll(int scrollY) {
        mShadeHeaderController.setQsScrollY(scrollY);
        if (scrollY > 0 && !mFullyExpanded) {
            // TODO (b/265193930): remove dependency on NPVC
            // If we are scrolling QS, we should be fully expanded.
            mPanelViewControllerLazy.get().expandWithQs();
        }
    }

    @VisibleForTesting
    boolean isTrackingBlocked() {
        return mConflictingExpansionGesture && getExpanded();
    }

    boolean isExpansionAnimating() {
        return mExpansionAnimator != null;
    }

    @VisibleForTesting
    boolean isConflictingExpansionGesture() {
        return mConflictingExpansionGesture;
    }

    /** handles touches in Qs panel area */
    public boolean handleTouch(MotionEvent event, boolean isFullyCollapsed,
            boolean isShadeOrQsHeightAnimationRunning) {
        if (isSplitShadeAndTouchXOutsideQs(event.getX())) {
            return false;
        }
        final int action = event.getActionMasked();
        boolean collapsedQs = !getExpanded() && !mSplitShadeEnabled;
        boolean expandedShadeCollapsedQs = mShadeExpandedFraction == 1f
                && mBarState != KEYGUARD && collapsedQs && isExpansionEnabled();
        if (action == MotionEvent.ACTION_DOWN && expandedShadeCollapsedQs) {
            // Down in the empty area while fully expanded - go to QS.
            mShadeLog.logMotionEvent(event, "handleQsTouch: down action, QS tracking enabled");
            mTracking = true;
            traceQsJank(true, false);
            mConflictingExpansionGesture = true;
            onExpansionStarted();
            mInitialHeightOnTouch = mExpansionHeight;
            mInitialTouchY = event.getY();
            mInitialTouchX = event.getX();
        }
        if (!isFullyCollapsed && !isShadeOrQsHeightAnimationRunning) {
            handleDown(event);
        }
        // defer touches on QQS to shade while shade is collapsing. Added margin for error
        // as sometimes the qsExpansionFraction can be a tiny value instead of 0 when in QQS.
        if (!mSplitShadeEnabled && !mLastShadeFlingWasExpanding
                && computeExpansionFraction() <= 0.01 && mShadeExpandedFraction < 1.0) {
            mShadeLog.logMotionEvent(event,
                    "handleQsTouch: shade touched while shade collapsing, QS tracking disabled");
            mTracking = false;
        }
        if (!isExpandImmediate() && mTracking) {
            onTouch(event);
            if (!mConflictingExpansionGesture && !mSplitShadeEnabled) {
                mShadeLog.logMotionEvent(event,
                        "handleQsTouch: not immediate expand or conflicting gesture");
                return true;
            }
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            mConflictingExpansionGesture = false;
        }
        if (action == MotionEvent.ACTION_DOWN && isFullyCollapsed && isExpansionEnabled()) {
            mTwoFingerExpandPossible = true;
        }
        if (mTwoFingerExpandPossible && isOpenQsEvent(event)
                && event.getY(event.getActionIndex())
                < mStatusBarMinHeight) {
            mMetricsLogger.count(COUNTER_PANEL_OPEN_QS, 1);
            setExpandImmediate(true);
            mNotificationStackScrollLayoutController.setShouldShowShelfOnly(!mSplitShadeEnabled);
            if (mExpansionHeightSetToMaxListener != null) {
                mExpansionHeightSetToMaxListener.onExpansionHeightSetToMax(false);
            }

            // Normally, we start listening when the panel is expanded, but here we need to start
            // earlier so the state is already up to date when dragging down.
            setListening(true);
        }
        return false;
    }

    private void handleDown(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && shouldQuickSettingsIntercept(event.getX(), event.getY(), -1)) {
            mFalsingCollector.onQsDown();
            mShadeLog.logMotionEvent(event, "handleQsDown: down action, QS tracking enabled");
            mTracking = true;
            onExpansionStarted();
            mInitialHeightOnTouch = mExpansionHeight;
            mInitialTouchY = event.getY();
            mInitialTouchX = event.getX();
            // TODO (b/265193930): remove dependency on NPVC
            // If we interrupt an expansion gesture here, make sure to update the state correctly.
            mPanelViewControllerLazy.get().notifyExpandingFinished();
        }
    }

    private void onTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);
        final float h = y - mInitialTouchY;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mShadeLog.logMotionEvent(event, "onQsTouch: down action, QS tracking enabled");
                mTracking = true;
                traceQsJank(true, false);
                mInitialTouchY = y;
                mInitialTouchX = x;
                onExpansionStarted();
                mInitialHeightOnTouch = mExpansionHeight;
                initVelocityTracker();
                trackMovement(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialHeightOnTouch = mExpansionHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                mShadeLog.logMotionEvent(event, "onQsTouch: move action, setting QS expansion");
                setExpansionHeight(h + mInitialHeightOnTouch);
                // TODO (b/265193930): remove dependency on NPVC
                if (h >= mPanelViewControllerLazy.get().getFalsingThreshold()) {
                    mTouchAboveFalsingThreshold = true;
                }
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mShadeLog.logMotionEvent(event,
                        "onQsTouch: up/cancel action, QS tracking disabled");
                mTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                float fraction = computeExpansionFraction();
                if (fraction != 0f || y >= mInitialTouchY) {
                    flingQsWithCurrentVelocity(y,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                } else {
                    traceQsJank(false,
                            event.getActionMasked() == MotionEvent.ACTION_CANCEL);
                }
                if (mQsVelocityTracker != null) {
                    mQsVelocityTracker.recycle();
                    mQsVelocityTracker = null;
                }
                break;
        }
    }

    /** intercepts touches on Qs panel area. */
    public boolean onIntercept(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                float qsExpansionFraction = computeExpansionFraction();
                // Intercept the touch if QS is between fully collapsed and fully expanded state
                if (!mSplitShadeEnabled
                        && qsExpansionFraction > 0.0 && qsExpansionFraction < 1.0) {
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: down action, QS partially expanded/collapsed");
                    return true;
                }
                // TODO (b/265193930): remove dependency on NPVC
                if (mPanelViewControllerLazy.get().getKeyguardShowing()
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    // Dragging down on the lockscreen statusbar should prohibit other interactions
                    // immediately, otherwise we'll wait on the touchslop. This is to allow
                    // dragging down to expanded quick settings directly on the lockscreen.
                    mPanelView.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (mExpansionAnimator != null) {
                    mInitialHeightOnTouch = mExpansionHeight;
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: down action, QS tracking enabled");
                    mTracking = true;
                    traceQsJank(true, false);
                    mNotificationStackScrollLayoutController.cancelLongPress();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (mTracking) {

                    // Already tracking because onOverscrolled was called. We need to update here
                    // so we don't stop for a frame until the next touch event gets handled in
                    // onTouchEvent.
                    setExpansionHeight(h + mInitialHeightOnTouch);
                    trackMovement(event);
                    return true;
                } else {
                    mShadeLog.logMotionEvent(event,
                            "onQsIntercept: move ignored because qs tracking disabled");
                }
                // TODO (b/265193930): remove dependency on NPVC
                float touchSlop = event.getClassification()
                        == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                        ? mTouchSlop * mSlopMultiplier
                        : mTouchSlop;
                if ((h > touchSlop || (h < -touchSlop && getExpanded()))
                        && Math.abs(h) > Math.abs(x - mInitialTouchX)
                        && shouldQuickSettingsIntercept(
                        mInitialTouchX, mInitialTouchY, h)) {
                    mPanelView.getParent().requestDisallowInterceptTouchEvent(true);
                    mShadeLog.onQsInterceptMoveQsTrackingEnabled(h);
                    mTracking = true;
                    traceQsJank(true, false);
                    onExpansionStarted();
                    mPanelViewControllerLazy.get().notifyExpandingFinished();
                    mInitialHeightOnTouch = mExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mNotificationStackScrollLayoutController.cancelLongPress();
                    return true;
                } else {
                    mShadeLog.logQsTrackingNotStarted(mInitialTouchY, y, h, touchSlop,
                            getExpanded(), mPanelViewControllerLazy.get().getKeyguardShowing(),
                            isExpansionEnabled());
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                mShadeLog.logMotionEvent(event, "onQsIntercept: up action, QS tracking disabled");
                mTracking = false;
                break;
        }
        return false;
    }

    @VisibleForTesting
    void onPanelExpansionChanged(ShadeExpansionChangeEvent event) {
        mShadeExpandedFraction = event.getFraction();
    }

    /**
     * Animate QS closing by flinging it.
     * If QS is expanded, it will collapse into QQS and stop.
     * If in split shade, it will collapse the whole shade.
     *
     * @param animateAway Do not stop when QS becomes QQS. Fling until QS isn't visible anymore.
     */
    public void animateCloseQs(boolean animateAway) {
        if (mExpansionAnimator != null) {
            if (!mAnimatorExpand) {
                return;
            }
            float height = mExpansionHeight;
            mExpansionAnimator.cancel();
            setExpansionHeight(height);
        }
        flingQs(0 /* vel */, animateAway ? FLING_HIDE : FLING_COLLAPSE);
    }

    private void cancelExpansionAnimation() {
        if (mExpansionAnimator != null) {
            mExpansionAnimator.cancel();
        }
    }

    /** @see #flingQs(float, int, Runnable, boolean) */
    public void flingQs(float vel, int type) {
        flingQs(vel, type, null /* onFinishRunnable */, false /* isClick */);
    }

    /**
     * Animates QS or QQS as if the user had swiped up or down.
     *
     * @param vel              Finger velocity or 0 when not initiated by touch events.
     * @param type             Either FLING_EXPAND, FLING_COLLAPSE or FLING_HIDE.
     * @param onFinishRunnable Runnable to be executed at the end of animation.
     * @param isClick          If originated by click (different interpolator and duration.)
     */
    private void flingQs(float vel, int type, final Runnable onFinishRunnable,
            boolean isClick) {
        mShadeLog.flingQs(type, isClick);
        float target;
        switch (type) {
            case FLING_EXPAND:
                target = getMaxExpansionHeight();
                break;
            case FLING_COLLAPSE:
                if (mSplitShadeEnabled) { // TODO:(b/269742565) remove below log
                    Log.wtfStack(TAG, "FLING_COLLAPSE called in split shade");
                }
                target = getMinExpansionHeight();
                break;
            case FLING_HIDE:
            default:
                if (isQsFragmentCreated()) {
                    mQs.closeDetail();
                }
                target = 0;
        }
        if (target == mExpansionHeight) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            traceQsJank(false, type != FLING_EXPAND);
            return;
        }

        // If we move in the opposite direction, reset velocity and use a different duration.
        boolean oppositeDirection = false;
        boolean expanding = type == FLING_EXPAND;
        if (vel > 0 && !expanding || vel < 0 && expanding) {
            vel = 0;
            oppositeDirection = true;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(
                mExpansionHeight, target);
        if (isClick) {
            animator.setInterpolator(Interpolators.TOUCH_RESPONSE);
            animator.setDuration(368);
        } else {
            if (mFlingQsWithoutClickListener != null) {
                mFlingQsWithoutClickListener.onFlingQsWithoutClick(animator, mExpansionHeight,
                        target, vel);
            }
        }
        if (oppositeDirection) {
            animator.setDuration(350);
        }
        animator.addUpdateListener(
                animation -> setExpansionHeight((Float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mIsCanceled;

            @Override
            public void onAnimationStart(Animator animation) {
                mPanelViewControllerLazy.get().notifyExpandingStarted();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mIsCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnimatingHiddenFromCollapsed = false;
                mAnimating = false;
                mPanelViewControllerLazy.get().notifyExpandingFinished();
                mNotificationStackScrollLayoutController.resetCheckSnoozeLeavebehind();
                mExpansionAnimator = null;
                if (onFinishRunnable != null) {
                    onFinishRunnable.run();
                }
                traceQsJank(false, mIsCanceled);
            }
        });
        // Let's note that we're animating QS. Moving the animator here will cancel it immediately,
        // so we need a separate flag.
        mAnimating = true;
        animator.start();
        mExpansionAnimator = animator;
        mAnimatorExpand = expanding;
        mAnimatingHiddenFromCollapsed =
                computeExpansionFraction() == 0.0f && target == 0;
    }

    private void flingQsWithCurrentVelocity(float y, boolean isCancelMotionEvent) {
        float vel = getCurrentVelocity();
        // TODO (b/265193930): remove dependency on NPVC
        boolean expandsQs = mPanelViewControllerLazy.get().flingExpandsQs(vel);
        if (expandsQs) {
            if (mFalsingManager.isUnlockingDisabled() || isQsFalseTouch()) {
                expandsQs = false;
            } else {
                logQsSwipeDown(y);
            }
        } else if (vel < 0) {
            mFalsingManager.isFalseTouch(QS_COLLAPSE);
        }

        int flingType;
        if (expandsQs && !isCancelMotionEvent) {
            flingType = FLING_EXPAND;
        } else if (mSplitShadeEnabled) {
            flingType = FLING_HIDE;
        } else {
            flingType = FLING_COLLAPSE;
        }
        flingQs(vel, flingType);
    }

    private void logQsSwipeDown(float y) {
        float vel = getCurrentVelocity();
        final int gesture = mBarState == KEYGUARD ? MetricsProto.MetricsEvent.ACTION_LS_QS
                : MetricsProto.MetricsEvent.ACTION_SHADE_QS_PULL;
        // TODO (b/265193930): remove dependency on NPVC
        float displayDensity = mPanelViewControllerLazy.get().getDisplayDensity();
        mLockscreenGestureLogger.write(gesture,
                (int) ((y - getInitialTouchY()) / displayDensity), (int) (vel / displayDensity));
    }

    /** */
    public FragmentHostManager.FragmentListener getQsFragmentListener() {
        return new QsFragmentListener();
    }

    /** */
    public final class QsFragmentListener implements FragmentHostManager.FragmentListener {
        /** */
        @Override
        public void onFragmentViewCreated(String tag, Fragment fragment) {
            mQs = (QS) fragment;
            mQs.setPanelView(mQsHeightListener);
            mQs.setCollapseExpandAction(mQsCollapseExpandAction);
            mQs.setHeaderClickable(isExpansionEnabled());
            mQs.setOverscrolling(mStackScrollerOverscrolling);
            mQs.setInSplitShade(mSplitShadeEnabled);
            mQs.setIsNotificationPanelFullWidth(mIsFullWidth);

            // recompute internal state when qspanel height changes
            mQs.getView().addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        final int height = bottom - top;
                        final int oldHeight = oldBottom - oldTop;
                        if (height != oldHeight) {
                            onHeightChanged();
                        }
                    });
            mQs.setCollapsedMediaVisibilityChangedListener((visible) -> {
                if (mQs.getHeader().isShown()) {
                    setAnimateNextNotificationBounds(
                            StackStateAnimator.ANIMATION_DURATION_STANDARD, 0);
                    mNotificationStackScrollLayoutController.animateNextTopPaddingChange();
                }
            });
            mLockscreenShadeTransitionController.setQS(mQs);
            mShadeTransitionController.setQs(mQs);
            mNotificationStackScrollLayoutController.setQsHeader((ViewGroup) mQs.getHeader());
            mQs.setScrollListener(mQsScrollListener);
            updateExpansion();
        }

        /** */
        @Override
        public void onFragmentViewDestroyed(String tag, Fragment fragment) {
            // Manual handling of fragment lifecycle is only required because this bridges
            // non-fragment and fragment code. Once we are using a fragment for the notification
            // panel, mQs will not need to be null cause it will be tied to the same lifecycle.
            if (fragment == mQs) {
                mQs = null;
            }
        }
    }

    private final class LockscreenShadeTransitionCallback
            implements LockscreenShadeTransitionController.Callback {
        /** Called when pulse expansion has finished and this is going to the full shade. */
        @Override
        public void onPulseExpansionFinished() {
            setAnimateNextNotificationBounds(
                    StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE, 0);
            mIsPulseExpansionResettingAnimator = true;
        }

        @Override
        public void setTransitionToFullShadeAmount(float pxAmount, boolean animate, long delay) {
            if (animate && mIsFullWidth) {
                setAnimateNextNotificationBounds(
                        StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE, delay);
                mIsTranslationResettingAnimator = mTranslationForFullShadeTransition > 0.0f;
            }
            float endPosition = 0;
            if (pxAmount > 0.0f) {
                if (mSplitShadeEnabled) {
                    float qsHeight = MathUtils.lerp(getMinExpansionHeight(),
                            getMaxExpansionHeight(),
                            mLockscreenShadeTransitionController.getQSDragProgress());
                    setExpansionHeight(qsHeight);
                }
                if (mNotificationStackScrollLayoutController.getVisibleNotificationCount() == 0
                        && !mMediaDataManager.hasActiveMediaOrRecommendation()) {
                    // No notifications are visible, let's animate to the height of qs instead
                    if (isQsFragmentCreated()) {
                        // Let's interpolate to the header height instead of the top padding,
                        // because the toppadding is way too low because of the large clock.
                        // we still want to take into account the edgePosition though as that nicely
                        // overshoots in the stackscroller
                        endPosition = getEdgePosition()
                                - mNotificationStackScrollLayoutController.getTopPadding()
                                + getHeaderHeight();
                    }
                } else {
                    // Interpolating to the new bottom edge position!
                    endPosition = getEdgePosition() + mNotificationStackScrollLayoutController
                            .getFullShadeTransitionInset();
                    if (mBarState == KEYGUARD) {
                        endPosition -= mLockscreenNotificationPadding;
                    }
                }
            }

            // Calculate the overshoot amount such that we're reaching the target after our desired
            // distance, but only reach it fully once we drag a full shade length.
            mTransitioningToFullShadeProgress = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                    MathUtils.saturate(pxAmount / mDistanceForFullShadeTransition));

            int position = (int) MathUtils.lerp((float) 0, endPosition,
                    mTransitioningToFullShadeProgress);
            if (mTransitioningToFullShadeProgress > 0.0f) {
                // we want at least 1 pixel otherwise the panel won't be clipped
                position = Math.max(1, position);
            }
            mTransitionToFullShadePosition = position;
            updateExpansion();
        }
    }

    private final class NsslOverscrollTopChangedListener implements
            NotificationStackScrollLayout.OnOverscrollTopChangedListener {
        @Override
        public void onOverscrollTopChanged(float amount, boolean isRubberbanded) {
            // When in split shade, overscroll shouldn't carry through to QS
            if (mSplitShadeEnabled) {
                return;
            }
            cancelExpansionAnimation();
            if (!isExpansionEnabled()) {
                amount = 0f;
            }
            float rounded = amount >= 1f ? amount : 0f;
            setOverScrolling(rounded != 0f && isRubberbanded);
            mExpansionFromOverscroll = rounded != 0f;
            mLastOverscroll = rounded;
            updateQsState();
            setExpansionHeight(getMinExpansionHeight() + rounded);
        }

        @Override
        public void flingTopOverscroll(float velocity, boolean open) {
            // in split shade mode we want to expand/collapse QS only when touch happens within QS
            if (isSplitShadeAndTouchXOutsideQs(mInitialTouchX)) {
                return;
            }
            mLastOverscroll = 0f;
            mExpansionFromOverscroll = false;
            if (open) {
                // During overscrolling, qsExpansion doesn't actually change that the qs is
                // becoming expanded. Any layout could therefore reset the position again. Let's
                // make sure we can expand
                setOverScrolling(false);
            }
            setExpansionHeight(getExpansionHeight());
            boolean canExpand = isExpansionEnabled();
            flingQs(!canExpand && open ? 0f : velocity,
                    open && canExpand ? FLING_EXPAND : FLING_COLLAPSE, () -> {
                        setOverScrolling(false);
                        updateQsState();
                    }, false);
        }
    }

    void beginJankMonitoring(boolean isFullyCollapsed) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        // TODO (b/265193930): remove dependency on NPVC
        InteractionJankMonitor.Configuration.Builder builder =
                InteractionJankMonitor.Configuration.Builder.withView(
                        InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
                        mPanelView).setTag(isFullyCollapsed ? "Expand" : "Collapse");
        mInteractionJankMonitor.begin(builder);
    }

    void endJankMonitoring() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().end(
                InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    void cancelJankMonitoring() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        InteractionJankMonitor.getInstance().cancel(
                InteractionJankMonitor.CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE);
    }

    void traceQsJank(boolean startTracing, boolean wasCancelled) {
        if (mInteractionJankMonitor == null) {
            return;
        }
        if (startTracing) {
            mInteractionJankMonitor.begin(mPanelView, CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
        } else {
            if (wasCancelled) {
                mInteractionJankMonitor.cancel(CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            } else {
                mInteractionJankMonitor.end(CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE);
            }
        }
    }

    interface ExpansionHeightSetToMaxListener {
        void onExpansionHeightSetToMax(boolean requestPaddingUpdate);
    }

    interface ExpansionHeightListener {
        void onQsSetExpansionHeightCalled(boolean qsFullyExpanded);
    }

    interface QsStateUpdateListener {
        void onQsStateUpdated(boolean qsExpanded, boolean isStackScrollerOverscrolling);
    }

    interface ApplyClippingImmediatelyListener {
        void onQsClippingImmediatelyApplied(boolean clipStatusView, Rect lastQsClipBounds,
                int top, boolean qsFragmentCreated, boolean qsVisible);
    }

    interface FlingQsWithoutClickListener {
        void onFlingQsWithoutClick(ValueAnimator animator, float qsExpansionHeight,
                float target, float vel);
    }
}
