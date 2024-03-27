/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.view;

import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH;
import static android.view.Surface.FRAME_RATE_CATEGORY_LOW;
import static android.view.Surface.FRAME_RATE_CATEGORY_NORMAL;
import static android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY;
import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;
import static android.view.flags.Flags.toolkitFrameRateBySizeReadOnly;
import static android.view.flags.Flags.toolkitFrameRateDefaultNormalReadOnly;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewFrameRateTest {

    @Rule
    public ActivityTestRule<ViewCaptureTestActivity> mActivityRule = new ActivityTestRule<>(
            ViewCaptureTestActivity.class);

    private Activity mActivity;
    private View mMovingView;
    private ViewRootImpl mViewRoot;

    @Before
    public void setUp() throws Throwable {
        mActivity = mActivityRule.getActivity();
        mActivityRule.runOnUiThread(() -> {
            mActivity.setContentView(R.layout.view_velocity_test);
            mMovingView = mActivity.findViewById(R.id.moving_view);
        });
        ViewParent parent = mActivity.getWindow().getDecorView().getParent();
        while (parent instanceof View) {
            parent = parent.getParent();
        }
        mViewRoot = (ViewRootImpl) parent;
    }

    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void frameRateChangesWhenContentMoves() {
        mMovingView.offsetLeftAndRight(100);
        float frameRate = mViewRoot.getPreferredFrameRate();
        assertTrue(frameRate > 0);
    }

    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void firstFrameNoMovement() {
        assertEquals(0f, mViewRoot.getPreferredFrameRate(), 0f);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void touchBoostDisable() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(
                    /* downTime */ now,
                    /* eventTime */ now,
                    /* action */ MotionEvent.ACTION_DOWN,
                    /* x */ 0f,
                    /* y */ 0f,
                    /* metaState */ 0
            );
            mActivity.dispatchTouchEvent(down);
            mMovingView.offsetLeftAndRight(10);
        });
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
        });

        mActivityRule.runOnUiThread(() -> {
            assertFalse(mViewRoot.getIsTouchBoosting());
        });
    }

    private void waitForFrameRateCategoryToSettle() throws Throwable {
        for (int i = 0; i < 5; i++) {
            final CountDownLatch drawLatch = new CountDownLatch(1);

            // Now that it is small, any invalidation should have a normal category
            mActivityRule.runOnUiThread(() -> {
                mMovingView.invalidate();
                mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch::countDown);
            });

            assertTrue(drawLatch.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void noVelocityUsesCategorySmall() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            float density = mActivity.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.height = 4 * ((int) (10 * density));
            layoutParams.width = 4 * ((int) (10 * density));
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            assertEquals(expected, mViewRoot.getPreferredFrameRateCategory());
        });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void noVelocityUsesCategoryNarrowWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            float density = mActivity.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.width = (int) (10 * density);
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            assertEquals(expected, mViewRoot.getPreferredFrameRateCategory());
        });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void noVelocityUsesCategoryNarrowHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            float density = mActivity.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.height = (int) (10 * density);
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            assertEquals(expected, mViewRoot.getPreferredFrameRateCategory());
        });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void noVelocityUsesCategoryLargeWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            float density = mActivity.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.height = 4 * ((int) (10 * density));
            layoutParams.width = 4 * ((int) Math.ceil(10 * density)) + 1;
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a high category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            assertEquals(expected, mViewRoot.getPreferredFrameRateCategory());
        });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void noVelocityUsesCategoryLargeHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            float density = mActivity.getResources().getDisplayMetrics().density;
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.height = 4 * ((int) Math.ceil(10 * density)) + 1;
            layoutParams.width = 4 * ((int) (10 * density));
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a high category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            assertEquals(expected, mViewRoot.getPreferredFrameRateCategory());
        });
    }

    @Test
    @RequiresFlagsEnabled(FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY)
    public void defaultNormal() throws Throwable {
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            assertEquals(expected,
                    mViewRoot.getPreferredFrameRateCategory());
        });
    }
}
