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
import static android.view.Surface.FRAME_RATE_COMPATIBILITY_GTE;
import static android.view.flags.Flags.FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY;
import static android.view.flags.Flags.FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY;
import static android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY;
import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;
import static android.view.flags.Flags.toolkitFrameRateBySizeReadOnly;
import static android.view.flags.Flags.toolkitFrameRateDefaultNormalReadOnly;
import static android.view.flags.Flags.toolkitFrameRateSmallUsesPercentReadOnly;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.DisplayMetrics;

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

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

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
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
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

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void lowVelocity60() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setFrameContentVelocity(1f);
            mMovingView.invalidate();
            assertEquals(60f, mViewRoot.getPreferredFrameRate(), 0f);
            assertEquals(FRAME_RATE_COMPATIBILITY_GTE, mViewRoot.getFrameRateCompatibility());
        });
    }

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void highVelocity140() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setFrameContentVelocity(1_000_000_000f);
            mMovingView.invalidate();
            assertEquals(140f, mViewRoot.getPreferredFrameRate(), 0f);
            assertEquals(FRAME_RATE_COMPATIBILITY_GTE, mViewRoot.getFrameRateCompatibility());
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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategorySmall() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = (int) smallSize;
                layoutParams.height = (int) smallSize;
            } else {
                float density = displayMetrics.density;
                layoutParams.height = ((int) (40 * density));
                layoutParams.width = ((int) (40 * density));
            }

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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryNarrowWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                int parentWidth = ((View) mMovingView.getParent()).getWidth();
                layoutParams.width = parentWidth;
                layoutParams.height = (int) (pixels / parentWidth);
            } else {
                float density = displayMetrics.density;
                layoutParams.width = (int) (10 * density);
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryNarrowHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                int parentHeight = ((View) mMovingView.getParent()).getHeight();
                layoutParams.width = (int) (pixels / parentHeight);
                layoutParams.height = parentHeight;
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                layoutParams.height = (int) (10 * density);
            }
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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryLargeWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = 1 + (int) Math.ceil(pixels / smallSize);
                layoutParams.height = (int) smallSize;
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ((int) Math.ceil(40 * density)) + 1;
                layoutParams.height = ((int) (40 * density));
            }
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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryLargeHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = (int) smallSize;
                layoutParams.height = 1 + (int) Math.ceil(pixels / smallSize);
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ((int) (40 * density));
                layoutParams.height = ((int) Math.ceil(40 * density)) + 1;
            }
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
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void defaultNormal() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            View parent = (View) mMovingView.getParent();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = parent.getWidth() / 2;
            layoutParams.height = parent.getHeight() / 2;
            mMovingView.setLayoutParams(layoutParams);
        });
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
