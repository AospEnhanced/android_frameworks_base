/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.unfold.compat

import android.content.Context
import android.content.res.Configuration
import com.android.systemui.unfold.updates.FoldProvider
import com.android.systemui.unfold.updates.FoldProvider.FoldCallback
import java.util.concurrent.Executor

/**
 * Fold provider that notifies about fold state based on the screen size
 * It could be used when no activity context is available
 * TODO(b/232369816): use Jetpack WM library when non-activity contexts supported b/169740873
 */
class ScreenSizeFoldProvider(private val context: Context) : FoldProvider {

    private var callbacks: MutableList<FoldCallback> = arrayListOf()
    private var lastWidth: Int = 0

    override fun registerCallback(callback: FoldCallback, executor: Executor) {
        callbacks += callback
        onConfigurationChange(context.resources.configuration)
    }

    override fun unregisterCallback(callback: FoldCallback) {
        callbacks -= callback
    }

    fun onConfigurationChange(newConfig: Configuration) {
        if (lastWidth == newConfig.smallestScreenWidthDp) {
            return
        }

        if (newConfig.smallestScreenWidthDp > INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP) {
            callbacks.forEach { it.onFoldUpdated(false) }
        } else {
            callbacks.forEach { it.onFoldUpdated(true) }
        }
        lastWidth = newConfig.smallestScreenWidthDp
    }
}

private const val INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP = 600
