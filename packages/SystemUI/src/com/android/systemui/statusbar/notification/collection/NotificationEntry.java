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

package com.android.systemui.statusbar.notification.collection;

import static android.app.Notification.CATEGORY_ALARM;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.CATEGORY_EVENT;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_REMINDER;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.app.Person;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationContentInflater.InflationFlag;
import com.android.systemui.statusbar.notification.row.NotificationGuts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a notification that the system UI knows about
 *
 * Whenever the NotificationManager tells us about the existence of a new notification, we wrap it
 * in a NotificationEntry. Thus, every notification has an associated NotificationEntry, even if
 * that notification is never displayed to the user (for example, if it's filtered out for some
 * reason).
 *
 * Entries store information about the current state of the notification. Essentially:
 * anything that needs to persist or be modifiable even when the notification's views don't
 * exist. Any other state should be stored on the views/view controllers themselves.
 *
 * At the moment, there are many things here that shouldn't be and vice-versa. Hopefully we can
 * clean this up in the future.
 */
public final class NotificationEntry {
    private static final long LAUNCH_COOLDOWN = 2000;
    private static final long REMOTE_INPUT_COOLDOWN = 500;
    private static final long INITIALIZATION_DELAY = 400;
    private static final long NOT_LAUNCHED_YET = -LAUNCH_COOLDOWN;
    private static final int COLOR_INVALID = 1;
    public final String key;
    public StatusBarNotification notification;
    public NotificationChannel channel;
    public long lastAudiblyAlertedMs;
    public boolean noisy;
    public boolean ambient;
    public int importance;
    public StatusBarIconView icon;
    public StatusBarIconView expandedIcon;
    public StatusBarIconView centeredIcon;
    public StatusBarIconView aodIcon;
    private boolean interruption;
    public boolean autoRedacted; // whether the redacted notification was generated by us
    public int targetSdk;
    private long lastFullScreenIntentLaunchTime = NOT_LAUNCHED_YET;
    public CharSequence remoteInputText;
    public List<SnoozeCriterion> snoozeCriteria;
    public int userSentiment = NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
    /** Smart Actions provided by the NotificationAssistantService. */
    @NonNull
    public List<Notification.Action> systemGeneratedSmartActions = Collections.emptyList();
    /** Smart replies provided by the NotificationAssistantService. */
    @NonNull
    public CharSequence[] systemGeneratedSmartReplies = new CharSequence[0];

    /**
     * If {@link android.app.RemoteInput#getEditChoicesBeforeSending} is enabled, and the user is
     * currently editing a choice (smart reply), then this field contains the information about the
     * suggestion being edited. Otherwise <code>null</code>.
     */
    public EditedSuggestionInfo editedSuggestionInfo;

    @VisibleForTesting
    public int suppressedVisualEffects;
    public boolean suspended;

    private NotificationEntry parent; // our parent (if we're in a group)
    private ExpandableNotificationRow row; // the outer expanded view

    private int mCachedContrastColor = COLOR_INVALID;
    private int mCachedContrastColorIsFor = COLOR_INVALID;
    private InflationTask mRunningTask = null;
    private Throwable mDebugThrowable;
    public CharSequence remoteInputTextWhenReset;
    public long lastRemoteInputSent = NOT_LAUNCHED_YET;
    public ArraySet<Integer> mActiveAppOps = new ArraySet<>(3);
    public CharSequence headsUpStatusBarText;
    public CharSequence headsUpStatusBarTextPublic;

    private long initializationTime = -1;

    /**
     * Whether or not this row represents a system notification. Note that if this is
     * {@code null}, that means we were either unable to retrieve the info or have yet to
     * retrieve the info.
     */
    public Boolean mIsSystemNotification;

    /**
     * Has the user sent a reply through this Notification.
     */
    private boolean hasSentReply;

    /**
     * Whether this notification has been approved globally, at the app level, and at the channel
     * level for bubbling.
     */
    public boolean canBubble;

    /**
     * Whether this notification has changed in visual appearance since the previous post.
     * New notifications are  interruptive by default.
     */
    public boolean isVisuallyInterruptive;

    /**
     * Whether this notification is shown to the user as a high priority notification: visible on
     * the lock screen/status bar and in the top section in the shade.
     */
    private boolean mHighPriority;

    private boolean mIsTopBucket;

    private boolean mSensitive = true;
    private Runnable mOnSensitiveChangedListener;
    private boolean mAutoHeadsUp;
    private boolean mPulseSupressed;

    public NotificationEntry(StatusBarNotification n) {
        this(n, null);
    }

