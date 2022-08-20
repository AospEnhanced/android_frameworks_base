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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.window.TaskFragmentOrganizer.putErrorInfoInBundle;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.TaskFragment.EMBEDDING_ALLOWED;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.RemoteAnimationDefinition;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentTransaction;

import com.android.internal.protolog.common.ProtoLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores and manages the client {@link android.window.TaskFragmentOrganizer}.
 */
public class TaskFragmentOrganizerController extends ITaskFragmentOrganizerController.Stub {
    private static final String TAG = "TaskFragmentOrganizerController";
    private static final long TEMPORARY_ACTIVITY_TOKEN_TIMEOUT_MS = 5000;

    private final ActivityTaskManagerService mAtmService;
    private final WindowManagerGlobalLock mGlobalLock;
    /**
     * A Map which manages the relationship between
     * {@link ITaskFragmentOrganizer} and {@link TaskFragmentOrganizerState}
     */
    private final ArrayMap<IBinder, TaskFragmentOrganizerState> mTaskFragmentOrganizerState =
            new ArrayMap<>();
    /**
     * Map from {@link ITaskFragmentOrganizer} to a list of related {@link PendingTaskFragmentEvent}
     */
    private final ArrayMap<IBinder, List<PendingTaskFragmentEvent>> mPendingTaskFragmentEvents =
            new ArrayMap<>();

    private final ArraySet<Task> mTmpTaskSet = new ArraySet<>();

