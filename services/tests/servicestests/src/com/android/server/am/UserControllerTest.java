/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import static android.Manifest.permission.INTERACT_ACROSS_PROFILES;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE;
import static android.app.ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.testing.DexmakerShareClassLoaderRule.runWithDexmakerShareClassLoader;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.UserController.CLEAR_USER_JOURNEY_SESSION_MSG;
import static com.android.server.am.UserController.COMPLETE_USER_SWITCH_MSG;
import static com.android.server.am.UserController.CONTINUE_USER_SWITCH_MSG;
import static com.android.server.am.UserController.REPORT_LOCKED_BOOT_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.UserController.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.UserController.USER_COMPLETED_EVENT_MSG;
import static com.android.server.am.UserController.USER_CURRENT_MSG;
import static com.android.server.am.UserController.USER_START_MSG;
import static com.android.server.am.UserController.USER_SWITCH_TIMEOUT_MSG;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_BACKGROUND;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_FOREGROUND;

import static com.google.android.collect.Lists.newArrayList;
import static com.google.android.collect.Sets.newHashSet;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IUserSwitchObserver;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.Log;
import android.view.Display;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.am.UserState.KeyEvictedCallback;
import com.android.server.pm.UserJourneyLogger;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerService;
import com.android.server.pm.UserTypeDetails;
import com.android.server.pm.UserTypeFactory;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tests for {@link UserController}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:UserControllerTest
 */
@SmallTest
@Presubmit

public class UserControllerTest {
    // Use big enough user id to avoid picking up already active user id.
    private static final int TEST_USER_ID = 100;
    private static final int TEST_USER_ID1 = 101;
    private static final int TEST_USER_ID2 = 102;
    private static final int TEST_USER_ID3 = 103;
    private static final int SYSTEM_USER_ID = UserHandle.SYSTEM.getIdentifier();
    private static final int NONEXIST_USER_ID = 2;
    private static final int TEST_PRE_CREATED_USER_ID = 103;

    private static final int NO_USERINFO_FLAGS = 0;

    private static final String TAG = UserControllerTest.class.getSimpleName();

    private static final long HANDLER_WAIT_TIME_MS = 100;

    private UserController mUserController;
    private TestInjector mInjector;
    private final HashMap<Integer, UserState> mUserStates = new HashMap<>();

    private final KeyEvictedCallback mKeyEvictedCallback = (userId) -> { /* ignore */ };

    private static final List<String> START_FOREGROUND_USER_ACTIONS = newArrayList(
            Intent.ACTION_USER_STARTED,
            Intent.ACTION_USER_STARTING);

    private static final List<String> START_FOREGROUND_USER_DEFERRED_ACTIONS = newArrayList(
            Intent.ACTION_USER_SWITCHED);

    private static final List<String> START_BACKGROUND_USER_ACTIONS = newArrayList(
            Intent.ACTION_USER_STARTED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_USER_STARTING);

    private static final Set<Integer> START_FOREGROUND_USER_MESSAGE_CODES = newHashSet(
            REPORT_USER_SWITCH_MSG,
            USER_SWITCH_TIMEOUT_MSG,
            USER_START_MSG,
            USER_CURRENT_MSG);

