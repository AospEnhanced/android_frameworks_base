/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.systemBars;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Insets;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper()
public class KeyguardSecurityContainerTest extends SysuiTestCase {
    private static final int SCREEN_WIDTH = 1600;
    private static final int FAKE_MEASURE_SPEC =
            View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH, View.MeasureSpec.EXACTLY);

    @Rule
    public MockitoRule mRule = MockitoJUnit.rule();

    @Mock
    private WindowInsetsController mWindowInsetsController;

    @Mock
    private KeyguardSecurityViewFlipper mSecurityViewFlipper;

    private KeyguardSecurityContainer mKeyguardSecurityContainer;

    @Before
    public void setup() {
        // Needed here, otherwise when mKeyguardSecurityContainer is created below, it'll cache
        // the real references (rather than the TestableResources that this call creates).
        mContext.ensureTestableResources();
        FrameLayout.LayoutParams securityViewFlipperLayoutParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        when(mSecurityViewFlipper.getWindowInsetsController()).thenReturn(mWindowInsetsController);
        when(mSecurityViewFlipper.getLayoutParams()).thenReturn(securityViewFlipperLayoutParams);
        mKeyguardSecurityContainer = new KeyguardSecurityContainer(getContext());
        mKeyguardSecurityContainer.mSecurityViewFlipper = mSecurityViewFlipper;
        mKeyguardSecurityContainer.addView(mSecurityViewFlipper, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Test
    public void onMeasure_usesHalfWidthWithOneHandedModeEnabled() {
        mKeyguardSecurityContainer.setOneHandedMode(/* oneHandedMode= */true);

        int halfWidthMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(SCREEN_WIDTH / 2, View.MeasureSpec.EXACTLY);
        mKeyguardSecurityContainer.onMeasure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);

        verify(mSecurityViewFlipper).measure(halfWidthMeasureSpec, FAKE_MEASURE_SPEC);
    }

    @Test
    public void onMeasure_usesFullWidthWithOneHandedModeDisabled() {
        mKeyguardSecurityContainer.setOneHandedMode(/* oneHandedMode= */false);

        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
    }

    @Test
    public void onMeasure_respectsViewInsets() {
        int imeInsetAmount = 100;
        int systemBarInsetAmount = 10;

        mKeyguardSecurityContainer.setOneHandedMode(/* oneHandedMode= */false);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        // It's reduced by the max of the systembar and IME, so just subtract IME inset.
        int expectedHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                SCREEN_WIDTH - imeInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, expectedHeightMeasureSpec);
    }

    @Test
    public void onMeasure_respectsViewInsets_largerSystembar() {
        int imeInsetAmount = 0;
        int systemBarInsetAmount = 10;

        mKeyguardSecurityContainer.setOneHandedMode(/* oneHandedMode= */false);

        Insets imeInset = Insets.of(0, 0, 0, imeInsetAmount);
        Insets systemBarInset = Insets.of(0, 0, 0, systemBarInsetAmount);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(ime(), imeInset)
                .setInsetsIgnoringVisibility(systemBars(), systemBarInset)
                .build();

        int expectedHeightMeasureSpec = View.MeasureSpec.makeMeasureSpec(
                SCREEN_WIDTH - systemBarInsetAmount, View.MeasureSpec.EXACTLY);

        mKeyguardSecurityContainer.onApplyWindowInsets(insets);
        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        verify(mSecurityViewFlipper).measure(FAKE_MEASURE_SPEC, expectedHeightMeasureSpec);
    }

    private void setupForUpdateKeyguardPosition(boolean oneHandedMode) {
        mKeyguardSecurityContainer.setOneHandedMode(oneHandedMode);
        mKeyguardSecurityContainer.setOneHandedModeLeftAligned(true, false);

        mKeyguardSecurityContainer.measure(FAKE_MEASURE_SPEC, FAKE_MEASURE_SPEC);
        mKeyguardSecurityContainer.layout(0, 0, SCREEN_WIDTH, SCREEN_WIDTH);

        // Clear any interactions with the mock so we know the interactions definitely come from the
        // below testing.
        reset(mSecurityViewFlipper);
    }

    @Test
    public void setIsLeftAligned_movesKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ true);

        mKeyguardSecurityContainer.setOneHandedModeLeftAligned(
                /* leftAligned= */false, /* animate= */false);
        verify(mSecurityViewFlipper).setTranslationX(
                mKeyguardSecurityContainer.getWidth() - mSecurityViewFlipper.getWidth());

        mKeyguardSecurityContainer.setOneHandedModeLeftAligned(
                /* leftAligned= */true, /* animate= */false);
        verify(mSecurityViewFlipper).setTranslationX(0.0f);
    }

    @Test
    public void setIsLeftAligned_doesntMoveTwoHandedKeyguard() {
        setupForUpdateKeyguardPosition(/* oneHandedMode= */ false);

        mKeyguardSecurityContainer.setOneHandedModeLeftAligned(
                /* leftAligned= */false, /* animate= */false);
        verify(mSecurityViewFlipper, never()).setTranslationX(anyInt());

        mKeyguardSecurityContainer.setOneHandedModeLeftAligned(
                /* leftAligned= */true, /* animate= */false);
        verify(mSecurityViewFlipper, never()).setTranslationX(anyInt());
    }
}
