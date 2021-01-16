/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.media.metrics;

import android.content.Context;
import android.media.metrics.IPlaybackMetricsManager;
import android.media.metrics.NetworkEvent;
import android.media.metrics.PlaybackErrorEvent;
import android.media.metrics.PlaybackMetrics;
import android.media.metrics.PlaybackStateEvent;
import android.os.Binder;
import android.util.Base64;
import android.util.StatsEvent;
import android.util.StatsLog;

import com.android.server.SystemService;

import java.security.SecureRandom;

/**
 * System service manages playback metrics.
 */
public final class PlaybackMetricsManagerService extends SystemService {
    private final SecureRandom mSecureRandom;

    /**
     * Initializes the playback metrics manager service.
     *
     * @param context The system server context.
     */
    public PlaybackMetricsManagerService(Context context) {
        super(context);
        mSecureRandom = new SecureRandom();
    }

    @Override
    public void onStart() {
        // TODO: make the service name a constant in Context.java
        publishBinderService("playback_metrics", new BinderService());
    }

    private final class BinderService extends IPlaybackMetricsManager.Stub {
        @Override
        public void reportPlaybackMetrics(String sessionId, PlaybackMetrics metrics, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(320)
                    .writeInt(Binder.getCallingUid())
                    .writeString(sessionId)
                    .writeLong(metrics.getMediaDurationMillis())
                    .writeInt(metrics.getStreamSource())
                    .writeInt(metrics.getStreamType())
                    .writeInt(metrics.getPlaybackType())
                    .writeInt(metrics.getDrmType())
                    .writeInt(metrics.getContentType())
                    .writeString(metrics.getPlayerName())
                    .writeString(metrics.getPlayerVersion())
                    .writeByteArray(new byte[0]) // TODO: write experiments proto
                    .writeInt(metrics.getVideoFramesPlayed())
                    .writeInt(metrics.getVideoFramesDropped())
                    .writeInt(metrics.getAudioUnderrunCount())
                    .writeLong(metrics.getNetworkBytesRead())
                    .writeLong(metrics.getLocalBytesRead())
                    .writeLong(metrics.getNetworkTransferDurationMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        @Override
        public void reportPlaybackStateEvent(
                String sessionId, PlaybackStateEvent event, int userId) {
            // TODO: log it to statsd
        }

        @Override
        public String getSessionId(int userId) {
            byte[] byteId = new byte[16]; // 128 bits
            mSecureRandom.nextBytes(byteId);
            String id = Base64.encodeToString(byteId, Base64.DEFAULT);
            return id;
        }

        @Override
        public void reportPlaybackErrorEvent(
                String sessionId, PlaybackErrorEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(323)
                    .writeString(sessionId)
                    .writeString(event.getExceptionStack())
                    .writeInt(event.getErrorCode())
                    .writeInt(event.getSubErrorCode())
                    .writeLong(event.getTimeSincePlaybackCreatedMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }

        public void reportNetworkEvent(
                String sessionId, NetworkEvent event, int userId) {
            StatsEvent statsEvent = StatsEvent.newBuilder()
                    .setAtomId(321)
                    .writeString(sessionId)
                    .writeInt(event.getType())
                    .writeLong(event.getTimeSincePlaybackCreatedMillis())
                    .usePooledBuffer()
                    .build();
            StatsLog.write(statsEvent);
        }
    }
}
