/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

/**
 * Class to encapsulate all possible status bar states regarding Keyguard.
 */
public class StatusBarState {

    /**
     * The status bar is in the "normal" shade mode.
     */
    public static final int SHADE = 0;

    /**
     * Status bar is currently the Keyguard.
     */
    public static final int KEYGUARD = 1;

    /**
     * Status bar is in the special mode, where it is fully interactive but still locked. So
     * dismissing the shade will still show the bouncer.
     */
    public static final int SHADE_LOCKED = 2;

    /**
     * Status bar in a special mode, where the notification panel is off screen and a live lock
     * screen has focus.  Dismissing the shade will still show the bouncer.
     */
    public static final int KEYGUARD_LLS = 3;

    public static boolean isKeyguardState(int state) {
        return state == KEYGUARD || state == KEYGUARD_LLS;
    }

}
