/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.app.IOnProjectionStateChangedListener;

/**
 * Interface used to control special UI modes.
 * @hide
 */
interface IUiModeManager {
    /**
     * Enables the car mode. Only the system can do this.
     * @hide
     */
    void enableCarMode(int flags, int priority, String callingPackage);

    /**
     * Disables the car mode.
     */
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void disableCarMode(int flags);

    /**
     * Disables car mode (the original version is marked unsupported app usage so cannot be changed
     * for the time being).
     */
    void disableCarModeByCallingPackage(int flags, String callingPackage);

    /**
     * Return the current running mode.
     */
    int getCurrentModeType();
    
    /**
     * Sets the night mode.
     * The mode can be one of:
     *   1 - notnight mode
     *   2 - night mode
     *   3 - automatic mode switching
     */
    void setNightMode(int mode);

    /**
     * Gets the currently configured night mode.  Return 1 for notnight,
     * 2 for night, and 3 for automatic mode switching.
     */
    int getNightMode();

    /**
     * Sets the dark mode for the given application. This setting is persisted and will override the
     * system configuration for this application.
     *   1 - notnight mode
     *   2 - night mode
     *   3 - automatic mode switching
     */
    void setApplicationNightMode(in int mode);

    /**
     * Tells if UI mode is locked or not.
     */
    boolean isUiModeLocked();

    /**
     * Tells if Night mode is locked or not.
     */
    boolean isNightModeLocked();

    /**
    * [De]Activates night mode
    */
    boolean setNightModeActivated(boolean active);

    /**
    * Returns custom start clock time
    */
    long getCustomNightModeStart();

    /**
    * Sets custom start clock time
    */
    void setCustomNightModeStart(long time);

    /**
    * Returns custom end clock time
    */
    long getCustomNightModeEnd();

    /**
    * Sets custom end clock time
    */
    void setCustomNightModeEnd(long time);

    /**
    * Sets projection state for the caller for the given projection type.
    */
    boolean requestProjection(in IBinder binder, int projectionType, String callingPackage);

    /**
    * Releases projection state for the caller for the given projection type.
    */
    boolean releaseProjection(int projectionType, String callingPackage);

    /**
    * Registers a listener for changes to projection state.
    */
    void addOnProjectionStateChangedListener(in IOnProjectionStateChangedListener listener, int projectionType);

    /**
    * Unregisters a listener for changes to projection state.
    */
    void removeOnProjectionStateChangedListener(in IOnProjectionStateChangedListener listener);

    /**
    * Returns packages that have currently set the given projection type.
    */
    List<String> getProjectingPackages(int projectionType);

    /**
    * Returns currently set projection types.
    */
    int getActiveProjectionTypes();
}
