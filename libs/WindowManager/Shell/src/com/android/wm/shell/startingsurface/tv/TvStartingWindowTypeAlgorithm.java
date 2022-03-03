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

package com.android.wm.shell.startingsurface.tv;

import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SOLID_COLOR_SPLASH_SCREEN;

import android.window.StartingWindowInfo;

import com.android.wm.shell.startingsurface.StartingWindowTypeAlgorithm;

/**
 * Algorithm for determining the type of a new starting window on Android TV.
 * For now we always show empty splash screens on Android TV.
 */
public class TvStartingWindowTypeAlgorithm implements StartingWindowTypeAlgorithm {
    @Override
    public int getSuggestedWindowType(StartingWindowInfo windowInfo) {
        // For now we want to always show empty splash screens on TV.
        return STARTING_WINDOW_TYPE_SOLID_COLOR_SPLASH_SCREEN;
    }
}
