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

package com.android.credentialmanager

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import platform.test.screenshot.GoldenImagePathManager
import platform.test.screenshot.PathConfig

/** The assets path to be used by all CredentialManager screenshot tests. */
private const val ASSETS_PREFIX = "frameworks/base/packages/CredentialManager"
private const val ASSETS_PATH = "${ASSETS_PREFIX}/tests/robotests/screenshot/customization/assets"
private const val ASSETS_PATH_ROBO =
        "${ASSETS_PREFIX}/tests/robotests/customization/assets"

private val isRobolectric = Build.FINGERPRINT.contains("robolectric")

class CredentialManagerGoldenImagePathManager(
        pathConfig: PathConfig,
        assetsPathRelativeToBuildRoot: String = if (isRobolectric) ASSETS_PATH_ROBO else ASSETS_PATH
) : GoldenImagePathManager(
        appContext = InstrumentationRegistry.getInstrumentation().context,
        assetsPathRelativeToBuildRoot = assetsPathRelativeToBuildRoot,
        deviceLocalPath =
        InstrumentationRegistry.getInstrumentation()
                .targetContext
                .filesDir
                .absolutePath
                .toString() + "/credman_screenshots",
        pathConfig = pathConfig,
) {
    override fun toString(): String {
        // This string is appended to all actual/expected screenshots on the device, so make sure
        // it is a static value.
        return "CredentialManagerGoldenImagePathManager"
    }
}