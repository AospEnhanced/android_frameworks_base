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

package com.android.wm.shell.desktopmode;

import com.android.wm.shell.desktopmode.IDesktopTaskListener;

/**
 * Interface that is exposed to remote callers to manipulate desktop mode features.
 */
interface IDesktopMode {

    /** Show apps on the desktop on the given display */
    void showDesktopApps(int displayId);

    /** Stash apps on the desktop to allow launching another app from home screen */
    void stashDesktopApps(int displayId);

    /** Hide apps that may be stashed */
    void hideStashedDesktopApps(int displayId);

    /** Bring task with the given id to front */
    oneway void showDesktopApp(int taskId);

    /** Get count of visible desktop tasks on the given display */
    int getVisibleTaskCount(int displayId);

    /** Set listener that will receive callbacks about updates to desktop tasks */
    oneway void setTaskListener(IDesktopTaskListener listener);
}