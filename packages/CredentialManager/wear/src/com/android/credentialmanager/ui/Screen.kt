/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.ui

sealed class Screen(
    val route: String,
) {
    data object Loading : Screen("loading")

    data object SinglePasswordScreen : Screen("singlePasswordScreen")

    data object SinglePasskeyScreen : Screen("singlePasskeyScreen")

    data object SignInWithProviderScreen : Screen("signInWithProviderScreen")

    data object MultipleCredentialsScreenFold : Screen("multipleCredentialsScreenFold")

    data object MultipleCredentialsScreenFlatten : Screen("multipleCredentialsScreenFlatten")
}
