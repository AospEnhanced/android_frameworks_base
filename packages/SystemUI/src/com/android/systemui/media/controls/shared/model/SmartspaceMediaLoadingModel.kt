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

package com.android.systemui.media.controls.shared.model

/** Models smartspace media loading state. */
sealed class SmartspaceMediaLoadingModel {
    /** The initial loading state when no smartspace media has yet loaded. */
    data object Unknown : SmartspaceMediaLoadingModel()

    /** Smartspace media has been loaded. */
    data class Loaded(
        val key: String,
        val isPrioritized: Boolean = false,
    ) : SmartspaceMediaLoadingModel()

    /** Smartspace media has been removed. */
    data class Removed(
        val key: String,
        val immediatelyUpdateUi: Boolean = true,
    ) : SmartspaceMediaLoadingModel()
}
