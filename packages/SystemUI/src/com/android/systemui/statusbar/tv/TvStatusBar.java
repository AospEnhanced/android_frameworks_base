/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.tv;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.IBinder;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.view.View;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.tv.pip.PipManager;

/**
 * Status bar implementation for "large screen" products that mostly present no on-screen nav
 */

public class TvStatusBar extends BaseStatusBar {

    @Override
    public void setIcon(String slot, StatusBarIcon icon) {
    }

    @Override
    public void removeIcon(String slot) {
    }

    @Override
    public void addNotification(StatusBarNotification notification, RankingMap ranking,
            NotificationData.Entry entry) {
    }

    @Override
    protected void updateNotificationRanking(RankingMap ranking) {
    }

    @Override
    public void removeNotification(String key, RankingMap ranking) {
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
    }

    @Override
    public void animateExpandNotificationsPanel() {
    }

    @Override
    public void animateCollapsePanels(int flags) {
    }

    @Override
    public void setSystemUiVisibility(int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, Rect fullscreenStackBounds, Rect dockedStackBounds) {
    }

    @Override
    public void topAppWindowChanged(boolean visible) {
    }

    @Override
    public void setImeWindowStatus(IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
    }

    @Override // CommandQueue
    public void setWindowState(int window, int state) {
    }

    @Override // CommandQueue
    public void buzzBeepBlinked() {
    }

    @Override // CommandQueue
    public void notificationLightOff() {
    }

    @Override // CommandQueue
    public void notificationLightPulse(int argb, int onMillis, int offMillis) {
    }

    @Override
    protected void setAreThereNotifications() {
    }

    @Override
    protected void updateNotifications() {
    }

    @Override
    public boolean shouldDisableNavbarGestures() {
        return true;
    }

    public View getStatusBarView() {
        return null;
    }

    @Override
    protected void toggleSplitScreenMode(int metricsDockAction, int metricsUndockAction) {
    }

    @Override
    public void maybeEscalateHeadsUp() {
    }

    @Override
    public boolean isPanelFullyCollapsed() {
        return false;
    }

    @Override
    protected int getMaxKeyguardNotifications(boolean recompute) {
        return 0;
    }

    @Override
    public void animateExpandSettingsPanel(String subPanel) {
    }

    @Override
    protected void createAndAddWindows() {
    }

    @Override
    protected void refreshLayout(int layoutDirection) {
    }

    @Override
    public void onActivated(ActivatableNotificationView view) {
    }

    @Override
    public void onActivationReset(ActivatableNotificationView view) {
    }

    @Override
    public void showScreenPinningRequest(int taskId) {
    }

    @Override
    public void appTransitionPending() {
    }

    @Override
    public void appTransitionCancelled() {
    }

    @Override
    public void appTransitionStarting(long startTime, long duration) {
    }

    @Override
    public void appTransitionFinished() {
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
    }

    @Override
    public void showTvPictureInPictureMenu() {
        PipManager.getInstance().showTvPictureInPictureMenu();
    }

    @Override
    protected void updateHeadsUp(String key, NotificationData.Entry entry, boolean shouldPeek,
            boolean alertAgain) {
    }

    @Override
    protected void setHeadsUpUser(int newUserId) {
    }

    protected boolean isSnoozedPackage(StatusBarNotification sbn) {
        return false;
    }

    @Override
    public void addQsTile(ComponentName tile) {
    }

    @Override
    public void remQsTile(ComponentName tile) {
    }

    @Override
    public void clickTile(ComponentName tile) {
    }

    @Override
    public void start() {
        super.start();
        putComponent(TvStatusBar.class, this);
    }

    @Override
    public void handleSystemNavigationKey(int arg1) {
        // Not implemented
    }
}
