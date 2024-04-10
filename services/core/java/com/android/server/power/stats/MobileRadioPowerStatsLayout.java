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

package com.android.server.power.stats;

import android.annotation.NonNull;
import android.os.PersistableBundle;
import android.telephony.ModemActivityInfo;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.PowerStats;

/**
 * Captures the positions and lengths of sections of the stats array, such as time-in-state,
 * power usage estimates etc.
 */
class MobileRadioPowerStatsLayout extends PowerStatsLayout {
    private static final String TAG = "MobileRadioPowerStatsLayout";
    private static final String EXTRA_DEVICE_SLEEP_TIME_POSITION = "dt-sleep";
    private static final String EXTRA_DEVICE_IDLE_TIME_POSITION = "dt-idle";
    private static final String EXTRA_DEVICE_SCAN_TIME_POSITION = "dt-scan";
    private static final String EXTRA_DEVICE_CALL_TIME_POSITION = "dt-call";
    private static final String EXTRA_DEVICE_CALL_POWER_POSITION = "dp-call";
    private static final String EXTRA_STATE_RX_TIME_POSITION = "srx";
    private static final String EXTRA_STATE_TX_TIMES_POSITION = "stx";
    private static final String EXTRA_STATE_TX_TIMES_COUNT = "stxc";
    private static final String EXTRA_UID_RX_BYTES_POSITION = "urxb";
    private static final String EXTRA_UID_TX_BYTES_POSITION = "utxb";
    private static final String EXTRA_UID_RX_PACKETS_POSITION = "urxp";
    private static final String EXTRA_UID_TX_PACKETS_POSITION = "utxp";

    private int mDeviceSleepTimePosition;
    private int mDeviceIdleTimePosition;
    private int mDeviceScanTimePosition;
    private int mDeviceCallTimePosition;
    private int mDeviceCallPowerPosition;
    private int mStateRxTimePosition;
    private int mStateTxTimesPosition;
    private int mStateTxTimesCount;
    private int mUidRxBytesPosition;
    private int mUidTxBytesPosition;
    private int mUidRxPacketsPosition;
    private int mUidTxPacketsPosition;

    MobileRadioPowerStatsLayout() {
    }

    MobileRadioPowerStatsLayout(@NonNull PowerStats.Descriptor descriptor) {
        super(descriptor);
    }

    void addDeviceMobileActivity() {
        mDeviceSleepTimePosition = addDeviceSection(1);
        mDeviceIdleTimePosition = addDeviceSection(1);
        mDeviceScanTimePosition = addDeviceSection(1);
        mDeviceCallTimePosition = addDeviceSection(1);
    }

    void addStateStats() {
        mStateRxTimePosition = addStateSection(1);
        mStateTxTimesCount = ModemActivityInfo.getNumTxPowerLevels();
        mStateTxTimesPosition = addStateSection(mStateTxTimesCount);
    }

    void addUidNetworkStats() {
        mUidRxBytesPosition = addUidSection(1);
        mUidTxBytesPosition = addUidSection(1);
        mUidRxPacketsPosition = addUidSection(1);
        mUidTxPacketsPosition = addUidSection(1);
    }

    @Override
    public void addDeviceSectionPowerEstimate() {
        super.addDeviceSectionPowerEstimate();
        mDeviceCallPowerPosition = addDeviceSection(1);
    }

    public void setDeviceSleepTime(long[] stats, long durationMillis) {
        stats[mDeviceSleepTimePosition] = durationMillis;
    }

    public long getDeviceSleepTime(long[] stats) {
        return stats[mDeviceSleepTimePosition];
    }

    public void setDeviceIdleTime(long[] stats, long durationMillis) {
        stats[mDeviceIdleTimePosition] = durationMillis;
    }

    public long getDeviceIdleTime(long[] stats) {
        return stats[mDeviceIdleTimePosition];
    }

    public void setDeviceScanTime(long[] stats, long durationMillis) {
        stats[mDeviceScanTimePosition] = durationMillis;
    }

    public long getDeviceScanTime(long[] stats) {
        return stats[mDeviceScanTimePosition];
    }

    public void setDeviceCallTime(long[] stats, long durationMillis) {
        stats[mDeviceCallTimePosition] = durationMillis;
    }

    public long getDeviceCallTime(long[] stats) {
        return stats[mDeviceCallTimePosition];
    }

    public void setDeviceCallPowerEstimate(long[] stats, double power) {
        stats[mDeviceCallPowerPosition] = (long) (power * MILLI_TO_NANO_MULTIPLIER);
    }

    public double getDeviceCallPowerEstimate(long[] stats) {
        return stats[mDeviceCallPowerPosition] / MILLI_TO_NANO_MULTIPLIER;
    }

    public void setStateRxTime(long[] stats, long durationMillis) {
        stats[mStateRxTimePosition] = durationMillis;
    }

    public long getStateRxTime(long[] stats) {
        return stats[mStateRxTimePosition];
    }

    public void setStateTxTime(long[] stats, int level, int durationMillis) {
        stats[mStateTxTimesPosition + level] = durationMillis;
    }

    public long getStateTxTime(long[] stats, int level) {
        return stats[mStateTxTimesPosition + level];
    }

