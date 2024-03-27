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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@SysUISingleton
class FromOccludedTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    powerInteractor: PowerInteractor,
    private val communalInteractor: CommunalInteractor,
    keyguardOcclusionInteractor: KeyguardOcclusionInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.OCCLUDED,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
        powerInteractor = powerInteractor,
        keyguardOcclusionInteractor = keyguardOcclusionInteractor,
    ) {

    override fun start() {
        listenForOccludedToLockscreenOrHub()
        listenForOccludedToDreaming()
        listenForOccludedToAsleep()
        listenForOccludedToGone()
        listenForOccludedToAlternateBouncer()
        listenForOccludedToPrimaryBouncer()
    }

    private fun listenForOccludedToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    private fun listenForOccludedToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream.sample(finishedKeyguardState, ::Pair).collect {
                (isAbleToDream, keyguardState) ->
                if (isAbleToDream && keyguardState == KeyguardState.OCCLUDED) {
                    startTransitionTo(KeyguardState.DREAMING)
                }
            }
        }
    }

    private fun listenForOccludedToLockscreenOrHub() {
        if (KeyguardWmStateRefactor.isEnabled) {
            scope.launch {
                keyguardOcclusionInteractor.isShowWhenLockedActivityOnTop
                    .filter { onTop -> !onTop }
                    .sample(
                        startedKeyguardState,
                        communalInteractor.isIdleOnCommunal,
                    )
                    .collect { (_, startedState, isIdleOnCommunal) ->
                        // Occlusion signals come from the framework, and should interrupt any
                        // existing transition
                        if (startedState == KeyguardState.OCCLUDED) {
                            val to =
                                if (isIdleOnCommunal) {
                                    KeyguardState.GLANCEABLE_HUB
                                } else {
                                    KeyguardState.LOCKSCREEN
                                }
                            startTransitionTo(to)
                        }
                    }
            }
        } else {
            scope.launch {
                keyguardInteractor.isKeyguardOccluded
                    .sample(
                        keyguardInteractor.isKeyguardShowing,
                        startedKeyguardTransitionStep,
                        communalInteractor.isIdleOnCommunal,
                    )
                    .collect { (isOccluded, isShowing, lastStartedKeyguardState, isIdleOnCommunal)
                        ->
                        // Occlusion signals come from the framework, and should interrupt any
                        // existing transition
                        if (
                            !isOccluded &&
                                isShowing &&
                                lastStartedKeyguardState.to == KeyguardState.OCCLUDED
                        ) {
                            val to =
                                if (isIdleOnCommunal) {
                                    KeyguardState.GLANCEABLE_HUB
                                } else {
                                    KeyguardState.LOCKSCREEN
                                }
                            startTransitionTo(to)
                        }
                    }
            }
        }
    }

    private fun listenForOccludedToGone() {
        if (KeyguardWmStateRefactor.isEnabled) {
            // We don't think OCCLUDED to GONE is possible. You should always have to go via a
            // *_BOUNCER state to end up GONE. Launching an activity over a dismissable keyguard
            // dismisses it, and even "extend unlock" doesn't unlock the device in the background.
            // If we're wrong - sorry, add it back here.
            return
        }

        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(
                    keyguardInteractor.isKeyguardShowing,
                    startedKeyguardTransitionStep,
                )
                .collect { (isOccluded, isShowing, lastStartedKeyguardState) ->
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    if (
                        !isOccluded &&
                            !isShowing &&
                            lastStartedKeyguardState.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    fun dismissToGone() {
        scope.launch { startTransitionTo(KeyguardState.GONE) }
    }

    private fun listenForOccludedToAsleep() {
        scope.launch { listenForSleepTransition(from = KeyguardState.OCCLUDED) }
    }

    private fun listenForOccludedToAlternateBouncer() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isAlternateBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isAlternateBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator =
                when (toState) {
                    KeyguardState.ALTERNATE_BOUNCER -> Interpolators.FAST_OUT_SLOW_IN
                    else -> Interpolators.LINEAR
                }

            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromOccludedTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 933.milliseconds
        val TO_AOD_DURATION = DEFAULT_DURATION
        val TO_DOZING_DURATION = DEFAULT_DURATION
    }
}
