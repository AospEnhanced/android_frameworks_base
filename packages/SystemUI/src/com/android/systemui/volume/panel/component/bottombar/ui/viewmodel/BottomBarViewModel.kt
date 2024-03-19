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

package com.android.systemui.volume.panel.component.bottombar.ui.viewmodel

import android.content.Intent
import android.provider.Settings
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Inject

@VolumePanelScope
class BottomBarViewModel
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val volumePanelViewModel: VolumePanelViewModel,
) {

    fun onDoneClicked() {
        volumePanelViewModel.dismissPanel()
    }

    fun onSettingsClicked() {
        volumePanelViewModel.dismissPanel()
        activityStarter.startActivity(
            Intent(Settings.ACTION_SOUND_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            true,
        )
    }
}
