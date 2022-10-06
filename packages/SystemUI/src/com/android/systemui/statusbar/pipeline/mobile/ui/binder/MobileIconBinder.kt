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
 * limitations under the License.
 */

package com.android.systemui.statusbar.pipeline.mobile.ui.binder

import android.content.res.ColorStateList
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.settingslib.graph.SignalDrawable
import com.android.systemui.R
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

object MobileIconBinder {
    /** Binds the view to the view-model, continuing to update the former based on the latter */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: MobileIconViewModel,
    ) {
        val iconView = view.requireViewById<ImageView>(R.id.mobile_signal)
        val mobileDrawable = SignalDrawable(view.context).also { iconView.setImageDrawable(it) }

        view.isVisible = true
        iconView.isVisible = true

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Set the icon for the triangle
                launch {
                    viewModel.iconId.distinctUntilChanged().collect { iconId ->
                        mobileDrawable.level = iconId
                    }
                }

                // Set the tint
                launch {
                    viewModel.tint.collect { tint ->
                        iconView.imageTintList = ColorStateList.valueOf(tint)
                    }
                }
            }
        }
    }
}
