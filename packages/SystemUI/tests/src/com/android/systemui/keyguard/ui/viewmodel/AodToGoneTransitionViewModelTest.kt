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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class AodToGoneTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: AodToGoneTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        val interactor =
            KeyguardTransitionInteractorFactory.create(
                    scope = TestScope().backgroundScope,
                    repository = repository,
                )
                .keyguardTransitionInteractor
        underTest = AodToGoneTransitionViewModel(interactor)
    }

    @Test
    fun deviceEntryParentViewHides() = runTest {
        val deviceEntryParentViewAlpha by collectValues(underTest.deviceEntryParentViewAlpha)
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        repository.sendTransitionStep(step(0.1f))
        repository.sendTransitionStep(step(0.3f))
        repository.sendTransitionStep(step(0.4f))
        repository.sendTransitionStep(step(0.5f))
        repository.sendTransitionStep(step(0.6f))
        repository.sendTransitionStep(step(0.8f))
        repository.sendTransitionStep(step(1f))
        deviceEntryParentViewAlpha.forEach { assertThat(it).isEqualTo(0f) }
    }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.AOD,
            to = KeyguardState.GONE,
            value = value,
            transitionState = state,
            ownerName = "AodToGoneTransitionViewModelTest"
        )
    }
}
