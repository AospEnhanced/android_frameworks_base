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

package com.android.systemui.qs.tiles.di

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.qSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.qsTileViewModelAdaperFactory
import com.android.systemui.util.mockito.mock
import javax.inject.Provider
import org.mockito.Mockito

var Kosmos.newFactoryTileMap by Kosmos.Fixture { emptyMap<String, Provider<QSTileViewModel>>() }

val Kosmos.newQSTileFactory by
    Kosmos.Fixture {
        NewQSTileFactory(
            qSTileConfigProvider,
            qsTileViewModelAdaperFactory,
            newFactoryTileMap,
            mock(Mockito.withSettings().defaultAnswer(Mockito.RETURNS_MOCKS)),
            mock(Mockito.withSettings().defaultAnswer(Mockito.RETURNS_MOCKS)),
        )
    }