    public NotificationEntry(
            StatusBarNotification n,
            @Nullable NotificationListenerService.Ranking ranking) {
        this.key = n.getKey();
        this.notification = n;
        if (ranking != null) {
            populateFromRanking(ranking);
        }
    }

    public void populateFromRanking(@NonNull NotificationListenerService.Ranking ranking) {
        channel = ranking.getChannel();
        lastAudiblyAlertedMs = ranking.getLastAudiblyAlertedMillis();
        importance = ranking.getImportance();
        ambient = ranking.isAmbient();
        snoozeCriteria = ranking.getSnoozeCriteria();
        userSentiment = ranking.getUserSentiment();
        systemGeneratedSmartActions = ranking.getSmartActions() == null
                ? Collections.emptyList() : ranking.getSmartActions();
        systemGeneratedSmartReplies = ranking.getSmartReplies() == null
                ? new CharSequence[0]
                : ranking.getSmartReplies().toArray(new CharSequence[0]);
        suppressedVisualEffects = ranking.getSuppressedVisualEffects();
        suspended = ranking.isSuspended();
        canBubble = ranking.canBubble();
        isVisuallyInterruptive = ranking.visuallyInterruptive();
    }

    public void setInterruption() {
        interruption = true;
    }

    public boolean hasInterrupted() {
        return interruption;
    }

    public boolean isHighPriority() {
        return mHighPriority;
    }

    public void setIsHighPriority(boolean highPriority) {
        this.mHighPriority = highPriority;
    }

    /**
     * @return True if the notif should appear in the "top" or "important" section of notifications
     * (as opposed to the "bottom" or "silent" section). This is usually the same as
     * {@link #isHighPriority()}, but there are certain exceptions, such as media notifs.
     */
    public boolean isTopBucket() {
        return mIsTopBucket;
    }
    public void setIsTopBucket(boolean isTopBucket) {
        mIsTopBucket = isTopBucket;
    }

    public boolean isBubble() {
        return (notification.getNotification().flags & FLAG_BUBBLE) != 0;
    }

    /**
     * Returns the data needed for a bubble for this notification, if it exists.
     */
    public Notification.BubbleMetadata getBubbleMetadata() {
        return notification.getNotification().getBubbleMetadata();
    }

    /**
     * Resets the notification entry to be re-used.
     */
    public void reset() {
        if (row != null) {
            row.reset();
        }
    }

    public ExpandableNotificationRow getRow() {
        return row;
    }

    //TODO: This will go away when we have a way to bind an entry to a row
    public void setRow(ExpandableNotificationRow row) {
        this.row = row;
    }

    @Nullable
    public List<NotificationEntry> getChildren() {
        if (row == null) {
            return null;
        }

        List<ExpandableNotificationRow> rowChildren = row.getNotificationChildren();
        if (rowChildren == null) {
            return null;
        }

        ArrayList<NotificationEntry> children = new ArrayList<>();
        for (ExpandableNotificationRow child : rowChildren) {
            children.add(child.getEntry());
        }

        return children;
    }

    public void notifyFullScreenIntentLaunched() {
        setInterruption();
        lastFullScreenIntentLaunchTime = SystemClock.elapsedRealtime();
    }

    public boolean hasJustLaunchedFullScreenIntent() {
        return SystemClock.elapsedRealtime() < lastFullScreenIntentLaunchTime + LAUNCH_COOLDOWN;
    }

    public boolean hasJustSentRemoteInput() {
        return SystemClock.elapsedRealtime() < lastRemoteInputSent + REMOTE_INPUT_COOLDOWN;
    }

    public boolean hasFinishedInitialization() {
        return initializationTime == -1
                || SystemClock.elapsedRealtime() > initializationTime + INITIALIZATION_DELAY;
    }

