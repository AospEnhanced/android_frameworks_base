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

package android.media.tv.ad;

import android.os.Bundle;
import android.view.InputChannel;

/**
 * Interface a client of the ITvAdManager implements, to identify itself and receive
 * information about changes to the state of each TV AD service.
 * @hide
 */
oneway interface ITvAdClient {
    void onSessionCreated(in String serviceId, IBinder token, in InputChannel channel, int seq);
    void onSessionReleased(int seq);
    void onLayoutSurface(int left, int top, int right, int bottom, int seq);
    void onRequestCurrentVideoBounds(int seq);
    void onRequestCurrentChannelUri(int seq);
    void onRequestTrackInfoList(int seq);
    void onRequestCurrentTvInputId(int seq);
    void onRequestSigning(
            in String id, in String algorithm, in String alias, in byte[] data, int seq);
    void onTvAdSessionData(in String type, in Bundle data, int seq);
}