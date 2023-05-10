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

package com.android.keyguard;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Trace;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.WirelessUtils;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository;
import com.android.systemui.telephony.TelephonyListenerManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

/**
 * Controller that generates text including the carrier names and/or the status of all the SIM
 * interfaces in the device. Through a callback, the updates can be retrieved either as a list or
 * separated by a given separator {@link CharSequence}.
 *
 * @deprecated use {@link com.android.systemui.statusbar.pipeline.wifi} instead
 */
@Deprecated
public class CarrierTextManager {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "CarrierTextController";

    private final boolean mIsEmergencyCallCapable;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;
    private boolean mTelephonyCapable;
    private final boolean mShowMissingSim;
    private final boolean mShowAirplaneMode;
    private final AtomicBoolean mNetworkSupported = new AtomicBoolean();
    @VisibleForTesting
    protected KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final WifiRepository mWifiRepository;
    private final boolean[] mSimErrorState;
    private final int mSimSlotsNumber;
    @Nullable // Check for nullability before dispatching
    private CarrierTextCallback mCarrierTextCallback;
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final CharSequence mSeparator;
    private final TelephonyListenerManager mTelephonyListenerManager;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onFinishedWakingUp() {
                    final CarrierTextCallback callback = mCarrierTextCallback;
                    if (callback != null) callback.finishedWakingUp();
                }

