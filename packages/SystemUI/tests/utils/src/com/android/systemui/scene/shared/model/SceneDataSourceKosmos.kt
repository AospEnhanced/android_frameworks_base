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

package com.android.systemui.scene.shared.model

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.scene.initialSceneKey
import com.android.systemui.scene.sceneContainerConfig

val Kosmos.fakeSceneDataSource by Fixture {
    FakeSceneDataSource(
        initialSceneKey = initialSceneKey,
    )
}

val Kosmos.sceneDataSourceDelegator by Fixture {
    SceneDataSourceDelegator(
            applicationScope = applicationCoroutineScope,
            config = sceneContainerConfig,
        )
        .apply { setDelegate(fakeSceneDataSource) }
}

val Kosmos.sceneDataSource by Fixture { sceneDataSourceDelegator }
