/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;


import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.internal.policy.SystemBarUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.shared.rotation.FloatingRotationButton;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Objects;

public class PhoneStatusBarView extends FrameLayout implements Callbacks {
    private static final String TAG = "PhoneStatusBarView";
    private final CommandQueue mCommandQueue;
    private final StatusBarContentInsetsProvider mContentInsetsProvider;

    private DarkReceiver mBattery;
    private ClockController mClockController;
    private int mRotationOrientation = -1;
    private RotationButtonController mRotationButtonController;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private TouchEventHandler mTouchEventHandler;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mCommandQueue = Dependency.get(CommandQueue.class);
        mContentInsetsProvider = Dependency.get(StatusBarContentInsetsProvider.class);

        // Only create FRB here if there is no navbar
        if (!hasNavigationBar()) {
            final Context lightContext = new ContextThemeWrapper(context,
                    Utils.getThemeAttr(context, R.attr.lightIconTheme));
            final Context darkContext = new ContextThemeWrapper(context,
                    Utils.getThemeAttr(context, R.attr.darkIconTheme));
            final int lightIconColor =
                    Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
            final int darkIconColor =
                    Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);
            final FloatingRotationButton floatingRotationButton = new FloatingRotationButton(
                    context,
                    R.string.accessibility_rotate_button, R.layout.rotate_suggestion,
                    R.id.rotate_suggestion, R.dimen.floating_rotation_button_min_margin,
                    R.dimen.rounded_corner_content_padding,
                    R.dimen.floating_rotation_button_taskbar_left_margin,
                    R.dimen.floating_rotation_button_taskbar_bottom_margin,
                    R.dimen.floating_rotation_button_diameter, R.dimen.key_button_ripple_max_width);

            mRotationButtonController = new RotationButtonController(lightContext, lightIconColor,
                    darkIconColor, R.drawable.ic_sysbar_rotate_button_ccw_start_0,
                    R.drawable.ic_sysbar_rotate_button_ccw_start_90,
                    R.drawable.ic_sysbar_rotate_button_cw_start_0,
                    R.drawable.ic_sysbar_rotate_button_cw_start_90,
                    () -> getDisplay().getRotation());
            mRotationButtonController.setRotationButton(floatingRotationButton, null);
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        if (mRotationButtonController != null && !hasNavigationBar()) {
            mRotationButtonController.onRotationProposal(rotation, isValid);
        }
    }

    private boolean hasNavigationBar() {
        try {
            IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            return windowManager.hasNavigationBar(Display.DEFAULT_DISPLAY);
        } catch (RemoteException ex) { }
        return false;
    }

    void setTouchEventHandler(TouchEventHandler handler) {
        mTouchEventHandler = handler;
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mBattery = findViewById(R.id.battery);
        mClockController = new ClockController(getContext(), this);
        mCutoutSpace = findViewById(R.id.cutout_space_view);

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Always have Battery meters in the status bar observe the dark/light modes.
        Dependency.get(DarkIconDispatcher.class).addDarkReceiver(mBattery);
        mClockController.addDarkReceiver();
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
        }

        if (mRotationButtonController != null && !hasNavigationBar()) {
            mCommandQueue.addCallback(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(DarkIconDispatcher.class).removeDarkReceiver(mBattery);
        mClockController.removeDarkReceiver();
        mDisplayCutout = null;

        if (mRotationButtonController != null) {
            mCommandQueue.removeCallback(this);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        final Rect newSize = mContext.getResources().getConfiguration().windowConfiguration
                .getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.handleTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        mTouchEventHandler.onInterceptTouchEvent(event);
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (mRotationButtonController != null) {
            final boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
            mRotationButtonController.getRotationButton().setCanShowRotationButton(!imeShown);
        }
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;

        int statusBarPaddingTop = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_top);
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        int statusBarPaddingEnd = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_end);

        View sbContents = findViewById(R.id.status_bar_contents);
        sbContents.setPaddingRelative(
                statusBarPaddingStart,
                statusBarPaddingTop,
                statusBarPaddingEnd,
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, statusBarPaddingStart, 0, 0);

        setLayoutParams(layoutParams);
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout = mContentInsetsProvider.currentRotationHasCornerCutout();
        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
    }

    private void updateSafeInsets() {
        Pair<Integer, Integer> insets = mContentInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();

        setPadding(
                insets.first,
                getPaddingTop(),
                insets.second,
                getPaddingBottom());

        // Apply negative paddings to centered area layout so that we'll actually be on the center.
        final int winRotation = getDisplay().getRotation();
        LayoutParams centeredAreaParams =
                (LayoutParams) findViewById(R.id.centered_area).getLayoutParams();
        centeredAreaParams.leftMargin =
                winRotation == Surface.ROTATION_0 ? -insets.first : 0;
        centeredAreaParams.rightMargin =
                winRotation == Surface.ROTATION_0 ? -insets.second : 0;
    }

    /**
     * A handler responsible for all touch event handling on the status bar.
     *
     * Touches that occur on the status bar view may have ramifications for the notification
     * panel (e.g. a touch that pulls down the shade could start on the status bar), so this
     * interface provides a way to notify the panel controller when these touches occur.
     *
     * The handler will be notified each time {@link PhoneStatusBarView#onTouchEvent} and
     * {@link PhoneStatusBarView#onInterceptTouchEvent} are called.
     **/
    public interface TouchEventHandler {
        /** Called each time {@link PhoneStatusBarView#onInterceptTouchEvent} is called. */
        void onInterceptTouchEvent(MotionEvent event);

        /**
         * Called each time {@link PhoneStatusBarView#onTouchEvent} is called.
         *
         * Should return true if the touch was handled by this handler and false otherwise. The
         * return value from the handler will be returned from
         * {@link PhoneStatusBarView#onTouchEvent}.
         */
        boolean handleTouchEvent(MotionEvent event);
    }

    public ClockController getClockController() {
        return mClockController;
    }
}
