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

package com.android.wm.shell.sysui;

import static android.content.pm.ActivityInfo.CONFIG_ASSETS_PATHS;
import static android.content.pm.ActivityInfo.CONFIG_FONT_SCALE;
import static android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION;
import static android.content.pm.ActivityInfo.CONFIG_LOCALE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_SYSUI_EVENTS;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles event callbacks from SysUI that can be used within the Shell.
 */
public class ShellController {
    private static final String TAG = ShellController.class.getSimpleName();

    private final ShellInit mShellInit;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellExecutor mMainExecutor;
    private final ShellInterfaceImpl mImpl = new ShellInterfaceImpl();

    private final CopyOnWriteArrayList<ConfigurationChangeListener> mConfigChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<KeyguardChangeListener> mKeyguardChangeListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<UserChangeListener> mUserChangeListeners =
            new CopyOnWriteArrayList<>();

    private Configuration mLastConfiguration;


    public ShellController(ShellInit shellInit, ShellCommandHandler shellCommandHandler,
            ShellExecutor mainExecutor) {
        mShellInit = shellInit;
        mShellCommandHandler = shellCommandHandler;
        mMainExecutor = mainExecutor;
    }

    /**
     * Returns the external interface to this controller.
     */
    public ShellInterface asShell() {
        return mImpl;
    }

    /**
     * Adds a new configuration listener. The configuration change callbacks are not made in any
     * particular order.
     */
    public void addConfigurationChangeListener(ConfigurationChangeListener listener) {
        mConfigChangeListeners.remove(listener);
        mConfigChangeListeners.add(listener);
    }

    /**
     * Removes an existing configuration listener.
     */
    public void removeConfigurationChangeListener(ConfigurationChangeListener listener) {
        mConfigChangeListeners.remove(listener);
    }

    /**
     * Adds a new Keyguard listener. The Keyguard change callbacks are not made in any
     * particular order.
     */
    public void addKeyguardChangeListener(KeyguardChangeListener listener) {
        mKeyguardChangeListeners.remove(listener);
        mKeyguardChangeListeners.add(listener);
    }

    /**
     * Removes an existing Keyguard listener.
     */
    public void removeKeyguardChangeListener(KeyguardChangeListener listener) {
        mKeyguardChangeListeners.remove(listener);
    }

    /**
     * Adds a new user-change listener. The user change callbacks are not made in any
     * particular order.
     */
    public void addUserChangeListener(UserChangeListener listener) {
        mUserChangeListeners.remove(listener);
        mUserChangeListeners.add(listener);
    }

    /**
     * Removes an existing user-change listener.
     */
    public void removeUserChangeListener(UserChangeListener listener) {
        mUserChangeListeners.remove(listener);
    }

