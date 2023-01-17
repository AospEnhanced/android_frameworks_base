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

package com.android.server.wm.flicker.launch

import android.app.Instrumentation
import android.app.WallpaperManager
import android.content.res.Resources
import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import androidx.test.filters.RequiresDevice
import com.android.server.wm.flicker.BaseTest
import com.android.server.wm.flicker.FlickerBuilder
import com.android.server.wm.flicker.FlickerTest
import com.android.server.wm.flicker.FlickerTestFactory
import com.android.server.wm.flicker.helpers.NewTasksAppHelper
import com.android.server.wm.flicker.helpers.SimpleAppHelper
import com.android.server.wm.flicker.helpers.WindowUtils
import com.android.server.wm.flicker.helpers.isShellTransitionsEnabled
import com.android.server.wm.flicker.junit.FlickerParametersRunnerFactory
import com.android.server.wm.traces.common.ComponentNameMatcher
import com.android.server.wm.traces.common.ComponentNameMatcher.Companion.DEFAULT_TASK_DISPLAY_AREA
import com.android.server.wm.traces.common.ComponentNameMatcher.Companion.SPLASH_SCREEN
import com.android.server.wm.traces.common.ComponentNameMatcher.Companion.WALLPAPER_BBQ_WRAPPER
import com.android.server.wm.traces.common.ComponentSplashScreenMatcher
import com.android.server.wm.traces.common.IComponentMatcher
import com.android.server.wm.traces.parser.toFlickerComponent
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test the back and forward transition between 2 activities.
 *
 * To run this test: `atest FlickerTests:TaskTransitionTest`
 *
 * Actions:
 * ```
 *     Launch the NewTaskLauncherApp [mTestApp]
 *     Open a new task (SimpleActivity) from the NewTaskLauncherApp [mTestApp]
 *     Go back to the NewTaskLauncherApp [mTestApp]
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TaskTransitionTest(flicker: FlickerTest) : BaseTest(flicker) {
    private val launchNewTaskApp = NewTasksAppHelper(instrumentation)
    private val simpleApp = SimpleAppHelper(instrumentation)
    private val wallpaper by lazy { getWallpaperPackage(instrumentation) }

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit = {
        setup { launchNewTaskApp.launchViaIntent(wmHelper) }
        teardown { launchNewTaskApp.exit(wmHelper) }
        transitions {
            launchNewTaskApp.openNewTask(device, wmHelper)
            tapl.pressBack()
            wmHelper.StateSyncBuilder().withAppTransitionIdle().waitForAndVerify()
        }
    }

    /**
     * Checks that the [wallpaper] window is never visible when performing task transitions. A solid
     * color background should be shown instead.
     */
    @FlakyTest(bugId = 253617416)
    @Test
    fun wallpaperWindowIsNeverVisible() {
        flicker.assertWm { this.isNonAppWindowInvisible(wallpaper) }
    }

    /**
     * Checks that the [wallpaper] layer is never visible when performing task transitions. A solid
     * color background should be shown instead.
     */
    @Presubmit
    @Test
    fun wallpaperLayerIsNeverVisible() {
        flicker.assertLayers {
            this.isInvisible(wallpaper)
            this.isInvisible(WALLPAPER_BBQ_WRAPPER)
        }
    }

    /**
     * Check that the [ComponentNameMatcher.LAUNCHER] window is never visible when performing task
     * transitions. A solid color background should be shown above it.
     */
    @Presubmit
    @Test
    fun launcherWindowIsNeverVisible() {
        flicker.assertWm { this.isAppWindowInvisible(ComponentNameMatcher.LAUNCHER) }
    }

    /**
     * Checks that the [ComponentNameMatcher.LAUNCHER] layer is never visible when performing task
     * transitions. A solid color background should be shown above it.
     */
    @Presubmit
    @Test
    fun launcherLayerIsNeverVisible() {
        flicker.assertLayers { this.isInvisible(ComponentNameMatcher.LAUNCHER) }
    }

    /** Checks that a color background is visible while the task transition is occurring. */
    @Presubmit
    @Test
    fun transitionHasColorBackground_legacy() {
        Assume.assumeFalse(isShellTransitionsEnabled)
        transitionHasColorBackground(DEFAULT_TASK_DISPLAY_AREA)
    }

    /** Checks that a color background is visible while the task transition is occurring. */
    @Presubmit
    @Test
    fun transitionHasColorBackground_shellTransit() {
        Assume.assumeTrue(isShellTransitionsEnabled)
        transitionHasColorBackground(ComponentNameMatcher("", "Animation Background"))
    }

    private fun transitionHasColorBackground(backgroundColorLayer: IComponentMatcher) {
        Assume.assumeTrue(isShellTransitionsEnabled)

        val displayBounds = WindowUtils.getDisplayBounds(flicker.scenario.startRotation)
        flicker.assertLayers {
            this.invoke("LAUNCH_NEW_TASK_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(launchNewTaskApp.componentMatcher).coversExactly(displayBounds)
                }
                .isInvisible(backgroundColorLayer)
                .hasNoColor(backgroundColorLayer)
                .then()
                // Transitioning
                .isVisible(backgroundColorLayer)
                .hasColor(backgroundColorLayer)
                .then()
                // Fully transitioned to simple SIMPLE_ACTIVITY
                .invoke(
                    "SIMPLE_ACTIVITY's splashscreen coversExactly displayBounds",
                    isOptional = true
                ) {
                    it.visibleRegion(ComponentSplashScreenMatcher(simpleApp.componentMatcher))
                        .coversExactly(displayBounds)
                }
                .invoke("SIMPLE_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(simpleApp.componentMatcher).coversExactly(displayBounds)
                }
                .isInvisible(backgroundColorLayer)
                .hasNoColor(backgroundColorLayer)
                .then()
                // Transitioning back
                .isVisible(backgroundColorLayer)
                .hasColor(backgroundColorLayer)
                .then()
                // Fully transitioned back to LAUNCH_NEW_TASK_ACTIVITY
                .invoke(
                    "LAUNCH_NEW_TASK_ACTIVITY's splashscreen coversExactly displayBounds",
                    isOptional = true
                ) {
                    it.visibleRegion(
                            ComponentSplashScreenMatcher(launchNewTaskApp.componentMatcher)
                        )
                        .coversExactly(displayBounds)
                }
                .invoke("LAUNCH_NEW_TASK_ACTIVITY coversExactly displayBounds") {
                    it.visibleRegion(launchNewTaskApp.componentMatcher).coversExactly(displayBounds)
                }
                .isInvisible(backgroundColorLayer)
                .hasNoColor(backgroundColorLayer)
        }
    }

    /**
     * Checks that we start with the LaunchNewTask activity on top and then open up the
     * SimpleActivity and then go back to the LaunchNewTask activity.
     */
    @Presubmit
    @Test
    fun newTaskOpensOnTopAndThenCloses() {
        flicker.assertWm {
            this.isAppWindowOnTop(launchNewTaskApp.componentMatcher)
                .then()
                .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowOnTop(simpleApp.componentMatcher)
                .then()
                .isAppWindowOnTop(SPLASH_SCREEN, isOptional = true)
                .then()
                .isAppWindowOnTop(launchNewTaskApp.componentMatcher)
        }
    }

    companion object {
        private fun getWallpaperPackage(instrumentation: Instrumentation): IComponentMatcher {
            val wallpaperManager = WallpaperManager.getInstance(instrumentation.targetContext)

            return wallpaperManager.wallpaperInfo?.component?.toFlickerComponent()
                ?: getStaticWallpaperPackage(instrumentation)
        }

        private fun getStaticWallpaperPackage(instrumentation: Instrumentation): IComponentMatcher {
            val resourceId =
                Resources.getSystem()
                    .getIdentifier("image_wallpaper_component", "string", "android")
            return ComponentNameMatcher.unflattenFromString(
                instrumentation.targetContext.resources.getString(resourceId)
            )
        }

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTest> {
            return FlickerTestFactory.nonRotationTests()
        }
    }
}
