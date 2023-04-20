/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics;

import android.util.Log;

import com.android.server.credentials.MetricUtilities;

import java.util.ArrayList;
import java.util.List;

/**
 * The central chosen provider metric object that mimics our defined metric setup. This is used
 * in the final phase of the flow and emits final status metrics.
 * Some types are redundant across these metric collectors, but that has debug use-cases as
 * these data-types are available at different moments of the flow (and typically, one can feed
 * into the next).
 * TODO(b/270403549) - iterate on this in V3+
 */
public class ChosenProviderFinalPhaseMetric {

    // TODO(b/270403549) - applies elsewhere, likely removed or replaced w/ some hashed/count index
    private static final String TAG = "ChosenFinalPhaseMetric";
    // The session id associated with this API call, used to unite split emits
    private int mSessionId = -1;
    // Reveals if the UI was returned, false by default
    private boolean mUiReturned = false;
    private int mChosenUid = -1;

    // Latency figures typically fed in from prior CandidateProviderMetric

    private int mPreQueryPhaseLatencyMicroseconds = -1;
    private int mQueryPhaseLatencyMicroseconds = -1;

    // Timestamps kept in raw nanoseconds. Expected to be converted to microseconds from using
    // reference 'mServiceBeganTimeNanoseconds' during metric log point

    // Kept for local reference purposes, the initial timestamp of the service called passed in
    private long mServiceBeganTimeNanoseconds = -1;
    // The first query timestamp, which upon emit is normalized to microseconds using the reference
    // start timestamp
    private long mQueryStartTimeNanoseconds = -1;
    // The timestamp at query end, which upon emit will be normalized to microseconds with reference
    private long mQueryEndTimeNanoseconds = -1;
    // The UI call timestamp, which upon emit will be normalized to microseconds using reference
    private long mUiCallStartTimeNanoseconds = -1;
    // The UI return timestamp, which upon emit will be normalized to microseconds using reference
    private long mUiCallEndTimeNanoseconds = -1;
    // The final finish timestamp, which upon emit will be normalized to microseconds with reference
    private long mFinalFinishTimeNanoseconds = -1;
    // The status of this provider after selection

    // Other General Information, such as final api status, provider status, entry info, etc...

    private int mChosenProviderStatus = -1;
    // Indicates if an exception was thrown by this provider, false by default
    private boolean mHasException = false;
    // Indicates the number of total entries available, defaults to -1. Not presently emitted, but
    // left as a utility
    private int mNumEntriesTotal = -1;
    // The count of action entries from this provider, defaults to -1
    private int mActionEntryCount = -1;
    // The count of credential entries from this provider, defaults to -1
    private int mCredentialEntryCount = -1;
    // The *type-count* of the credential entries, defaults to -1
    private int mCredentialEntryTypeCount = -1;
    // The count of remote entries from this provider, defaults to -1
    private int mRemoteEntryCount = -1;
    // The count of authentication entries from this provider, defaults to -1
    private int mAuthenticationEntryCount = -1;
    // Gathered to pass on to chosen provider when required
    private List<Integer> mAvailableEntries = new ArrayList<>();


    public ChosenProviderFinalPhaseMetric() {
    }

    /* ------------------- UID ------------------- */

    public int getChosenUid() {
        return mChosenUid;
    }

    public void setChosenUid(int chosenUid) {
        mChosenUid = chosenUid;
    }

    /* ---------------- Latencies ------------------ */


    /* ----- Direct Delta Latencies for Local Utility ------- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link InitialPhaseMetric} can directly pass this initial latency figure into
     * this chosen provider metric.
     *
     * @param preQueryPhaseLatencyMicroseconds the millisecond latency for the service start,
     *                                         typically passed in through the
     *                                         {@link InitialPhaseMetric}
     */
    public void setPreQueryPhaseLatencyMicroseconds(int preQueryPhaseLatencyMicroseconds) {
        mPreQueryPhaseLatencyMicroseconds = preQueryPhaseLatencyMicroseconds;
    }

