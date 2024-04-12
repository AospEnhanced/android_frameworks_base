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

package com.android.systemui.keyguard.ui.binder

import android.os.Handler
import android.os.Trace
import android.transition.Transition
import android.transition.TransitionManager
import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.KeyguardBottomAreaRefactor
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.BaseBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransition.Config
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBlueprintViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import javax.inject.Inject
import kotlin.math.max

private const val TAG = "KeyguardBlueprintViewBinder"
private const val DEBUG = false

@SysUISingleton
class KeyguardBlueprintViewBinder
@Inject
constructor(
    @Main private val handler: Handler,
) {
    private var runningPriority = -1
    private val runningTransitions = mutableSetOf<Transition>()
    private val isTransitionRunning: Boolean
        get() = runningTransitions.size > 0
    private val transitionListener =
        object : Transition.TransitionListener {
            override fun onTransitionCancel(transition: Transition) {
                if (DEBUG) Log.e(TAG, "onTransitionCancel: ${transition::class.simpleName}")
                runningTransitions.remove(transition)
            }

            override fun onTransitionEnd(transition: Transition) {
                if (DEBUG) Log.e(TAG, "onTransitionEnd: ${transition::class.simpleName}")
                runningTransitions.remove(transition)
            }

            override fun onTransitionPause(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionPause: ${transition::class.simpleName}")
                runningTransitions.remove(transition)
            }

            override fun onTransitionResume(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionResume: ${transition::class.simpleName}")
                runningTransitions.add(transition)
            }

            override fun onTransitionStart(transition: Transition) {
                if (DEBUG) Log.i(TAG, "onTransitionStart: ${transition::class.simpleName}")
                runningTransitions.add(transition)
            }
        }

    fun bind(
        constraintLayout: ConstraintLayout,
        viewModel: KeyguardBlueprintViewModel,
        clockViewModel: KeyguardClockViewModel,
        smartspaceViewModel: KeyguardSmartspaceViewModel,
    ) {
        constraintLayout.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch("$TAG#viewModel.blueprint") {
                    viewModel.blueprint.collect { blueprint ->
                        Trace.beginSection("KeyguardBlueprintViewBinder#applyBlueprint")
                        val prevBluePrint = viewModel.currentBluePrint

                        val cs =
                            ConstraintSet().apply {
                                clone(constraintLayout)
                                val emptyLayout = ConstraintSet.Layout()
                                knownIds.forEach { getConstraint(it).layout.copyFrom(emptyLayout) }
                                blueprint.applyConstraints(this)
                            }

                        var transition =
                            if (
                                !KeyguardBottomAreaRefactor.isEnabled &&
                                    prevBluePrint != null &&
                                    prevBluePrint != blueprint
                            ) {
                                BaseBlueprintTransition(clockViewModel)
                                    .addTransition(
                                        IntraBlueprintTransition(
                                            Config.DEFAULT,
                                            clockViewModel,
                                            smartspaceViewModel
                                        )
                                    )
                            } else {
                                IntraBlueprintTransition(
                                    Config.DEFAULT,
                                    clockViewModel,
                                    smartspaceViewModel
                                )
                            }

                        runTransition(constraintLayout, transition, Config.DEFAULT) {
                            // Add and remove views of sections that are not contained by the other.
                            blueprint.replaceViews(prevBluePrint, constraintLayout)
                            logAlphaVisibilityOfAppliedConstraintSet(cs, clockViewModel)
                            cs.applyTo(constraintLayout)
                        }

                        viewModel.currentBluePrint = blueprint
                        Trace.endSection()
                    }
                }

                launch("$TAG#viewModel.refreshTransition") {
                    viewModel.refreshTransition.collect { transition ->
                        Trace.beginSection("KeyguardBlueprintViewBinder#refreshTransition")
                        val cs =
                            ConstraintSet().apply {
                                clone(constraintLayout)
                                viewModel.currentBluePrint?.applyConstraints(this)
                            }

                        runTransition(
                            constraintLayout,
                            IntraBlueprintTransition(
                                transition,
                                clockViewModel,
                                smartspaceViewModel
                            ),
                            transition,
                        ) {
                            logAlphaVisibilityOfAppliedConstraintSet(cs, clockViewModel)
                            cs.applyTo(constraintLayout)
                        }
                        Trace.endSection()
                    }
                }
            }
        }
    }

    private fun runTransition(
        constraintLayout: ConstraintLayout,
        transition: Transition,
        config: Config,
        apply: () -> Unit,
    ) {
        val currentPriority = if (isTransitionRunning) runningPriority else -1
        if (config.checkPriority && config.type.priority < currentPriority) {
            if (DEBUG) {
                Log.w(
                    TAG,
                    "runTransition: skipping ${transition::class.simpleName}: " +
                        "currentPriority=$currentPriority; config=$config"
                )
            }
            apply()
            return
        }

        if (DEBUG) {
            Log.i(
                TAG,
                "runTransition: running ${transition::class.simpleName}: " +
                    "currentPriority=$currentPriority; config=$config"
            )
        }

        // beginDelayedTransition makes a copy, so we temporarially add the uncopied transition to
        // the running set until the copy is started by the handler.
        runningTransitions.add(transition)
        transition.addListener(transitionListener)
        runningPriority = max(currentPriority, config.type.priority)

        handler.post {
            if (config.terminatePrevious) {
                TransitionManager.endTransitions(constraintLayout)
            }

            TransitionManager.beginDelayedTransition(constraintLayout, transition)
            runningTransitions.remove(transition)
            apply()
        }
    }

    private fun logAlphaVisibilityOfAppliedConstraintSet(
        cs: ConstraintSet,
        viewModel: KeyguardClockViewModel
    ) {
        val currentClock = viewModel.currentClock.value
        if (!DEBUG || currentClock == null) return
        val smallClockViewId = R.id.lockscreen_clock_view
        val largeClockViewId = currentClock.largeClock.layout.views[0].id
        val smartspaceDateId = sharedR.id.date_smartspace_view
        Log.i(
            TAG,
            "applyCsToSmallClock: vis=${cs.getVisibility(smallClockViewId)} " +
                "alpha=${cs.getConstraint(smallClockViewId).propertySet.alpha}"
        )
        Log.i(
            TAG,
            "applyCsToLargeClock: vis=${cs.getVisibility(largeClockViewId)} " +
                "alpha=${cs.getConstraint(largeClockViewId).propertySet.alpha}"
        )
        Log.i(
            TAG,
            "applyCsToSmartspaceDate: vis=${cs.getVisibility(smartspaceDateId)} " +
                "alpha=${cs.getConstraint(smartspaceDateId).propertySet.alpha}"
        )
    }
}
