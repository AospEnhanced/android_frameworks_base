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

package com.android.wm.shell.flicker.bubble

import android.content.Context
import android.graphics.Point
import android.platform.test.annotations.Presubmit
import android.tools.device.flicker.junit.FlickerParametersRunnerFactory
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.FlickerTest
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.test.filters.RequiresDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test launching a new activity from bubble.
 *
 * To run this test: `atest WMShellFlickerTests:DismissBubbleScreen`
 *
 * Actions:
 * ```
 *     Dismiss a bubble notification
 * ```
 */
@RequiresDevice
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
open class DragToDismissBubbleScreenTest(flicker: FlickerTest) : BaseBubbleScreen(flicker) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displaySize = DisplayMetrics()

    /** {@inheritDoc} */
    override val transition: FlickerBuilder.() -> Unit
        get() = buildTransition {
            setup {
                val addBubbleBtn = waitAndGetAddBubbleBtn()
                addBubbleBtn?.click() ?: error("Add Bubble not found")
            }
            transitions {
                wm.run { wm.defaultDisplay.getMetrics(displaySize) }
                val dist = Point((displaySize.widthPixels / 2), displaySize.heightPixels)
                val showBubble =
                    device.wait(
                        Until.findObject(By.res(SYSTEM_UI_PACKAGE, BUBBLE_RES_NAME)),
                        FIND_OBJECT_TIMEOUT
                    )
                showBubble?.run { drag(dist, 1000) } ?: error("Show bubble not found")
            }
        }

    @Presubmit
    @Test
    open fun testAppIsAlwaysVisible() {
        flicker.assertLayers { this.isVisible(testApp) }
    }
}
