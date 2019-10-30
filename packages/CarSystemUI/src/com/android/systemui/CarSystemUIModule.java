/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import static com.android.systemui.Dependency.ALLOW_NOTIFICATION_LONG_PRESS_NAME;
import static com.android.systemui.Dependency.LEAK_REPORT_EMAIL_NAME;

import android.content.Context;

import com.android.systemui.car.CarNotificationEntryManager;
import com.android.systemui.car.CarNotificationInterruptionStateProvider;
import com.android.systemui.dagger.SystemUIRootComponent;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerImpl;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.power.EnhancedEstimatesImpl;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl;
import com.android.systemui.statusbar.car.CarStatusBar;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.phone.KeyguardEnvironmentImpl;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.volume.CarVolumeDialogComponent;
import com.android.systemui.volume.VolumeDialogComponent;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

@Module
abstract class CarSystemUIModule {

    @Binds
    abstract NotificationInterruptionStateProvider bindNotificationInterruptionStateProvider(
            CarNotificationInterruptionStateProvider notificationInterruptionStateProvider);

    @Singleton
    @Provides
    @Named(ALLOW_NOTIFICATION_LONG_PRESS_NAME)
    static boolean provideAllowNotificationLongPress() {
        return false;
    }

    /**
     * Use {@link CarNotificationEntryManager}, which does nothing when adding a notification.
     */
    @Binds
    abstract NotificationEntryManager bindNotificationEntryManager(
            CarNotificationEntryManager notificationEntryManager);

    @Singleton
    @Provides
    @Named(LEAK_REPORT_EMAIL_NAME)
    static String provideLeakReportEmail() {
        return "buganizer-system+181579@google.com";
    }

    @Binds
    abstract EnhancedEstimates bindEnhancedEstimates(EnhancedEstimatesImpl enhancedEstimates);

    @Binds
    abstract NotificationLockscreenUserManager bindNotificationLockscreenUserManager(
            NotificationLockscreenUserManagerImpl notificationLockscreenUserManager);

    @Binds
    abstract DockManager bindDockManager(DockManagerImpl dockManager);

    @Binds
    abstract NotificationData.KeyguardEnvironment bindKeyguardEnvironment(
            KeyguardEnvironmentImpl keyguardEnvironment);

    @Singleton
    @Provides
    static ShadeController provideShadeController(Context context) {
        return SysUiServiceProvider.getComponent(context, StatusBar.class);
    }

    @Binds
    abstract SystemUIRootComponent bindSystemUIRootComponent(
            CarSystemUIRootComponent systemUIRootComponent);

    @Binds
    public abstract StatusBar bindStatusBar(CarStatusBar statusBar);

    @Binds
    abstract VolumeDialogComponent bindVolumeDialogComponent(
            CarVolumeDialogComponent carVolumeDialogComponent);
}
