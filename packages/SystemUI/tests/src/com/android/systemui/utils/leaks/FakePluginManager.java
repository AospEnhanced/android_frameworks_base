/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.testing.LeakCheck;

import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;

public class FakePluginManager implements PluginManager {

    private final BaseLeakChecker<PluginListener<?>> mLeakChecker;

    public FakePluginManager(LeakCheck test) {
        mLeakChecker = new BaseLeakChecker<>(test, "Plugin");
    }

    @Override
    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            Class<T> cls, boolean allowMultiple) {
        mLeakChecker.addCallback(listener);
    }

    @Override
    public <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<T> cls) {
        mLeakChecker.addCallback(listener);
    }

    @Override
    public <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<T> cls,
            boolean allowMultiple) {
        mLeakChecker.addCallback(listener);
    }

    @Override
    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            Class<T> cls) {
        mLeakChecker.addCallback(listener);
    }

    @Override
    public void removePluginListener(PluginListener<?> listener) {
        mLeakChecker.removeCallback(listener);
    }

    @Override
    public <T> boolean dependsOn(Plugin p, Class<T> cls) {
        return false;
    }

    @Override
    public String[] getPrivilegedPlugins() {
        return new String[0];
    }
}
