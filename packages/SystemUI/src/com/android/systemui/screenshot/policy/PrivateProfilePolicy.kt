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

import android.os.UserHandle
import com.android.systemui.screenshot.data.model.DisplayContentModel
import com.android.systemui.screenshot.data.model.ProfileType
import com.android.systemui.screenshot.data.repository.ProfileTypeRepository
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.Matched
import com.android.systemui.screenshot.policy.CapturePolicy.PolicyResult.NotMatched
import com.android.systemui.screenshot.policy.CaptureType.FullScreen
import javax.inject.Inject

private const val POLICY_NAME = "PrivateProfile"

/**
 * Condition: When any visible task belongs to a private user.
 *
 * Parameters: Capture the whole screen, owned by the private user.
 */
class PrivateProfilePolicy
@Inject
constructor(
    private val profileTypes: ProfileTypeRepository,
) : CapturePolicy {
    override suspend fun check(content: DisplayContentModel): PolicyResult {
        // The systemUI notification shade isn't a private profile app, skip.
        if (content.systemUiState.shadeExpanded) {
            return NotMatched(policy = POLICY_NAME, reason = "Notification shade is expanded")
        }

        // Find the first visible rootTaskInfo with a child task owned by a private user
        val (rootTask, childTask) =
            content.rootTasks
                .filter { it.isVisible }
                .firstNotNullOfOrNull { root ->
                    root
                        .childTasksTopDown()
                        .firstOrNull {
                            profileTypes.getProfileType(it.userId) == ProfileType.PRIVATE
                        }
                        ?.let { root to it }
                }
                ?: return NotMatched(
                    policy = POLICY_NAME,
                    reason = "No private profile tasks are visible"
                )

        // If matched, return parameters needed to modify the request.
        return Matched(
            policy = POLICY_NAME,
            reason = "At least one private profile task is visible",
            CaptureParameters(
                type = FullScreen(content.displayId),
                component = childTask.componentName ?: rootTask.topActivity,
                owner = UserHandle.of(childTask.userId),
            )
        )
    }
}
