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

import androidx.navigation.NavController

fun NavController.navigateToLoading() {
    navigateToAsRoot(Screen.Loading.route)
}

fun NavController.navigateToSinglePasswordScreen() {
    navigateToAsRoot(Screen.SinglePasswordScreen.route)
}

fun NavController.navigateToSinglePasskeyScreen() {
    navigateToAsRoot(Screen.SinglePasskeyScreen.route)
}

fun NavController.navigateToSignInWithProviderScreen() {
    navigateToAsRoot(Screen.SignInWithProviderScreen.route)
}

fun NavController.navigateToMultipleCredentialsFoldScreen() {
    navigateToAsRoot(Screen.MultipleCredentialsScreenFold.route)
}

fun NavController.navigateToMultipleCredentialsFlattenScreen() {
    navigateToAsRoot(Screen.MultipleCredentialsScreenFlatten.route)
}

fun NavController.navigateToAsRoot(route: String) {
    popBackStack()
    navigate(route)
}
