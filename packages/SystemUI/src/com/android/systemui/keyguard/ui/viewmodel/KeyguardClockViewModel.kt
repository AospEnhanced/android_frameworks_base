/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import androidx.constraintlayout.helper.widget.Layer
import com.android.keyguard.KeyguardClockSwitch.LARGE
import com.android.keyguard.KeyguardClockSwitch.SMALL
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.ComposeLockscreen
import com.android.systemui.keyguard.shared.model.SettingsClockSize
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.notification.domain.interactor.NotificationsKeyguardInteractor
import com.android.systemui.util.Utils
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardClockViewModel
@Inject
constructor(
    keyguardInteractor: KeyguardInteractor,
    private val keyguardClockInteractor: KeyguardClockInteractor,
    @Application private val applicationScope: CoroutineScope,
    notifsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
) {
    var burnInLayer: Layer? = null
    val useLargeClock: Boolean
        get() = clockSize.value == LARGE

    val clockSize =
        combine(keyguardClockInteractor.selectedClockSize, keyguardClockInteractor.clockSize) {
                selectedSize,
                clockSize ->
                if (selectedSize == SettingsClockSize.SMALL) {
                    SMALL
                } else {
                    clockSize
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = LARGE,
            )

    val isLargeClockVisible =
        clockSize
            .map { it == LARGE }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    val currentClock = keyguardClockInteractor.currentClock

    val hasCustomWeatherDataDisplay =
        combine(clockSize, currentClock) { size, clock ->
                clock?.let {
                    (if (size == LARGE) clock.largeClock.config.hasCustomWeatherDataDisplay
                    else clock.smallClock.config.hasCustomWeatherDataDisplay)
                }
                    ?: false
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = currentClock.value?.largeClock?.config?.hasCustomWeatherDataDisplay
                        ?: false
            )

    val clockShouldBeCentered: StateFlow<Boolean> =
        keyguardInteractor.clockShouldBeCentered.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val isAodIconsVisible: StateFlow<Boolean> =
        notifsKeyguardInteractor.areNotificationsFullyHidden.stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    val currentClockLayout: StateFlow<ClockLayout> =
        combine(isLargeClockVisible, clockShouldBeCentered, shadeInteractor.shadeMode) {
                isLargeClockVisible,
                clockShouldBeCentered,
                shadeMode ->
                val shouldUseSplitShade = shadeMode == ShadeMode.Split
                when {
                    shouldUseSplitShade && clockShouldBeCentered -> ClockLayout.LARGE_CLOCK
                    shouldUseSplitShade && isLargeClockVisible ->
                        ClockLayout.SPLIT_SHADE_LARGE_CLOCK
                    shouldUseSplitShade -> ClockLayout.SPLIT_SHADE_SMALL_CLOCK
                    isLargeClockVisible -> ClockLayout.LARGE_CLOCK
                    else -> ClockLayout.SMALL_CLOCK
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ClockLayout.SMALL_CLOCK
            )

    val hasCustomPositionUpdatedAnimation: StateFlow<Boolean> =
        combine(currentClock, isLargeClockVisible) { currentClock, isLargeClockVisible ->
                isLargeClockVisible &&
                    currentClock?.largeClock?.config?.hasCustomPositionUpdatedAnimation == true
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false
            )

    /** Calculates the top margin for the small clock. */
    fun getSmallClockTopMargin(context: Context): Int {
        var topMargin: Int
        val statusBarHeight = Utils.getStatusBarHeaderHeightKeyguard(context)

        if (shadeInteractor.shadeMode.value == ShadeMode.Split) {
            topMargin =
                context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)
            if (ComposeLockscreen.isEnabled) {
                topMargin -= statusBarHeight
            }
        } else {
            topMargin = context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin)
            if (!ComposeLockscreen.isEnabled) {
                topMargin += statusBarHeight
            }
        }
        return topMargin
    }

    enum class ClockLayout {
        LARGE_CLOCK,
        SMALL_CLOCK,
        SPLIT_SHADE_LARGE_CLOCK,
        SPLIT_SHADE_SMALL_CLOCK,
    }
}
