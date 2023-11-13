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
import com.android.SysUITestComponent
import com.android.SysUITestModule
import com.android.TestMocksModule
import com.android.collectLastValue
import com.android.collectValues
import com.android.runCurrent
import com.android.runTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.user.domain.UserDomainLayerModule
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenToDreamingTransitionViewModelTest : SysuiTestCase() {
    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent : SysUITestComponent<LockscreenToDreamingTransitionViewModel> {
        val repository: FakeKeyguardTransitionRepository
        val keyguardRepository: FakeKeyguardRepository
        val shadeRepository: FakeShadeRepository

        @Component.Factory
        interface Factory {
            fun create(
                @BindsInstance test: SysuiTestCase,
                featureFlags: FakeFeatureFlagsClassicModule,
                mocks: TestMocksModule,
            ): TestComponent
        }

        fun shadeExpanded(expanded: Boolean) {
            if (expanded) {
                shadeRepository.setQsExpansion(1f)
            } else {
                keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
                shadeRepository.setQsExpansion(0f)
                shadeRepository.setLockscreenShadeExpansion(0f)
            }
        }
    }

    private val testComponent: TestComponent =
        DaggerLockscreenToDreamingTransitionViewModelTest_TestComponent.factory()
            .create(
                test = this,
                featureFlags =
                    FakeFeatureFlagsClassicModule {
                        set(Flags.FACE_AUTH_REFACTOR, true)
                        set(Flags.FULL_SCREEN_USER_SWITCHER, true)
                    },
                mocks = TestMocksModule(),
            )
    @Test
    fun lockscreenFadeOut() =
        testComponent.runTest {
            val values by collectValues(underTest.lockscreenAlpha)
            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED), // Should start running here...
                        step(0f),
                        step(.1f),
                        step(.2f),
                        step(.3f), // ...up to here
                        step(1f),
                    ),
                testScope = testScope,
            )

            // Only three values should be present, since the dream overlay runs for a small
            // fraction of the overall animation time
            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationY() =
        testComponent.runTest {
            val pixels = 100
            val values by collectValues(underTest.lockscreenTranslationY(pixels))

            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED), // Should start running here...
                        step(0f),
                        step(.3f),
                        step(.5f),
                        step(1f),
                        step(1f, TransitionState.FINISHED), // Final reset event on FINISHED
                    ),
                testScope = testScope,
            )

            assertThat(values.size).isEqualTo(6)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }
            // Validate finished value
            assertThat(values[5]).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeExpanded() =
        testComponent.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(true)
            runCurrent()

            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED),
                        step(0f),
                        step(.3f),
                        step(.5f),
                        step(1f),
                        step(1f, TransitionState.FINISHED),
                    ),
                testScope = testScope,
            )

            // immediately 0f
            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeNotExpanded() =
        testComponent.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(false)
            runCurrent()

            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.1f))
            assertThat(actual).isIn(Range.open(.1f, .9f))

            // alpha is 1f before the full transition starts ending
            repository.sendTransitionStep(step(0.8f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.DREAMING,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToDreamingTransitionViewModelTest"
        )
    }
}
