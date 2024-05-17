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

package com.android.systemui.statusbar.notification.row.wrapper

import android.graphics.drawable.AnimatedImageDrawable
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.MessagingGroup
import com.android.internal.widget.MessagingImageMessage
import com.android.internal.widget.MessagingLayout
import com.android.internal.widget.MessagingLinearLayout
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotificationMessagingTemplateViewWrapperTest : SysuiTestCase() {

    private lateinit var mRow: ExpandableNotificationRow
    private lateinit var helper: NotificationTestHelper

    @Before
    fun setUp() {
        helper = NotificationTestHelper(mContext, mDependency)
        mRow = helper.createRow()
    }

    @Test
    fun setAnimationsRunning_Run() {
        // Creates a mocked out NotificationEntry of MessagingLayout/MessagingStyle type,
        // with a mock imageMessage.drawable embedded in its MessagingImageMessage.
        val mockDrawable = mock<AnimatedImageDrawable>()
        val mockLayoutView: View = fakeMessagingLayout(mockDrawable)

        val wrapper: NotificationViewWrapper =
            NotificationMessagingTemplateViewWrapper(mContext, mockLayoutView, mRow)
        wrapper.setAnimationsRunning(true)

        // Verifies that each AnimatedImageDrawable is started animating.
        verify(mockDrawable).start()
    }

    @Test
    fun setAnimationsRunning_Stop() {
        // Creates a mocked out NotificationEntry of MessagingLayout/MessagingStyle type,
        // with a mock imageMessage.drawable embedded in its MessagingImageMessage.
        val mockDrawable = mock<AnimatedImageDrawable>()
        val mockLayoutView: View = fakeMessagingLayout(mockDrawable)

        val wrapper: NotificationViewWrapper =
            NotificationMessagingTemplateViewWrapper(mContext, mockLayoutView, mRow)
        wrapper.setAnimationsRunning(false)

        // Verifies that each AnimatedImageDrawable is started animating.
        verify(mockDrawable).stop()
    }

    private fun fakeMessagingLayout(mockDrawable: AnimatedImageDrawable): View {
        val mockMessagingImageMessage: MessagingImageMessage =
            mock<MessagingImageMessage>().apply { whenever(drawable).thenReturn(mockDrawable) }
        val mockMessageContainer: MessagingLinearLayout =
            mock<MessagingLinearLayout>().apply {
                whenever(childCount).thenReturn(1)
                whenever(getChildAt(any())).thenReturn(mockMessagingImageMessage)
            }
        val mockGroup: MessagingGroup =
            mock<MessagingGroup>().apply {
                whenever(messageContainer).thenReturn(mockMessageContainer)
            }
        val mockView: View =
            mock<MessagingLayout>().apply {
                whenever(messagingGroups).thenReturn(ArrayList<MessagingGroup>(listOf(mockGroup)))
            }
        return mockView
    }
}
