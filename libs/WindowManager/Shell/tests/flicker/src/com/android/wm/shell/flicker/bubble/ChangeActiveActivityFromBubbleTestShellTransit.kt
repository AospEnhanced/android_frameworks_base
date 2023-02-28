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

package com.android.wm.shell.flicker.bubble

import android.platform.test.annotations.FlakyTest
import android.tools.device.flicker.isShellTransitionsEnabled
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerTest
import androidx.test.filters.RequiresDevice
import org.junit.Assume
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FlakyTest(bugId = 217777115)
class ChangeActiveActivityFromBubbleTestShellTransit(flicker: FlickerTest) :
    ChangeActiveActivityFromBubbleTest(flicker) {
    @Before
    override fun before() {
        Assume.assumeTrue(isShellTransitionsEnabled)
    }
}
