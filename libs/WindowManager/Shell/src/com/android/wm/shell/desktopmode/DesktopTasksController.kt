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

package com.android.wm.shell.desktopmode

import android.app.ActivityManager
import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_HOME
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemProperties
import android.view.SurfaceControl
import android.view.WindowManager.TRANSIT_CHANGE
import android.view.WindowManager.TRANSIT_NONE
import android.view.WindowManager.TRANSIT_OPEN
import android.view.WindowManager.TRANSIT_TO_FRONT
import android.window.TransitionInfo
import android.window.TransitionRequestInfo
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction
import androidx.annotation.BinderThread
import com.android.internal.protolog.common.ProtoLog
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.ExecutorUtils
import com.android.wm.shell.common.ExternalInterfaceBinder
import com.android.wm.shell.common.RemoteCallable
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.annotations.ExternalThread
import com.android.wm.shell.common.annotations.ShellMainThread
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository.VisibleTasksListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.sysui.ShellSharedConstants
import com.android.wm.shell.transition.Transitions
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Handles moving tasks in and out of desktop */
class DesktopTasksController(
        private val context: Context,
        shellInit: ShellInit,
        private val shellController: ShellController,
        private val displayController: DisplayController,
        private val shellTaskOrganizer: ShellTaskOrganizer,
        private val syncQueue: SyncTransactionQueue,
        private val rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
        private val transitions: Transitions,
        private val enterDesktopTaskTransitionHandler: EnterDesktopTaskTransitionHandler,
        private val exitDesktopTaskTransitionHandler: ExitDesktopTaskTransitionHandler,
        private val desktopModeTaskRepository: DesktopModeTaskRepository,
        @ShellMainThread private val mainExecutor: ShellExecutor
) : RemoteCallable<DesktopTasksController>, Transitions.TransitionHandler {

    private val desktopMode: DesktopModeImpl
    private var visualIndicator: DesktopModeVisualIndicator? = null

    init {
        desktopMode = DesktopModeImpl()
        if (DesktopModeStatus.isProto2Enabled()) {
            shellInit.addInitCallback({ onInit() }, this)
        }
    }

    private fun onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopTasksController")
        shellController.addExternalInterface(
            ShellSharedConstants.KEY_EXTRA_SHELL_DESKTOP_MODE,
            { createExternalInterface() },
            this
        )
        transitions.addHandler(this)
    }

    /** Show all tasks, that are part of the desktop, on top of launcher */
    fun showDesktopApps() {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "showDesktopApps")
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(wct)

        // Execute transaction if there are pending operations
        if (!wct.isEmpty) {
            if (Transitions.ENABLE_SHELL_TRANSITIONS) {
                // TODO(b/268662477): add animation for the transition
                transitions.startTransition(TRANSIT_NONE, wct, null /* handler */)
            } else {
                shellTaskOrganizer.applyTransaction(wct)
            }
        }
    }

    /** Get number of tasks that are marked as visible */
    fun getVisibleTaskCount(): Int {
        return desktopModeTaskRepository.getVisibleTaskCount()
    }

    /** Move a task with given `taskId` to desktop */
    fun moveToDesktop(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveToDesktop(task) }
    }

    /** Move a task to desktop */
    fun moveToDesktop(task: RunningTaskInfo) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToDesktop: %d", task.taskId)

        val wct = WindowContainerTransaction()
        // Bring other apps to front first
        bringDesktopAppsToFront(wct)
        addMoveToDesktopChanges(wct, task.token)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Moves a single task to freeform and sets the taskBounds to the passed in bounds,
     * startBounds
     */
    fun moveToFreeform(
            taskInfo: RunningTaskInfo,
            startBounds: Rect
    ) {
        val wct = WindowContainerTransaction()
        moveHomeTaskToFront(wct)
        addMoveToDesktopChanges(wct, taskInfo.getToken())
        wct.setBounds(taskInfo.token, startBounds)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startTransition(
                    Transitions.TRANSIT_ENTER_FREEFORM, wct)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Brings apps to front and sets freeform task bounds */
    fun moveToDesktopWithAnimation(
            taskInfo: RunningTaskInfo,
            freeformBounds: Rect
    ) {
        val wct = WindowContainerTransaction()
        bringDesktopAppsToFront(wct)
        addMoveToDesktopChanges(wct, taskInfo.getToken())
        wct.setBounds(taskInfo.token, freeformBounds)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startTransition(
                Transitions.TRANSIT_ENTER_DESKTOP_MODE, wct)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Move a task with given `taskId` to fullscreen */
    fun moveToFullscreen(taskId: Int) {
        shellTaskOrganizer.getRunningTaskInfo(taskId)?.let { task -> moveToFullscreen(task) }
    }

    /** Move a task to fullscreen */
    fun moveToFullscreen(task: RunningTaskInfo) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "moveToFullscreen: %d", task.taskId)

        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task.token)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_CHANGE, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }


    /**
     * Move a task to fullscreen after being dragged from fullscreen and released back into
     * status bar area
     */
    fun cancelMoveToFreeform(task: RunningTaskInfo, startPosition: Point) {
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task.token)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            enterDesktopTaskTransitionHandler.startCancelMoveToDesktopMode(wct, startPosition)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    fun moveToFullscreenWithAnimation(task: ActivityManager.RunningTaskInfo) {
        val wct = WindowContainerTransaction()
        addMoveToFullscreenChanges(wct, task.token)

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            exitDesktopTaskTransitionHandler.startTransition(
            Transitions.TRANSIT_EXIT_DESKTOP_MODE, wct)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /** Move a task to the front **/
    fun moveTaskToFront(taskInfo: RunningTaskInfo) {
        val wct = WindowContainerTransaction()
        wct.reorder(taskInfo.token, true)
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            transitions.startTransition(TRANSIT_TO_FRONT, wct, null /* handler */)
        } else {
            shellTaskOrganizer.applyTransaction(wct)
        }
    }

    /**
     * Get windowing move for a given `taskId`
     *
     * @return [WindowingMode] for the task or [WINDOWING_MODE_UNDEFINED] if task is not found
     */
    @WindowingMode
    fun getTaskWindowingMode(taskId: Int): Int {
        return shellTaskOrganizer.getRunningTaskInfo(taskId)?.windowingMode
            ?: WINDOWING_MODE_UNDEFINED
    }

    private fun bringDesktopAppsToFront(wct: WindowContainerTransaction) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "bringDesktopAppsToFront")
        val activeTasks = desktopModeTaskRepository.getActiveTasks()

        // First move home to front and then other tasks on top of it
        moveHomeTaskToFront(wct)

        val allTasksInZOrder = desktopModeTaskRepository.getFreeformTasksInZOrder()
        activeTasks
            // Sort descending as the top task is at index 0. It should be ordered to top last
            .sortedByDescending { taskId -> allTasksInZOrder.indexOf(taskId) }
            .mapNotNull { taskId -> shellTaskOrganizer.getRunningTaskInfo(taskId) }
            .forEach { task -> wct.reorder(task.token, true /* onTop */) }
    }

    private fun moveHomeTaskToFront(wct: WindowContainerTransaction) {
        shellTaskOrganizer
            .getRunningTasks(context.displayId)
            .firstOrNull { task -> task.activityType == ACTIVITY_TYPE_HOME }
            ?.let { homeTask -> wct.reorder(homeTask.getToken(), true /* onTop */) }
    }

    override fun getContext(): Context {
        return context
    }

    override fun getRemoteCallExecutor(): ShellExecutor {
        return mainExecutor
    }

    override fun startAnimation(
        transition: IBinder,
        info: TransitionInfo,
        startTransaction: SurfaceControl.Transaction,
        finishTransaction: SurfaceControl.Transaction,
        finishCallback: Transitions.TransitionFinishCallback
    ): Boolean {
        // This handler should never be the sole handler, so should not animate anything.
        return false
    }

    override fun handleRequest(
        transition: IBinder,
        request: TransitionRequestInfo
    ): WindowContainerTransaction? {
        // Check if we should skip handling this transition
        val task: RunningTaskInfo? = request.triggerTask
        val shouldHandleRequest =
            when {
                // Only handle open or to front transitions
                request.type != TRANSIT_OPEN && request.type != TRANSIT_TO_FRONT -> false
                // Only handle when it is a task transition
                task == null -> false
                // Only handle standard type tasks
                task.activityType != ACTIVITY_TYPE_STANDARD -> false
                // Only handle fullscreen or freeform tasks
                task.windowingMode != WINDOWING_MODE_FULLSCREEN &&
                    task.windowingMode != WINDOWING_MODE_FREEFORM -> false
                // Otherwise process it
                else -> true
            }

        if (!shouldHandleRequest) {
            return null
        }

        val activeTasks = desktopModeTaskRepository.getActiveTasks()

        // Check if we should switch a fullscreen task to freeform
        if (task?.windowingMode == WINDOWING_MODE_FULLSCREEN) {
            // If there are any visible desktop tasks, switch the task to freeform
            if (activeTasks.any { desktopModeTaskRepository.isVisibleTask(it) }) {
                ProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController#handleRequest: switch fullscreen task to freeform," +
                        " taskId=%d",
                    task.taskId
                )
                return WindowContainerTransaction().also { wct ->
                    addMoveToDesktopChanges(wct, task.token)
                }
            }
        }

        // CHeck if we should switch a freeform task to fullscreen
        if (task?.windowingMode == WINDOWING_MODE_FREEFORM) {
            // If no visible desktop tasks, switch this task to freeform as the transition came
            // outside of this controller
            if (activeTasks.none { desktopModeTaskRepository.isVisibleTask(it) }) {
                ProtoLog.d(
                    WM_SHELL_DESKTOP_MODE,
                    "DesktopTasksController#handleRequest: switch freeform task to fullscreen," +
                        " taskId=%d",
                    task.taskId
                )
                return WindowContainerTransaction().also { wct ->
                    addMoveToFullscreenChanges(wct, task.token)
                }
            }
        }
        return null
    }

    private fun addMoveToDesktopChanges(
        wct: WindowContainerTransaction,
        token: WindowContainerToken
    ) {
        wct.setWindowingMode(token, WINDOWING_MODE_FREEFORM)
        wct.reorder(token, true /* onTop */)
        if (isDesktopDensityOverrideSet()) {
            wct.setDensityDpi(token, getDesktopDensityDpi())
        }
    }

    private fun addMoveToFullscreenChanges(
        wct: WindowContainerTransaction,
        token: WindowContainerToken
    ) {
        wct.setWindowingMode(token, WINDOWING_MODE_FULLSCREEN)
        wct.setBounds(token, null)
        if (isDesktopDensityOverrideSet()) {
            wct.setDensityDpi(token, getFullscreenDensityDpi())
        }
    }

    private fun getFullscreenDensityDpi(): Int {
        return context.resources.displayMetrics.densityDpi
    }

    private fun getDesktopDensityDpi(): Int {
        return DESKTOP_DENSITY_OVERRIDE
    }

    /** Creates a new instance of the external interface to pass to another process. */
    private fun createExternalInterface(): ExternalInterfaceBinder {
        return IDesktopModeImpl(this)
    }

    /** Get connection interface between sysui and shell */
    fun asDesktopMode(): DesktopMode {
        return desktopMode
    }

    /**
     * Perform checks required on drag move. Create/release fullscreen indicator as needed.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface SurfaceControl of dragged task.
     * @param y coordinate of dragged task. Used for checks against status bar height.
     */
    fun onDragPositioningMove(
            taskInfo: RunningTaskInfo,
            taskSurface: SurfaceControl,
            y: Float
    ) {
        if (taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
            val statusBarHeight = getStatusBarHeight(taskInfo)
            if (y <= statusBarHeight && visualIndicator == null) {
                visualIndicator = DesktopModeVisualIndicator(syncQueue, taskInfo,
                        displayController, context, taskSurface, shellTaskOrganizer,
                        rootTaskDisplayAreaOrganizer)
                visualIndicator?.createFullscreenIndicatorWithAnimatedBounds()
            } else if (y > statusBarHeight && visualIndicator != null) {
                visualIndicator?.releaseVisualIndicator()
                visualIndicator = null
            }
        }
    }

    /**
     * Perform checks required on drag end. Move to fullscreen if drag ends in status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param y height of drag, to be checked against status bar height.
     */
    fun onDragPositioningEnd(
            taskInfo: RunningTaskInfo,
            y: Float
    ) {
        val statusBarHeight = getStatusBarHeight(taskInfo)
        if (y <= statusBarHeight && taskInfo.windowingMode == WINDOWING_MODE_FREEFORM) {
            visualIndicator?.releaseVisualIndicator()
            visualIndicator = null
            moveToFullscreenWithAnimation(taskInfo)
        }
    }

    /**
     * Perform checks required on drag move. Create/release fullscreen indicator and transitions
     * indicator to freeform or fullscreen dimensions as needed.
     *
     * @param taskInfo the task being dragged.
     * @param taskSurface SurfaceControl of dragged task.
     * @param y coordinate of dragged task. Used for checks against status bar height.
     */
    fun onDragPositioningMoveThroughStatusBar(
            taskInfo: RunningTaskInfo,
            taskSurface: SurfaceControl,
            y: Float
    ) {
        if (visualIndicator == null) {
            visualIndicator = DesktopModeVisualIndicator(syncQueue, taskInfo,
                    displayController, context, taskSurface, shellTaskOrganizer,
                    rootTaskDisplayAreaOrganizer)
            visualIndicator?.createFullscreenIndicator()
        }
        val indicator = visualIndicator ?: return
        if (y >= getFreeformTransitionStatusBarDragThreshold(taskInfo)) {
            if (indicator.isFullscreen) {
                indicator.transitionFullscreenIndicatorToFreeform()
            }
        } else if (!indicator.isFullscreen) {
            indicator.transitionFreeformIndicatorToFullscreen()
        }
    }

    /**
     * Perform checks required when drag ends under status bar area.
     *
     * @param taskInfo the task being dragged.
     * @param y height of drag, to be checked against status bar height.
     */
    fun onDragPositioningEndThroughStatusBar(
            taskInfo: RunningTaskInfo,
            freeformBounds: Rect
    ) {
        moveToDesktopWithAnimation(taskInfo, freeformBounds)
        visualIndicator?.releaseVisualIndicator()
        visualIndicator = null
    }


    private fun getStatusBarHeight(taskInfo: RunningTaskInfo): Int {
        return displayController.getDisplayLayout(taskInfo.displayId)?.stableInsets()?.top ?: 0
    }

    /**
     * Returns the threshold at which we transition a task into freeform when dragging a
     * fullscreen task down from the status bar
     */
    private fun getFreeformTransitionStatusBarDragThreshold(taskInfo: RunningTaskInfo): Int {
        return 2 * getStatusBarHeight(taskInfo)
    }


    /**
     * Adds a listener to find out about changes in the visibility of freeform tasks.
     *
     * @param listener the listener to add.
     * @param callbackExecutor the executor to call the listener on.
     */
    fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
        desktopModeTaskRepository.addVisibleTasksListener(listener, callbackExecutor)
    }

    /** The interface for calls from outside the shell, within the host process. */
    @ExternalThread
    private inner class DesktopModeImpl : DesktopMode {
        override fun addListener(listener: VisibleTasksListener, callbackExecutor: Executor) {
            mainExecutor.execute {
                this@DesktopTasksController.addListener(listener, callbackExecutor)
            }
        }
    }

    /** The interface for calls from outside the host process. */
    @BinderThread
    private class IDesktopModeImpl(private var controller: DesktopTasksController?) :
        IDesktopMode.Stub(), ExternalInterfaceBinder {
        /** Invalidates this instance, preventing future calls from updating the controller. */
        override fun invalidate() {
            controller = null
        }

        override fun showDesktopApps() {
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "showDesktopApps",
                Consumer(DesktopTasksController::showDesktopApps)
            )
        }

        override fun getVisibleTaskCount(): Int {
            val result = IntArray(1)
            ExecutorUtils.executeRemoteCallWithTaskPermission(
                controller,
                "getVisibleTaskCount",
                { controller -> result[0] = controller.getVisibleTaskCount() },
                true /* blocking */
            )
            return result[0]
        }
    }

    companion object {
        private val DESKTOP_DENSITY_OVERRIDE =
            SystemProperties.getInt("persist.wm.debug.desktop_mode_density", 0)
        private val DESKTOP_DENSITY_ALLOWED_RANGE = (100..1000)

        /**
         * Check if desktop density override is enabled
         */
        @JvmStatic
        fun isDesktopDensityOverrideSet(): Boolean {
            return DESKTOP_DENSITY_OVERRIDE in DESKTOP_DENSITY_ALLOWED_RANGE
        }
    }
}