                @Override
                public void onStartedGoingToSleep() {
                    final CarrierTextCallback callback = mCarrierTextCallback;
                    if (callback != null) callback.startedGoingToSleep();
                }
            };

    @VisibleForTesting
    protected final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            if (DEBUG) {
                Log.d(TAG, "onRefreshCarrierInfo(), mTelephonyCapable: "
                        + Boolean.toString(mTelephonyCapable));
            }
            updateCarrierText();
        }

        @Override
        public void onTelephonyCapable(boolean capable) {
            if (DEBUG) {
                Log.d(TAG, "onTelephonyCapable() mTelephonyCapable: "
                        + Boolean.toString(capable));
            }
            mTelephonyCapable = capable;
            updateCarrierText();
        }

        public void onSimStateChanged(int subId, int slotId, int simState) {
            if (slotId < 0 || slotId >= mSimSlotsNumber) {
                Log.d(TAG, "onSimStateChanged() - slotId invalid: " + slotId
                        + " mTelephonyCapable: " + Boolean.toString(mTelephonyCapable));
                return;
            }

            if (DEBUG) Log.d(TAG, "onSimStateChanged: " + getStatusForIccState(simState));
            if (getStatusForIccState(simState) == CarrierTextManager.StatusMode.SimIoError) {
                mSimErrorState[slotId] = true;
                updateCarrierText();
            } else if (mSimErrorState[slotId]) {
                mSimErrorState[slotId] = false;
                updateCarrierText();
            }
        }
    };

    private final ActiveDataSubscriptionIdListener mPhoneStateListener =
            new ActiveDataSubscriptionIdListener() {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            if (mNetworkSupported.get() && mCarrierTextCallback != null) {
                updateCarrierText();
            }
        }
    };

    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    private enum StatusMode {
        Normal, // Normal case (sim card present, it's not locked)
        NetworkLocked, // SIM card is 'network locked'.
        SimMissing, // SIM card is missing.
        SimMissingLocked, // SIM card is missing, and device isn't provisioned; don't allow access
        SimPukLocked, // SIM card is PUK locked because SIM entered wrong too many times
        SimLocked, // SIM card is currently locked
        SimPermDisabled, // SIM card is permanently disabled due to PUK unlock failure
        SimNotReady, // SIM is not ready yet. May never be on devices w/o a SIM.
        SimIoError, // SIM card is faulty
        SimUnknown // SIM card is unknown
    }

    /**
     * Controller that provides updates on text with carriers names or SIM status.
     * Used by {@link CarrierText}.
     *
     * @param separator Separator between different parts of the text
     */
    private CarrierTextManager(
            Context context,
            CharSequence separator,
            boolean showAirplaneMode,
            boolean showMissingSim,
            WifiRepository wifiRepository,
            TelephonyManager telephonyManager,
            TelephonyListenerManager telephonyListenerManager,
            WakefulnessLifecycle wakefulnessLifecycle,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            KeyguardUpdateMonitor keyguardUpdateMonitor) {
        mContext = context;
        mIsEmergencyCallCapable = telephonyManager.isVoiceCapable();

        mShowAirplaneMode = showAirplaneMode;
        mShowMissingSim = showMissingSim;
        mWifiRepository = wifiRepository;
        mTelephonyManager = telephonyManager;
        mSeparator = separator;
        mTelephonyListenerManager = telephonyListenerManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mSimSlotsNumber = getTelephonyManager().getSupportedModemCount();
        mSimErrorState = new boolean[mSimSlotsNumber];
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBgExecutor.execute(() -> {
            boolean supported = mContext.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
            if (supported && mNetworkSupported.compareAndSet(false, supported)) {
                // This will set/remove the listeners appropriately. Note that it will never double
                // add the listeners.
                handleSetListening(mCarrierTextCallback);
            }
        });
    }

    private TelephonyManager getTelephonyManager() {
        return mTelephonyManager;
    }

    /**
     * Checks if there are faulty cards. Adds the text depending on the slot of the card
     *
     * @param text:   current carrier text based on the sim state
     * @param carrierNames names order by subscription order
     * @param subOrderBySlot array containing the sub index for each slot ID
     * @param noSims: whether a valid sim card is inserted
     * @return text
     */
    private CharSequence updateCarrierTextWithSimIoError(CharSequence text,
            CharSequence[] carrierNames, int[] subOrderBySlot, boolean noSims) {
        final CharSequence carrier = "";
        CharSequence carrierTextForSimIOError = getCarrierTextForSimState(
                TelephonyManager.SIM_STATE_CARD_IO_ERROR, carrier);
        // mSimErrorState has the state of each sim indexed by slotID.
        for (int index = 0; index < getTelephonyManager().getActiveModemCount(); index++) {
            if (!mSimErrorState[index]) {
                continue;
            }
            // In the case when no sim cards are detected but a faulty card is inserted
            // overwrite the text and only show "Invalid card"
            if (noSims) {
                return concatenate(carrierTextForSimIOError,
                        getContext().getText(
                                com.android.internal.R.string.emergency_calls_only),
                        mSeparator);
            } else if (subOrderBySlot[index] != -1) {
                int subIndex = subOrderBySlot[index];
                // prepend "Invalid card" when faulty card is inserted in slot 0 or 1
                carrierNames[subIndex] = concatenate(carrierTextForSimIOError,
                        carrierNames[subIndex],
                        mSeparator);
            } else {
                // concatenate "Invalid card" when faulty card is inserted in other slot
                text = concatenate(text, carrierTextForSimIOError, mSeparator);
            }

        }
        return text;
    }

    /**
     * This may be called internally after retrieving the correct value of {@code mNetworkSupported}
     * (assumed false to start). In that case, the following happens:
     * <ul>
     *     <li> If there was a registered callback, and the network is supported, it will register
     *          listeners.
     *     <li> If there was not a registered callback, it will try to remove unregistered listeners
     *          which is a no-op
     * </ul>
     *
     * This call will always be processed in a background thread.
     */
    private void handleSetListening(CarrierTextCallback callback) {
        if (callback != null) {
            mCarrierTextCallback = callback;
            if (mNetworkSupported.get()) {
                // Keyguard update monitor expects callbacks from main thread
                mMainExecutor.execute(() -> {
                    mKeyguardUpdateMonitor.registerCallback(mCallback);
                    mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
                });
                mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mPhoneStateListener);
            } else {
                // Don't listen and clear out the text when the device isn't a phone.
                mMainExecutor.execute(() -> callback.updateCarrierInfo(
                        new CarrierTextCallbackInfo("", null, false, null)
                ));
            }
        } else {
            mCarrierTextCallback = null;
            mMainExecutor.execute(() -> {
                mKeyguardUpdateMonitor.removeCallback(mCallback);
                mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
            });
            mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mPhoneStateListener);
        }
    }

    /**
     * Sets the listening status of this controller. If the callback is null, it is set to
     * not listening.
     *
     * @param callback Callback to provide text updates
     */
    public void setListening(CarrierTextCallback callback) {
        mBgExecutor.execute(() -> handleSetListening(callback));
    }

    protected List<SubscriptionInfo> getSubscriptionInfo() {
        return mKeyguardUpdateMonitor.getFilteredSubscriptionInfo();
    }

    protected void updateCarrierText() {
        Trace.beginSection("CarrierTextManager#updateCarrierText");
        boolean allSimsMissing = true;
        boolean anySimReadyAndInService = false;
        CharSequence displayText = null;
        List<SubscriptionInfo> subs = getSubscriptionInfo();

        final int numSubs = subs.size();
        final int[] subsIds = new int[numSubs];
        // This array will contain in position i, the index of subscription in slot ID i.
        // -1 if no subscription in that slot
        final int[] subOrderBySlot = new int[mSimSlotsNumber];
        for (int i = 0; i < mSimSlotsNumber; i++) {
            subOrderBySlot[i] = -1;
        }
        final CharSequence[] carrierNames = new CharSequence[numSubs];
        if (DEBUG) Log.d(TAG, "updateCarrierText(): " + numSubs);

        for (int i = 0; i < numSubs; i++) {
            int subId = subs.get(i).getSubscriptionId();
            carrierNames[i] = "";
            subsIds[i] = subId;
            subOrderBySlot[subs.get(i).getSimSlotIndex()] = i;
            int simState = mKeyguardUpdateMonitor.getSimState(subId);
            CharSequence carrierName = subs.get(i).getCarrierName();
            CharSequence carrierTextForSimState = getCarrierTextForSimState(simState, carrierName);
            if (DEBUG) {
                Log.d(TAG, "Handling (subId=" + subId + "): " + simState + " " + carrierName);
            }
            if (carrierTextForSimState != null) {
                allSimsMissing = false;
                carrierNames[i] = carrierTextForSimState;
            }
            if (simState == TelephonyManager.SIM_STATE_READY) {
                Trace.beginSection("WFC check");
                ServiceState ss = mKeyguardUpdateMonitor.mServiceStates.get(subId);
                if (ss != null && ss.getDataRegistrationState() == ServiceState.STATE_IN_SERVICE) {
                    // hack for WFC (IWLAN) not turning off immediately once
                    // Wi-Fi is disassociated or disabled
                    if (ss.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                            || mWifiRepository.isWifiConnectedWithValidSsid()) {
                        if (DEBUG) {
                            Log.d(TAG, "SIM ready and in service: subId=" + subId + ", ss=" + ss);
                        }
                        anySimReadyAndInService = true;
                    }
                }
                Trace.endSection();
            }
        }
        // Only create "No SIM card" if no cards with CarrierName && no wifi when some sim is READY
        // This condition will also be true always when numSubs == 0
        if (allSimsMissing && !anySimReadyAndInService) {
            if (numSubs != 0) {
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                // Grab the first subscripton, because they all should contain the emergency text,
                // described above.
                displayText = makeCarrierStringOnEmergencyCapable(
                        getMissingSimMessage(), subs.get(0).getCarrierName());
            } else {
                // We don't have a SubscriptionInfo to get the emergency calls only from.
                // Grab it from the old sticky broadcast if possible instead. We can use it
                // here because no subscriptions are active, so we don't have
                // to worry about MSIM clashing.
                CharSequence text =
                        getContext().getText(com.android.internal.R.string.emergency_calls_only);
                Intent i = getContext().registerReceiver(null,
                        new IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED));
                if (i != null) {
                    String spn = "";
                    String plmn = "";
                    if (i.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false)) {
                        spn = i.getStringExtra(TelephonyManager.EXTRA_SPN);
                    }
                    if (i.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false)) {
                        plmn = i.getStringExtra(TelephonyManager.EXTRA_PLMN);
                    }
                    if (DEBUG) Log.d(TAG, "Getting plmn/spn sticky brdcst " + plmn + "/" + spn);
                    if (Objects.equals(plmn, spn)) {
                        text = plmn;
                    } else {
                        text = concatenate(plmn, spn, mSeparator);
                    }
                }
                displayText = makeCarrierStringOnEmergencyCapable(getMissingSimMessage(), text);
            }
        }

        if (TextUtils.isEmpty(displayText)) displayText = joinNotEmpty(mSeparator, carrierNames);

        displayText = updateCarrierTextWithSimIoError(displayText, carrierNames, subOrderBySlot,
                allSimsMissing);

        boolean airplaneMode = false;
        // APM (airplane mode) != no carrier state. There are carrier services
        // (e.g. WFC = Wi-Fi calling) which may operate in APM.
        if (!anySimReadyAndInService && WirelessUtils.isAirplaneModeOn(mContext)) {
            displayText = getAirplaneModeMessage();
            airplaneMode = true;
        }

        final CarrierTextCallbackInfo info = new CarrierTextCallbackInfo(
                displayText,
                carrierNames,
                !allSimsMissing,
                subsIds,
                airplaneMode);
        postToCallback(info);
        Trace.endSection();
    }

    @VisibleForTesting
    protected void postToCallback(CarrierTextCallbackInfo info) {
        final CarrierTextCallback callback = mCarrierTextCallback;
        if (callback != null) {
            mMainExecutor.execute(() -> callback.updateCarrierInfo(info));
        }
    }

    private Context getContext() {
        return mContext;
    }

    private String getMissingSimMessage() {
        return mShowMissingSim && mTelephonyCapable
                ? getContext().getString(R.string.keyguard_missing_sim_message_short) : "";
    }

    private String getAirplaneModeMessage() {
        return mShowAirplaneMode
                ? getContext().getString(R.string.airplane_mode) : "";
    }

    /**
     * Top-level function for creating carrier text. Makes text based on simState, PLMN
     * and SPN as well as device capabilities, such as being emergency call capable.
     *
     * @return Carrier text if not in missing state, null otherwise.
     */
    private CharSequence getCarrierTextForSimState(int simState, CharSequence text) {
        CharSequence carrierText = null;
        CarrierTextManager.StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case Normal:
                carrierText = text;
                break;

            case SimNotReady:
                // Null is reserved for denoting missing, in this case we have nothing to display.
                carrierText = ""; // nothing to display yet.
                break;

            case NetworkLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        mContext.getText(R.string.keyguard_network_locked_message), text);
                break;

            case SimMissing:
                carrierText = null;
                break;

            case SimPermDisabled:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(
                                R.string.keyguard_permanent_disabled_sim_message_short),
                        text);
                break;

            case SimMissingLocked:
                carrierText = null;
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnLocked(
                        getContext().getText(R.string.keyguard_sim_locked_message),
                        text);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnLocked(
                        getContext().getText(R.string.keyguard_sim_puk_locked_message),
                        text);
                break;
            case SimIoError:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_error_message_short),
                        text);
                break;
            case SimUnknown:
                carrierText = null;
                break;
        }

        return carrierText;
    }

    /*
     * Add emergencyCallMessage to carrier string only if phone supports emergency calls.
     */
    private CharSequence makeCarrierStringOnEmergencyCapable(
            CharSequence simMessage, CharSequence emergencyCallMessage) {
        if (mIsEmergencyCallCapable) {
            return concatenate(simMessage, emergencyCallMessage, mSeparator);
        }
        return simMessage;
    }

    /*
     * Add "SIM card is locked" in parenthesis after carrier name, so it is easily associated in
     * DSDS
     */
    private CharSequence makeCarrierStringOnLocked(CharSequence simMessage,
            CharSequence carrierName) {
        final boolean simMessageValid = !TextUtils.isEmpty(simMessage);
        final boolean carrierNameValid = !TextUtils.isEmpty(carrierName);
        if (simMessageValid && carrierNameValid) {
            return mContext.getString(R.string.keyguard_carrier_name_with_sim_locked_template,
                    carrierName, simMessage);
        } else if (simMessageValid) {
            return simMessage;
        } else if (carrierNameValid) {
            return carrierName;
        } else {
            return "";
        }
    }

    /**
     * Determine the current status of the lock screen given the SIM state and other stuff.
     */
    private CarrierTextManager.StatusMode getStatusForIccState(int simState) {
        final boolean missingAndNotProvisioned =
                !mKeyguardUpdateMonitor.isDeviceProvisioned()
                        && (simState == TelephonyManager.SIM_STATE_ABSENT
                        || simState == TelephonyManager.SIM_STATE_PERM_DISABLED);

        // Assume we're NETWORK_LOCKED if not provisioned
        simState = missingAndNotProvisioned ? TelephonyManager.SIM_STATE_NETWORK_LOCKED : simState;
        switch (simState) {
            case TelephonyManager.SIM_STATE_ABSENT:
                return CarrierTextManager.StatusMode.SimMissing;
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED:
                return CarrierTextManager.StatusMode.SimMissingLocked;
            case TelephonyManager.SIM_STATE_NOT_READY:
                return CarrierTextManager.StatusMode.SimNotReady;
            case TelephonyManager.SIM_STATE_PIN_REQUIRED:
                return CarrierTextManager.StatusMode.SimLocked;
            case TelephonyManager.SIM_STATE_PUK_REQUIRED:
                return CarrierTextManager.StatusMode.SimPukLocked;
            case TelephonyManager.SIM_STATE_READY:
                return CarrierTextManager.StatusMode.Normal;
            case TelephonyManager.SIM_STATE_PERM_DISABLED:
                return CarrierTextManager.StatusMode.SimPermDisabled;
            case TelephonyManager.SIM_STATE_UNKNOWN:
                return CarrierTextManager.StatusMode.SimUnknown;
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR:
                return CarrierTextManager.StatusMode.SimIoError;
        }
        return CarrierTextManager.StatusMode.SimUnknown;
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn,
            CharSequence separator) {
        final boolean plmnValid = !TextUtils.isEmpty(plmn);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (plmnValid && spnValid) {
            return new StringBuilder().append(plmn).append(separator).append(spn).toString();
        } else if (plmnValid) {
            return plmn;
        } else if (spnValid) {
            return spn;
        } else {
            return "";
        }
    }

    /**
     * Joins the strings in a sequence using a separator. Empty strings are discarded with no extra
     * separator added so there are no extra separators that are not needed.
     */
    private static CharSequence joinNotEmpty(CharSequence separator, CharSequence[] sequences) {
        int length = sequences.length;
        if (length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            if (!TextUtils.isEmpty(sequences[i])) {
                if (!TextUtils.isEmpty(sb)) {
                    sb.append(separator);
                }
                sb.append(sequences[i]);
            }
        }
        return sb.toString();
    }

    private static List<CharSequence> append(List<CharSequence> list, CharSequence string) {
        if (!TextUtils.isEmpty(string)) {
            list.add(string);
        }
        return list;
    }

    private CharSequence getCarrierHelpTextForSimState(int simState,
            String plmn, String spn) {
        int carrierHelpTextId = 0;
        CarrierTextManager.StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case NetworkLocked:
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

    /** Injectable Buildeer for {@#link CarrierTextManager}. */
    public static class Builder {
        private final Context mContext;
        private final String mSeparator;
        private final WifiRepository mWifiRepository;
        private final TelephonyManager mTelephonyManager;
        private final TelephonyListenerManager mTelephonyListenerManager;
        private final WakefulnessLifecycle mWakefulnessLifecycle;
        private final Executor mMainExecutor;
        private final Executor mBgExecutor;
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private boolean mShowAirplaneMode;
        private boolean mShowMissingSim;

        @Inject
        public Builder(
                Context context,
                @Main Resources resources,
                @Nullable WifiRepository wifiRepository,
                TelephonyManager telephonyManager,
                TelephonyListenerManager telephonyListenerManager,
                WakefulnessLifecycle wakefulnessLifecycle,
                @Main Executor mainExecutor,
                @Background Executor bgExecutor,
                KeyguardUpdateMonitor keyguardUpdateMonitor) {
            mContext = context;
            mSeparator = resources.getString(
                    com.android.internal.R.string.kg_text_message_separator);
            mWifiRepository = wifiRepository;
            mTelephonyManager = telephonyManager;
            mTelephonyListenerManager = telephonyListenerManager;
            mWakefulnessLifecycle = wakefulnessLifecycle;
            mMainExecutor = mainExecutor;
            mBgExecutor = bgExecutor;
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        }

        /** */
        public Builder setShowAirplaneMode(boolean showAirplaneMode) {
            mShowAirplaneMode = showAirplaneMode;
            return this;
        }

        /** */
        public Builder setShowMissingSim(boolean showMissingSim) {
            mShowMissingSim = showMissingSim;
            return this;
        }

        /** Create a CarrierTextManager. */
        public CarrierTextManager build() {
            return new CarrierTextManager(
                    mContext, mSeparator, mShowAirplaneMode, mShowMissingSim, mWifiRepository,
                    mTelephonyManager, mTelephonyListenerManager, mWakefulnessLifecycle,
                    mMainExecutor, mBgExecutor, mKeyguardUpdateMonitor);
        }
    }
    /**
     * Data structure for passing information to CarrierTextController subscribers
     */
    public static final class CarrierTextCallbackInfo {
        public final CharSequence carrierText;
        public final CharSequence[] listOfCarriers;
        public final boolean anySimReady;
        public final int[] subscriptionIds;
        public boolean airplaneMode;

        @VisibleForTesting
        public CarrierTextCallbackInfo(CharSequence carrierText, CharSequence[] listOfCarriers,
                boolean anySimReady, int[] subscriptionIds) {
            this(carrierText, listOfCarriers, anySimReady, subscriptionIds, false);
        }

        @VisibleForTesting
        public CarrierTextCallbackInfo(CharSequence carrierText, CharSequence[] listOfCarriers,
                boolean anySimReady, int[] subscriptionIds, boolean airplaneMode) {
            this.carrierText = carrierText;
            this.listOfCarriers = listOfCarriers;
            this.anySimReady = anySimReady;
            this.subscriptionIds = subscriptionIds;
            this.airplaneMode = airplaneMode;
        }
    }

    /**
     * Callback to communicate to Views
     */
    public interface CarrierTextCallback {
        /**
         * Provides updated carrier information.
         */
        default void updateCarrierInfo(CarrierTextCallbackInfo info) {};

        /**
         * Notifies the View that the device is going to sleep
         */
        default void startedGoingToSleep() {};

        /**
         * Notifies the View that the device finished waking up
         */
        default void finishedWakingUp() {};
    }
}