    /**
     * In order for a chosen provider to be selected, a candidate provider must exist. The
     * candidate provider can directly pass the final latency figure into this chosen provider
     * metric.
     *
     * @param queryPhaseLatencyMicroseconds the millisecond latency for the query phase, typically
     *                                      passed in through the {@link CandidatePhaseMetric}
     */
    public void setQueryPhaseLatencyMicroseconds(int queryPhaseLatencyMicroseconds) {
        mQueryPhaseLatencyMicroseconds = queryPhaseLatencyMicroseconds;
    }

    public int getPreQueryPhaseLatencyMicroseconds() {
        return mPreQueryPhaseLatencyMicroseconds;
    }

    public int getQueryPhaseLatencyMicroseconds() {
        return mQueryPhaseLatencyMicroseconds;
    }

    public int getUiPhaseLatencyMicroseconds() {
        return (int) ((mUiCallEndTimeNanoseconds
                - mUiCallStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full provider (invocation to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link CandidatePhaseMetric}.
     */
    public int getEntireProviderLatencyMicroseconds() {
        return (int) ((mFinalFinishTimeNanoseconds
                - mQueryStartTimeNanoseconds) / 1000);
    }

    /**
     * Returns the full (platform invoked to response) latency in microseconds. Expects the
     * start time to be provided, such as from {@link InitialPhaseMetric}.
     */
    public int getEntireLatencyMicroseconds() {
        return (int) ((mFinalFinishTimeNanoseconds
                - mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ----- Timestamps for Latency ----- */

    /**
     * In order for a chosen provider to be selected, the call must have successfully begun.
     * Thus, the {@link InitialPhaseMetric} can directly pass this initial timestamp into this
     * chosen provider metric.
     *
     * @param serviceBeganTimeNanoseconds the timestamp moment when the platform was called,
     *                                    typically passed in through the {@link InitialPhaseMetric}
     */
    public void setServiceBeganTimeNanoseconds(long serviceBeganTimeNanoseconds) {
        mServiceBeganTimeNanoseconds = serviceBeganTimeNanoseconds;
    }

    public void setQueryStartTimeNanoseconds(long queryStartTimeNanoseconds) {
        mQueryStartTimeNanoseconds = queryStartTimeNanoseconds;
    }

    public void setQueryEndTimeNanoseconds(long queryEndTimeNanoseconds) {
        mQueryEndTimeNanoseconds = queryEndTimeNanoseconds;
    }

    public void setUiCallStartTimeNanoseconds(long uiCallStartTimeNanoseconds) {
        mUiCallStartTimeNanoseconds = uiCallStartTimeNanoseconds;
    }

    public void setUiCallEndTimeNanoseconds(long uiCallEndTimeNanoseconds) {
        mUiCallEndTimeNanoseconds = uiCallEndTimeNanoseconds;
    }

    public void setFinalFinishTimeNanoseconds(long finalFinishTimeNanoseconds) {
        mFinalFinishTimeNanoseconds = finalFinishTimeNanoseconds;
    }

    public long getServiceBeganTimeNanoseconds() {
        return mServiceBeganTimeNanoseconds;
    }

    public long getQueryStartTimeNanoseconds() {
        return mQueryStartTimeNanoseconds;
    }

    public long getQueryEndTimeNanoseconds() {
        return mQueryEndTimeNanoseconds;
    }

    public long getUiCallStartTimeNanoseconds() {
        return mUiCallStartTimeNanoseconds;
    }

    public long getUiCallEndTimeNanoseconds() {
        return mUiCallEndTimeNanoseconds;
    }

    public long getFinalFinishTimeNanoseconds() {
        return mFinalFinishTimeNanoseconds;
    }

    /* --- Time Stamp Conversion to Microseconds from Reference Point --- */

    /**
     * We collect raw timestamps in nanoseconds for ease of collection. However, given the scope
     * of our logging timeframe, and size considerations of the metric, we require these to give us
     * the microsecond timestamps from the start reference point.
     *
     * @param specificTimestamp the timestamp to consider, must be greater than the reference
     * @return the microsecond integer timestamp from service start to query began
     */
    public int getTimestampFromReferenceStartMicroseconds(long specificTimestamp) {
        if (specificTimestamp < mServiceBeganTimeNanoseconds) {
            Log.i(TAG, "The timestamp is before service started, falling back to default int");
            return MetricUtilities.DEFAULT_INT_32;
        }
        return (int) ((specificTimestamp
                - mServiceBeganTimeNanoseconds) / 1000);
    }

    /* ----------- Provider Status -------------- */

    public int getChosenProviderStatus() {
        return mChosenProviderStatus;
    }

    public void setChosenProviderStatus(int chosenProviderStatus) {
        mChosenProviderStatus = chosenProviderStatus;
    }

    /* ----------- Session ID -------------- */

    public void setSessionId(int sessionId) {
        mSessionId = sessionId;
    }

    public int getSessionId() {
        return mSessionId;
    }

    /* ----------- UI Returned Successfully -------------- */

    public void setUiReturned(boolean uiReturned) {
        mUiReturned = uiReturned;
    }

    public boolean isUiReturned() {
        return mUiReturned;
    }

    /* -------------- Number of Entries ---------------- */

    public void setNumEntriesTotal(int numEntriesTotal) {
        mNumEntriesTotal = numEntriesTotal;
    }

    public int getNumEntriesTotal() {
        return mNumEntriesTotal;
    }

    /* -------------- Count of Action Entries ---------------- */

    public void setActionEntryCount(int actionEntryCount) {
        mActionEntryCount = actionEntryCount;
    }

    public int getActionEntryCount() {
        return mActionEntryCount;
    }

    /* -------------- Count of Credential Entries ---------------- */

    public void setCredentialEntryCount(int credentialEntryCount) {
        mCredentialEntryCount = credentialEntryCount;
    }

    public int getCredentialEntryCount() {
        return mCredentialEntryCount;
    }

    /* -------------- Count of Credential Entry Types ---------------- */

    public void setCredentialEntryTypeCount(int credentialEntryTypeCount) {
        mCredentialEntryTypeCount = credentialEntryTypeCount;
    }

    public int getCredentialEntryTypeCount() {
        return mCredentialEntryTypeCount;
    }

    /* -------------- Count of Remote Entries ---------------- */

    public void setRemoteEntryCount(int remoteEntryCount) {
        mRemoteEntryCount = remoteEntryCount;
    }

    public int getRemoteEntryCount() {
        return mRemoteEntryCount;
    }

    /* -------------- Count of Authentication Entries ---------------- */

    public void setAuthenticationEntryCount(int authenticationEntryCount) {
        mAuthenticationEntryCount = authenticationEntryCount;
    }

    public int getAuthenticationEntryCount() {
        return mAuthenticationEntryCount;
    }

    /* -------------- The Entries Gathered ---------------- */

    /**
     * Sets the collected list of entries from the candidate phase to be retrievable in the
     * chosen phase in a semantically correct way.
     */
    public void setAvailableEntries(List<Integer> entries) {
        mAvailableEntries = new ArrayList<>(entries); // no alias copy
    }

    /**
     * Returns a list of the entries captured by this metric collector associated
     * with a particular chosen provider.
     *
     * @return the full collection of entries encountered by the chosen provider during the
     * candidate phase.
     */
    public List<Integer> getAvailableEntries() {
        return new ArrayList<>(mAvailableEntries); // no alias copy
    }

    /* -------------- Has Exception ---------------- */

    public void setHasException(boolean hasException) {
        mHasException = hasException;
    }

    public boolean isHasException() {
        return mHasException;
    }
}