    /**
     * Create the icons for a notification
     * @param context the context to create the icons with
     * @param sbn the notification
     * @throws InflationException Exception if required icons are not valid or specified
     */
    public void createIcons(Context context, StatusBarNotification sbn)
            throws InflationException {
        Notification n = sbn.getNotification();
        final Icon smallIcon = n.getSmallIcon();
        if (smallIcon == null) {
            throw new InflationException("No small icon in notification from "
                    + sbn.getPackageName());
        }

        // Construct the icon.
        icon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Construct the expanded icon.
        expandedIcon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        expandedIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Construct the expanded icon.
        aodIcon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        aodIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        aodIcon.setIncreasedSize(true);

        final StatusBarIcon ic = new StatusBarIcon(
                sbn.getUser(),
                sbn.getPackageName(),
                smallIcon,
                n.iconLevel,
                n.number,
                StatusBarIconView.contentDescForNotification(context, n));

        if (!icon.set(ic) || !expandedIcon.set(ic) || !aodIcon.set(ic)) {
            icon = null;
            expandedIcon = null;
            centeredIcon = null;
            aodIcon = null;
            throw new InflationException("Couldn't create icon: " + ic);
        }
        expandedIcon.setVisibility(View.INVISIBLE);
        expandedIcon.setOnVisibilityChangedListener(
                newVisibility -> {
                    if (row != null) {
                        row.setIconsVisible(newVisibility != View.VISIBLE);
                    }
                });

        // Construct the centered icon
        if (notification.getNotification().isMediaNotification()) {
            centeredIcon = new StatusBarIconView(context,
                    sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
            centeredIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            if (!centeredIcon.set(ic)) {
                centeredIcon = null;
                throw new InflationException("Couldn't update centered icon: " + ic);
            }
        }
    }

    public void setIconTag(int key, Object tag) {
        if (icon != null) {
            icon.setTag(key, tag);
            expandedIcon.setTag(key, tag);
        }

        if (centeredIcon != null) {
            centeredIcon.setTag(key, tag);
        }

        if (aodIcon != null) {
            aodIcon.setTag(key, tag);
        }
    }

    /**
     * Update the notification icons.
     *
     * @param context the context to create the icons with.
     * @param sbn the notification to read the icon from.
     * @throws InflationException Exception if required icons are not valid or specified
     */
    public void updateIcons(Context context, StatusBarNotification sbn)
            throws InflationException {
        if (icon != null) {
            // Update the icon
            Notification n = sbn.getNotification();
            final StatusBarIcon ic = new StatusBarIcon(
                    notification.getUser(),
                    notification.getPackageName(),
                    n.getSmallIcon(),
                    n.iconLevel,
                    n.number,
                    StatusBarIconView.contentDescForNotification(context, n));
            icon.setNotification(sbn);
            expandedIcon.setNotification(sbn);
            aodIcon.setNotification(sbn);
            if (!icon.set(ic) || !expandedIcon.set(ic) || !aodIcon.set(ic)) {
                throw new InflationException("Couldn't update icon: " + ic);
            }

            if (centeredIcon != null) {
                centeredIcon.setNotification(sbn);
                if (!centeredIcon.set(ic)) {
                    throw new InflationException("Couldn't update centered icon: " + ic);
                }
            }
        }
    }

    public int getContrastedColor(Context context, boolean isLowPriority,
            int backgroundColor) {
        int rawColor = isLowPriority ? Notification.COLOR_DEFAULT :
                notification.getNotification().color;
        if (mCachedContrastColorIsFor == rawColor && mCachedContrastColor != COLOR_INVALID) {
            return mCachedContrastColor;
        }
        final int contrasted = ContrastColorUtil.resolveContrastColor(context, rawColor,
                backgroundColor);
        mCachedContrastColorIsFor = rawColor;
        mCachedContrastColor = contrasted;
        return mCachedContrastColor;
    }

    /**
     * Abort all existing inflation tasks
     */
    public void abortTask() {
        if (mRunningTask != null) {
            mRunningTask.abort();
            mRunningTask = null;
        }
    }

    public void setInflationTask(InflationTask abortableTask) {
        // abort any existing inflation
        InflationTask existing = mRunningTask;
        abortTask();
        mRunningTask = abortableTask;
        if (existing != null && mRunningTask != null) {
            mRunningTask.supersedeTask(existing);
        }
    }

    public void onInflationTaskFinished() {
        mRunningTask = null;
    }

    @VisibleForTesting
    public InflationTask getRunningTask() {
        return mRunningTask;
    }

    /**
     * Set a throwable that is used for debugging
     *
     * @param debugThrowable the throwable to save
     */
    public void setDebugThrowable(Throwable debugThrowable) {
        mDebugThrowable = debugThrowable;
    }

    public Throwable getDebugThrowable() {
        return mDebugThrowable;
    }

    public void onRemoteInputInserted() {
        lastRemoteInputSent = NOT_LAUNCHED_YET;
        remoteInputTextWhenReset = null;
    }

    public void setHasSentReply() {
        hasSentReply = true;
    }

    public boolean isLastMessageFromReply() {
        if (!hasSentReply) {
            return false;
        }
        Bundle extras = notification.getNotification().extras;
        CharSequence[] replyTexts = extras.getCharSequenceArray(
                Notification.EXTRA_REMOTE_INPUT_HISTORY);
        if (!ArrayUtils.isEmpty(replyTexts)) {
            return true;
        }
        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0) {
            Parcelable message = messages[messages.length - 1];
            if (message instanceof Bundle) {
                Notification.MessagingStyle.Message lastMessage =
                        Notification.MessagingStyle.Message.getMessageFromBundle(
                                (Bundle) message);
                if (lastMessage != null) {
                    Person senderPerson = lastMessage.getSenderPerson();
                    if (senderPerson == null) {
                        return true;
                    }
                    Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
                    return Objects.equals(user, senderPerson);
                }
            }
        }
        return false;
    }

