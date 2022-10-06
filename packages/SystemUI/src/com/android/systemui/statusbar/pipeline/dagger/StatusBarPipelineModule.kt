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

package com.android.systemui.statusbar.pipeline.dagger

import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileSubscriptionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileSubscriptionRepositoryImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.UserSetupRepositoryImpl
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl
import dagger.Binds
import dagger.Module

@Module
abstract class StatusBarPipelineModule {
    @Binds
    abstract fun connectivityRepository(impl: ConnectivityRepositoryImpl): ConnectivityRepository

    @Binds
    abstract fun wifiRepository(impl: WifiRepositoryImpl): WifiRepository

    @Binds
    abstract fun mobileSubscriptionRepository(
        impl: MobileSubscriptionRepositoryImpl
    ): MobileSubscriptionRepository

    @Binds
    abstract fun userSetupRepository(impl: UserSetupRepositoryImpl): UserSetupRepository
}
