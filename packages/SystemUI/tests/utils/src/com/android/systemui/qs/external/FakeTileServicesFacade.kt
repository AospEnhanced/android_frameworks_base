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

package com.android.systemui.qs.external

import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

class FakeTileServicesFacade(
    private val TileServiceManager: TileServiceManager,
    val tileServices: TileServices = mock {}
) {

    var customTileInterface: CustomTileInterface? = null
        private set

    init {
        with(tileServices) {
            whenever(getTileWrapper(any())).then {
                customTileInterface = it.getArgument(0)
                TileServiceManager
            }
        }
    }
}
