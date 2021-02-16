/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vcn;

import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for VcnGatewayConnection.ConnectingState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionConnectingStateTest extends VcnGatewayConnectionTestBase {
    private VcnIkeSession mIkeSession;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);
        mGatewayConnection.transitionTo(mGatewayConnection.mConnectingState);
        mTestLooper.dispatchAll();

        mIkeSession = mGatewayConnection.getIkeSession();
    }

    @Test
    public void testEnterStateCreatesNewIkeSession() throws Exception {
        verify(mDeps).newIkeSession(any(), any(), any(), any(), any());
    }

    @Test
    public void testNullNetworkTriggersDisconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).kill();
        verifyDisconnectRequestAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testNewNetworkTriggersReconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();
        verify(mIkeSession, never()).kill();
        verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testSameNetworkDoesNotTriggerReconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectingState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testChildSessionClosedTriggersDisconnect() throws Exception {
        getChildSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();
        verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testIkeSessionClosedTriggersDisconnect() throws Exception {
        getIkeSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();
        verifyTeardownTimeoutAlarmAndGetCallback(true /* expectCanceled */);
    }

    @Test
    public void testSafemodeTimeoutNotifiesCallback() {
        verifySafemodeTimeoutNotifiesCallback(mGatewayConnection.mConnectingState);
    }
}
