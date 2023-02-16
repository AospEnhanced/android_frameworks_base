/*
 * Copyright 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.telephony.satellite.ISatelliteDatagramReceiverAck;
import android.telephony.satellite.SatelliteDatagram;

/**
 * Interface for satellite datagrams callback.
 * @hide
 */
oneway interface ISatelliteDatagramCallback {
    /**
     * Called when datagrams are received from satellite.
     *
     * @param datagramId An id that uniquely identifies incoming datagram.
     * @param datagram datagram received from satellite.
     * @param pendingCount Number of datagrams yet to be received from satellite.
     * @param callback This callback will be used by datagram receiver app to send ack back to
     *                 Telephony. If the callback is not received within five minutes,
     *                 Telephony will resend the datagrams.
     */
    void onSatelliteDatagramReceived(long datagramId, in SatelliteDatagram datagram,
            int pendingCount, ISatelliteDatagramReceiverAck callback);
}
