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

import android.media.tv.ad.ITvAdClient;
import android.view.Surface;

/**
 * Interface to the TV AD service.
 * @hide
 */
interface ITvAdManager {
    void createSession(
            in ITvAdClient client, in String serviceId, in String type, int seq, int userId);
    void releaseSession(in IBinder sessionToken, int userId);
    void startAdService(in IBinder sessionToken, int userId);
    void setSurface(in IBinder sessionToken, in Surface surface, int userId);
    void dispatchSurfaceChanged(in IBinder sessionToken, int format, int width, int height,
            int userId);
}
