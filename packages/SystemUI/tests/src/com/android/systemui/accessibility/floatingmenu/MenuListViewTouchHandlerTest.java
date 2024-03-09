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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.View.OVER_SCROLL_NEVER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.MotionEventHelper;
import com.android.systemui.util.settings.SecureSettings;
import com.android.wm.shell.bubbles.DismissViewUtils;
import com.android.wm.shell.common.bubbles.DismissView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Tests for {@link MenuListViewTouchHandler}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuListViewTouchHandlerTest extends SysuiTestCase {
    private final List<AccessibilityTarget> mStubTargets = new ArrayList<>(
            Collections.singletonList(mock(AccessibilityTarget.class)));
    private final MotionEventHelper mMotionEventHelper = new MotionEventHelper();
    private MenuView mStubMenuView;
    private MenuListViewTouchHandler mTouchHandler;
    private MenuAnimationController mMenuAnimationController;
    private DismissAnimationController mDismissAnimationController;
    private RecyclerView mStubListView;
    private DismissView mDismissView;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AccessibilityManager mAccessibilityManager;

    @Before
    public void setUp() throws Exception {
        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        final MenuViewModel stubMenuViewModel = new MenuViewModel(mContext, mAccessibilityManager,
                mock(SecureSettings.class));
        final MenuViewAppearance stubMenuViewAppearance = new MenuViewAppearance(mContext,
                windowManager);
        mStubMenuView = new MenuView(mContext, stubMenuViewModel, stubMenuViewAppearance);
        mStubMenuView.setTranslationX(0);
        mStubMenuView.setTranslationY(0);
        mMenuAnimationController = spy(new MenuAnimationController(
                mStubMenuView, stubMenuViewAppearance));
        mDismissView = spy(new DismissView(mContext));
        DismissViewUtils.setup(mDismissView);
        mDismissAnimationController =
                spy(new DismissAnimationController(mDismissView, mStubMenuView));
        mTouchHandler = new MenuListViewTouchHandler(mMenuAnimationController,
                mDismissAnimationController);
        final AccessibilityTargetAdapter stubAdapter = new AccessibilityTargetAdapter(mStubTargets);
        mStubListView = (RecyclerView) mStubMenuView.getChildAt(0);
        mStubListView.setAdapter(stubAdapter);
    }

    @Test
    public void onActionDownEvent_shouldCancelAnimations() {
        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());

        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);

        verify(mMenuAnimationController).cancelAnimations();
    }

    @Test
    public void onActionMoveEvent_notConsumedEvent_shouldMoveToPosition() {
        doReturn(false).when(mDismissAnimationController).maybeConsumeMoveMotionEvent(
                any(MotionEvent.class));
        final int offset = 100;
        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());
        final MotionEvent stubMoveEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 3,
                        MotionEvent.ACTION_MOVE, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);
        mStubListView.setOverScrollMode(OVER_SCROLL_NEVER);

        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubMoveEvent);

        assertThat(mStubMenuView.getTranslationX()).isEqualTo(offset);
        assertThat(mStubMenuView.getTranslationY()).isEqualTo(offset);
    }

    @Test
    public void onActionMoveEvent_shouldShowDismissView() {
        final int offset = 100;
        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());
        final MotionEvent stubMoveEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 3,
                        MotionEvent.ACTION_MOVE, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);

        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubMoveEvent);

        verify(mDismissView).show();
    }

    @Test
    public void dragAndDrop_shouldFlingMenuThenSpringToEdge() {
        final int offset = 100;
        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());
        final MotionEvent stubMoveEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 3,
                        MotionEvent.ACTION_MOVE, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);
        final MotionEvent stubUpEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 5,
                        MotionEvent.ACTION_UP, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubMoveEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubUpEvent);

        verify(mMenuAnimationController).flingMenuThenSpringToEdge(anyFloat(), anyFloat(),
                anyFloat());
    }

    @Test
    public void dragMenuOutOfBoundsAndDrop_moveToLeftEdge_shouldMoveToEdgeAndHide() {
        final int offset = -100;
        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());
        final MotionEvent stubMoveEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 3,
                        MotionEvent.ACTION_MOVE, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);
        final MotionEvent stubUpEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 5,
                        MotionEvent.ACTION_UP, mStubMenuView.getTranslationX() + offset,
                        mStubMenuView.getTranslationY() + offset);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubMoveEvent);
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubUpEvent);

        verify(mMenuAnimationController).moveToEdgeAndHide();
    }

    @Test
    public void receiveActionDownMotionEvent_verifyOnActionDownEnd() {
        final Runnable onActionDownEnd = mock(Runnable.class);
        mTouchHandler.setOnActionDownEndListener(onActionDownEnd);

        final MotionEvent stubDownEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0, /* eventTime= */ 1,
                        MotionEvent.ACTION_DOWN, mStubMenuView.getTranslationX(),
                        mStubMenuView.getTranslationY());
        mTouchHandler.onInterceptTouchEvent(mStubListView, stubDownEvent);

        verify(onActionDownEnd).run();
    }

    @After
    public void tearDown() {
        mMotionEventHelper.recycleEvents();
        mMenuAnimationController.mPositionAnimations.values().forEach(DynamicAnimation::cancel);
    }
}