    public void setUidRxBytes(long[] stats, long count) {
        stats[mUidRxBytesPosition] = count;
    }

    public long getUidRxBytes(long[] stats) {
        return stats[mUidRxBytesPosition];
    }

    public void setUidTxBytes(long[] stats, long count) {
        stats[mUidTxBytesPosition] = count;
    }

    public long getUidTxBytes(long[] stats) {
        return stats[mUidTxBytesPosition];
    }

    public void setUidRxPackets(long[] stats, long count) {
        stats[mUidRxPacketsPosition] = count;
    }

    public long getUidRxPackets(long[] stats) {
        return stats[mUidRxPacketsPosition];
    }

    public void setUidTxPackets(long[] stats, long count) {
        stats[mUidTxPacketsPosition] = count;
    }

    public long getUidTxPackets(long[] stats) {
        return stats[mUidTxPacketsPosition];
    }

    /**
     * Copies the elements of the stats array layout into <code>extras</code>
     */
    public void toExtras(PersistableBundle extras) {
        super.toExtras(extras);
        extras.putInt(EXTRA_DEVICE_SLEEP_TIME_POSITION, mDeviceSleepTimePosition);
        extras.putInt(EXTRA_DEVICE_IDLE_TIME_POSITION, mDeviceIdleTimePosition);
        extras.putInt(EXTRA_DEVICE_SCAN_TIME_POSITION, mDeviceScanTimePosition);
        extras.putInt(EXTRA_DEVICE_CALL_TIME_POSITION, mDeviceCallTimePosition);
        extras.putInt(EXTRA_DEVICE_CALL_POWER_POSITION, mDeviceCallPowerPosition);
        extras.putInt(EXTRA_STATE_RX_TIME_POSITION, mStateRxTimePosition);
        extras.putInt(EXTRA_STATE_TX_TIMES_POSITION, mStateTxTimesPosition);
        extras.putInt(EXTRA_STATE_TX_TIMES_COUNT, mStateTxTimesCount);
        extras.putInt(EXTRA_UID_RX_BYTES_POSITION, mUidRxBytesPosition);
        extras.putInt(EXTRA_UID_TX_BYTES_POSITION, mUidTxBytesPosition);
        extras.putInt(EXTRA_UID_RX_PACKETS_POSITION, mUidRxPacketsPosition);
        extras.putInt(EXTRA_UID_TX_PACKETS_POSITION, mUidTxPacketsPosition);
    }

    /**
     * Retrieves elements of the stats array layout from <code>extras</code>
     */
    public void fromExtras(PersistableBundle extras) {
        super.fromExtras(extras);
        mDeviceSleepTimePosition = extras.getInt(EXTRA_DEVICE_SLEEP_TIME_POSITION);
        mDeviceIdleTimePosition = extras.getInt(EXTRA_DEVICE_IDLE_TIME_POSITION);
        mDeviceScanTimePosition = extras.getInt(EXTRA_DEVICE_SCAN_TIME_POSITION);
        mDeviceCallTimePosition = extras.getInt(EXTRA_DEVICE_CALL_TIME_POSITION);
        mDeviceCallPowerPosition = extras.getInt(EXTRA_DEVICE_CALL_POWER_POSITION);
        mStateRxTimePosition = extras.getInt(EXTRA_STATE_RX_TIME_POSITION);
        mStateTxTimesPosition = extras.getInt(EXTRA_STATE_TX_TIMES_POSITION);
        mStateTxTimesCount = extras.getInt(EXTRA_STATE_TX_TIMES_COUNT);
        mUidRxBytesPosition = extras.getInt(EXTRA_UID_RX_BYTES_POSITION);
        mUidTxBytesPosition = extras.getInt(EXTRA_UID_TX_BYTES_POSITION);
        mUidRxPacketsPosition = extras.getInt(EXTRA_UID_RX_PACKETS_POSITION);
        mUidTxPacketsPosition = extras.getInt(EXTRA_UID_TX_PACKETS_POSITION);
    }

    public void addRxTxTimesForRat(SparseArray<long[]> stateStats, int networkType, int freqRange,
            long rxTime, int[] txTime) {
        if (txTime.length != mStateTxTimesCount) {
            Slog.wtf(TAG, "Invalid TX time array size: " + txTime.length);
            return;
        }

        boolean nonZero = false;
        if (rxTime != 0) {
            nonZero = true;
        } else {
            for (int i = txTime.length - 1; i >= 0; i--) {
                if (txTime[i] != 0) {
                    nonZero = true;
                    break;
                }
            }
        }

        if (!nonZero) {
            return;
        }

        int rat = MobileRadioPowerStatsCollector.mapRadioAccessNetworkTypeToRadioAccessTechnology(
                networkType);
        int stateKey = MobileRadioPowerStatsCollector.makeStateKey(rat, freqRange);
        long[] stats = stateStats.get(stateKey);
        if (stats == null) {
            stats = new long[getStateStatsArrayLength()];
            stateStats.put(stateKey, stats);
        }

        stats[mStateRxTimePosition] += rxTime;
        for (int i = mStateTxTimesCount - 1; i >= 0; i--) {
            stats[mStateTxTimesPosition + i] += txTime[i];
        }
    }
}
