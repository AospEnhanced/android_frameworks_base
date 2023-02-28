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
package com.android.systemui.notetask

import androidx.annotation.VisibleForTesting
import com.android.systemui.statusbar.CommandQueue
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject

/** Class responsible to "glue" all note task dependencies. */
internal class NoteTaskInitializer
@Inject
constructor(
    private val controller: NoteTaskController,
    private val commandQueue: CommandQueue,
    private val optionalBubbles: Optional<Bubbles>,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) {

    @VisibleForTesting
    val callbacks =
        object : CommandQueue.Callbacks {
            override fun handleSystemKey(keyCode: Int) {
                if (keyCode == NoteTaskController.NOTE_TASK_KEY_EVENT) {
                    controller.showNoteTask(NoteTaskEntryPoint.TAIL_BUTTON)
                }
            }
        }

    fun initialize() {
        controller.setNoteTaskShortcutEnabled(isEnabled)

        // Guard against feature not being enabled or mandatory dependencies aren't available.
        if (!isEnabled || optionalBubbles.isEmpty) return

        commandQueue.addCallback(callbacks)
    }
}
