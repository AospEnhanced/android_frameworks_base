/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.net.ConnectivityManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.method.SingleLineTransformationMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.widget.LockPatternUtils;

import java.util.List;
import java.util.Locale;

public class CarrierText extends TextView {
    private static final String TAG = "CarrierText";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static CharSequence mSeparator;

    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private boolean mDisplayAirplaneMode;
    private boolean mAirplaneModeActive;

    private KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            updateCarrierText();
        }

        @Override
        public void onSimStateChanged(int subId, int slotId, IccCardConstants.State simState) {
            updateCarrierText();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            updateCarrierText();
        }

        @Override
        void onAirplaneModeChanged(boolean on) {
            mAirplaneModeActive = on;
            if (mDisplayAirplaneMode) {
                updateCarrierText();
            }
        }

        @Override
        public void onScreenTurnedOff(int why) {
            setSelected(false);
        };

        @Override
        public void onScreenTurnedOn() {
            setSelected(true);
            updateCarrierText();
        };
    };
    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    private static enum StatusMode {
        // Normal case (sim card present, it's not locked)
        Normal,
        // SIM card is 'perso locked'.
        PersoLocked,
        // SIM card is missing.
        SimMissing,
        // SIM card is missing, and device isn't provisioned; dont allow access
        SimMissingLocked,
        // SIM card is PUK locked because SIM entered wrong too many times
        SimPukLocked,
        // SIM card is currently locked
        SimLocked,
        // SIM card is permanently disabled due to PUK unlock failure
        SimPermDisabled,
        // SIM is not ready yet. May never be on devices w/o a SIM.
        SimNotReady,
        // The sim card is faulty
        SimIoError,
        // Unknown - The SIM card isn't really missing.
        Unknown
    }

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLockPatternUtils = new LockPatternUtils(mContext);

	        boolean useAllCaps;
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CarrierText, 0, 0);
        try {
            useAllCaps = a.getBoolean(R.styleable.CarrierText_allCaps, false);
        } finally {
            a.recycle();
        }
        setTransformationMethod(new CarrierTextTransformationMethod(mContext, useAllCaps));
    }

    protected void updateCarrierText() {
        if (mDisplayAirplaneMode && mAirplaneModeActive) {
            setText(com.android.internal.R.string.lockscreen_airplane_mode_on);
            return;
        }

        boolean allSimsMissing = true;
        CharSequence displayText = null;
        List<SubscriptionInfo> subs = mKeyguardUpdateMonitor.getSubscriptionInfo(false);
        final int N = subs.size();

        if (DEBUG) Log.d(TAG, "updateCarrierText(): " + N);

        for (int i = 0; i < N; i++) {
            int subId = subs.get(i).getSubscriptionId();
            State simState = mKeyguardUpdateMonitor.getSimState(subId);
            ServiceState serviceState = mKeyguardUpdateMonitor.getServiceState(subId);
            CharSequence carrierName = subs.get(i).getCarrierName();
            CharSequence carrierTextForState =
                    getCarrierTextForState(simState, serviceState, carrierName);
            if (carrierTextForState != null) {
                allSimsMissing = false;
                displayText = concatenate(displayText, carrierTextForState, " | ");
            }
        }

        if (allSimsMissing) {
            if (N != 0) {
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                // Grab the first subscripton, because they all should contain the emergency text,
                // described above.
                displayText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        subs.get(0).getCarrierName());
            } else {
                // We don't have a SubscriptionInfo to get the emergency calls only from.
                // Lets just make it ourselves.
                displayText =  makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_missing_sim_message_short),
                        getContext().getText(com.android.internal.R.string.emergency_calls_only));
            }
        }
        setText(displayText);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSeparator = getResources().getString(
                com.android.internal.R.string.kg_text_message_separator);
        mDisplayAirplaneMode = getResources().getBoolean(R.bool.config_display_APM);

        final boolean screenOn = KeyguardUpdateMonitor.getInstance(mContext).isScreenOn();
        setSelected(screenOn); // Allow marquee to work.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ConnectivityManager.from(mContext).isNetworkSupported(
                ConnectivityManager.TYPE_MOBILE)) {
            mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            mKeyguardUpdateMonitor.registerCallback(mCallback);
        } else {
            // Don't listen and clear out the text when the device isn't a phone.
            mKeyguardUpdateMonitor = null;
            setText("");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
        }
    }

    /**
     * Top-level function for creating carrier text. Makes text based on simState, PLMN
     * and SPN as well as device capabilities, such as being emergency call capable.
     *
     * @param simState
     * @param plmn
     * @param spn
     * @return
     */
    private CharSequence getCarrierTextForState(IccCardConstants.State simState,
            ServiceState serviceState, CharSequence text) {
        CharSequence carrierText = null;
        StatusMode status = getStatusForIccState(simState);

        switch (status) {
            case Unknown:
            case Normal:
                carrierText = text;
                break;

            case SimNotReady:
                // Null is reserved for denoting missing, in this case we have nothing to display.
                carrierText = ""; // nothing to display yet.
                break;

            case PersoLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_perso_locked_message), text);
                break;

            case SimMissing:
                carrierText = null;
                break;

            case SimPermDisabled:
                carrierText = getContext().getText(
                        R.string.keyguard_permanent_disabled_sim_message_short);
                break;

            case SimMissingLocked:
                carrierText = null;
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_locked_message),
                        text);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_puk_locked_message),
                        text);
                break;
            case SimIoError:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.lockscreen_sim_error_message_short),
                        text);
                break;
        }

        //display 2G/3G/4G if operator ask for showing radio tech
        if (serviceState != null && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_display_rat)) {
            int networkType = serviceState.getDataNetworkType();
            if (networkType == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                networkType = serviceState.getVoiceNetworkType();
            }
            if (networkType != TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                TelephonyManager tm = TelephonyManager.from(mContext);
                carrierText = carrierText + " " + tm.networkTypeToString(networkType);
            }
        }

        if (DEBUG) Log.d(TAG, "getCarrierTextForSimState: carrierText=" + carrierText);
        return carrierText;
    }

    /*
     * Add emergencyCallMessage to carrier string only if phone supports emergency calls.
     */
    private CharSequence makeCarrierStringOnEmergencyCapable(
            CharSequence simMessage, CharSequence emergencyCallMessage) {
        if (mLockPatternUtils.isEmergencyCallCapable()) {
            return concatenate(simMessage, emergencyCallMessage);
        }
        return simMessage;
    }

    /**
     * Determine the current status of the lock screen given the SIM state and other stuff.
     */
    private StatusMode getStatusForIccState(IccCardConstants.State simState) {
        // Since reading the SIM may take a while, we assume it is present until told otherwise.
        if (simState == null) {
            return StatusMode.Normal;
        }

        final boolean missingAndNotProvisioned =
                !KeyguardUpdateMonitor.getInstance(mContext).isDeviceProvisioned()
                && (simState == IccCardConstants.State.ABSENT ||
                        simState == IccCardConstants.State.PERM_DISABLED);

        // Assume we're PERSO_LOCKED if not provisioned
        simState = missingAndNotProvisioned ? IccCardConstants.State.PERSO_LOCKED : simState;
        switch (simState) {
            case ABSENT:
                return StatusMode.SimMissing;
            case PERSO_LOCKED:
                return StatusMode.PersoLocked;
            case NOT_READY:
                return StatusMode.SimNotReady;
            case PIN_REQUIRED:
                return StatusMode.SimLocked;
            case PUK_REQUIRED:
                return StatusMode.SimPukLocked;
            case READY:
                return StatusMode.Normal;
            case PERM_DISABLED:
                return StatusMode.SimPermDisabled;
            case UNKNOWN:
                return StatusMode.Unknown;
            case CARD_IO_ERROR:
                return StatusMode.SimIoError;
        }
        return StatusMode.SimMissing;
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn) {
        return concatenate(plmn, spn, mSeparator);
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn,
            CharSequence separator) {
        final boolean plmnValid = !TextUtils.isEmpty(plmn);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (plmnValid && spnValid) {
            if (plmn.equals(spn)) {
                return plmn;
            } else {
                return new StringBuilder().append(plmn).append(separator).append(spn).toString();
            }
        } else if (plmnValid) {
            return plmn;
        } else if (spnValid) {
            return spn;
        } else {
            return "";
        }
    }

    private CharSequence getCarrierHelpTextForSimState(IccCardConstants.State simState,
            String plmn, String spn) {
        int carrierHelpTextId = 0;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case PersoLocked:
                carrierHelpTextId = R.string.keyguard_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                carrierHelpTextId = R.string.keyguard_permanent_disabled_sim_instructions;
                break;

            case SimMissingLocked:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions;
                break;

            case Normal:
            case SimLocked:
            case SimPukLocked:
                break;
        }

        return mContext.getText(carrierHelpTextId);
    }

    private class CarrierTextTransformationMethod extends SingleLineTransformationMethod {
        private final Locale mLocale;
        private final boolean mAllCaps;

        public CarrierTextTransformationMethod(Context context, boolean allCaps) {
            mLocale = context.getResources().getConfiguration().locale;
            mAllCaps = allCaps;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            source = super.getTransformation(source, view);

            if (mAllCaps && source != null) {
                source = source.toString().toUpperCase(mLocale);
            }

            return source;
        }
    }
}
