/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.packageinstaller.test

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ExportedComponentTest {

    private val context: Context = InstrumentationRegistry.getContext()

    @Test
    fun verifyNoExportedReceivers() {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = Uri.parse("content://mockForTest")
        }
        val packageInstallers = context.packageManager.queryIntentActivities(intent,
            PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo.packageName }
            .distinct()
            .map { context.packageManager.getPackageInfo(it, PackageManager.GET_RECEIVERS) }

        assertThat(packageInstallers).isNotEmpty()

        packageInstallers.forEach {
            val exported = it.receivers.filter { it.exported }
            assertWithMessage("Receivers should not be exported").that(exported).isEmpty()
        }
    }
}
