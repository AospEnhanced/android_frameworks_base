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

package com.android.systemui.communal.domain.model

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetProviderInfo
import android.widget.RemoteViews
import com.android.systemui.communal.shared.model.CommunalContentSize
import java.util.UUID

/** Encapsulates data for a communal content. */
sealed interface CommunalContentModel {
    /** Unique key across all types of content models. */
    val key: String

    /** Size to be rendered in the grid. */
    val size: CommunalContentSize

    /**
     * A type of communal content is ongoing / live / ephemeral, and can be sized and ordered
     * dynamically.
     */
    sealed interface Ongoing : CommunalContentModel {
        override var size: CommunalContentSize

        /** Timestamp in milliseconds of when the content was created. */
        val createdTimestampMillis: Long
    }

    class Widget(
        val appWidgetId: Int,
        val providerInfo: AppWidgetProviderInfo,
        val appWidgetHost: AppWidgetHost,
    ) : CommunalContentModel {
        override val key = KEY.widget(appWidgetId)
        // Widget size is always half.
        override val size = CommunalContentSize.HALF
    }

    /** A placeholder item representing a new widget being added */
    class WidgetPlaceholder : CommunalContentModel {
        override val key: String = KEY.widgetPlaceholder()
        // Same as widget size.
        override val size = CommunalContentSize.HALF
    }

    class Tutorial(
        id: Int,
        override var size: CommunalContentSize,
    ) : CommunalContentModel {
        override val key = KEY.tutorial(id)
    }

    class Smartspace(
        smartspaceTargetId: String,
        val remoteViews: RemoteViews,
        override val createdTimestampMillis: Long,
        override var size: CommunalContentSize = CommunalContentSize.HALF,
    ) : Ongoing {
        override val key = KEY.smartspace(smartspaceTargetId)
    }

    class Umo(
        override val createdTimestampMillis: Long,
        override var size: CommunalContentSize = CommunalContentSize.HALF,
    ) : Ongoing {
        override val key = KEY.umo()
    }

    class KEY {
        companion object {
            fun widget(id: Int): String {
                return "widget_$id"
            }

            fun widgetPlaceholder(): String {
                return "widget_placeholder_${UUID.randomUUID()}"
            }

            fun tutorial(id: Int): String {
                return "tutorial_$id"
            }

            fun smartspace(id: String): String {
                return "smartspace_$id"
            }

            fun umo(): String {
                return "umo"
            }
        }
    }
}
