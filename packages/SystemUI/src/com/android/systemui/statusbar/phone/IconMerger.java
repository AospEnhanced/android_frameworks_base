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

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.Clock;

public class IconMerger extends LinearLayout {
    private static final String TAG = "IconMerger";
    private static final boolean DEBUG = false;

    private int mIconSize;
    private int mClockLocation;
    private View mMoreView;

    public IconMerger(Context context, AttributeSet attrs) {
        super(context, attrs);

        mIconSize = context.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_size);

        if (DEBUG) {
            setBackgroundColor(0x800099FF);
        }
    }

    public void setOverflowIndicator(View v) {
        mMoreView = v;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        // we need to constrain this to an integral multiple of our children
        int width = getMeasuredWidth();
        if (mClockLocation == ClockController.STYLE_CLOCK_CENTER) {
            int totalWidth = getResources().getDisplayMetrics().widthPixels;
            width = totalWidth / 2 - mIconSize * 2;
        }
        setMeasuredDimension(width - (width % mIconSize), getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkOverflow(r - l);
    }

    private void checkOverflow(int width) {
        if (mMoreView == null) return;

        final int N = getChildCount();
        int visibleChildren = 0;
        for (int i=0; i<N; i++) {
            if (getChildAt(i).getVisibility() != GONE) visibleChildren++;
        }
        final boolean overflowShown = (mMoreView.getVisibility() == View.VISIBLE);
        // let's assume we have one more slot if the more icon is already showing
        if (overflowShown) {
            int totalWidth = getResources().getDisplayMetrics().widthPixels;
            if ((mClockLocation != ClockController.STYLE_CLOCK_CENTER &&
                    mClockLocation != ClockController.STYLE_CLOCK_LEFT) ||
                    (visibleChildren > (totalWidth / mIconSize / 2 + 1))) {
                visibleChildren--;
            }
        }
        final boolean moreRequired = visibleChildren * mIconSize > width;
        if (moreRequired != overflowShown) {
            post(new Runnable() {
                @Override
                public void run() {
                    mMoreView.setVisibility(moreRequired ? View.VISIBLE : View.GONE);
                }
            });
        }
    }

    public void setClockAndDateStatus(int mode) {
        mClockLocation = mode;

    }
}
