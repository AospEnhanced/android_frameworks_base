/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm.flicker.close

import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.Group4
import com.android.server.wm.flicker.dsl.FlickerBuilder
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test app closes by pressing home button
 *
 * To run this test: `atest FlickerTests:CloseAppHomeButtonTest`
 *
 * Actions:
 *     Make sure no apps are running on the device
 *     Launch an app [testApp] and wait animation to complete
 *     Press home button
 *
 * To run only the presubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Presubmit`
 *
 * To run only the postsubmit assertions add: `--
 *      --module-arg FlickerTests:exclude-annotation:androidx.test.filters.FlakyTest
 *      --module-arg FlickerTests:include-annotation:android.platform.test.annotations.Postsubmit`
 *
 * To run only the flaky assertions add: `--
 *      --module-arg FlickerTests:include-annotation:androidx.test.filters.FlakyTest`
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [CloseAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group4
class CloseAppHomeButtonTest(testSpec: FlickerTestParameter) : CloseAppTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = {
            super.transition(this, it)
            transitions {
                device.pressHome()
                wmHelper.waitForHomeActivityVisible()
            }
        }

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerRotatesAndScales() = super.navBarLayerRotatesAndScales()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests(repetitions = 5)
        }
    }
}