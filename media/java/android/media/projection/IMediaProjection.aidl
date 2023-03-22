/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.media.projection.IMediaProjectionCallback;
import android.os.IBinder;

/** {@hide} */
interface IMediaProjection {
    void start(IMediaProjectionCallback callback);
    void stop();

    boolean canProjectAudio();
    boolean canProjectVideo();
    boolean canProjectSecureVideo();

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    int applyVirtualDisplayFlags(int flags);

    void registerCallback(IMediaProjectionCallback callback);

    void unregisterCallback(IMediaProjectionCallback callback);

    /**
     * Returns the {@link android.os.IBinder} identifying the task to record, or {@code null} if
     * there is none.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    IBinder getLaunchCookie();

    /**
     * Updates the {@link android.os.IBinder} identifying the task to record, or {@code null} if
     * there is none.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MANAGE_MEDIA_PROJECTION)")
    void setLaunchCookie(in IBinder launchCookie);
}
