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

package com.android.systemui.shade.domain.interactor

import android.content.Intent
import android.provider.AlarmClock
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.shade.data.repository.ShadeHeaderClockRepository
import javax.inject.Inject

@SysUISingleton
class ShadeHeaderClockInteractor
@Inject
constructor(
    private val repository: ShadeHeaderClockRepository,
    private val activityStarter: ActivityStarter,
) {
    /** Launch the clock activity. */
    fun launchClockActivity() {
        val nextAlarmIntent = repository.nextAlarmIntent
        if (nextAlarmIntent != null) {
            activityStarter.postStartActivityDismissingKeyguard(nextAlarmIntent)
        } else {
            activityStarter.postStartActivityDismissingKeyguard(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                0
            )
        }
    }
}