    TaskFragmentOrganizerController(ActivityTaskManagerService atm) {
        mAtmService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    /**
     * A class to manage {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments}.
     */
    private class TaskFragmentOrganizerState implements IBinder.DeathRecipient {
        private final ArrayList<TaskFragment> mOrganizedTaskFragments = new ArrayList<>();
        private final ITaskFragmentOrganizer mOrganizer;
        private final int mOrganizerPid;
        private final int mOrganizerUid;

        /**
         * Map from {@link TaskFragment} to the last {@link TaskFragmentInfo} sent to the
         * organizer.
         */
        private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
                new WeakHashMap<>();

        /**
         * Map from {@link TaskFragment} to its leaf {@link Task#mTaskId}. Embedded
         * {@link TaskFragment} will not be reparented until it is removed.
         */
        private final Map<TaskFragment, Integer> mTaskFragmentTaskIds = new WeakHashMap<>();

        /**
         * Map from {@link Task#mTaskId} to the last Task {@link Configuration} sent to the
         * organizer.
         */
        private final SparseArray<Configuration> mLastSentTaskFragmentParentConfigs =
                new SparseArray<>();

        /**
         * Map from temporary activity token to the corresponding {@link ActivityRecord}.
         */
        private final Map<IBinder, ActivityRecord> mTemporaryActivityTokens =
                new WeakHashMap<>();

        /**
         * Map from Task Id to {@link RemoteAnimationDefinition}.
         * @see android.window.TaskFragmentOrganizer#registerRemoteAnimations(int,
         * RemoteAnimationDefinition) )
         */
        private final SparseArray<RemoteAnimationDefinition> mRemoteAnimationDefinitions =
                new SparseArray<>();

        TaskFragmentOrganizerState(ITaskFragmentOrganizer organizer, int pid, int uid) {
            mOrganizer = organizer;
            mOrganizerPid = pid;
            mOrganizerUid = uid;
            try {
                mOrganizer.asBinder().linkToDeath(this, 0 /*flags*/);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskFragmentOrganizer failed to register death recipient");
            }
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                removeOrganizer(mOrganizer);
            }
        }

        /**
         * @return {@code true} if taskFragment is organized and not sent the appeared event before.
         */
        boolean addTaskFragment(TaskFragment taskFragment) {
            if (taskFragment.mTaskFragmentAppearedSent) {
                return false;
            }
            if (mOrganizedTaskFragments.contains(taskFragment)) {
                return false;
            }
            mOrganizedTaskFragments.add(taskFragment);
            return true;
        }

        void removeTaskFragment(TaskFragment taskFragment) {
            mOrganizedTaskFragments.remove(taskFragment);
        }

        void dispose() {
            while (!mOrganizedTaskFragments.isEmpty()) {
                final TaskFragment taskFragment = mOrganizedTaskFragments.get(0);
                // Cleanup before remove to prevent it from sending any additional event, such as
                // #onTaskFragmentVanished, to the removed organizer.
                taskFragment.onTaskFragmentOrganizerRemoved();
                taskFragment.removeImmediately();
                mOrganizedTaskFragments.remove(taskFragment);
            }
            mOrganizer.asBinder().unlinkToDeath(this, 0 /*flags*/);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentAppeared(@NonNull TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final int taskId = tf.getTask().mTaskId;
            tf.mTaskFragmentAppearedSent = true;
            mLastSentTaskFragmentInfos.put(tf, info);
            mTaskFragmentTaskIds.put(tf, taskId);
            return new TaskFragmentTransaction.Change(
                    TYPE_TASK_FRAGMENT_APPEARED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(info)
                    .setTaskId(taskId);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentVanished(@NonNull TaskFragment tf) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
            tf.mTaskFragmentAppearedSent = false;
            mLastSentTaskFragmentInfos.remove(tf);

            // Cleanup TaskFragmentParentConfig if this is the last TaskFragment in the Task.
            final int taskId;
            if (mTaskFragmentTaskIds.containsKey(tf)) {
                taskId = mTaskFragmentTaskIds.remove(tf);
                if (!mTaskFragmentTaskIds.containsValue(taskId)) {
                    // No more TaskFragment in the Task.
                    mLastSentTaskFragmentParentConfigs.remove(taskId);
                }
            } else {
                // This can happen if the appeared wasn't sent before remove.
                taskId = INVALID_TASK_ID;
            }

            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_VANISHED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(tf.getTaskFragmentInfo())
                    .setTaskId(taskId);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareTaskFragmentInfoChanged(
                @NonNull TaskFragment tf) {
            // Check if the info is different from the last reported info.
            final TaskFragmentInfo info = tf.getTaskFragmentInfo();
            final TaskFragmentInfo lastInfo = mLastSentTaskFragmentInfos.get(tf);
            if (info.equalsForTaskFragmentOrganizer(lastInfo) && configurationsAreEqualForOrganizer(
                    info.getConfiguration(), lastInfo.getConfiguration())) {
                return null;
            }

            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment info changed name=%s",
                    tf.getName());
            mLastSentTaskFragmentInfos.put(tf, info);
            return new TaskFragmentTransaction.Change(
                    TYPE_TASK_FRAGMENT_INFO_CHANGED)
                    .setTaskFragmentToken(tf.getFragmentToken())
                    .setTaskFragmentInfo(info)
                    .setTaskId(tf.getTask().mTaskId);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareTaskFragmentParentInfoChanged(
                @NonNull Task task) {
            final int taskId = task.mTaskId;
            // Check if the parent info is different from the last reported parent info.
            final Configuration taskConfig = task.getConfiguration();
            final Configuration lastParentConfig = mLastSentTaskFragmentParentConfigs.get(taskId);
            if (configurationsAreEqualForOrganizer(taskConfig, lastParentConfig)
                    && taskConfig.windowConfiguration.getWindowingMode()
                    == lastParentConfig.windowConfiguration.getWindowingMode()) {
                return null;
            }

            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "TaskFragment parent info changed name=%s parentTaskId=%d",
                    task.getName(), taskId);
            mLastSentTaskFragmentParentConfigs.put(taskId, new Configuration(taskConfig));
            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED)
                    .setTaskId(taskId)
                    .setTaskConfiguration(taskConfig);
        }

        @NonNull
        TaskFragmentTransaction.Change prepareTaskFragmentError(
                @Nullable IBinder errorCallbackToken, @Nullable TaskFragment taskFragment,
                int opType, @NonNull Throwable exception) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Sending TaskFragment error exception=%s", exception.toString());
            final TaskFragmentInfo info =
                    taskFragment != null ? taskFragment.getTaskFragmentInfo() : null;
            final Bundle errorBundle = putErrorInfoInBundle(exception, info, opType);
            return new TaskFragmentTransaction.Change(TYPE_TASK_FRAGMENT_ERROR)
                    .setErrorCallbackToken(errorCallbackToken)
                    .setErrorBundle(errorBundle);
        }

        @Nullable
        TaskFragmentTransaction.Change prepareActivityReparentedToTask(
                @NonNull ActivityRecord activity) {
            if (activity.finishing) {
                Slog.d(TAG, "Reparent activity=" + activity.token + " is finishing");
                return null;
            }
            final Task task = activity.getTask();
            if (task == null || task.effectiveUid != mOrganizerUid) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not in a task belong to the organizer app.");
                return null;
            }
            if (task.isAllowedToEmbedActivity(activity, mOrganizerUid) != EMBEDDING_ALLOWED) {
                Slog.d(TAG, "Reparent activity=" + activity.token
                        + " is not allowed to be embedded.");
                return null;
            }

            final IBinder activityToken;
            if (activity.getPid() == mOrganizerPid) {
                // We only pass the actual token if the activity belongs to the organizer process.
                activityToken = activity.token;
            } else {
                // For security, we can't pass the actual token if the activity belongs to a
                // different process. In this case, we will pass a temporary token that organizer
                // can use to reparent through WindowContainerTransaction.
                activityToken = new Binder("TemporaryActivityToken");
                mTemporaryActivityTokens.put(activityToken, activity);
                final Runnable timeout = () -> {
                    synchronized (mGlobalLock) {
                        mTemporaryActivityTokens.remove(activityToken);
                    }
                };
                mAtmService.mWindowManager.mH.postDelayed(timeout,
                        TEMPORARY_ACTIVITY_TOKEN_TIMEOUT_MS);
            }
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Activity=%s reparent to taskId=%d",
                    activity.token, task.mTaskId);
            return new TaskFragmentTransaction.Change(TYPE_ACTIVITY_REPARENTED_TO_TASK)
                    .setTaskId(task.mTaskId)
                    .setActivityIntent(activity.intent)
                    .setActivityToken(activityToken);
        }
    }

    @Nullable
    ActivityRecord getReparentActivityFromTemporaryToken(
            @Nullable ITaskFragmentOrganizer organizer, @Nullable IBinder activityToken) {
        if (organizer == null || activityToken == null) {
            return null;
        }
        final TaskFragmentOrganizerState state = mTaskFragmentOrganizerState.get(
                organizer.asBinder());
        return state != null
                ? state.mTemporaryActivityTokens.remove(activityToken)
                : null;
    }

    @Override
    public void registerOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            if (mTaskFragmentOrganizerState.containsKey(organizer.asBinder())) {
                throw new IllegalStateException(
                        "Replacing existing organizer currently unsupported");
            }
            mTaskFragmentOrganizerState.put(organizer.asBinder(),
                    new TaskFragmentOrganizerState(organizer, pid, uid));
            mPendingTaskFragmentEvents.put(organizer.asBinder(), new ArrayList<>());
        }
    }

    @Override
    public void unregisterOrganizer(ITaskFragmentOrganizer organizer) {
        validateAndGetState(organizer);
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Unregister task fragment organizer=%s uid=%d pid=%d",
                        organizer.asBinder(), uid, pid);
                removeOrganizer(organizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void registerRemoteAnimations(ITaskFragmentOrganizer organizer, int taskId,
            RemoteAnimationDefinition definition) {
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                throw new IllegalStateException("The organizer hasn't been registered.");
            }
            if (organizerState.mRemoteAnimationDefinitions.contains(taskId)) {
                throw new IllegalStateException(
                        "The organizer has already registered remote animations="
                                + organizerState.mRemoteAnimationDefinitions.get(taskId)
                                + " for TaskId=" + taskId);
            }

            definition.setCallingPidUid(pid, uid);
            organizerState.mRemoteAnimationDefinitions.put(taskId, definition);
        }
    }

    @Override
    public void unregisterRemoteAnimations(ITaskFragmentOrganizer organizer, int taskId) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Unregister remote animations for organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            if (organizerState == null) {
                Slog.e(TAG, "The organizer hasn't been registered.");
                return;
            }

            organizerState.mRemoteAnimationDefinitions.remove(taskId);
        }
    }

    /**
     * Gets the {@link RemoteAnimationDefinition} set on the given organizer if exists. Returns
     * {@code null} if it doesn't, or if the organizer has activity(ies) embedded in untrusted mode.
     */
    @Nullable
    public RemoteAnimationDefinition getRemoteAnimationDefinition(
            @NonNull ITaskFragmentOrganizer organizer, int taskId) {
        synchronized (mGlobalLock) {
            final TaskFragmentOrganizerState organizerState =
                    mTaskFragmentOrganizerState.get(organizer.asBinder());
            return organizerState != null
                    ? organizerState.mRemoteAnimationDefinitions.get(taskId)
                    : null;
        }
    }

    int getTaskFragmentOrganizerUid(@NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        return state.mOrganizerUid;
    }

    void onTaskFragmentAppeared(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        if (taskFragment.getTask() == null) {
            Slog.w(TAG, "onTaskFragmentAppeared failed because it is not attached tf="
                    + taskFragment);
            return;
        }
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        if (!state.addTaskFragment(taskFragment)) {
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_APPEARED);
        if (pendingEvent == null) {
            addPendingEvent(new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_APPEARED, organizer)
                    .setTaskFragment(taskFragment)
                    .build());
        }
    }

    void onTaskFragmentInfoChanged(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        validateAndGetState(organizer);
        if (!taskFragment.mTaskFragmentAppearedSent) {
            // Skip if TaskFragment still not appeared.
            return;
        }
        PendingTaskFragmentEvent pendingEvent = getLastPendingLifecycleEvent(taskFragment);
        if (pendingEvent == null) {
            pendingEvent = new PendingTaskFragmentEvent.Builder(
                    PendingTaskFragmentEvent.EVENT_INFO_CHANGED, organizer)
                    .setTaskFragment(taskFragment)
                    .build();
        } else {
            if (pendingEvent.mEventType == PendingTaskFragmentEvent.EVENT_VANISHED) {
                // Skipped the info changed event if vanished event is pending.
                return;
            }
            // Remove and add for re-ordering.
            removePendingEvent(pendingEvent);
            // Reset the defer time when TaskFragment is changed, so that it can check again if
            // the event should be sent to the organizer, for example the TaskFragment may become
            // empty.
            pendingEvent.mDeferTime = 0;
        }
        addPendingEvent(pendingEvent);
    }

    void onTaskFragmentVanished(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragment taskFragment) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        final List<PendingTaskFragmentEvent> pendingEvents = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        // Remove any pending events since this TaskFragment is being removed.
        for (int i = pendingEvents.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = pendingEvents.get(i);
            if (taskFragment == event.mTaskFragment) {
                pendingEvents.remove(i);
            }
        }
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_VANISHED, organizer)
                .setTaskFragment(taskFragment)
                .build());
        state.removeTaskFragment(taskFragment);
    }

    void onTaskFragmentError(@NonNull ITaskFragmentOrganizer organizer,
            @Nullable IBinder errorCallbackToken, @Nullable TaskFragment taskFragment,
            int opType, @NonNull Throwable exception) {
        validateAndGetState(organizer);
        Slog.w(TAG, "onTaskFragmentError ", exception);
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ERROR, organizer)
                .setErrorCallbackToken(errorCallbackToken)
                .setTaskFragment(taskFragment)
                .setException(exception)
                .setOpType(opType)
                .build());
        // Make sure the error event will be dispatched if there are no other changes.
        mAtmService.mWindowManager.mWindowPlacerLocked.requestTraversal();
    }

    void onActivityReparentedToTask(@NonNull ActivityRecord activity) {
        final ITaskFragmentOrganizer organizer;
        if (activity.mLastTaskFragmentOrganizerBeforePip != null) {
            // If the activity is previously embedded in an organized TaskFragment.
            organizer = activity.mLastTaskFragmentOrganizerBeforePip;
        } else {
            // Find the topmost TaskFragmentOrganizer.
            final Task task = activity.getTask();
            final TaskFragment[] organizedTf = new TaskFragment[1];
            task.forAllLeafTaskFragments(tf -> {
                if (tf.isOrganizedTaskFragment()) {
                    organizedTf[0] = tf;
                    return true;
                }
                return false;
            });
            if (organizedTf[0] == null) {
                return;
            }
            organizer = organizedTf[0].getTaskFragmentOrganizer();
        }
        if (!mTaskFragmentOrganizerState.containsKey(organizer.asBinder())) {
            Slog.w(TAG, "The last TaskFragmentOrganizer no longer exists");
            return;
        }
        addPendingEvent(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENTED_TO_TASK, organizer)
                .setActivity(activity)
                .build());
    }

    private void addPendingEvent(@NonNull PendingTaskFragmentEvent event) {
        mPendingTaskFragmentEvents.get(event.mTaskFragmentOrg.asBinder()).add(event);
    }

    private void removePendingEvent(@NonNull PendingTaskFragmentEvent event) {
        mPendingTaskFragmentEvents.get(event.mTaskFragmentOrg.asBinder()).remove(event);
    }

    boolean isOrganizerRegistered(@NonNull ITaskFragmentOrganizer organizer) {
        return mTaskFragmentOrganizerState.containsKey(organizer.asBinder());
    }

    private void removeOrganizer(@NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state = validateAndGetState(organizer);
        // remove all of the children of the organized TaskFragment
        state.dispose();
        // Remove any pending event of this organizer.
        mPendingTaskFragmentEvents.remove(organizer.asBinder());
        mTaskFragmentOrganizerState.remove(organizer.asBinder());
    }

    /**
     * Makes sure that the organizer has been correctly registered to prevent any Sidecar
     * implementation from organizing {@link TaskFragment} without registering first. In such case,
     * we wouldn't register {@link DeathRecipient} for the organizer, and might not remove the
     * {@link TaskFragment} after the organizer process died.
     */
    @NonNull
    private TaskFragmentOrganizerState validateAndGetState(
            @NonNull ITaskFragmentOrganizer organizer) {
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(organizer.asBinder());
        if (state == null) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
        return state;
    }

    /**
     * A class to store {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments} with different pending event request.
     */
    private static class PendingTaskFragmentEvent {
        static final int EVENT_APPEARED = 0;
        static final int EVENT_VANISHED = 1;
        static final int EVENT_INFO_CHANGED = 2;
        static final int EVENT_PARENT_INFO_CHANGED = 3;
        static final int EVENT_ERROR = 4;
        static final int EVENT_ACTIVITY_REPARENTED_TO_TASK = 5;

        @IntDef(prefix = "EVENT_", value = {
                EVENT_APPEARED,
                EVENT_VANISHED,
                EVENT_INFO_CHANGED,
                EVENT_PARENT_INFO_CHANGED,
                EVENT_ERROR,
                EVENT_ACTIVITY_REPARENTED_TO_TASK
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface EventType {}

        @EventType
        private final int mEventType;
        private final ITaskFragmentOrganizer mTaskFragmentOrg;
        @Nullable
        private final TaskFragment mTaskFragment;
        @Nullable
        private final IBinder mErrorCallbackToken;
        @Nullable
        private final Throwable mException;
        @Nullable
        private final ActivityRecord mActivity;
        @Nullable
        private final Task mTask;
        // Set when the event is deferred due to the host task is invisible. The defer time will
        // be the last active time of the host task.
        private long mDeferTime;
        private int mOpType;

        private PendingTaskFragmentEvent(@EventType int eventType,
                ITaskFragmentOrganizer taskFragmentOrg,
                @Nullable TaskFragment taskFragment,
                @Nullable IBinder errorCallbackToken,
                @Nullable Throwable exception,
                @Nullable ActivityRecord activity,
                @Nullable Task task,
                int opType) {
            mEventType = eventType;
            mTaskFragmentOrg = taskFragmentOrg;
            mTaskFragment = taskFragment;
            mErrorCallbackToken = errorCallbackToken;
            mException = exception;
            mActivity = activity;
            mTask = task;
            mOpType = opType;
        }

        /**
         * @return {@code true} if the pending event is related with taskFragment created, vanished
         * and information changed.
         */
        boolean isLifecycleEvent() {
            switch (mEventType) {
                case EVENT_APPEARED:
                case EVENT_VANISHED:
                case EVENT_INFO_CHANGED:
                case EVENT_PARENT_INFO_CHANGED:
                    return true;
                default:
                    return false;
            }
        }

        private static class Builder {
            @EventType
            private final int mEventType;
            private final ITaskFragmentOrganizer mTaskFragmentOrg;
            @Nullable
            private TaskFragment mTaskFragment;
            @Nullable
            private IBinder mErrorCallbackToken;
            @Nullable
            private Throwable mException;
            @Nullable
            private ActivityRecord mActivity;
            @Nullable
            private Task mTask;
            private int mOpType;

            Builder(@EventType int eventType, @NonNull ITaskFragmentOrganizer taskFragmentOrg) {
                mEventType = eventType;
                mTaskFragmentOrg = requireNonNull(taskFragmentOrg);
            }

            Builder setTaskFragment(@Nullable TaskFragment taskFragment) {
                mTaskFragment = taskFragment;
                return this;
            }

            Builder setErrorCallbackToken(@Nullable IBinder errorCallbackToken) {
                mErrorCallbackToken = errorCallbackToken;
                return this;
            }

            Builder setException(@NonNull Throwable exception) {
                mException = requireNonNull(exception);
                return this;
            }

            Builder setActivity(@NonNull ActivityRecord activity) {
                mActivity = requireNonNull(activity);
                return this;
            }

            Builder setTask(@NonNull Task task) {
                mTask = requireNonNull(task);
                return this;
            }

            Builder setOpType(int opType) {
                mOpType = opType;
                return this;
            }

            PendingTaskFragmentEvent build() {
                return new PendingTaskFragmentEvent(mEventType, mTaskFragmentOrg, mTaskFragment,
                        mErrorCallbackToken, mException, mActivity, mTask, mOpType);
            }
        }
    }

    @Nullable
    private PendingTaskFragmentEvent getLastPendingLifecycleEvent(@NonNull TaskFragment tf) {
        final ITaskFragmentOrganizer organizer = tf.getTaskFragmentOrganizer();
        final List<PendingTaskFragmentEvent> events = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        for (int i = events.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = events.get(i);
            if (tf == event.mTaskFragment && event.isLifecycleEvent()) {
                return event;
            }
        }
        return null;
    }

    @Nullable
    private PendingTaskFragmentEvent getPendingTaskFragmentEvent(@NonNull TaskFragment taskFragment,
            int type) {
        final ITaskFragmentOrganizer organizer = taskFragment.getTaskFragmentOrganizer();
        final List<PendingTaskFragmentEvent> events = mPendingTaskFragmentEvents
                .get(organizer.asBinder());
        for (int i = events.size() - 1; i >= 0; i--) {
            final PendingTaskFragmentEvent event = events.get(i);
            if (taskFragment == event.mTaskFragment && type == event.mEventType) {
                return event;
            }
        }
        return null;
    }

    private boolean shouldSendEventWhenTaskInvisible(@NonNull PendingTaskFragmentEvent event) {
        if (event.mEventType == PendingTaskFragmentEvent.EVENT_ERROR) {
            return true;
        }

        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(event.mTaskFragmentOrg.asBinder());
        final TaskFragmentInfo lastInfo = state.mLastSentTaskFragmentInfos.get(event.mTaskFragment);
        final TaskFragmentInfo info = event.mTaskFragment.getTaskFragmentInfo();
        // Send an info changed callback if this event is for the last activities to finish in a
        // TaskFragment so that the {@link TaskFragmentOrganizer} can delete this TaskFragment.
        return event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED
                && lastInfo != null && lastInfo.hasRunningActivity() && info.isEmpty();
    }

    void dispatchPendingEvents() {
        if (mAtmService.mWindowManager.mWindowPlacerLocked.isLayoutDeferred()
                || mPendingTaskFragmentEvents.isEmpty()) {
            return;
        }
        final int organizerNum = mPendingTaskFragmentEvents.size();
        for (int i = 0; i < organizerNum; i++) {
            final ITaskFragmentOrganizer organizer = mTaskFragmentOrganizerState.get(
                    mPendingTaskFragmentEvents.keyAt(i)).mOrganizer;
            dispatchPendingEvents(organizer, mPendingTaskFragmentEvents.valueAt(i));
        }
    }

    void dispatchPendingEvents(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull List<PendingTaskFragmentEvent> pendingEvents) {
        if (pendingEvents.isEmpty()) {
            return;
        }

        final ArrayList<Task> visibleTasks = new ArrayList<>();
        final ArrayList<Task> invisibleTasks = new ArrayList<>();
        final ArrayList<PendingTaskFragmentEvent> candidateEvents = new ArrayList<>();
        for (int i = 0, n = pendingEvents.size(); i < n; i++) {
            final PendingTaskFragmentEvent event = pendingEvents.get(i);
            final Task task = event.mTaskFragment != null ? event.mTaskFragment.getTask() : null;
            if (task != null && (task.lastActiveTime <= event.mDeferTime
                    || !(isTaskVisible(task, visibleTasks, invisibleTasks)
                    || shouldSendEventWhenTaskInvisible(event)))) {
                // Defer sending events to the TaskFragment until the host task is active again.
                event.mDeferTime = task.lastActiveTime;
                continue;
            }
            candidateEvents.add(event);
        }
        final int numEvents = candidateEvents.size();
        if (numEvents == 0) {
            return;
        }

        mTmpTaskSet.clear();
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        for (int i = 0; i < numEvents; i++) {
            final PendingTaskFragmentEvent event = candidateEvents.get(i);
            if (event.mEventType == PendingTaskFragmentEvent.EVENT_APPEARED
                    || event.mEventType == PendingTaskFragmentEvent.EVENT_INFO_CHANGED) {
                final Task task = event.mTaskFragment.getTask();
                if (mTmpTaskSet.add(task)) {
                    // Make sure the organizer know about the Task config.
                    transaction.addChange(prepareChange(new PendingTaskFragmentEvent.Builder(
                            PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED, organizer)
                            .setTask(task)
                            .build()));
                }
            }
            transaction.addChange(prepareChange(event));
        }
        mTmpTaskSet.clear();
        dispatchTransactionInfo(organizer, transaction);
        pendingEvents.removeAll(candidateEvents);
    }

    private static boolean isTaskVisible(@NonNull Task task,
            @NonNull ArrayList<Task> knownVisibleTasks,
            @NonNull ArrayList<Task> knownInvisibleTasks) {
        if (knownVisibleTasks.contains(task)) {
            return true;
        }
        if (knownInvisibleTasks.contains(task)) {
            return false;
        }
        if (task.shouldBeVisible(null /* starting */)) {
            knownVisibleTasks.add(task);
            return true;
        } else {
            knownInvisibleTasks.add(task);
            return false;
        }
    }

    void dispatchPendingInfoChangedEvent(@NonNull TaskFragment taskFragment) {
        final PendingTaskFragmentEvent event = getPendingTaskFragmentEvent(taskFragment,
                PendingTaskFragmentEvent.EVENT_INFO_CHANGED);
        if (event == null) {
            return;
        }

        final ITaskFragmentOrganizer organizer = taskFragment.getTaskFragmentOrganizer();
        final TaskFragmentTransaction transaction = new TaskFragmentTransaction();
        // Make sure the organizer know about the Task config.
        transaction.addChange(prepareChange(new PendingTaskFragmentEvent.Builder(
                PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED, organizer)
                .setTask(taskFragment.getTask())
                .build()));
        transaction.addChange(prepareChange(event));
        dispatchTransactionInfo(event.mTaskFragmentOrg, transaction);
        mPendingTaskFragmentEvents.get(organizer.asBinder()).remove(event);
    }

    private void dispatchTransactionInfo(@NonNull ITaskFragmentOrganizer organizer,
            @NonNull TaskFragmentTransaction transaction) {
        if (transaction.isEmpty()) {
            return;
        }
        try {
            organizer.onTransactionReady(transaction);
        } catch (RemoteException e) {
            Slog.d(TAG, "Exception sending TaskFragmentTransaction", e);
        }
    }

    @Nullable
    private TaskFragmentTransaction.Change prepareChange(
            @NonNull PendingTaskFragmentEvent event) {
        final ITaskFragmentOrganizer taskFragmentOrg = event.mTaskFragmentOrg;
        final TaskFragment taskFragment = event.mTaskFragment;
        final TaskFragmentOrganizerState state =
                mTaskFragmentOrganizerState.get(taskFragmentOrg.asBinder());
        if (state == null) {
            return null;
        }
        switch (event.mEventType) {
            case PendingTaskFragmentEvent.EVENT_APPEARED:
                return state.prepareTaskFragmentAppeared(taskFragment);
            case PendingTaskFragmentEvent.EVENT_VANISHED:
                return state.prepareTaskFragmentVanished(taskFragment);
            case PendingTaskFragmentEvent.EVENT_INFO_CHANGED:
                return state.prepareTaskFragmentInfoChanged(taskFragment);
            case PendingTaskFragmentEvent.EVENT_PARENT_INFO_CHANGED:
                return state.prepareTaskFragmentParentInfoChanged(event.mTask);
            case PendingTaskFragmentEvent.EVENT_ERROR:
                return state.prepareTaskFragmentError(event.mErrorCallbackToken, taskFragment,
                        event.mOpType, event.mException);
            case PendingTaskFragmentEvent.EVENT_ACTIVITY_REPARENTED_TO_TASK:
                return state.prepareActivityReparentedToTask(event.mActivity);
            default:
                throw new IllegalArgumentException("Unknown TaskFragmentEvent=" + event.mEventType);
        }
    }

    // TODO(b/204399167): change to push the embedded state to the client side
    @Override
    public boolean isActivityEmbedded(IBinder activityToken) {
        synchronized (mGlobalLock) {
            final ActivityRecord activity = ActivityRecord.forTokenLocked(activityToken);
            if (activity == null) {
                return false;
            }
            final TaskFragment taskFragment = activity.getOrganizedTaskFragment();
            if (taskFragment == null) {
                return false;
            }
            final Task parentTask = taskFragment.getTask();
            if (parentTask != null) {
                final Rect taskBounds = parentTask.getBounds();
                final Rect taskFragBounds = taskFragment.getBounds();
                return !taskBounds.equals(taskFragBounds) && taskBounds.contains(taskFragBounds);
            }
            return false;
        }
    }
}