    private static final Set<Integer> START_BACKGROUND_USER_MESSAGE_CODES = newHashSet(
            USER_START_MSG,
            REPORT_LOCKED_BOOT_COMPLETE_MSG);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        runWithDexmakerShareClassLoader(() -> {
            mInjector = spy(new TestInjector(getInstrumentation().getTargetContext()));
            doNothing().when(mInjector).clearAllLockedTasks(anyString());
            doNothing().when(mInjector).startHomeActivity(anyInt(), anyString());
            doReturn(false).when(mInjector).taskSupervisorSwitchUser(anyInt(), any());
            doNothing().when(mInjector).taskSupervisorResumeFocusedStackTopActivity();
            doNothing().when(mInjector).systemServiceManagerOnUserStopped(anyInt());
            doNothing().when(mInjector).systemServiceManagerOnUserCompletedEvent(
                    anyInt(), anyInt());
            doNothing().when(mInjector).activityManagerForceStopPackage(anyInt(), anyString());
            doNothing().when(mInjector).activityManagerOnUserStopped(anyInt());
            doNothing().when(mInjector).clearBroadcastQueueForUser(anyInt());
            doNothing().when(mInjector).taskSupervisorRemoveUser(anyInt());
            doNothing().when(mInjector).lockDeviceNowAndWaitForKeyguardShown();
            mockIsUsersOnSecondaryDisplaysEnabled(false);
            // All UserController params are set to default.

            // Starts with a generic assumption that the user starts visible, but on tests where
            // that's not the case, the test should call mockAssignUserToMainDisplay()
            doReturn(UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE)
                    .when(mInjector.mUserManagerInternalMock)
                    .assignUserToDisplayOnStart(anyInt(), anyInt(), anyInt(), anyInt());

            mUserController = new UserController(mInjector);
            mUserController.setAllowUserUnlocking(true);
            setUpUser(TEST_USER_ID, NO_USERINFO_FLAGS);
            setUpUser(TEST_PRE_CREATED_USER_ID, NO_USERINFO_FLAGS, /* preCreated= */ true, null);
            mInjector.mRelevantUser = null;
        });
    }

    @After
    public void tearDown() throws Exception {
        mInjector.mHandlerThread.quit();
        validateMockitoUsage();
    }

    @Test
    public void testStartUser_foreground() {
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        verify(mInjector, never()).dismissUserSwitchingDialog(any());
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        verify(mInjector.getWindowManager()).setSwitchingUser(true);
        verify(mInjector).clearAllLockedTasks(anyString());
        startForegroundUserAssertions();
        verifyUserAssignedToDisplay(TEST_USER_ID, Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testStartUser_background() {
        boolean started = mUserController.startUser(TEST_USER_ID, USER_START_MODE_BACKGROUND);
        assertWithMessage("startUser(%s, foreground=false)", TEST_USER_ID).that(started).isTrue();
        verify(mInjector, never()).showUserSwitchingDialog(
                any(), any(), anyString(), anyString(), any());
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        verify(mInjector, never()).clearAllLockedTasks(anyString());
        startBackgroundUserAssertions();
        verifyUserAssignedToDisplay(TEST_USER_ID, Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testStartUser_background_duringBootHsum() {
        mockIsHeadlessSystemUserMode(true);
        mUserController.setAllowUserUnlocking(false);
        mInjector.mRelevantUser = TEST_USER_ID;
        boolean started = mUserController.startUser(TEST_USER_ID, USER_START_MODE_BACKGROUND);
        assertWithMessage("startUser(%s, foreground=false)", TEST_USER_ID).that(started).isTrue();

        // ACTION_LOCKED_BOOT_COMPLETED not sent yet
        startUserAssertions(newArrayList(Intent.ACTION_USER_STARTED, Intent.ACTION_USER_STARTING),
                START_BACKGROUND_USER_MESSAGE_CODES);

        mUserController.onBootComplete(null);

        startUserAssertions(newArrayList(Intent.ACTION_USER_STARTED, Intent.ACTION_USER_STARTING,
                        Intent.ACTION_LOCKED_BOOT_COMPLETED),
                START_BACKGROUND_USER_MESSAGE_CODES);
    }

    @Test
    public void testStartUser_sendsNoBroadcastsForSystemUserInNonHeadlessMode() {
        setUpUser(SYSTEM_USER_ID, UserInfo.FLAG_SYSTEM, /* preCreated= */ false,
                UserManager.USER_TYPE_FULL_SYSTEM);
        mockIsHeadlessSystemUserMode(false);

        mUserController.startUser(SYSTEM_USER_ID, USER_START_MODE_FOREGROUND);

        assertWithMessage("Broadcasts for starting the system user in non-headless mode")
                .that(mInjector.mSentIntents).isEmpty();
    }

    @Test
    public void testStartUser_sendsBroadcastsForSystemUserInHeadlessMode() {
        setUpUser(SYSTEM_USER_ID, UserInfo.FLAG_SYSTEM, /* preCreated= */ false,
                UserManager.USER_TYPE_SYSTEM_HEADLESS);
        mockIsHeadlessSystemUserMode(true);

        mUserController.startUser(SYSTEM_USER_ID, USER_START_MODE_FOREGROUND);

        assertWithMessage("Broadcasts for starting the system user in headless mode")
                .that(getActions(mInjector.mSentIntents)).containsExactly(
                        Intent.ACTION_USER_STARTED, Intent.ACTION_USER_STARTING);
    }

    @Test
    public void testStartUser_displayAssignmentFailed() {
        doReturn(UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE)
                .when(mInjector.mUserManagerInternalMock)
                .assignUserToDisplayOnStart(eq(TEST_USER_ID), anyInt(),
                        eq(USER_START_MODE_FOREGROUND), anyInt());

        boolean started = mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);

        assertWithMessage("startUser(%s, foreground=true)", TEST_USER_ID).that(started).isFalse();
    }

    @Test
    public void testStartUserVisibleOnDisplay() {
        boolean started = mUserController.startUserVisibleOnDisplay(TEST_USER_ID, 42,
                /* unlockProgressListener= */ null);

        assertWithMessage("startUserOnDisplay(%s, %s)", TEST_USER_ID, 42).that(started).isTrue();
        verifyUserAssignedToDisplay(TEST_USER_ID, 42);

        verify(mInjector, never()).showUserSwitchingDialog(
                any(), any(), anyString(), anyString(), any());
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        verify(mInjector, never()).clearAllLockedTasks(anyString());
        startBackgroundUserAssertions();
    }

    @Test
    public void testStartUserUIDisabled() {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ false,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        verify(mInjector, never()).showUserSwitchingDialog(
                any(), any(), anyString(), anyString(), any());
        verify(mInjector, never()).dismissUserSwitchingDialog(any());
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        startForegroundUserAssertions();
    }

    @Test
    public void testStartPreCreatedUser_foreground() {
        assertFalse(
                mUserController.startUser(TEST_PRE_CREATED_USER_ID, USER_START_MODE_FOREGROUND));
        // Make sure no intents have been fired for pre-created users.
        assertTrue(mInjector.mSentIntents.isEmpty());

        verifyUserNeverAssignedToDisplay();
    }

    @Test
    public void testStartPreCreatedUser_background() throws Exception {
        assertTrue(mUserController.startUser(TEST_PRE_CREATED_USER_ID, USER_START_MODE_BACKGROUND));
        // Make sure no intents have been fired for pre-created users.
        assertTrue(mInjector.mSentIntents.isEmpty());

        verify(mInjector, never()).showUserSwitchingDialog(
                any(), any(), anyString(), anyString(), any());
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(anyBoolean());
        verify(mInjector, never()).clearAllLockedTasks(anyString());

        assertWithMessage("should not have received intents")
                .that(getActions(mInjector.mSentIntents)).isEmpty();
        // TODO(b/140868593): should have received a USER_UNLOCK_MSG message as well, but it doesn't
        // because StorageManager.isCeStorageUnlocked(TEST_PRE_CREATED_USER_ID) returns false - to
        // properly fix it, we'd need to move this class to FrameworksMockingServicesTests so we can
        // mock static methods (but moving this class would involve changing the presubmit tests,
        // and the cascade effect goes on...). In fact, a better approach would to not assert the
        // binder calls, but their side effects (in this case, that the user is stopped right away)
        assertWithMessage("wrong binder message calls").that(mInjector.mHandler.getMessageCodes())
                .containsExactly(USER_START_MSG);
    }

    private void startUserAssertions(
            List<String> expectedActions, Set<Integer> expectedMessageCodes) {
        assertEquals(expectedActions, getActions(mInjector.mSentIntents));
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedMessageCodes, actualCodes);
    }

    private void startBackgroundUserAssertions() {
        startUserAssertions(START_BACKGROUND_USER_ACTIONS, START_BACKGROUND_USER_MESSAGE_CODES);
    }

    private void startForegroundUserAssertions() {
        startUserAssertions(START_FOREGROUND_USER_ACTIONS, START_FOREGROUND_USER_MESSAGE_CODES);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, reportMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, reportMsg.arg2);
        verifyUserAssignedToDisplay(TEST_USER_ID, Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testFailedStartUserInForeground() {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ false,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        mUserController.startUserInForeground(NONEXIST_USER_ID);
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(anyBoolean());
        verify(mInjector.getWindowManager()).setSwitchingUser(false);

        verifyUserNeverAssignedToDisplay();
    }

    @Test
    public void testDispatchUserSwitch() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        doAnswer(invocation -> {
            IRemoteCallback callback = (IRemoteCallback) invocation.getArguments()[1];
            callback.sendResult(null);
            return null;
        }).when(observer).onUserSwitching(anyInt(), any());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.mHandler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        verify(observer, times(1)).onBeforeUserSwitching(eq(TEST_USER_ID));
        verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        Set<Integer> expectedCodes = Collections.singleton(CONTINUE_USER_SWITCH_MSG);
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message conMsg = mInjector.mHandler.getMessageForCode(CONTINUE_USER_SWITCH_MSG);
        assertNotNull(conMsg);
        userState = (UserState) conMsg.obj;
        assertNotNull(userState);
        assertEquals(TEST_USER_ID, userState.mHandle.getIdentifier());
        assertEquals("User must be in STATE_BOOTING", UserState.STATE_BOOTING, userState.state);
        assertEquals("Unexpected old user id", 0, conMsg.arg1);
        assertEquals("Unexpected new user id", TEST_USER_ID, conMsg.arg2);
    }

    @Test
    public void testDispatchUserSwitchBadReceiver() throws RemoteException {
        // Prepare mock observer which doesn't notify the callback and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        // Call dispatchUserSwitch and verify that observer was called only once
        mInjector.mHandler.clearAllRecordedMessages();
        mUserController.dispatchUserSwitch(userState, oldUserId, newUserId);
        verify(observer, times(1)).onBeforeUserSwitching(eq(TEST_USER_ID));
        verify(observer, times(1)).onUserSwitching(eq(TEST_USER_ID), any());
        // Verify that CONTINUE_USER_SWITCH_MSG is not sent (triggers timeout)
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertWithMessage("No messages should be sent").that(actualCodes).isEmpty();
    }

    private void continueAndCompleteUserSwitch(UserState userState, int oldUserId, int newUserId) {
        mUserController.continueUserSwitch(userState, oldUserId, newUserId);
        mInjector.mHandler.removeMessages(UserController.COMPLETE_USER_SWITCH_MSG);
        mUserController.completeUserSwitch(oldUserId, newUserId);
    }

    @Test
    public void testContinueUserSwitch() {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        continueAndCompleteUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector, times(0)).dismissKeyguard(any());
        verify(mInjector, times(1)).dismissUserSwitchingDialog(any());
        continueUserSwitchAssertions(oldUserId, TEST_USER_ID, false);
        verifySystemUserVisibilityChangesNeverNotified();
    }

    @Test
    public void testContinueUserSwitchDismissKeyguard() {
        when(mInjector.mKeyguardManagerMock.isDeviceSecure(anyInt())).thenReturn(false);
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        continueAndCompleteUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector, times(1)).dismissKeyguard(any());
        verify(mInjector, times(1)).dismissUserSwitchingDialog(any());
        continueUserSwitchAssertions(oldUserId, TEST_USER_ID, false);
        verifySystemUserVisibilityChangesNeverNotified();
    }

    @Test
    public void testContinueUserSwitchUIDisabled() {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ false,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        continueAndCompleteUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector, never()).dismissUserSwitchingDialog(any());
        continueUserSwitchAssertions(oldUserId, TEST_USER_ID, false);
    }

    private void continueUserSwitchAssertions(int expectedOldUserId, int expectedNewUserId,
            boolean backgroundUserStopping) {
        Set<Integer> expectedCodes = new LinkedHashSet<>();
        expectedCodes.add(COMPLETE_USER_SWITCH_MSG);
        expectedCodes.add(REPORT_USER_SWITCH_COMPLETE_MSG);
        if (backgroundUserStopping) {
            expectedCodes.add(CLEAR_USER_JOURNEY_SESSION_MSG);
            expectedCodes.add(0); // this is for directly posting in stopping.
        }
        Set<Integer> actualCodes = mInjector.mHandler.getMessageCodes();
        assertEquals("Unexpected message sent", expectedCodes, actualCodes);
        Message msg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_COMPLETE_MSG);
        assertNotNull(msg);
        assertEquals("Unexpected oldUserId", expectedOldUserId, msg.arg1);
        assertEquals("Unexpected newUserId", expectedNewUserId, msg.arg2);
    }

    @Test
    public void testDispatchUserSwitchComplete() throws RemoteException {
        // Prepare mock observer and register it
        IUserSwitchObserver observer = mock(IUserSwitchObserver.class);
        when(observer.asBinder()).thenReturn(new Binder());
        mUserController.registerUserSwitchObserver(observer, "mock");
        // Start user -- this will update state of mUserController
        mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        int oldUserId = reportMsg.arg1;
        int newUserId = reportMsg.arg2;
        mInjector.mHandler.clearAllRecordedMessages();
        // Mockito can't reset only interactions, so just verify that this hasn't been
        // called with 'false' until after dispatchUserSwitchComplete.
        verify(mInjector.getWindowManager(), never()).setSwitchingUser(false);
        // Call dispatchUserSwitchComplete
        mUserController.dispatchUserSwitchComplete(oldUserId, newUserId);
        verify(observer, times(1)).onUserSwitchComplete(anyInt());
        verify(observer).onUserSwitchComplete(TEST_USER_ID);
        verify(mInjector.getWindowManager(), times(1)).setSwitchingUser(false);
        startUserAssertions(Stream.concat(
                        START_FOREGROUND_USER_ACTIONS.stream(),
                        START_FOREGROUND_USER_DEFERRED_ACTIONS.stream()
                ).collect(Collectors.toList()), Collections.emptySet());
    }

    @Test
    public void testExplicitSystemUserStartInBackground() {
        setUpUser(UserHandle.USER_SYSTEM, 0);
        assertFalse(mUserController.isSystemUserStarted());
        assertTrue(mUserController.startUser(UserHandle.USER_SYSTEM, USER_START_MODE_BACKGROUND,
                null));
        assertTrue(mUserController.isSystemUserStarted());
    }

    /**
     * Test stopping of user from max running users limit.
     */
    @Test
    public void testUserLockingFromUserSwitchingForMultipleUsersNonDelayedLocking()
            throws InterruptedException, RemoteException {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        setUpUser(TEST_USER_ID1, 0);
        setUpUser(TEST_USER_ID2, 0);
        int numerOfUserSwitches = 1;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID, UserHandle.USER_SYSTEM,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID}),
                mUserController.getRunningUsersLU());

        numerOfUserSwitches++;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID1, TEST_USER_ID,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID, USER_ID1
        assertFalse(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID, TEST_USER_ID1}),
                mUserController.getRunningUsersLU());

        numerOfUserSwitches++;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID2, TEST_USER_ID1,
                numerOfUserSwitches, false);
        UserState ussUser2 = mUserStates.get(TEST_USER_ID2);
        // skip middle step and call this directly.
        mUserController.finishUserSwitch(ussUser2);
        waitForHandlerToComplete(mInjector.mHandler, HANDLER_WAIT_TIME_MS);
        // running: user 0, USER_ID1, USER_ID2
        // USER_ID should be stopped as it is least recently used non user0.
        assertFalse(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID1, TEST_USER_ID2}),
                mUserController.getRunningUsersLU());
        verifySystemUserVisibilityChangesNeverNotified();
    }

    /**
     * This test tests delayed locking mode using 4 users. As core logic of delayed locking is
     * happening in finishUserStopped call, the test also calls finishUserStopped while skipping
     * all middle steps which takes too much work to mock.
     */
    @Test
    public void testUserLockingFromUserSwitchingForMultipleUsersDelayedLockingMode()
            throws Exception {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ true);

        setUpUser(TEST_USER_ID1, 0);
        setUpUser(TEST_USER_ID2, 0);
        int numerOfUserSwitches = 1;
        addForegroundUserAndContinueUserSwitch(TEST_USER_ID, UserHandle.USER_SYSTEM,
                numerOfUserSwitches, false);
        // running: user 0, USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID}),
                mUserController.getRunningUsersLU());
        numerOfUserSwitches++;

        addForegroundUserAndContinueUserSwitch(TEST_USER_ID1, TEST_USER_ID,
                numerOfUserSwitches, true);
        // running: user 0, USER_ID1
        // stopped + unlocked: USER_ID
        numerOfUserSwitches++;
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID1}),
                mUserController.getRunningUsersLU());
        // Skip all other steps and test unlock delaying only
        UserState uss = mUserStates.get(TEST_USER_ID);
        uss.setState(UserState.STATE_SHUTDOWN); // necessary state change from skipped part
        mUserController.finishUserStopped(uss, /* allowDelayedLocking= */ true);
        // Cannot mock FgThread handler, so confirm that there is no posted message left before
        // checking.
        waitForHandlerToComplete(FgThread.getHandler(), HANDLER_WAIT_TIME_MS);
        verify(mInjector.mStorageManagerMock, times(0))
                .lockCeStorage(anyInt());

        addForegroundUserAndContinueUserSwitch(TEST_USER_ID2, TEST_USER_ID1,
                numerOfUserSwitches, true);
        // running: user 0, USER_ID2
        // stopped + unlocked: USER_ID1
        // stopped + locked: USER_ID
        assertTrue(mUserController.canStartMoreUsers());
        assertEquals(Arrays.asList(new Integer[] {0, TEST_USER_ID2}),
                mUserController.getRunningUsersLU());
        UserState ussUser1 = mUserStates.get(TEST_USER_ID1);
        ussUser1.setState(UserState.STATE_SHUTDOWN);
        mUserController.finishUserStopped(ussUser1, /* allowDelayedLocking= */ true);
        waitForHandlerToComplete(FgThread.getHandler(), HANDLER_WAIT_TIME_MS);
        verify(mInjector.mStorageManagerMock, times(1))
                .lockCeStorage(TEST_USER_ID);
    }

    /**
     * Test locking user with mDelayUserDataLocking false.
     */
    @Test
    public void testUserLockingWithStopUserForNonDelayedLockingMode() throws Exception {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        setUpAndStartUserInBackground(TEST_USER_ID);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback= */ null, /* expectLocking= */ true);

        setUpAndStartUserInBackground(TEST_USER_ID1);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback= */ mKeyEvictedCallback, /* expectLocking= */ true);

        setUpAndStartUserInBackground(TEST_USER_ID2);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID2, /* allowDelayedLocking= */ false,
                /* keyEvictedCallback= */ null, /* expectLocking= */ true);

        setUpAndStartUserInBackground(TEST_USER_ID3);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID3, /* allowDelayedLocking= */ false,
                /* keyEvictedCallback= */ mKeyEvictedCallback, /* expectLocking= */ true);
    }

    @Test
    public void testStopUser_invalidUser() {
        int userId = -1;

        assertThrows(IllegalArgumentException.class,
                () -> mUserController.stopUser(userId, /* force= */ true,
                        /* allowDelayedLocking= */ true, /* stopUserCallback= */ null,
                        /* keyEvictedCallback= */ null));
    }

    @Test
    public void testStopUser_systemUser() {
        int userId = UserHandle.USER_SYSTEM;

        int r = mUserController.stopUser(userId, /* force= */ true,
                /* allowDelayedLocking= */ true, /* stopUserCallback= */ null,
                /* keyEvictedCallback= */ null);

        assertThat(r).isEqualTo(ActivityManager.USER_OP_ERROR_IS_SYSTEM);
    }

    @Test
    public void testStopUser_currentUser() {
        setUpUser(TEST_USER_ID1, /* flags= */ 0);
        mUserController.startUser(TEST_USER_ID1, USER_START_MODE_FOREGROUND);

        int r = mUserController.stopUser(TEST_USER_ID1, /* force= */ true,
                /* allowDelayedLocking= */ true, /* stopUserCallback= */ null,
                /* keyEvictedCallback= */ null);

        assertThat(r).isEqualTo(ActivityManager.USER_OP_IS_CURRENT);
    }

    /**
     * Test conditional delayed locking with mDelayUserDataLocking true.
     */
    @Test
    public void testUserLockingForDelayedLockingMode() throws Exception {
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ true);

        // allowDelayedLocking set and no KeyEvictedCallback, so it should not lock.
        setUpAndStartUserInBackground(TEST_USER_ID);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback= */ null, /* expectLocking= */ false);

        setUpAndStartUserInBackground(TEST_USER_ID1);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback= */ mKeyEvictedCallback, /* expectLocking= */ true);

        setUpAndStartUserInBackground(TEST_USER_ID2);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID2, /* allowDelayedLocking= */ false,
                /* keyEvictedCallback= */ null, /* expectLocking= */ true);

        setUpAndStartUserInBackground(TEST_USER_ID3);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID3, /* allowDelayedLocking= */ false,
                /* keyEvictedCallback= */ mKeyEvictedCallback, /* expectLocking= */ true);
    }

    @Test
    public void testUserNotUnlockedBeforeAllowed() throws Exception {
        mUserController.setAllowUserUnlocking(false);

        mUserController.startUser(TEST_USER_ID, USER_START_MODE_BACKGROUND);

        verify(mInjector.mStorageManagerMock, never()).unlockCeStorage(eq(TEST_USER_ID), any());
    }

    @Test
    public void testStartProfile_fullUserFails() {
        setUpUser(TEST_USER_ID1, 0);
        assertThrows(IllegalArgumentException.class,
                () -> mUserController.startProfile(TEST_USER_ID1, /* evenWhenDisabled= */ false,
                        /* unlockListener= */ null));

        verifyUserNeverAssignedToDisplay();
    }

    @Test
    public void testStopProfile_fullUserFails() throws Exception {
        setUpAndStartUserInBackground(TEST_USER_ID1);
        assertThrows(IllegalArgumentException.class,
                () -> mUserController.stopProfile(TEST_USER_ID1));
        verifyUserUnassignedFromDisplayNeverCalled(TEST_USER_ID);
    }

    @Test
    public void testStartProfile_disabledProfileFails() {
        setUpUser(TEST_USER_ID1, UserInfo.FLAG_PROFILE | UserInfo.FLAG_DISABLED, /* preCreated= */
                false, UserManager.USER_TYPE_PROFILE_MANAGED);
        assertThat(mUserController.startProfile(TEST_USER_ID1, /* evenWhenDisabled=*/ false,
                /* unlockListener= */ null)).isFalse();

        verifyUserNeverAssignedToDisplay();
    }

    @Test
    public void testStartManagedProfile() throws Exception {
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_MANAGED);

        startBackgroundUserAssertions();
        verifyUserAssignedToDisplay(TEST_USER_ID1, Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testStartManagedProfile_whenUsersOnSecondaryDisplaysIsEnabled() throws Exception {
        mockIsUsersOnSecondaryDisplaysEnabled(true);

        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_MANAGED);

        startBackgroundUserAssertions();
        verifyUserAssignedToDisplay(TEST_USER_ID1, Display.DEFAULT_DISPLAY);
    }

    @Test
    public void testStopManagedProfile() throws Exception {
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_MANAGED);
        assertProfileLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* expectLocking= */ true);
        verifyUserUnassignedFromDisplay(TEST_USER_ID1);
    }

    @Test
    public void testStopPrivateProfile() throws Exception {
        mUserController.setInitialConfig(/* mUserSwitchUiEnabled */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertProfileLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* expectLocking= */ true);
        verifyUserUnassignedFromDisplay(TEST_USER_ID1);

        mSetFlagsRule.disableFlags(
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID2, UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertProfileLockedOrUnlockedAfterStopping(TEST_USER_ID2, /* expectLocking= */ true);
        verifyUserUnassignedFromDisplay(TEST_USER_ID2);
    }

    @Test
    public void testStopPrivateProfileWithDelayedLocking() throws Exception {
        mUserController.setInitialConfig(/* mUserSwitchUiEnabled */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback */ null, /* expectLocking= */ false);
    }

    @Test
    public void testStopPrivateProfileWithDelayedLocking_flagDisabled() throws Exception {
        mUserController.setInitialConfig(/* mUserSwitchUiEnabled */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE);
        mSetFlagsRule.disableFlags(
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback */ null, /* expectLocking= */ true);

        mSetFlagsRule.disableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE);
        mSetFlagsRule.enableFlags(
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID2, UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID2, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback */ null, /* expectLocking= */ true);
    }

    /** Delayed-locking users (as opposed to devices) have no limits on how many can be unlocked. */
    @Test
    public void testStopPrivateProfileWithDelayedLocking_imperviousToNumberOfRunningUsers()
            throws Exception {
        mUserController.setInitialConfig(/* mUserSwitchUiEnabled */ true,
                /* maxRunningUsers= */ 1, /* delayUserDataLocking= */ false);
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_PRIVATE);
        setUpAndStartProfileInBackground(TEST_USER_ID2, UserManager.USER_TYPE_PROFILE_MANAGED);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback */ null, /* expectLocking= */ false);
    }

    /**
        * Tests that when a device/user (managed profile) does not permit delayed locking, then
        * even if allowDelayedLocking is true, the user will still be locked.
    */
    @Test
    public void testStopManagedProfileWithDelayedLocking() throws Exception {
        mUserController.setInitialConfig(/* mUserSwitchUiEnabled */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        setUpAndStartProfileInBackground(TEST_USER_ID1, UserManager.USER_TYPE_PROFILE_MANAGED);
        assertUserLockedOrUnlockedAfterStopping(TEST_USER_ID1, /* allowDelayedLocking= */ true,
                /* keyEvictedCallback */ null, /* expectLocking= */ true);
    }

    /** Tests handleIncomingUser() for a variety of permissions and situations. */
    @Test
    public void testHandleIncomingUser() throws Exception {
        final UserInfo user1a = new UserInfo(111, "user1a", 0);
        final UserInfo user1b = new UserInfo(112, "user1b", 0);
        final UserInfo user2 = new UserInfo(113, "user2", 0);
        // user1a and user2b are in the same profile group; user2 is in a different one.
        user1a.profileGroupId = 5;
        user1b.profileGroupId = 5;
        user2.profileGroupId = 6;

        final List<UserInfo> users = Arrays.asList(user1a, user1b, user2);
        when(mInjector.mUserManagerMock.getUsers(false)).thenReturn(users);
        mUserController.onSystemReady(); // To set the profileGroupIds in UserController.


        // Has INTERACT_ACROSS_USERS_FULL.
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS_FULL), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mInjector.checkPermissionForPreflight(
                eq(INTERACT_ACROSS_PROFILES), anyInt(), anyInt(), any())).thenReturn(false);

        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL, true);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL_IN_PROFILE, true);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_FULL_ONLY, true);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_PROFILES_OR_NON_FULL, true);

        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL, true);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL_IN_PROFILE, true);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_FULL_ONLY, true);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_PROFILES_OR_NON_FULL, true);


        // Has INTERACT_ACROSS_USERS.
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS_FULL), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mInjector.checkPermissionForPreflight(
                eq(INTERACT_ACROSS_PROFILES), anyInt(), anyInt(), any())).thenReturn(false);

        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL, true);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL_IN_PROFILE, false);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_FULL_ONLY, false);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_PROFILES_OR_NON_FULL, true);

        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL, true);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL_IN_PROFILE, true);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_FULL_ONLY, false);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_PROFILES_OR_NON_FULL, true);


        // Has INTERACT_ACROSS_PROFILES.
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS_FULL), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mInjector.checkComponentPermission(
                eq(INTERACT_ACROSS_USERS), anyInt(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mInjector.checkPermissionForPreflight(
                eq(INTERACT_ACROSS_PROFILES), anyInt(), anyInt(), any())).thenReturn(true);

        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL, false);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_NON_FULL_IN_PROFILE, false);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_FULL_ONLY, false);
        checkHandleIncomingUser(user1a.id, user2.id, ALLOW_PROFILES_OR_NON_FULL, false);

        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL, false);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_NON_FULL_IN_PROFILE, false);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_FULL_ONLY, false);
        checkHandleIncomingUser(user1a.id, user1b.id, ALLOW_PROFILES_OR_NON_FULL, true);
    }

    private void checkHandleIncomingUser(int fromUser, int toUser, int allowMode, boolean pass) {
        final int pid = 100;
        final int uid = fromUser * UserHandle.PER_USER_RANGE + 34567 + fromUser;
        final String name = "whatever";
        final String pkg = "some.package";
        final boolean allowAll = false;

        if (pass) {
            mUserController.handleIncomingUser(pid, uid, toUser, allowAll, allowMode, name, pkg);
        } else {
            assertThrows(SecurityException.class, () -> mUserController.handleIncomingUser(
                    pid, uid, toUser, allowAll, allowMode, name, pkg));
        }
    }

    @Test
    public void testScheduleOnUserCompletedEvent() throws Exception {
        // user1 is starting, switching, and unlocked, but not scheduled unlocked yet
        // user2 is starting and had unlocked but isn't unlocked anymore for whatever reason

        final int user1 = 101;
        final int user2 = 102;
        setUpUser(user1, 0);
        setUpUser(user2, 0);

        mUserController.startUser(user1, USER_START_MODE_FOREGROUND);
        mUserController.getStartedUserState(user1).setState(UserState.STATE_RUNNING_UNLOCKED);

        mUserController.startUser(user2, USER_START_MODE_BACKGROUND);
        mUserController.getStartedUserState(user2).setState(UserState.STATE_RUNNING_LOCKED);

        final int event1a = SystemService.UserCompletedEventType.EVENT_TYPE_USER_STARTING;
        final int event1b = SystemService.UserCompletedEventType.EVENT_TYPE_USER_SWITCHING;

        final int event2a = SystemService.UserCompletedEventType.EVENT_TYPE_USER_STARTING;
        final int event2b = SystemService.UserCompletedEventType.EVENT_TYPE_USER_UNLOCKED;


        mUserController.scheduleOnUserCompletedEvent(user1, event1a, 2000);
        assertNotNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user1));
        assertNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user2));

        mUserController.scheduleOnUserCompletedEvent(user2, event2a, 2000);
        assertNotNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user1));
        assertNotNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user2));

        mUserController.scheduleOnUserCompletedEvent(user2, event2b, 2000);
        mUserController.scheduleOnUserCompletedEvent(user1, event1b, 2000);
        mUserController.scheduleOnUserCompletedEvent(user1, 0, 2000);

        assertNotNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user1));
        assertNotNull(mInjector.mHandler.getMessageForCode(USER_COMPLETED_EVENT_MSG, user2));

        mUserController.reportOnUserCompletedEvent(user1);
        verify(mInjector, times(1))
                .systemServiceManagerOnUserCompletedEvent(eq(user1), eq(event1a | event1b));
        verify(mInjector, never()).systemServiceManagerOnUserCompletedEvent(eq(user2), anyInt());

        mUserController.reportOnUserCompletedEvent(user2);
        verify(mInjector, times(1))
                .systemServiceManagerOnUserCompletedEvent(eq(user2), eq(event2a));
    }

    @Test
    public void testStallUserSwitchUntilTheKeyguardIsShown() throws Exception {
        // enable user switch ui, because keyguard is only shown then
        mUserController.setInitialConfig(/* userSwitchUiEnabled= */ true,
                /* maxRunningUsers= */ 3, /* delayUserDataLocking= */ false);

        // mock the device to be secure in order to expect the keyguard to be shown
        when(mInjector.mKeyguardManagerMock.isDeviceSecure(anyInt())).thenReturn(true);

        // call real lockDeviceNowAndWaitForKeyguardShown method for this test
        doCallRealMethod().when(mInjector).lockDeviceNowAndWaitForKeyguardShown();

        // call startUser on a thread because we're expecting it to be blocked
        Thread threadStartUser = new Thread(()-> {
            mUserController.startUser(TEST_USER_ID, USER_START_MODE_FOREGROUND);
        });
        threadStartUser.start();

        // make sure the switch is stalled...
        Thread.sleep(2000);
        // by checking REPORT_USER_SWITCH_MSG is not sent yet
        assertNull(mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG));
        // and the thread is still alive
        assertTrue(threadStartUser.isAlive());

        // mock send the keyguard shown event
        ArgumentCaptor<ActivityTaskManagerInternal.ScreenObserver> captor = ArgumentCaptor.forClass(
                ActivityTaskManagerInternal.ScreenObserver.class);
        verify(mInjector.mActivityTaskManagerInternal).registerScreenObserver(captor.capture());
        captor.getValue().onKeyguardStateChanged(true);

        // verify the switch now moves on...
        Thread.sleep(1000);
        // by checking REPORT_USER_SWITCH_MSG is sent
        assertNotNull(mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG));
        // and the thread is finished
        assertFalse(threadStartUser.isAlive());
    }

    private void setUpAndStartUserInBackground(int userId) throws Exception {
        setUpUser(userId, 0);
        mUserController.startUser(userId, USER_START_MODE_BACKGROUND);
        verify(mInjector.mLockPatternUtilsMock, times(1)).unlockUserKeyIfUnsecured(userId);
        mUserStates.put(userId, mUserController.getStartedUserState(userId));
    }

    private void setUpAndStartProfileInBackground(int userId, String userType) throws Exception {
        setUpUser(userId, UserInfo.FLAG_PROFILE, false, userType);
        assertThat(mUserController.startProfile(userId, /* evenWhenDisabled=*/ false,
                /* unlockListener= */ null)).isTrue();

        verify(mInjector.mLockPatternUtilsMock, times(1)).unlockUserKeyIfUnsecured(userId);
        mUserStates.put(userId, mUserController.getStartedUserState(userId));
    }

    private void assertUserLockedOrUnlockedAfterStopping(int userId, boolean allowDelayedLocking,
            KeyEvictedCallback keyEvictedCallback, boolean expectLocking) throws Exception {
        int r = mUserController.stopUser(userId, /* force= */ true, /* allowDelayedLocking= */
                allowDelayedLocking, null, keyEvictedCallback);
        assertThat(r).isEqualTo(ActivityManager.USER_OP_SUCCESS);
        assertUserLockedOrUnlockedState(userId, allowDelayedLocking, expectLocking);
    }

    private void assertProfileLockedOrUnlockedAfterStopping(int userId, boolean expectLocking)
            throws Exception {
        boolean profileStopped = mUserController.stopProfile(userId);
        assertThat(profileStopped).isTrue();
        assertUserLockedOrUnlockedState(userId, /* allowDelayedLocking= */ false, expectLocking);
    }

    private void assertUserLockedOrUnlockedState(int userId, boolean allowDelayedLocking,
            boolean expectLocking) throws InterruptedException, RemoteException {
        // fake all interim steps
        UserState ussUser = mUserStates.get(userId);
        ussUser.setState(UserState.STATE_SHUTDOWN);
        // Passing delayedLocking invalidates incorrect internal data passing but currently there is
        // no easy way to get that information passed through lambda.
        mUserController.finishUserStopped(ussUser, allowDelayedLocking);
        waitForHandlerToComplete(FgThread.getHandler(), HANDLER_WAIT_TIME_MS);
        verify(mInjector.mStorageManagerMock, times(expectLocking ? 1 : 0))
                .lockCeStorage(userId);
    }

    private void addForegroundUserAndContinueUserSwitch(int newUserId, int expectedOldUserId,
            int expectedNumberOfCalls, boolean expectOldUserStopping) {
        // Start user -- this will update state of mUserController
        mUserController.startUser(newUserId, USER_START_MODE_FOREGROUND);
        Message reportMsg = mInjector.mHandler.getMessageForCode(REPORT_USER_SWITCH_MSG);
        assertNotNull(reportMsg);
        UserState userState = (UserState) reportMsg.obj;
        int oldUserId = reportMsg.arg1;
        assertEquals(expectedOldUserId, oldUserId);
        assertEquals(newUserId, reportMsg.arg2);
        mUserStates.put(newUserId, userState);
        mInjector.mHandler.clearAllRecordedMessages();
        // Verify that continueUserSwitch worked as expected
        continueAndCompleteUserSwitch(userState, oldUserId, newUserId);
        verify(mInjector, times(expectedNumberOfCalls)).dismissUserSwitchingDialog(any());
        continueUserSwitchAssertions(oldUserId, newUserId, expectOldUserStopping);
    }

    private void setUpUser(@UserIdInt int userId, @UserInfoFlag int flags) {
        setUpUser(userId, flags, /* preCreated= */ false, /* userType */ null);
    }

    private void setUpUser(@UserIdInt int userId, @UserInfoFlag int flags, boolean preCreated,
            @Nullable String userType) {
        if (userType == null) {
            userType = UserInfo.getDefaultUserType(flags);
        }
        UserInfo userInfo = new UserInfo(userId, "User" + userId, /* iconPath= */ null, flags,
                userType);
        userInfo.preCreated = preCreated;
        when(mInjector.mUserManagerMock.getUserInfo(eq(userId))).thenReturn(userInfo);
        when(mInjector.mUserManagerMock.isPreCreated(userId)).thenReturn(preCreated);

        UserTypeDetails userTypeDetails = UserTypeFactory.getUserTypes().get(userType);
        assertThat(userTypeDetails).isNotNull();
        when(mInjector.mUserManagerInternalMock.getUserProperties(eq(userId)))
                .thenReturn(userTypeDetails.getDefaultUserPropertiesReference());
    }

    private static List<String> getActions(List<Intent> intents) {
        List<String> result = new ArrayList<>();
        for (Intent intent : intents) {
            result.add(intent.getAction());
        }
        return result;
    }

    private void waitForHandlerToComplete(Handler handler, long waitTimeMs)
            throws InterruptedException {
        final Object lock = new Object();
        synchronized (lock) {
            handler.post(() -> {
                synchronized (lock) {
                    lock.notify();
                }
            });
            lock.wait(waitTimeMs);
        }
    }

    private void mockIsHeadlessSystemUserMode(boolean value) {
        when(mInjector.isHeadlessSystemUserMode()).thenReturn(value);
    }

    private void mockIsUsersOnSecondaryDisplaysEnabled(boolean value) {
        when(mInjector.isUsersOnSecondaryDisplaysEnabled()).thenReturn(value);
    }

    private void verifyUserAssignedToDisplay(@UserIdInt int userId, int displayId) {
        verify(mInjector.getUserManagerInternal()).assignUserToDisplayOnStart(eq(userId), anyInt(),
                anyInt(), eq(displayId));
    }

    private void verifyUserNeverAssignedToDisplay() {
        verify(mInjector.getUserManagerInternal(), never()).assignUserToDisplayOnStart(anyInt(),
                anyInt(), anyInt(), anyInt());
    }

    private void verifyUserUnassignedFromDisplay(@UserIdInt int userId) {
        verify(mInjector.getUserManagerInternal()).unassignUserFromDisplayOnStop(userId);
    }

    private void verifyUserUnassignedFromDisplayNeverCalled(@UserIdInt int userId) {
        verify(mInjector.getUserManagerInternal(), never()).unassignUserFromDisplayOnStop(userId);
    }

    private void verifySystemUserVisibilityChangesNeverNotified() {
        verify(mInjector, never()).onSystemUserVisibilityChanged(anyBoolean());
    }

    // Should be public to allow mocking
    private static class TestInjector extends UserController.Injector {
        public final TestHandler mHandler;
        public final HandlerThread mHandlerThread;
        public final UserManagerService mUserManagerMock;
        public final List<Intent> mSentIntents = new ArrayList<>();

        private final TestHandler mUiHandler;

        private final IStorageManager mStorageManagerMock;
        private final UserManagerInternal mUserManagerInternalMock;
        private final WindowManagerService mWindowManagerMock;
        private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
        private final PowerManagerInternal mPowerManagerInternal;
        private final KeyguardManager mKeyguardManagerMock;
        private final LockPatternUtils mLockPatternUtilsMock;

        private final UserJourneyLogger mUserJourneyLoggerMock;

        private final Context mCtx;

        private Integer mRelevantUser;

        TestInjector(Context ctx) {
            super(null);
            mCtx = ctx;
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();
            mHandler = new TestHandler(mHandlerThread.getLooper());
            mUiHandler = new TestHandler(mHandlerThread.getLooper());
            mUserManagerMock = mock(UserManagerService.class);
            mUserManagerInternalMock = mock(UserManagerInternal.class);
            mWindowManagerMock = mock(WindowManagerService.class);
            mActivityTaskManagerInternal = mock(ActivityTaskManagerInternal.class);
            mStorageManagerMock = mock(IStorageManager.class);
            mPowerManagerInternal = mock(PowerManagerInternal.class);
            mKeyguardManagerMock = mock(KeyguardManager.class);
            when(mKeyguardManagerMock.isDeviceSecure(anyInt())).thenReturn(true);
            mLockPatternUtilsMock = mock(LockPatternUtils.class);
            mUserJourneyLoggerMock = mock(UserJourneyLogger.class);
        }

        @Override
        protected Handler getHandler(Handler.Callback callback) {
            return mHandler;
        }

        @Override
        protected Handler getUiHandler(Handler.Callback callback) {
            return mUiHandler;
        }

        @Override
        protected UserManagerService getUserManager() {
            return mUserManagerMock;
        }

        @Override
        UserManagerInternal getUserManagerInternal() {
            return mUserManagerInternalMock;
        }

        @Override
        protected Context getContext() {
            return mCtx;
        }

        @Override
        int checkCallingPermission(String permission) {
            Log.i(TAG, "checkCallingPermission " + permission);
            return PERMISSION_GRANTED;
        }

        @Override
        int checkComponentPermission(String permission, int pid, int uid, int owner, boolean exp) {
            Log.i(TAG, "checkComponentPermission " + permission);
            return PERMISSION_GRANTED;
        }

        @Override
        boolean checkPermissionForPreflight(String permission, int pid, int uid, String pkg) {
            Log.i(TAG, "checkPermissionForPreflight " + permission);
            return true;
        }

        @Override
        boolean isCallerRecents(int uid) {
            return false;
        }

        @Override
        WindowManagerService getWindowManager() {
            return mWindowManagerMock;
        }

        @Override
        ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return mActivityTaskManagerInternal;
        }

        @Override
        PowerManagerInternal getPowerManagerInternal() {
            return mPowerManagerInternal;
        }

        @Override
        KeyguardManager getKeyguardManager() {
            return mKeyguardManagerMock;
        }

        @Override
        void updateUserConfiguration() {
            Log.i(TAG, "updateUserConfiguration");
        }

        @Override
        protected int broadcastIntent(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData, Bundle resultExtras,
                String[] requiredPermissions, int appOp, Bundle bOptions, boolean ordered,
                boolean sticky, int callingPid, int callingUid, int realCallingUid,
                int realCallingPid, int userId) {
            Log.i(TAG, "broadcastIntentLocked " + intent);
            if (mRelevantUser == null || mRelevantUser == userId || userId == UserHandle.USER_ALL) {
                mSentIntents.add(intent);
            }
            return 0;
        }

        @Override
        void reportGlobalUsageEvent(int event) {
        }

        @Override
        void reportCurWakefulnessUsageEvent() {
        }

        @Override
        boolean isRuntimeRestarted() {
            // to pass all metrics related calls
            return true;
        }

        @Override
        protected IStorageManager getStorageManager() {
            return mStorageManagerMock;
        }

        @Override
        protected void dismissKeyguard(Runnable runnable) {
            runnable.run();
        }

        @Override
        void showUserSwitchingDialog(UserInfo fromUser, UserInfo toUser,
                String switchingFromSystemUserMessage, String switchingToSystemUserMessage,
                Runnable onShown) {
            if (onShown != null) {
                onShown.run();
            }
        }

        @Override
        void dismissUserSwitchingDialog(Runnable onDismissed) {
            if (onDismissed != null) {
                onDismissed.run();
            }
        }

        @Override
        protected LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtilsMock;
        }

        @Override
        void onUserStarting(@UserIdInt int userId) {
            Log.i(TAG, "onUserStarting(" + userId + ")");
        }

        @Override
        void onSystemUserVisibilityChanged(boolean visible) {
            Log.i(TAG, "onSystemUserVisibilityChanged(" + visible + ")");
        }

        @Override
        protected UserJourneyLogger getUserJourneyLogger() {
            return mUserJourneyLoggerMock;
        }
    }

    private static class TestHandler extends Handler {
        private final List<Message> mMessages = new ArrayList<>();

        TestHandler(Looper looper) {
            super(looper);
        }

        Set<Integer> getMessageCodes() {
            Set<Integer> result = new LinkedHashSet<>();
            for (Message msg : mMessages) {
                result.add(msg.what);
            }
            return result;
        }

        Message getMessageForCode(int what) {
            return getMessageForCode(what, null);
        }

        Message getMessageForCode(int what, Object obj) {
            for (Message msg : mMessages) {
                if (msg.what == what && (obj == null || obj.equals(msg.obj))) {
                    return msg;
                }
            }
            return null;
        }

        void clearAllRecordedMessages() {
            mMessages.clear();
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            Message copy = new Message();
            copy.copyFrom(msg);
            mMessages.add(copy);
            if (msg.getCallback() != null) {
                msg.getCallback().run();
                msg.setCallback(null);
            }
            return super.sendMessageAtTime(msg, uptimeMillis);
        }
    }
}