    public void setInitializationTime(long time) {
        if (initializationTime == -1) {
            initializationTime = time;
        }
    }

    public void sendAccessibilityEvent(int eventType) {
        if (row != null) {
            row.sendAccessibilityEvent(eventType);
        }
    }

    /**
     * Used by NotificationMediaManager to determine... things
     * @return {@code true} if we are a media notification
     */
    public boolean isMediaNotification() {
        if (row == null) return false;

        return row.isMediaRow();
    }

    /**
     * We are a top level child if our parent is the list of notifications duh
     * @return {@code true} if we're a top level notification
     */
    public boolean isTopLevelChild() {
        return row != null && row.isTopLevelChild();
    }

    public void resetUserExpansion() {
        if (row != null) row.resetUserExpansion();
    }

    public void freeContentViewWhenSafe(@InflationFlag int inflationFlag) {
        if (row != null) row.freeContentViewWhenSafe(inflationFlag);
    }

    public boolean rowExists() {
        return row != null;
    }

    public boolean isRowDismissed() {
        return row != null && row.isDismissed();
    }

    public boolean isRowRemoved() {
        return row != null && row.isRemoved();
    }

    /**
     * @return {@code true} if the row is null or removed
     */
    public boolean isRemoved() {
        //TODO: recycling invalidates this
        return row == null || row.isRemoved();
    }

    public boolean isRowPinned() {
        return row != null && row.isPinned();
    }

    public void setRowPinned(boolean pinned) {
        if (row != null) row.setPinned(pinned);
    }

    public boolean isRowHeadsUp() {
        return row != null && row.isHeadsUp();
    }

    public boolean showingPulsing() {
        return row != null && row.showingPulsing();
    }

    public void setHeadsUp(boolean shouldHeadsUp) {
        if (row != null) row.setHeadsUp(shouldHeadsUp);
    }

    public void setHeadsUpAnimatingAway(boolean animatingAway) {
        if (row != null) row.setHeadsUpAnimatingAway(animatingAway);
    }

    /**
     * Set that this notification was automatically heads upped. This happens for example when
     * the user bypasses the lockscreen and media is playing.
     */
    public void setAutoHeadsUp(boolean autoHeadsUp) {
        mAutoHeadsUp = autoHeadsUp;
    }

    /**
     * @return if this notification was automatically heads upped. This happens for example when
     *      * the user bypasses the lockscreen and media is playing.
     */
    public boolean isAutoHeadsUp() {
        return mAutoHeadsUp;
    }

    public boolean mustStayOnScreen() {
        return row != null && row.mustStayOnScreen();
    }

    public void setHeadsUpIsVisible() {
        if (row != null) row.setHeadsUpIsVisible();
    }

    //TODO: i'm imagining a world where this isn't just the row, but I could be rwong
    public ExpandableNotificationRow getHeadsUpAnimationView() {
        return row;
    }

    public void setUserLocked(boolean userLocked) {
        if (row != null) row.setUserLocked(userLocked);
    }

    public void setUserExpanded(boolean userExpanded, boolean allowChildExpansion) {
        if (row != null) row.setUserExpanded(userExpanded, allowChildExpansion);
    }

    public void setGroupExpansionChanging(boolean changing) {
        if (row != null) row.setGroupExpansionChanging(changing);
    }

    public void notifyHeightChanged(boolean needsAnimation) {
        if (row != null) row.notifyHeightChanged(needsAnimation);
    }

    public void closeRemoteInput() {
        if (row != null) row.closeRemoteInput();
    }

    public boolean areChildrenExpanded() {
        return row != null && row.areChildrenExpanded();
    }

    public boolean keepInParent() {
        return row != null && row.keepInParent();
    }

    //TODO: probably less confusing to say "is group fully visible"
    public boolean isGroupNotFullyVisible() {
        return row == null || row.isGroupNotFullyVisible();
    }

    public NotificationGuts getGuts() {
        if (row != null) return row.getGuts();
        return null;
    }

    public void removeRow() {
        if (row != null) row.setRemoved();
    }

