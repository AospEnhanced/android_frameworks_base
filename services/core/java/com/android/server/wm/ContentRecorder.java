/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONTENT_RECORDING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;

/**
 * Manages content recording for a particular {@link DisplayContent}.
 */
final class ContentRecorder {

    /**
     * The display content this class is handling recording for.
     */
    @NonNull
    private final DisplayContent mDisplayContent;

    /**
     * The session for content recording, or null if this DisplayContent is not being used for
     * recording.
     */
    @VisibleForTesting private ContentRecordingSession mContentRecordingSession = null;

    /**
     * The WindowContainer for the level of the hierarchy to record.
     */
    @Nullable private WindowContainer mRecordedWindowContainer = null;

    /**
     * The surface for recording the contents of this hierarchy, or null if content recording is
     * temporarily disabled.
     */
    @Nullable private SurfaceControl mRecordedSurface = null;

    /**
     * The last bounds of the region to record.
     */
    @Nullable private Rect mLastRecordedBounds = null;

    ContentRecorder(@NonNull DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /**
     * Sets the incoming recording session. Should only be used when starting to record on
     * this display; stopping recording is handled separately when the display is destroyed.
     *
     * @param session the new session indicating recording will begin on this display.
     */
    void setContentRecordingSession(@Nullable ContentRecordingSession session) {
        mContentRecordingSession = session;
    }

    /**
     * Returns {@code true} if this DisplayContent is currently recording.
     */
    boolean isCurrentlyRecording() {
        return mContentRecordingSession != null && mRecordedSurface != null;
    }

    /**
     * Start recording if this DisplayContent no longer has content. Stop recording if it now
     * has content or the display is not on.
     */
    @VisibleForTesting void updateRecording() {
        if (isCurrentlyRecording() && (mDisplayContent.getLastHasContent()
                || mDisplayContent.getDisplay().getState() == Display.STATE_OFF)) {
            pauseRecording();
        } else {
            // Display no longer has content, or now has a surface to write to, so try to start
            // recording.
            startRecordingIfNeeded();
        }
    }

    /**
     * Handle a configuration change on the display content, and resize recording if needed.
     * @param lastOrientation the prior orientation of the configuration
     */
    void onConfigurationChanged(@Configuration.Orientation int lastOrientation) {
        // Update surface for MediaProjection, if this DisplayContent is being used for recording.
        if (isCurrentlyRecording() && mLastRecordedBounds != null) {
            // Recording has already begun, but update recording since the display is now on.
            if (mRecordedWindowContainer == null) {
                ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                        "Unexpectedly null window container; unable to update recording for "
                                + "display %d",
                        mDisplayContent.getDisplayId());
                return;
            }

            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Display %d was already recording, so apply transformations if necessary",
                    mDisplayContent.getDisplayId());
            // Retrieve the size of the region to record, and continue with the update
            // if the bounds or orientation has changed.
            final Rect recordedContentBounds = mRecordedWindowContainer.getBounds();
            int recordedContentOrientation = mRecordedWindowContainer.getOrientation();
            if (!mLastRecordedBounds.equals(recordedContentBounds)
                    || lastOrientation != recordedContentOrientation) {
                Point surfaceSize = fetchSurfaceSizeIfPresent();
                if (surfaceSize != null) {
                    ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                            "Going ahead with updating recording for display %d to new "
                                    + "bounds %s and/or orientation %d.",
                            mDisplayContent.getDisplayId(), recordedContentBounds,
                            recordedContentOrientation);
                    updateMirroredSurface(mDisplayContent.mWmService.mTransactionFactory.get(),
                            recordedContentBounds, surfaceSize);
                } else {
                    // If the surface removed, do nothing. We will handle this via onDisplayChanged
                    // (the display will be off if the surface is removed).
                    ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                            "Unable to update recording for display %d to new bounds %s"
                                    + " and/or orientation %d, since the surface is not available.",
                            mDisplayContent.getDisplayId(), recordedContentBounds,
                            recordedContentOrientation);
                }
            }
        }
    }

    /**
     * Pauses recording on this display content. Note the session does not need to be updated,
     * since recording can be resumed still.
     */
    void pauseRecording() {
        if (mRecordedSurface == null) {
            return;
        }
        ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                "Display %d has content (%b) so pause recording", mDisplayContent.getDisplayId(),
                mDisplayContent.getLastHasContent());
        // If the display is not on and it is a virtual display, then it no longer has an
        // associated surface to write output to.
        // If the display now has content, stop mirroring to it.
        mDisplayContent.mWmService.mTransactionFactory.get()
                // Remove the reference to mMirroredSurface, to clean up associated memory.
                .remove(mRecordedSurface)
                // Reparent the SurfaceControl of this DisplayContent back to mSurfaceControl,
                // to allow content to be added to it. This allows this DisplayContent to stop
                // mirroring and show content normally.
                .reparent(mDisplayContent.getWindowingLayer(), mDisplayContent.getSurfaceControl())
                .reparent(mDisplayContent.getOverlayLayer(), mDisplayContent.getSurfaceControl())
                .apply();
        // Pause mirroring by destroying the reference to the mirrored layer.
        mRecordedSurface = null;
        // Do not un-set the token, in case content is removed and recording should begin again.
    }

    /**
     * Stops recording on this DisplayContent, and updates the session details.
     */
    void remove() {
        if (mRecordedSurface != null) {
            // Do not wait for the mirrored surface to be garbage collected, but clean up
            // immediately.
            mDisplayContent.mWmService.mTransactionFactory.get().remove(mRecordedSurface).apply();
            mRecordedSurface = null;
            clearContentRecordingSession();
        }
    }

    /**
     * Removes both the local cache and WM Service view of the current session, to stop the session
     * on this display.
     */
    private void clearContentRecordingSession() {
        // Update the cached session state first, since updating the service will result in always
        // returning to this instance to update recording state.
        mContentRecordingSession = null;
        mDisplayContent.mWmService.setContentRecordingSession(null);
    }

    /**
     * Start recording to this DisplayContent if it does not have its own content. Captures the
     * content of a WindowContainer indicated by a WindowToken. If unable to start recording, falls
     * back to original MediaProjection approach.
     */
    private void startRecordingIfNeeded() {
        // Only record if this display does not have its own content, is not recording already,
        // and if this display is on (it has a surface to write output to).
        if (mDisplayContent.getLastHasContent() || isCurrentlyRecording()
                || mDisplayContent.getDisplay().getState() == Display.STATE_OFF
                || mContentRecordingSession == null) {
            return;
        }

        final int contentToRecord = mContentRecordingSession.getContentToRecord();
        if (contentToRecord != RECORD_CONTENT_DISPLAY) {
            // TODO(b/216625226) handle task-based recording
            // Not a valid region, or recording is disabled, so fall back to prior MediaProjection
            // approach.
            clearContentRecordingSession();
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Unable to start recording due to invalid region for display %d",
                    mDisplayContent.getDisplayId());
            return;
        }
        // Given the WindowToken of the DisplayArea to record, retrieve the associated
        // SurfaceControl.
        IBinder tokenToRecord = mContentRecordingSession.getTokenToRecord();
        if (tokenToRecord == null) {
            // Unexpectedly missing token. Fall back to prior MediaProjection approach.
            clearContentRecordingSession();
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Unable to start recording due to null token for display %d",
                    mDisplayContent.getDisplayId());
            return;
        }

        final WindowContainer wc =
                mDisplayContent.mWmService.mWindowContextListenerController.getContainer(
                        tokenToRecord);
        if (wc == null) {
            // Un-set the window token to record for this VirtualDisplay. Fall back to the
            // original MediaProjection approach.
            mDisplayContent.mWmService.mDisplayManagerInternal.setWindowManagerMirroring(
                    mDisplayContent.getDisplayId(), false);
            clearContentRecordingSession();
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Unable to retrieve window container to start recording for "
                            + "display %d",
                    mDisplayContent.getDisplayId());
            return;
        }
        // TODO(206461622) Migrate to using the RootDisplayArea
        mRecordedWindowContainer = wc.getDisplayContent();

        final Point surfaceSize = fetchSurfaceSizeIfPresent();
        if (surfaceSize == null) {
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Unable to start recording for display %d since the surface is not "
                            + "available.",
                    mDisplayContent.getDisplayId());
            return;
        }
        ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                "Display %d has no content and is on, so start recording for state %d",
                mDisplayContent.getDisplayId(), mDisplayContent.getDisplay().getState());

        // Create a mirrored hierarchy for the SurfaceControl of the DisplayArea to capture.
        mRecordedSurface = SurfaceControl.mirrorSurface(
                mRecordedWindowContainer.getSurfaceControl());
        SurfaceControl.Transaction transaction =
                mDisplayContent.mWmService.mTransactionFactory.get()
                        // Set the mMirroredSurface's parent to the root SurfaceControl for this
                        // DisplayContent. This brings the new mirrored hierarchy under this
                        // DisplayContent,
                        // so SurfaceControl will write the layers of this hierarchy to the
                        // output surface
                        // provided by the app.
                        .reparent(mRecordedSurface, mDisplayContent.getSurfaceControl())
                        // Reparent the SurfaceControl of this DisplayContent to null, to prevent
                        // content
                        // being added to it. This ensures that no app launched explicitly on the
                        // VirtualDisplay will show up as part of the mirrored content.
                        .reparent(mDisplayContent.getWindowingLayer(), null)
                        .reparent(mDisplayContent.getOverlayLayer(), null);
        // Retrieve the size of the DisplayArea to mirror.
        updateMirroredSurface(transaction, mRecordedWindowContainer.getBounds(), surfaceSize);

        // No need to clean up. In SurfaceFlinger, parents hold references to their children. The
        // mirrored SurfaceControl is alive since the parent DisplayContent SurfaceControl is
        // holding a reference to it. Therefore, the mirrored SurfaceControl will be cleaned up
        // when the VirtualDisplay is destroyed - which will clean up this DisplayContent.
    }

    /**
     * Apply transformations to the mirrored surface to ensure the captured contents are scaled to
     * fit and centred in the output surface.
     *
     * @param transaction           the transaction to include transformations of mMirroredSurface
     *                              to. Transaction is not applied before returning.
     * @param recordedContentBounds bounds of the content to record to the surface provided by
     *                              the app.
     * @param surfaceSize           the default size of the surface to write the display area
     *                              content to
     */
    @VisibleForTesting void updateMirroredSurface(SurfaceControl.Transaction transaction,
            Rect recordedContentBounds, Point surfaceSize) {
        // Calculate the scale to apply to the root mirror SurfaceControl to fit the size of the
        // output surface.
        float scaleX = surfaceSize.x / (float) recordedContentBounds.width();
        float scaleY = surfaceSize.y / (float) recordedContentBounds.height();
        float scale = Math.min(scaleX, scaleY);
        int scaledWidth = Math.round(scale * (float) recordedContentBounds.width());
        int scaledHeight = Math.round(scale * (float) recordedContentBounds.height());

        // Calculate the shift to apply to the root mirror SurfaceControl to centre the mirrored
        // contents in the output surface.
        int shiftedX = 0;
        if (scaledWidth != surfaceSize.x) {
            shiftedX = (surfaceSize.x - scaledWidth) / 2;
        }
        int shiftedY = 0;
        if (scaledHeight != surfaceSize.y) {
            shiftedY = (surfaceSize.y - scaledHeight) / 2;
        }

        transaction
                // Crop the area to capture to exclude the 'extra' wallpaper that is used
                // for parallax (b/189930234).
                .setWindowCrop(mRecordedSurface, recordedContentBounds.width(),
                        recordedContentBounds.height())
                // Scale the root mirror SurfaceControl, based upon the size difference between the
                // source (DisplayArea to capture) and output (surface the app reads images from).
                .setMatrix(mRecordedSurface, scale, 0 /* dtdx */, 0 /* dtdy */, scale)
                // Position needs to be updated when the mirrored DisplayArea has changed, since
                // the content will no longer be centered in the output surface.
                .setPosition(mRecordedSurface, shiftedX /* x */, shiftedY /* y */)
                .apply();
        mLastRecordedBounds = new Rect(recordedContentBounds);
    }

    /**
     * Returns a non-null {@link Point} if the surface is present, or null otherwise
     */
    private Point fetchSurfaceSizeIfPresent() {
        // Retrieve the default size of the surface the app provided to
        // MediaProjection#createVirtualDisplay. Note the app is the consumer of the surface,
        // since it reads out buffers from the surface, and SurfaceFlinger is the producer since
        // it writes the mirrored layers to the buffers.
        Point surfaceSize =
                mDisplayContent.mWmService.mDisplayManagerInternal.getDisplaySurfaceDefaultSize(
                        mDisplayContent.getDisplayId());
        if (surfaceSize == null) {
            // Layer mirroring started with a null surface, so do not apply any transformations yet.
            // State of virtual display will change to 'ON' when the surface is set.
            // will get event DISPLAY_DEVICE_EVENT_CHANGED
            ProtoLog.v(WM_DEBUG_CONTENT_RECORDING,
                    "Provided surface for recording on display %d is not present, so do not"
                            + " update the surface",
                    mDisplayContent.getDisplayId());
            return null;
        }
        return surfaceSize;
    }
}
