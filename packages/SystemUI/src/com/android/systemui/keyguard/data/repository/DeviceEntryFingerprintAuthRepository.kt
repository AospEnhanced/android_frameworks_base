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

package com.android.systemui.keyguard.data.repository

import android.hardware.biometrics.BiometricSourceType
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Encapsulates state about device entry fingerprint auth mechanism. */
interface DeviceEntryFingerprintAuthRepository {
    /** Whether the device entry fingerprint auth is locked out. */
    val isLockedOut: Flow<Boolean>
}

/**
 * Implementation of [DeviceEntryFingerprintAuthRepository] that uses [KeyguardUpdateMonitor] as the
 * source of truth.
 *
 * Dependency on [KeyguardUpdateMonitor] will be removed once fingerprint auth state is moved out of
 * [KeyguardUpdateMonitor]
 */
@SysUISingleton
class DeviceEntryFingerprintAuthRepositoryImpl
@Inject
constructor(
    val keyguardUpdateMonitor: KeyguardUpdateMonitor,
) : DeviceEntryFingerprintAuthRepository {

    override val isLockedOut: Flow<Boolean> = conflatedCallbackFlow {
        val sendLockoutUpdate =
            fun() {
                trySendWithFailureLogging(
                    keyguardUpdateMonitor.isFingerprintLockedOut,
                    TAG,
                    "onLockedOutStateChanged"
                )
            }
        val callback =
            object : KeyguardUpdateMonitorCallback() {
                override fun onLockedOutStateChanged(biometricSourceType: BiometricSourceType?) {
                    if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                        sendLockoutUpdate()
                    }
                }
            }
        keyguardUpdateMonitor.registerCallback(callback)
        sendLockoutUpdate()
        awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
    }

    companion object {
        const val TAG = "DeviceEntryFingerprintAuthRepositoryImpl"
    }
}
