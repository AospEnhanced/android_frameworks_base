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
 */

package com.android.systemui.keyguard.ui.composable.section

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.compose.animation.scene.SceneScope
import com.android.systemui.Flags.migrateClocksToBlueprint
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.notifications.ui.composable.NotificationStack
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.NotificationStackViewBinder
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import javax.inject.Inject

@SysUISingleton
class NotificationSection
@Inject
constructor(
    private val viewModel: NotificationsPlaceholderViewModel,
    sceneContainerFlags: SceneContainerFlags,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    stackScrollLayout: NotificationStackScrollLayout,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    notificationStackViewBinder: NotificationStackViewBinder,
) {

    init {
        if (!migrateClocksToBlueprint()) {
            throw IllegalStateException("this requires migrateClocksToBlueprint()")
        }
        // This scene container section moves the NSSL to the SharedNotificationContainer.
        // This also requires that SharedNotificationContainer gets moved to the
        // SceneWindowRootView by the SceneWindowRootViewBinder. Prior to Scene Container,
        // but when the KeyguardShadeMigrationNssl flag is enabled, NSSL is moved into this
        // container by the NotificationStackScrollLayoutSection.
        // Ensure stackScrollLayout is a child of sharedNotificationContainer.

        if (stackScrollLayout.parent != sharedNotificationContainer) {
            (stackScrollLayout.parent as? ViewGroup)?.removeView(stackScrollLayout)
            sharedNotificationContainer.addNotificationStackScrollLayout(stackScrollLayout)
        }

        sharedNotificationContainerBinder.bind(
            sharedNotificationContainer,
            sharedNotificationContainerViewModel,
        )

        if (sceneContainerFlags.isEnabled()) {
            notificationStackViewBinder.bindWhileAttached()
        }
    }

    @Composable
    fun SceneScope.Notifications(modifier: Modifier = Modifier) {
        NotificationStack(
            viewModel = viewModel,
            modifier = modifier,
        )
    }
}
