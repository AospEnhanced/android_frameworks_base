/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS;

import android.app.ActivityManager;
import android.app.IAppTask;
import android.app.IApplicationThread;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

/**
 * An implementation of IAppTask, that allows an app to manage its own tasks via
 * {@link android.app.ActivityManager.AppTask}.  We keep track of the callingUid to ensure that
 * only the process that calls getAppTasks() can call the AppTask methods.
 */
class AppTaskImpl extends IAppTask.Stub {
    private static final String TAG = "AppTaskImpl";
    private ActivityTaskManagerService mService;

    private int mTaskId;
    private int mCallingUid;

    public AppTaskImpl(ActivityTaskManagerService service, int taskId, int callingUid) {
        mService = service;
        mTaskId = taskId;
        mCallingUid = callingUid;
    }

    private void checkCaller() {
        if (mCallingUid != Binder.getCallingUid()) {
            throw new SecurityException("Caller " + mCallingUid
                    + " does not match caller of getAppTasks(): " + Binder.getCallingUid());
        }
    }

    @Override
    public void finishAndRemoveTask() {
        checkCaller();

        synchronized (mService.mGlobalLock) {
            long origId = Binder.clearCallingIdentity();
            try {
                // We remove the task from recents to preserve backwards
                if (!mService.mStackSupervisor.removeTaskByIdLocked(mTaskId, false,
                        REMOVE_FROM_RECENTS, "finish-and-remove-task")) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public ActivityManager.RecentTaskInfo getTaskInfo() {
        checkCaller();

        synchronized (mService.mGlobalLock) {
            long origId = Binder.clearCallingIdentity();
            try {
                TaskRecord tr = mService.mRootActivityContainer.anyTaskForId(mTaskId,
                        MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
                if (tr == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                return mService.getRecentTasks().createRecentTaskInfo(tr,
                        true /* getTasksAllowed */);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void moveToFront(IApplicationThread appThread, String callingPackage) {
        checkCaller();
        // Will bring task to front if it already has a root activity.
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        if (!mService.isSameApp(callingUid, callingPackage)) {
            String msg = "Permission Denial: moveToFront() from pid="
                    + Binder.getCallingPid() + " as package " + callingPackage;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid, -1, -1,
                        "Move to front")) {
                    return;
                }
                WindowProcessController callerApp = null;
                if (appThread != null) {
                    callerApp = mService.getProcessController(appThread);
                }
                final ActivityStarter starter = mService.getActivityStartController().obtainStarter(
                        null /* intent */, "moveToFront");
                if (starter.shouldAbortBackgroundActivityStart(callingUid, callingPid,
                        callingPackage, -1, -1, callerApp, null, false, null, null)) {
                    if (!mService.isBackgroundActivityStartsEnabled()) {
                        return;
                    }
                }
                mService.mStackSupervisor.startActivityFromRecents(callingPid,
                        callingUid, mTaskId, null);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int startActivity(IBinder whoThread, String callingPackage,
            Intent intent, String resolvedType, Bundle bOptions) {
        checkCaller();

        int callingUser = UserHandle.getCallingUserId();
        TaskRecord tr;
        IApplicationThread appThread;
        synchronized (mService.mGlobalLock) {
            tr = mService.mRootActivityContainer.anyTaskForId(mTaskId,
                    MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
            if (tr == null) {
                throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
            }
            appThread = IApplicationThread.Stub.asInterface(whoThread);
            if (appThread == null) {
                throw new IllegalArgumentException("Bad app thread " + appThread);
            }
        }

        return mService.getActivityStartController().obtainStarter(intent, "AppTaskImpl")
                .setCaller(appThread)
                .setCallingPackage(callingPackage)
                .setResolvedType(resolvedType)
                .setActivityOptions(bOptions)
                .setMayWait(callingUser)
                .setInTask(tr)
                .execute();
    }

    @Override
    public void setExcludeFromRecents(boolean exclude) {
        checkCaller();

        synchronized (mService.mGlobalLock) {
            long origId = Binder.clearCallingIdentity();
            try {
                TaskRecord tr = mService.mRootActivityContainer.anyTaskForId(mTaskId,
                        MATCH_TASK_IN_STACKS_OR_RECENT_TASKS);
                if (tr == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                Intent intent = tr.getBaseIntent();
                if (exclude) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                } else {
                    intent.setFlags(intent.getFlags()
                            & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
