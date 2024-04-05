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

package com.android.systemui.screenshot.policy

import android.app.ActivityTaskManager.RootTaskInfo
import com.android.systemui.screenshot.data.model.ChildTaskModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map

internal fun RootTaskInfo.childTasksTopDown(): Flow<ChildTaskModel> {
    return ((numActivities - 1) downTo 0).asFlow().map { index ->
        ChildTaskModel(
            childTaskIds[index],
            childTaskNames[index],
            childTaskBounds[index],
            childTaskUserIds[index]
        )
    }
}

internal suspend fun RootTaskInfo.firstChildTaskOrNull(
    filter: suspend (Int) -> Boolean
): Pair<RootTaskInfo, Int>? {
    // Child tasks are provided in bottom-up order
    // Filtering is done top-down, so iterate backwards here.
    for (index in numActivities - 1 downTo 0) {
        if (filter(index)) {
            return (this to index)
        }
    }
    return null
}
