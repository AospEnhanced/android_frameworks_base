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

package com.android.server.media.projection;

import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED;
import static com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.FrameworkStatsLog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;

/**
 * Tests for the {@link MediaProjectionMetricsLoggerTest} class.
 *
 * <p>Build/Install/Run: atest FrameworksServicesTests:MediaProjectionMetricsLoggerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class MediaProjectionMetricsLoggerTest {

    private static final int TEST_HOST_UID = 123;
    private static final int TEST_TARGET_UID = 456;
    private static final int TEST_CREATION_SOURCE = 789;

    @Mock private FrameworkStatsLogWrapper mFrameworkStatsLogWrapper;
    @Mock private MediaProjectionSessionIdGenerator mSessionIdGenerator;
    @Mock private MediaProjectionTimestampStore mTimestampStore;

    private MediaProjectionMetricsLogger mLogger;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mLogger =
                new MediaProjectionMetricsLogger(
                        mFrameworkStatsLogWrapper, mSessionIdGenerator, mTimestampStore);
    }

    @Test
    public void logInitiated_logsStateChangedAtomId() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyStateChangedAtomIdLogged();
    }

    @Test
    public void logInitiated_logsStateInitiated() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyStateLogged(MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);
    }

    @Test
    public void logInitiated_logsHostUid() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyHostUidLogged(TEST_HOST_UID);
    }

    @Test
    public void logInitiated_logsSessionCreationSource() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyCreationSourceLogged(TEST_CREATION_SOURCE);
    }

    @Test
    public void logInitiated_logsUnknownTargetUid() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyTargetUidLogged(-2);
    }

    @Test
    public void logInitiated_noPreviousSession_logsUnknownTimeSinceLastActive() {
        when(mTimestampStore.timeSinceLastActiveSession()).thenReturn(null);

        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyTimeSinceLastActiveSessionLogged(-1);
    }

    @Test
    public void logInitiated_previousSession_logsTimeSinceLastActiveInSeconds() {
        Duration timeSinceLastActiveSession = Duration.ofHours(1234);
        when(mTimestampStore.timeSinceLastActiveSession()).thenReturn(timeSinceLastActiveSession);

        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifyTimeSinceLastActiveSessionLogged((int) timeSinceLastActiveSession.toSeconds());
    }

    @Test
    public void logInitiated_logsNewSessionId() {
        int newSessionId = 123;
        when(mSessionIdGenerator.createAndGetNewSessionId()).thenReturn(newSessionId);

        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);

        verifySessionIdLogged(newSessionId);
    }

    @Test
    public void logInitiated_logsPreviousState() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN);

        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);
    }

    @Test
    public void logStopped_logsStateChangedAtomId() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyStateChangedAtomIdLogged();
    }

    @Test
    public void logStopped_logsCurrentSessionId() {
        int currentSessionId = 987;
        when(mSessionIdGenerator.getCurrentSessionId()).thenReturn(currentSessionId);

        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifySessionIdLogged(currentSessionId);
    }

    @Test
    public void logStopped_logsStateStopped() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyStateLogged(MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED);
    }

    @Test
    public void logStopped_logsHostUid() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyHostUidLogged(TEST_HOST_UID);
    }

    @Test
    public void logStopped_logsTargetUid() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyTargetUidLogged(TEST_TARGET_UID);
    }

    @Test
    public void logStopped_logsUnknownTimeSinceLastActive() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyTimeSinceLastActiveSessionLogged(-1);
    }

    @Test
    public void logStopped_logsUnknownSessionCreationSource() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verifyCreationSourceLogged(
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    @Test
    public void logStopped_logsPreviousState() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN);

        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED);

        mLogger.logStopped(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);
    }

    @Test
    public void logStopped_capturingWasInProgress_registersActiveSessionEnded() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verify(mTimestampStore).registerActiveSessionEnded();
    }

    @Test
    public void logStopped_capturingWasNotInProgress_doesNotRegistersActiveSessionEnded() {
        mLogger.logStopped(TEST_HOST_UID, TEST_TARGET_UID);

        verify(mTimestampStore, never()).registerActiveSessionEnded();
    }

    @Test
    public void logInProgress_logsStateChangedAtomId() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyStateChangedAtomIdLogged();
    }

    @Test
    public void logInProgress_logsCurrentSessionId() {
        int currentSessionId = 987;
        when(mSessionIdGenerator.getCurrentSessionId()).thenReturn(currentSessionId);

        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifySessionIdLogged(currentSessionId);
    }

    @Test
    public void logInProgress_logsStateInProgress() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS);
    }

    @Test
    public void logInProgress_logsHostUid() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyHostUidLogged(TEST_HOST_UID);
    }

    @Test
    public void logInProgress_logsTargetUid() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyTargetUidLogged(TEST_TARGET_UID);
    }

    @Test
    public void logInProgress_logsUnknownTimeSinceLastActive() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyTimeSinceLastActiveSessionLogged(-1);
    }

    @Test
    public void logInProgress_logsUnknownSessionCreationSource() {
        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);

        verifyCreationSourceLogged(
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    @Test
    public void logInProgress_logsPreviousState() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN);

        mLogger.logInProgress(TEST_HOST_UID, TEST_TARGET_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);

        mLogger.logStopped(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_CAPTURING_IN_PROGRESS);

        mLogger.logInProgress(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED);
    }

    @Test
    public void logPermissionRequestDisplayed_logsStateChangedAtomId() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyStateChangedAtomIdLogged();
    }

    @Test
    public void logPermissionRequestDisplayed_logsCurrentSessionId() {
        int currentSessionId = 765;
        when(mSessionIdGenerator.getCurrentSessionId()).thenReturn(currentSessionId);

        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifySessionIdLogged(currentSessionId);
    }

    @Test
    public void logPermissionRequestDisplayed_logsStateDisplayed() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED);
    }

    @Test
    public void logPermissionRequestDisplayed_logsHostUid() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyHostUidLogged(TEST_HOST_UID);
    }

    @Test
    public void logPermissionRequestDisplayed_logsUnknownTargetUid() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyTargetUidLogged(-2);
    }

    @Test
    public void logPermissionRequestDisplayed_logsUnknownTimeSinceLastActive() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyTimeSinceLastActiveSessionLogged(-1);
    }

    @Test
    public void logPermissionRequestDisplayed_logsUnknownSessionCreationSource() {
        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);

        verifyCreationSourceLogged(
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    @Test
    public void logPermissionRequestDisplayed_logsPreviousState() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN);

        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);

        mLogger.logStopped(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_PERMISSION_REQUEST_DISPLAYED);

        mLogger.logPermissionRequestDisplayed(TEST_HOST_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED);
    }

    @Test
    public void logAppSelectorDisplayed_logsStateChangedAtomId() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyStateChangedAtomIdLogged();
    }

    @Test
    public void logAppSelectorDisplayed_logsCurrentSessionId() {
        int currentSessionId = 765;
        when(mSessionIdGenerator.getCurrentSessionId()).thenReturn(currentSessionId);

        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifySessionIdLogged(currentSessionId);
    }

    @Test
    public void logAppSelectorDisplayed_logsStateDisplayed() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED);
    }

    @Test
    public void logAppSelectorDisplayed_logsHostUid() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyHostUidLogged(TEST_HOST_UID);
    }

    @Test
    public void logAppSelectorDisplayed_logsUnknownTargetUid() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyTargetUidLogged(-2);
    }

    @Test
    public void logAppSelectorDisplayed_logsUnknownTimeSinceLastActive() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyTimeSinceLastActiveSessionLogged(-1);
    }

    @Test
    public void logAppSelectorDisplayed_logsUnknownSessionCreationSource() {
        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);

        verifyCreationSourceLogged(
                MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN);
    }

    @Test
    public void logAppSelectorDisplayed_logsPreviousState() {
        mLogger.logInitiated(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN);

        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_INITIATED);

        mLogger.logStopped(TEST_HOST_UID, TEST_CREATION_SOURCE);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_APP_SELECTOR_DISPLAYED);

        mLogger.logAppSelectorDisplayed(TEST_HOST_UID);
        verifyPreviousStateLogged(
                MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_STOPPED);
    }

    private void verifyStateChangedAtomIdLogged() {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ eq(FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }

    private void verifyStateLogged(int state) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        eq(state),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }

    private void verifyHostUidLogged(int hostUid) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        eq(hostUid),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }

    private void verifyCreationSourceLogged(int creationSource) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        eq(creationSource));
    }

    private void verifyTargetUidLogged(int targetUid) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        eq(targetUid),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }

    private void verifyTimeSinceLastActiveSessionLogged(int timeSinceLastActiveSession) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ eq(timeSinceLastActiveSession),
                        /* creationSource= */ anyInt());
    }

    private void verifySessionIdLogged(int newSessionId) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ eq(newSessionId),
                        /* state= */ anyInt(),
                        /* previousState= */ anyInt(),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }

    private void verifyPreviousStateLogged(int previousState) {
        verify(mFrameworkStatsLogWrapper)
                .write(
                        /* code= */ anyInt(),
                        /* sessionId= */ anyInt(),
                        /* state= */ anyInt(),
                        eq(previousState),
                        /* hostUid= */ anyInt(),
                        /* targetUid= */ anyInt(),
                        /* timeSinceLastActive= */ anyInt(),
                        /* creationSource= */ anyInt());
    }
}
