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

@file:JvmName("FlickerExtensions")
package com.android.server.wm.flicker.helpers

import com.android.server.wm.flicker.Flicker
import com.android.server.wm.flicker.rules.ChangeDisplayOrientationRule

/**
 * Changes the device [rotation] and wait for the rotation animation to complete
 *
 * @param rotation New device rotation
 */
fun Flicker.setRotation(rotation: Int) =
    ChangeDisplayOrientationRule.setRotation(rotation, instrumentation, wmHelper)
