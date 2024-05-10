/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.fakeAccessibilityRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel.Companion.UNLOCKED_DELAY_MS
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryIconViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var fingerprintAuthRepository: FakeDeviceEntryFingerprintAuthRepository
    private lateinit var deviceEntryIconTransition: FakeDeviceEntryIconTransition
    private lateinit var underTest: DeviceEntryIconViewModel

    @Before
    fun setUp() {
        keyguardRepository = kosmos.fakeKeyguardRepository
        fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
        fingerprintAuthRepository = kosmos.fakeDeviceEntryFingerprintAuthRepository
        deviceEntryIconTransition = kosmos.fakeDeviceEntryIconViewModelTransition
        underTest = kosmos.deviceEntryIconViewModel
    }

    @Test
    fun isLongPressEnabled_udfpsRunning() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            fingerprintPropertyRepository.supportsUdfps()
            fingerprintAuthRepository.setIsRunning(true)
            keyguardRepository.setKeyguardDismissible(false)
            assertThat(isLongPressEnabled).isFalse()
        }

    @Test
    fun isLongPressEnabled_unlocked() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            fingerprintPropertyRepository.supportsUdfps()
            keyguardRepository.setKeyguardDismissible(true)
            advanceTimeBy(UNLOCKED_DELAY_MS * 2) // wait for unlocked delay
            runCurrent()
            assertThat(isLongPressEnabled).isTrue()
        }

    @Test
    fun isLongPressEnabled_lock() =
        testScope.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            keyguardRepository.setKeyguardDismissible(false)

            // udfps supported
            fingerprintPropertyRepository.supportsUdfps()
            assertThat(isLongPressEnabled).isTrue()

            // udfps isn't supported
            fingerprintPropertyRepository.supportsRearFps()
            assertThat(isLongPressEnabled).isFalse()
        }

    @Test
    fun isVisible() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            deviceEntryIconTransitionAlpha(1f)
            assertThat(isVisible).isTrue()

            deviceEntryIconTransitionAlpha(0f)
            assertThat(isVisible).isFalse()

            deviceEntryIconTransitionAlpha(.5f)
            assertThat(isVisible).isTrue()
        }

    @Test
    fun iconType_fingerprint() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            keyguardRepository.setKeyguardDismissible(false)
            fingerprintPropertyRepository.supportsUdfps()
            fingerprintAuthRepository.setIsRunning(true)
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.FINGERPRINT)
        }

    @Test
    fun iconType_locked() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            keyguardRepository.setKeyguardDismissible(false)
            fingerprintAuthRepository.setIsRunning(false)
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.LOCK)
        }

    @Test
    fun iconType_unlocked() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            keyguardRepository.setKeyguardDismissible(true)
            advanceTimeBy(UNLOCKED_DELAY_MS * 2) // wait for unlocked delay
            fingerprintAuthRepository.setIsRunning(false)
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.UNLOCK)
        }

    @Test
    fun iconType_none() =
        testScope.runTest {
            val iconType by collectLastValue(underTest.iconType)
            keyguardRepository.setKeyguardDismissible(true)
            advanceTimeBy(UNLOCKED_DELAY_MS * 2) // wait for unlocked delay
            fingerprintPropertyRepository.supportsUdfps()
            fingerprintAuthRepository.setIsRunning(true)
            assertThat(iconType).isEqualTo(DeviceEntryIconView.IconType.NONE)
        }

    fun accessibilityDelegateHint_accessibilityNotEnabled() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = false
            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.NONE)
        }

    @Test
    fun accessibilityDelegateHint_accessibilityEnabled_locked() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = true

            // interactive lock icon
            keyguardRepository.setKeyguardDismissible(false)
            fingerprintPropertyRepository.supportsUdfps()

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.AUTHENTICATE)

            // non-interactive lock icon
            keyguardRepository.setKeyguardDismissible(false)
            fingerprintPropertyRepository.supportsRearFps()

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.NONE)
        }

    @Test
    fun accessibilityDelegateHint_accessibilityEnabled_unlocked() =
        testScope.runTest {
            val accessibilityDelegateHint by collectLastValue(underTest.accessibilityDelegateHint)
            kosmos.fakeAccessibilityRepository.isEnabled.value = true

            // interactive unlock icon
            keyguardRepository.setKeyguardDismissible(true)
            fingerprintPropertyRepository.supportsUdfps()
            advanceTimeBy(UNLOCKED_DELAY_MS * 2) // wait for unlocked delay
            runCurrent()

            assertThat(accessibilityDelegateHint)
                .isEqualTo(DeviceEntryIconView.AccessibilityHintType.ENTER)
        }

    private fun deviceEntryIconTransitionAlpha(alpha: Float) {
        deviceEntryIconTransition.setDeviceEntryParentViewAlpha(alpha)
    }
}
