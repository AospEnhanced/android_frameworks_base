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

package com.android.wm.shell.flicker.pip

import android.platform.test.annotations.Postsubmit
import android.view.Surface
import androidx.test.filters.FlakyTest
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.LAUNCHER_COMPONENT
import com.android.server.wm.flicker.annotation.Group3
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import com.android.server.wm.flicker.startRotation
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip launch.
 * To run this test: `atest WMShellFlickerTests:PipToAppTest`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group3
class PipToAppTest(testSpec: FlickerTestParameter) : PipTransition(testSpec) {
    override val transition: FlickerBuilder.(Map<String, Any?>) -> Unit
        get() = buildTransition(eachRun = true) { configuration ->
            setup {
                eachRun {
                    this.setRotation(configuration.startRotation)
                }
            }
            teardown {
                eachRun {
                    this.setRotation(Surface.ROTATION_0)
                }
            }
            transitions {
                pipApp.expandPipWindowToApp(wmHelper)
            }
        }

    @Postsubmit
    @Test
    override fun navBarLayerIsVisible() = super.navBarLayerIsVisible()

    @Postsubmit
    @Test
    override fun statusBarLayerIsVisible() = super.statusBarLayerIsVisible()

    @FlakyTest
    @Test
    fun appReplacesPipWindow() {
        testSpec.assertWm {
            this.invoke("hasPipWindow") { it.isPinned(pipApp.component) }
                .isAppWindowOnTop(pipApp.component)
                .then()
                .invoke("hasNotPipWindow") { it.isNotPinned(pipApp.component) }
                .isAppWindowOnTop(pipApp.component)
        }
    }

    @FlakyTest
    @Test
    fun appReplacesPipLayer() {
        testSpec.assertLayers {
            this.isVisible(pipApp.component)
                .isVisible(LAUNCHER_COMPONENT)
                .then()
                .isVisible(pipApp.component)
                .isInvisible(LAUNCHER_COMPONENT)
        }
    }

    @FlakyTest
    @Test
    fun testAppCoversFullScreen() {
        testSpec.assertLayersStart {
            visibleRegion(pipApp.component).coversExactly(displayBounds)
        }
    }

    @FlakyTest(bugId = 151179149)
    @Test
    fun focusChanges() {
        testSpec.assertEventLog {
            this.focusChanges("NexusLauncherActivity",
                    pipApp.launcherName, "NexusLauncherActivity")
        }
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): List<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                    .getConfigNonRotationTests(supportedRotations = listOf(Surface.ROTATION_0),
                            repetitions = 5)
        }
    }
}
