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
 *
 */

package com.android.systemui.keyboard.data.repository

import android.hardware.input.InputManager
import android.hardware.input.InputManager.KeyboardBacklightListener
import android.hardware.input.KeyboardBacklightState
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.data.model.BacklightModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

interface KeyboardRepository {
    val keyboardConnected: Flow<Boolean>
    val backlight: Flow<BacklightModel>
}

@SysUISingleton
class KeyboardRepositoryImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val inputManager: InputManager,
) : KeyboardRepository {

    private val connectedDeviceIds: Flow<Set<Int>> =
        conflatedCallbackFlow {
                var connectedKeyboards = inputManager.inputDeviceIds.toSet()
                val listener =
                    object : InputManager.InputDeviceListener {
                        override fun onInputDeviceAdded(deviceId: Int) {
                            connectedKeyboards = connectedKeyboards + deviceId
                            sendWithLogging(connectedKeyboards)
                        }

                        override fun onInputDeviceChanged(deviceId: Int) = Unit

                        override fun onInputDeviceRemoved(deviceId: Int) {
                            connectedKeyboards = connectedKeyboards - deviceId
                            sendWithLogging(connectedKeyboards)
                        }
                    }
                sendWithLogging(connectedKeyboards)
                inputManager.registerInputDeviceListener(listener, /* handler= */ null)
                awaitClose { inputManager.unregisterInputDeviceListener(listener) }
            }
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                replay = 1,
            )

    private val backlightStateListener: Flow<KeyboardBacklightState> = conflatedCallbackFlow {
        val listener = KeyboardBacklightListener { _, state, isTriggeredByKeyPress ->
            if (isTriggeredByKeyPress) {
                sendWithLogging(state)
            }
        }
        inputManager.registerKeyboardBacklightListener(Executor(Runnable::run), listener)
        awaitClose { inputManager.unregisterKeyboardBacklightListener(listener) }
    }

    override val keyboardConnected: Flow<Boolean> =
        connectedDeviceIds
            .map { it.any { deviceId -> isPhysicalFullKeyboard(deviceId) } }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    override val backlight: Flow<BacklightModel> =
        backlightStateListener
            .map { BacklightModel(it.brightnessLevel, it.maxBrightnessLevel) }
            .flowOn(backgroundDispatcher)

    private fun <T> SendChannel<T>.sendWithLogging(element: T) {
        trySendWithFailureLogging(element, TAG)
    }

    private fun isPhysicalFullKeyboard(deviceId: Int): Boolean {
        val device = inputManager.getInputDevice(deviceId)
        return !device.isVirtual && device.isFullKeyboard
    }

    companion object {
        const val TAG = "KeyboardRepositoryImpl"
    }
}
