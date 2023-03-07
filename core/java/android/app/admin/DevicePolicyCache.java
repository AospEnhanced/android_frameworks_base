/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.app.admin;

import android.annotation.UserIdInt;

import com.android.server.LocalServices;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores a copy of the set of device policies maintained by {@link DevicePolicyManager} that
 * can be accessed from any place without risking dead locks.
 *
 * @hide
 */
public abstract class DevicePolicyCache {
    protected DevicePolicyCache() {
    }

    /**
     * @return the instance.
     */
    public static DevicePolicyCache getInstance() {
        final DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        return (dpmi != null) ? dpmi.getDevicePolicyCache() : EmptyDevicePolicyCache.INSTANCE;
    }

    /**
     * See {@link DevicePolicyManager#getScreenCaptureDisabled}
     */
    public abstract boolean isScreenCaptureAllowed(@UserIdInt int userHandle);

    /**
     * Caches {@link DevicePolicyManager#getPasswordQuality(android.content.ComponentName)} of the
     * given user with {@code null} passed in as argument.
     */
    public abstract int getPasswordQuality(@UserIdInt int userHandle);

    /**
     * Caches {@link DevicePolicyManager#getPermissionPolicy(android.content.ComponentName)} of
     * the given user.
     */
    public abstract int getPermissionPolicy(@UserIdInt int userHandle);

    /**
     * True if there is an admin on the device who can grant sensor permissions.
     */
    public abstract boolean canAdminGrantSensorsPermissions();

    /**
     * Returns a list of package names for which all launcher shortcuts should be modified to be
     * launched in the managed profile and badged accordingly.
     */
    public abstract List<String> getLauncherShortcutOverrides();

    /**
     * Empty implementation.
     */
    private static class EmptyDevicePolicyCache extends DevicePolicyCache {
        private static final EmptyDevicePolicyCache INSTANCE = new EmptyDevicePolicyCache();

        @Override
        public boolean isScreenCaptureAllowed(int userHandle) {
            return true;
        }

        @Override
        public int getPasswordQuality(int userHandle) {
            return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        }

        @Override
        public int getPermissionPolicy(int userHandle) {
            return DevicePolicyManager.PERMISSION_POLICY_PROMPT;
        }

        @Override
        public boolean canAdminGrantSensorsPermissions() {
            return false;
        }
        @Override
        public List<String> getLauncherShortcutOverrides() {
            return new ArrayList<>();
        }
    }
}