    public boolean isSummaryWithChildren() {
        return row != null && row.isSummaryWithChildren();
    }

    public void setKeepInParent(boolean keep) {
        if (row != null) row.setKeepInParent(keep);
    }

    public void onDensityOrFontScaleChanged() {
        if (row != null) row.onDensityOrFontScaleChanged();
    }

    public boolean areGutsExposed() {
        return row != null && row.getGuts() != null && row.getGuts().isExposed();
    }

    public boolean isChildInGroup() {
        return parent == null;
    }

    /**
     * @return Can the underlying notification be cleared? This can be different from whether the
     *         notification can be dismissed in case notifications are sensitive on the lockscreen.
     * @see #canViewBeDismissed()
     */
    public boolean isClearable() {
        if (notification == null || !notification.isClearable()) {
            return false;
        }

        List<NotificationEntry> children = getChildren();
        if (children != null && children.size() > 0) {
            for (int i = 0; i < children.size(); i++) {
                NotificationEntry child =  children.get(i);
                if (!child.isClearable()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean canViewBeDismissed() {
        if (row == null) return true;
        return row.canViewBeDismissed();
    }

    @VisibleForTesting
    boolean isExemptFromDndVisualSuppression() {
        if (isNotificationBlockedByPolicy(notification.getNotification())) {
            return false;
        }

        if ((notification.getNotification().flags
                & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return true;
        }
        if (notification.getNotification().isMediaNotification()) {
            return true;
        }
        if (mIsSystemNotification != null && mIsSystemNotification) {
            return true;
        }
        return false;
    }

    private boolean shouldSuppressVisualEffect(int effect) {
        if (isExemptFromDndVisualSuppression()) {
            return false;
        }
        return (suppressedVisualEffects & effect) != 0;
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_FULL_SCREEN_INTENT}
     * is set for this entry.
     */
    public boolean shouldSuppressFullScreenIntent() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_PEEK}
     * is set for this entry.
     */
    public boolean shouldSuppressPeek() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_PEEK);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_STATUS_BAR}
     * is set for this entry.
     */
    public boolean shouldSuppressStatusBar() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_STATUS_BAR);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_AMBIENT}
     * is set for this entry.
     */
    public boolean shouldSuppressAmbient() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_AMBIENT);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_NOTIFICATION_LIST}
     * is set for this entry.
     */
    public boolean shouldSuppressNotificationList() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_NOTIFICATION_LIST);
    }


    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_BADGE}
     * is set for this entry. This badge is not an app badge, but rather an indicator of "unseen"
     * content. Typically this is referred to as a "dot" internally in Launcher & SysUI code.
     */
    public boolean shouldSuppressNotificationDot() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_BADGE);
    }

    /**
     * Categories that are explicitly called out on DND settings screens are always blocked, if
     * DND has flagged them, even if they are foreground or system notifications that might
     * otherwise visually bypass DND.
     */
    private static boolean isNotificationBlockedByPolicy(Notification n) {
        return isCategory(CATEGORY_CALL, n)
                || isCategory(CATEGORY_MESSAGE, n)
                || isCategory(CATEGORY_ALARM, n)
                || isCategory(CATEGORY_EVENT, n)
                || isCategory(CATEGORY_REMINDER, n);
    }

    private static boolean isCategory(String category, Notification n) {
        return Objects.equals(n.category, category);
    }

    /**
     * Set this notification to be sensitive.
     *
     * @param sensitive true if the content of this notification is sensitive right now
     * @param deviceSensitive true if the device in general is sensitive right now
     */
    public void setSensitive(boolean sensitive, boolean deviceSensitive) {
        getRow().setSensitive(sensitive, deviceSensitive);
        if (sensitive != mSensitive) {
            mSensitive = sensitive;
            if (mOnSensitiveChangedListener != null) {
                mOnSensitiveChangedListener.run();
            }
        }
    }

    public boolean isSensitive() {
        return mSensitive;
    }

    public void setOnSensitiveChangedListener(Runnable listener) {
        mOnSensitiveChangedListener = listener;
    }

    public boolean isPulseSuppressed() {
        return mPulseSupressed;
    }

    public void setPulseSuppressed(boolean suppressed) {
        mPulseSupressed = suppressed;
    }

    /** Information about a suggestion that is being edited. */
    public static class EditedSuggestionInfo {

        /**
         * The value of the suggestion (before any user edits).
         */
        public final CharSequence originalText;

        /**
         * The index of the suggestion that is being edited.
         */
        public final int index;

        public EditedSuggestionInfo(CharSequence originalText, int index) {
            this.originalText = originalText;
            this.index = index;
        }
    }
}
