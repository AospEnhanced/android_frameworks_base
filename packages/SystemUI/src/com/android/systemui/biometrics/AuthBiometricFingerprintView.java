/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;


import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;

import lineageos.app.LineageContextConstants;

public class AuthBiometricFingerprintView extends AuthBiometricView {

    private static final String TAG = "BiometricPrompt/AuthBiometricFingerprintView";

    private final boolean mHasFod;

    public AuthBiometricFingerprintView(Context context) {
        this(context, null);
    }

    public AuthBiometricFingerprintView(Context context, AttributeSet attrs) {
        super(context, attrs);

        PackageManager packageManager = mContext.getPackageManager();
        mHasFod = packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) &&
                packageManager.hasSystemFeature(LineageContextConstants.Features.FOD);
    }

    @Override
    protected int getDelayAfterAuthenticatedDurationMs() {
        return 0;
    }

    @Override
    protected int getStateForAfterError() {
        return STATE_AUTHENTICATING;
    }

    @Override
    protected void handleResetAfterError() {
        showTouchSensorString();
    }

    @Override
    protected void handleResetAfterHelp() {
        showTouchSensorString();
    }

    @Override
    protected boolean supportsSmallDialog() {
        return false;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        if (mHasFod) {
            final int navbarHeight = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.navigation_bar_height);
            final int fodMargin = getResources().getDimensionPixelSize(
                    R.dimen.biometric_dialog_fod_margin);

            mIconView.setVisibility(View.INVISIBLE);
            // The view is invisible, so it still takes space and
            // we use that to adjust for the FOD.
            mIconView.setPadding(0, 0, 0, fodMargin - navbarHeight);

            // Add error text above the biometric icon.
            this.removeView(mIndicatorView);
            this.addView(mIndicatorView, this.indexOfChild(mIconView));
        } else {
            mIconView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void updateState(@BiometricState int newState) {
        updateIcon(mState, newState);

        // Do this last since the state variable gets updated.
        super.updateState(newState);
    }

    @Override
    void onAttachedToWindowInternal() {
        super.onAttachedToWindowInternal();
        showTouchSensorString();
    }

    private void showTouchSensorString() {
        mIndicatorView.setText(R.string.fingerprint_dialog_touch_sensor);
        mIndicatorView.setTextColor(R.color.biometric_dialog_gray);
    }

    private void updateIcon(int lastState, int newState) {
        final Drawable icon = getAnimationForTransition(lastState, newState);
        if (icon == null) {
            Log.e(TAG, "Animation not found, " + lastState + " -> " + newState);
            return;
        }

        final AnimatedVectorDrawable animation = icon instanceof AnimatedVectorDrawable
                ? (AnimatedVectorDrawable) icon
                : null;

        mIconView.setImageDrawable(icon);

        if (animation != null && shouldAnimateForTransition(lastState, newState)) {
            animation.forceAnimationOnUI();
            animation.start();
        }
    }

    private boolean shouldAnimateForTransition(int oldState, int newState) {
        switch (newState) {
            case STATE_HELP:
            case STATE_ERROR:
                return true;
            case STATE_AUTHENTICATING_ANIMATING_IN:
            case STATE_AUTHENTICATING:
                if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                    return true;
                } else {
                    return false;
                }
            case STATE_AUTHENTICATED:
                return false;
            default:
                return false;
        }
    }

    private Drawable getAnimationForTransition(int oldState, int newState) {
        int iconRes;

        switch (newState) {
            case STATE_HELP:
            case STATE_ERROR:
                iconRes = R.drawable.fingerprint_dialog_fp_to_error;
                break;
            case STATE_AUTHENTICATING_ANIMATING_IN:
            case STATE_AUTHENTICATING:
                if (oldState == STATE_ERROR || oldState == STATE_HELP) {
                    iconRes = R.drawable.fingerprint_dialog_error_to_fp;
                } else {
                    iconRes = R.drawable.fingerprint_dialog_fp_to_error;
                }
                break;
            case STATE_AUTHENTICATED:
                iconRes = R.drawable.fingerprint_dialog_fp_to_error;
                break;
            default:
                return null;
        }

        return mContext.getDrawable(iconRes);
    }
}