    @VisibleForTesting
    void onConfigurationChanged(Configuration newConfig) {
        // The initial config is send on startup and doesn't trigger listener callbacks
        if (mLastConfiguration == null) {
            mLastConfiguration = new Configuration(newConfig);
            ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Initial Configuration: %s", newConfig);
            return;
        }

        final int diff = newConfig.diff(mLastConfiguration);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "New configuration change: %s", newConfig);
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "\tchanges=%s",
                Configuration.configurationDiffToString(diff));
        final boolean densityFontScaleChanged = (diff & CONFIG_FONT_SCALE) != 0
                || (diff & ActivityInfo.CONFIG_DENSITY) != 0;
        final boolean smallestScreenWidthChanged = (diff & CONFIG_SMALLEST_SCREEN_SIZE) != 0;
        final boolean themeChanged = (diff & CONFIG_ASSETS_PATHS) != 0
                || (diff & CONFIG_UI_MODE) != 0;
        final boolean localOrLayoutDirectionChanged = (diff & CONFIG_LOCALE) != 0
                || (diff & CONFIG_LAYOUT_DIRECTION) != 0;

        // Update the last configuration and call listeners
        mLastConfiguration.updateFrom(newConfig);
        for (ConfigurationChangeListener listener : mConfigChangeListeners) {
            listener.onConfigurationChanged(newConfig);
            if (densityFontScaleChanged) {
                listener.onDensityOrFontScaleChanged();
            }
            if (smallestScreenWidthChanged) {
                listener.onSmallestScreenWidthChanged();
            }
            if (themeChanged) {
                listener.onThemeChanged();
            }
            if (localOrLayoutDirectionChanged) {
                listener.onLocaleOrLayoutDirectionChanged();
            }
        }
    }

    @VisibleForTesting
    void onKeyguardVisibilityChanged(boolean visible, boolean occluded, boolean animatingDismiss) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Keyguard visibility changed: visible=%b "
                + "occluded=%b animatingDismiss=%b", visible, occluded, animatingDismiss);
        for (KeyguardChangeListener listener : mKeyguardChangeListeners) {
            listener.onKeyguardVisibilityChanged(visible, occluded, animatingDismiss);
        }
    }

    @VisibleForTesting
    void onKeyguardDismissAnimationFinished() {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "Keyguard dismiss animation finished");
        for (KeyguardChangeListener listener : mKeyguardChangeListeners) {
            listener.onKeyguardDismissAnimationFinished();
        }
    }

    @VisibleForTesting
    void onUserChanged(int newUserId, @NonNull Context userContext) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "User changed: id=%d", newUserId);
        for (UserChangeListener listener : mUserChangeListeners) {
            listener.onUserChanged(newUserId, userContext);
        }
    }

    @VisibleForTesting
    void onUserProfilesChanged(@NonNull List<UserInfo> profiles) {
        ProtoLog.v(WM_SHELL_SYSUI_EVENTS, "User profiles changed");
        for (UserChangeListener listener : mUserChangeListeners) {
            listener.onUserProfilesChanged(profiles);
        }
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mConfigChangeListeners=" + mConfigChangeListeners.size());
        pw.println(innerPrefix + "mLastConfiguration=" + mLastConfiguration);
        pw.println(innerPrefix + "mKeyguardChangeListeners=" + mKeyguardChangeListeners.size());
        pw.println(innerPrefix + "mUserChangeListeners=" + mUserChangeListeners.size());
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class ShellInterfaceImpl implements ShellInterface {

        @Override
        public void onInit() {
            try {
                mMainExecutor.executeBlocking(() -> mShellInit.init());
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to initialize the Shell in 2s", e);
            }
        }

        @Override
        public void dump(PrintWriter pw) {
            try {
                mMainExecutor.executeBlocking(() -> mShellCommandHandler.dump(pw));
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to dump the Shell in 2s", e);
            }
        }

        @Override
        public boolean handleCommand(String[] args, PrintWriter pw) {
            try {
                boolean[] result = new boolean[1];
                mMainExecutor.executeBlocking(() -> {
                    result[0] = mShellCommandHandler.handleCommand(args, pw);
                });
                return result[0];
            } catch (InterruptedException e) {
                throw new RuntimeException("Failed to handle Shell command in 2s", e);
            }
        }

        @Override
        public void onConfigurationChanged(Configuration newConfiguration) {
            mMainExecutor.execute(() ->
                    ShellController.this.onConfigurationChanged(newConfiguration));
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
                boolean animatingDismiss) {
            mMainExecutor.execute(() ->
                    ShellController.this.onKeyguardVisibilityChanged(visible, occluded,
                            animatingDismiss));
        }

        @Override
        public void onKeyguardDismissAnimationFinished() {
            mMainExecutor.execute(() ->
                    ShellController.this.onKeyguardDismissAnimationFinished());
        }

        @Override
        public void onUserChanged(int newUserId, @NonNull Context userContext) {
            mMainExecutor.execute(() ->
                    ShellController.this.onUserChanged(newUserId, userContext));
        }

        @Override
        public void onUserProfilesChanged(@NonNull List<UserInfo> profiles) {
            mMainExecutor.execute(() ->
                    ShellController.this.onUserProfilesChanged(profiles));
        }
    }
}
