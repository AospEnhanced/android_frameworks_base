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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.internal.widget.CallLayout
import javax.inject.Inject

class CallLayoutSetDataAsyncFactory @Inject constructor() : NotifRemoteViewsFactory {
    override fun instantiate(
        row: ExpandableNotificationRow,
        @NotificationRowContentBinder.InflationFlag layoutType: Int,
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? =
        if (name == CallLayout::class.java.name)
            CallLayout(context, attrs).apply { setSetDataAsyncEnabled(true) }
        else null
}
