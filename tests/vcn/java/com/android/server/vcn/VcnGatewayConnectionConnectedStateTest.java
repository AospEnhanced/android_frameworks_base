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

import static android.net.IpSecManager.DIRECTION_IN;
import static android.net.IpSecManager.DIRECTION_OUT;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static com.android.server.vcn.VcnGatewayConnection.VcnChildSessionConfiguration;
import static com.android.server.vcn.VcnGatewayConnection.VcnIkeSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

/** Tests for VcnGatewayConnection.ConnectedState */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VcnGatewayConnectionConnectedStateTest extends VcnGatewayConnectionTestBase {
    private VcnIkeSession mIkeSession;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        mGatewayConnection.setUnderlyingNetwork(TEST_UNDERLYING_NETWORK_RECORD_1);

        mIkeSession = mGatewayConnection.buildIkeSession();
        mGatewayConnection.setIkeSession(mIkeSession);

        mGatewayConnection.transitionTo(mGatewayConnection.mConnectedState);
        mTestLooper.dispatchAll();
    }

    @Test
    public void testEnterStateCreatesNewIkeSession() throws Exception {
        verify(mDeps).newIkeSession(any(), any(), any(), any(), any());
    }

    @Test
    public void testEnterStateDoesNotCancelSafemodeAlarm() {
        verifySafemodeTimeoutAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testNullNetworkDoesNotTriggerDisconnect() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(null);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        verify(mIkeSession, never()).close();
        verifyDisconnectRequestAlarmAndGetCallback(false /* expectCanceled */);
    }

    @Test
    public void testNewNetworkTriggersMigration() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_2);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
        verify(mIkeSession, never()).close();
        verify(mIkeSession).setNetwork(TEST_UNDERLYING_NETWORK_RECORD_2.network);
    }

    @Test
    public void testSameNetworkDoesNotTriggerMigration() throws Exception {
        mGatewayConnection
                .getUnderlyingNetworkTrackerCallback()
                .onSelectedUnderlyingNetworkChanged(TEST_UNDERLYING_NETWORK_RECORD_1);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testCreatedTransformsAreApplied() throws Exception {
        for (int direction : new int[] {DIRECTION_IN, DIRECTION_OUT}) {
            getChildSessionCallback().onIpSecTransformCreated(makeDummyIpSecTransform(), direction);
            mTestLooper.dispatchAll();

            verify(mIpSecSvc)
                    .applyTunnelModeTransform(
                            eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(direction), anyInt(), any());
        }

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());
    }

    @Test
    public void testChildOpenedRegistersNetwork() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafemodeTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        final VcnChildSessionConfiguration mMockChildSessionConfig =
                mock(VcnChildSessionConfiguration.class);
        doReturn(Collections.singletonList(TEST_INTERNAL_ADDR))
                .when(mMockChildSessionConfig)
                .getInternalAddresses();
        doReturn(Collections.singletonList(TEST_DNS_ADDR))
                .when(mMockChildSessionConfig)
                .getInternalDnsServers();

        getChildSessionCallback().onOpened(mMockChildSessionConfig);
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mConnectedState, mGatewayConnection.getCurrentState());

        final ArgumentCaptor<LinkProperties> lpCaptor =
                ArgumentCaptor.forClass(LinkProperties.class);
        final ArgumentCaptor<NetworkCapabilities> ncCaptor =
                ArgumentCaptor.forClass(NetworkCapabilities.class);
        verify(mConnMgr)
                .registerNetworkAgent(
                        any(),
                        any(),
                        lpCaptor.capture(),
                        ncCaptor.capture(),
                        anyInt(),
                        any(),
                        anyInt());
        verify(mIpSecSvc)
                .addAddressToTunnelInterface(
                        eq(TEST_IPSEC_TUNNEL_RESOURCE_ID), eq(TEST_INTERNAL_ADDR), any());

        final LinkProperties lp = lpCaptor.getValue();
        assertEquals(Collections.singletonList(TEST_INTERNAL_ADDR), lp.getLinkAddresses());
        assertEquals(Collections.singletonList(TEST_DNS_ADDR), lp.getDnsServers());

        final NetworkCapabilities nc = ncCaptor.getValue();
        assertTrue(nc.hasTransport(TRANSPORT_CELLULAR));
        assertFalse(nc.hasTransport(TRANSPORT_WIFI));
        for (int cap : mConfig.getAllExposedCapabilities()) {
            assertTrue(nc.hasCapability(cap));
        }

        // Now that Vcn Network is up, notify it as validated and verify the Safemode alarm is
        // canceled
        mGatewayConnection.mNetworkAgent.onValidationStatus(
                NetworkAgent.VALIDATION_STATUS_VALID, null /* redirectUri */);
        verify(mSafemodeTimeoutAlarm).cancel();
    }

    @Test
    public void testChildSessionClosedTriggersDisconnect() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafemodeTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        getChildSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mDisconnectingState, mGatewayConnection.getCurrentState());
        verifyTeardownTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        // Since network never validated, verify mSafemodeTimeoutAlarm not canceled
        verifyNoMoreInteractions(mSafemodeTimeoutAlarm);
    }

    @Test
    public void testIkeSessionClosedTriggersDisconnect() throws Exception {
        // Verify scheduled but not canceled when entering ConnectedState
        verifySafemodeTimeoutAlarmAndGetCallback(false /* expectCanceled */);

        getIkeSessionCallback().onClosed();
        mTestLooper.dispatchAll();

        assertEquals(mGatewayConnection.mRetryTimeoutState, mGatewayConnection.getCurrentState());
        verify(mIkeSession).close();

        // Since network never validated, verify mSafemodeTimeoutAlarm not canceled
        verifyNoMoreInteractions(mSafemodeTimeoutAlarm);
    }
}
