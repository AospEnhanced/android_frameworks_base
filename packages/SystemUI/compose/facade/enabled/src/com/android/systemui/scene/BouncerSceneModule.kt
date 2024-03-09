/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.scene

import android.app.AlertDialog
import android.content.Context
import com.android.systemui.bouncer.ui.composable.BouncerDialogFactory
import com.android.systemui.bouncer.ui.composable.BouncerScene
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.model.Scene
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet

@Module
interface BouncerSceneModule {

    @Binds @IntoSet fun bouncerScene(scene: BouncerScene): Scene

    companion object {

        @Provides
        @SysUISingleton
        fun bouncerSceneDialogFactory(@Application context: Context): BouncerDialogFactory {
            return object : BouncerDialogFactory {
                override fun invoke(): AlertDialog {
                    return SystemUIDialog(context)
                }
            }
        }
    }
}
