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

package com.android.wm.shell.activityembedding;

import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.graphics.Rect;
import android.window.TransitionInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ActivityEmbeddingController}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:ActivityEmbeddingControllerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActivityEmbeddingControllerTests extends ActivityEmbeddingAnimationTestBase {

    private static final Rect TASK_BOUNDS = new Rect(0, 0, 1000, 500);
    private static final Rect EMBEDDED_LEFT_BOUNDS = new Rect(0, 0, 500, 500);
    private static final Rect EMBEDDED_RIGHT_BOUNDS = new Rect(500, 0, 1000, 500);

    @Before
    public void setup() {
        super.setUp();
        doReturn(mAnimator).when(mAnimRunner).createAnimator(any(), any(), any(), any(), any());
    }

    @Test
    public void testInstantiate() {
        verify(mShellInit).addInitCallback(any(), any());
    }

    @Test
    public void testOnInit() {
        mController.onInit();

        verify(mTransitions).addHandler(mController);
    }

    @Test
    public void testSetAnimScaleSetting() {
        mController.setAnimScaleSetting(1.0f);

        verify(mAnimRunner).setAnimScaleSetting(1.0f);
        verify(mAnimSpec).setAnimScaleSetting(1.0f);
    }

    @Test
    public void testStartAnimation_containsNonActivityEmbeddingChange() {
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(EMBEDDED_LEFT_BOUNDS,
                EMBEDDED_LEFT_BOUNDS, TASK_BOUNDS);
        final TransitionInfo.Change nonEmbeddingChange = createChange();
        info.addChange(embeddingChange);
        info.addChange(nonEmbeddingChange);

        // No-op because it contains non-embedded change.
        assertFalse(mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback));
        verify(mAnimRunner, never()).startAnimation(any(), any(), any(), any());
        verifyNoMoreInteractions(mStartTransaction);
        verifyNoMoreInteractions(mFinishTransaction);
        verifyNoMoreInteractions(mFinishCallback);
    }

    @Test
    public void testStartAnimation_containsOnlyFillTaskActivityEmbeddingChange() {
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(TASK_BOUNDS, TASK_BOUNDS,
                TASK_BOUNDS);
        info.addChange(embeddingChange);

        // No-op because it only contains embedded change that fills the Task. We will let the
        // default handler to animate such transition.
        assertFalse(mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback));
        verify(mAnimRunner, never()).startAnimation(any(), any(), any(), any());
        verifyNoMoreInteractions(mStartTransaction);
        verifyNoMoreInteractions(mFinishTransaction);
        verifyNoMoreInteractions(mFinishCallback);
    }

    @Test
    public void testStartAnimation_containsActivityEmbeddingSplitChange() {
        // Change that occupies only part of the Task.
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(EMBEDDED_LEFT_BOUNDS,
                EMBEDDED_LEFT_BOUNDS, TASK_BOUNDS);
        info.addChange(embeddingChange);

        // ActivityEmbeddingController will handle such transition.
        assertTrue(mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback));
        verify(mAnimRunner).startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction);
        verify(mStartTransaction).apply();
        verifyNoMoreInteractions(mFinishTransaction);
    }

    @Test
    public void testStartAnimation_containsChangeEnterActivityEmbeddingSplit() {
        // Change that is entering ActivityEmbedding split.
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(TASK_BOUNDS,
                EMBEDDED_LEFT_BOUNDS, TASK_BOUNDS);
        info.addChange(embeddingChange);

        // ActivityEmbeddingController will handle such transition.
        assertTrue(mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback));
        verify(mAnimRunner).startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction);
        verify(mStartTransaction).apply();
        verifyNoMoreInteractions(mFinishTransaction);
    }

    @Test
    public void testStartAnimation_containsChangeExitActivityEmbeddingSplit() {
        // Change that is exiting ActivityEmbedding split.
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(EMBEDDED_RIGHT_BOUNDS,
                TASK_BOUNDS, TASK_BOUNDS);
        info.addChange(embeddingChange);

        // ActivityEmbeddingController will handle such transition.
        assertTrue(mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback));
        verify(mAnimRunner).startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction);
        verify(mStartTransaction).apply();
        verifyNoMoreInteractions(mFinishTransaction);
    }

    @Test
    public void testOnAnimationFinished() {
        // Should not call finish when there is no transition.
        assertThrows(IllegalStateException.class,
                () -> mController.onAnimationFinished(mTransition));

        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createEmbeddedChange(EMBEDDED_LEFT_BOUNDS,
                EMBEDDED_LEFT_BOUNDS, TASK_BOUNDS);
        info.addChange(embeddingChange);
        mController.startAnimation(mTransition, info, mStartTransaction,
                mFinishTransaction, mFinishCallback);

        verify(mFinishCallback, never()).onTransitionFinished(any(), any());
        mController.onAnimationFinished(mTransition);
        verify(mFinishCallback).onTransitionFinished(any(), any());

        // Should not call finish when the finish has already been called.
        assertThrows(IllegalStateException.class,
                () -> mController.onAnimationFinished(mTransition));
    }
}
