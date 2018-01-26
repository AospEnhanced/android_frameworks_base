/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.media.MediaController2.PlaybackInfo;
import android.media.MediaItem2;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.PlaylistParams;
import android.media.PlaybackState2;
import android.media.Rating2;
import android.media.SessionToken2;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.util.List;

/**
 * @hide
 */
public interface MediaController2Provider extends TransportControlProvider {
    void close_impl();
    SessionToken2 getSessionToken_impl();
    boolean isConnected_impl();

    PendingIntent getSessionActivity_impl();
    int getRatingType_impl();

    void setVolumeTo_impl(int value, int flags);
    void adjustVolume_impl(int direction, int flags);
    PlaybackInfo getPlaybackInfo_impl();

    void prepareFromUri_impl(Uri uri, Bundle extras);
    void prepareFromSearch_impl(String query, Bundle extras);
    void prepareMediaId_impl(String mediaId, Bundle extras);
    void playFromSearch_impl(String query, Bundle extras);
    void playFromUri_impl(String uri, Bundle extras);
    void playFromMediaId_impl(String mediaId, Bundle extras);

    void setRating_impl(Rating2 rating);
    void sendCustomCommand_impl(Command command, Bundle args, ResultReceiver cb);
    List<MediaItem2> getPlaylist_impl();

    void removePlaylistItem_impl(MediaItem2 index);
    void addPlaylistItem_impl(int index, MediaItem2 item);

    PlaylistParams getPlaylistParam_impl();
    void setPlaylistParams_impl(PlaylistParams params);
    PlaybackState2 getPlaybackState_impl();
}
