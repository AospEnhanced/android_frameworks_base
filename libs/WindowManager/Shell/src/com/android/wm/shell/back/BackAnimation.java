/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.back;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.window.BackEvent;

import com.android.wm.shell.common.annotations.ExternalThread;

/**
 * Interface for external process to get access to the Back animation related methods.
 */
@ExternalThread
public interface BackAnimation {

    /**
     * Returns a binder that can be passed to an external process to update back animations.
     */
    default IBackAnimation createExternalInterface() {
        return null;
    }

    /**
     * Called when a {@link MotionEvent} is generated by a back gesture.
     *
     * @param touchX the X touch position of the {@link MotionEvent}.
     * @param touchY the Y touch position of the {@link MotionEvent}.
     * @param keyAction the original {@link KeyEvent#getAction()} when the event was dispatched to
     *               the process. This is forwarded separately because the input pipeline may mutate
     *               the {#event} action state later.
     * @param swipeEdge the edge from which the swipe begins.
     */
    void onBackMotion(float touchX, float touchY, int keyAction,
            @BackEvent.SwipeEdge int swipeEdge);

    /**
     * Sets whether the back gesture is past the trigger threshold or not.
     */
    void setTriggerBack(boolean triggerBack);

    /**
     * Sets whether the back long swipe gesture is past the trigger threshold or not.
     */
    void setTriggerLongSwipe(boolean triggerLongSwipe);

    /**
     * Sets the threshold values that defining edge swipe behavior.
     * @param triggerThreshold the min threshold to trigger back.
     * @param progressThreshold the max threshold to keep progressing back animation.
     */
    void setSwipeThresholds(float triggerThreshold, float progressThreshold);
}
