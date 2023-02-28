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

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.Bubbles
import com.android.wm.shell.bubbles.Bubbles.BubbleExpandListener
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Entry point for creating and managing note.
 *
 * The controller decides how a note is launched based in the device state: locked or unlocked.
 *
 * Currently, we only support a single task per time.
 */
@SysUISingleton
class NoteTaskController
@Inject
constructor(
    private val context: Context,
    private val resolver: NoteTaskInfoResolver,
    private val eventLogger: NoteTaskEventLogger,
    private val optionalBubbles: Optional<Bubbles>,
    private val optionalUserManager: Optional<UserManager>,
    private val optionalKeyguardManager: Optional<KeyguardManager>,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) {

    @VisibleForTesting val infoReference = AtomicReference<NoteTaskInfo?>()

    /** @see BubbleExpandListener */
    fun onBubbleExpandChanged(isExpanding: Boolean, key: String?) {
        if (!isEnabled) return

        if (key != Bubble.KEY_APP_BUBBLE) return

        val info = infoReference.getAndSet(null)

        // Safe guard mechanism, this callback should only be called for app bubbles.
        if (info?.launchMode != NoteTaskLaunchMode.AppBubble) return

        if (isExpanding) {
            logDebug { "onBubbleExpandChanged - expanding: $info" }
            eventLogger.logNoteTaskOpened(info)
        } else {
            logDebug { "onBubbleExpandChanged - collapsing: $info" }
            eventLogger.logNoteTaskClosed(info)
        }
    }

    /**
     * Shows a note task. How the task is shown will depend on when the method is invoked.
     *
     * If in multi-window mode, notes will open as a full screen experience. That is particularly
     * important for Large screen devices. These devices may support a taskbar that let users to
     * drag and drop a shortcut into multi-window mode, and notes should comply with this behaviour.
     *
     * If the keyguard is locked, notes will open as a full screen experience. A locked device has
     * no contextual information which let us use the whole screen space available.
     *
     * If not in multi-window or the keyguard is unlocked, notes will open as a bubble OR it will be
     * collapsed if the notes bubble is already opened.
     *
     * That will let users open other apps in full screen, and take contextual notes.
     */
    @JvmOverloads
    fun showNoteTask(
        entryPoint: NoteTaskEntryPoint,
        isInMultiWindowMode: Boolean = false,
    ) {
        if (!isEnabled) return

        val bubbles = optionalBubbles.getOrNull() ?: return
        val userManager = optionalUserManager.getOrNull() ?: return
        val keyguardManager = optionalKeyguardManager.getOrNull() ?: return

        // TODO(b/249954038): We should handle direct boot (isUserUnlocked). For now, we do nothing.
        if (!userManager.isUserUnlocked) return

        val info =
            resolver.resolveInfo(
                entryPoint = entryPoint,
                isInMultiWindowMode = isInMultiWindowMode,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
                ?: return

        infoReference.set(info)

        // TODO(b/266686199): We should handle when app not available. For now, we log.
        val intent = createNoteIntent(info)
        try {
            logDebug { "onShowNoteTask - start: $info" }
            when (info.launchMode) {
                is NoteTaskLaunchMode.AppBubble -> {
                    bubbles.showOrHideAppBubble(intent)
                    // App bubble logging happens on `onBubbleExpandChanged`.
                    logDebug { "onShowNoteTask - opened as app bubble: $info" }
                }
                is NoteTaskLaunchMode.Activity -> {
                    context.startActivity(intent)
                    eventLogger.logNoteTaskOpened(info)
                    logDebug { "onShowNoteTask - opened as activity: $info" }
                }
            }
            logDebug { "onShowNoteTask - success: $info" }
        } catch (e: ActivityNotFoundException) {
            logDebug { "onShowNoteTask - failed: $info" }
        }
        logDebug { "onShowNoteTask - compoleted: $info" }
    }

    /**
     * Set `android:enabled` property in the `AndroidManifest` associated with the Shortcut
     * component to [value].
     *
     * If the shortcut entry `android:enabled` is set to `true`, the shortcut will be visible in the
     * Widget Picker to all users.
     */
    fun setNoteTaskShortcutEnabled(value: Boolean) {
        val componentName = ComponentName(context, CreateNoteTaskShortcutActivity::class.java)

        val enabledState =
            if (value) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        context.packageManager.setComponentEnabledSetting(
            componentName,
            enabledState,
            PackageManager.DONT_KILL_APP,
        )

        logDebug { "setNoteTaskShortcutEnabled - completed: $isEnabled" }
    }

    companion object {
        val TAG = NoteTaskController::class.simpleName.orEmpty()

        // TODO(b/254604589): Use final KeyEvent.KEYCODE_* instead.
        const val NOTE_TASK_KEY_EVENT = 311

        // TODO(b/265912743): Use Intent.ACTION_CREATE_NOTE instead.
        const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"

        // TODO(b/265912743): Use Intent.INTENT_EXTRA_USE_STYLUS_MODE instead.
        const val INTENT_EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE"
    }
}

private fun createNoteIntent(info: NoteTaskInfo): Intent =
    Intent(NoteTaskController.ACTION_CREATE_NOTE)
        .setPackage(info.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // EXTRA_USE_STYLUS_MODE does not mean a stylus is in-use, but a stylus entrypoint
        // was used to start it.
        .putExtra(NoteTaskController.INTENT_EXTRA_USE_STYLUS_MODE, true)

private inline fun logDebug(message: () -> String) {
    if (Build.IS_DEBUGGABLE) {
        Log.d(NoteTaskController.TAG, message())
    }
}
