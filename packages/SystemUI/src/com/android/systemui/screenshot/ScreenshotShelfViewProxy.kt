/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ScrollCaptureResponse
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.android.internal.logging.UiEventLogger
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.res.R
import com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS
import com.android.systemui.screenshot.LogConfig.DEBUG_DISMISS
import com.android.systemui.screenshot.LogConfig.DEBUG_INPUT
import com.android.systemui.screenshot.LogConfig.DEBUG_WINDOW
import com.android.systemui.screenshot.ScreenshotController.SavedImageData
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER
import com.android.systemui.screenshot.scroll.ScrollCaptureController
import com.android.systemui.screenshot.ui.ScreenshotAnimationController
import com.android.systemui.screenshot.ui.ScreenshotShelfView
import com.android.systemui.screenshot.ui.binder.ScreenshotShelfViewBinder
import com.android.systemui.screenshot.ui.viewmodel.ActionButtonViewModel
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Controls the screenshot view and viewModel. */
class ScreenshotShelfViewProxy
@AssistedInject
constructor(
    private val logger: UiEventLogger,
    private val viewModel: ScreenshotViewModel,
    @Assisted private val context: Context,
    @Assisted private val displayId: Int
) : ScreenshotViewProxy, ScreenshotActionsProvider.ScreenshotActionsCallback {
    override val view: ScreenshotShelfView =
        LayoutInflater.from(context).inflate(R.layout.screenshot_shelf, null) as ScreenshotShelfView
    override val screenshotPreview: View
    override var packageName: String = ""
    override var callbacks: ScreenshotView.ScreenshotViewCallback? = null
    override var screenshot: ScreenshotData? = null
        set(value) {
            viewModel.setScreenshotBitmap(value?.bitmap)
            field = value
        }

    override val isAttachedToWindow
        get() = view.isAttachedToWindow
    override var isDismissing = false
    override var isPendingSharedTransition = false

    private val animationController = ScreenshotAnimationController(view)
    private var imageData: SavedImageData? = null
    private var runOnImageDataAcquired: ((SavedImageData) -> Unit)? = null

    init {
        ScreenshotShelfViewBinder.bind(view, viewModel, LayoutInflater.from(context))
        addPredictiveBackListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        setOnKeyListener { requestDismissal(SCREENSHOT_DISMISSED_OTHER) }
        debugLog(DEBUG_WINDOW) { "adding OnComputeInternalInsetsListener" }
        screenshotPreview = view.screenshotPreview
    }

    override fun reset() {
        animationController.cancel()
        isPendingSharedTransition = false
        imageData = null
        viewModel.reset()
        runOnImageDataAcquired = null
    }
    override fun updateInsets(insets: WindowInsets) {}
    override fun updateOrientation(insets: WindowInsets) {}

    override fun createScreenshotDropInAnimation(screenRect: Rect, showFlash: Boolean): Animator {
        return animationController.getEntranceAnimation()
    }

    override fun addQuickShareChip(quickShareAction: Notification.Action) {}

    override fun setChipIntents(data: SavedImageData) {
        imageData = data
        runOnImageDataAcquired?.invoke(data)
    }

    override fun requestDismissal(event: ScreenshotEvent) {
        debugLog(DEBUG_DISMISS) { "screenshot dismissal requested: $event" }

        // If we're already animating out, don't restart the animation
        if (isDismissing) {
            debugLog(DEBUG_DISMISS) { "Already dismissing, ignoring duplicate command $event" }
            return
        }
        logger.log(event, 0, packageName)
        val animator = animationController.getExitAnimation()
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    isDismissing = true
                }
                override fun onAnimationEnd(animator: Animator) {
                    isDismissing = false
                    callbacks?.onDismiss()
                }
            }
        )
        animator.start()
    }

    override fun showScrollChip(packageName: String, onClick: Runnable) {}

    override fun hideScrollChip() {}

    override fun prepareScrollingTransition(
        response: ScrollCaptureResponse,
        screenBitmap: Bitmap,
        newScreenshot: Bitmap,
        screenshotTakenInPortrait: Boolean,
        onTransitionPrepared: Runnable,
    ) {}

    override fun startLongScreenshotTransition(
        transitionDestination: Rect,
        onTransitionEnd: Runnable,
        longScreenshot: ScrollCaptureController.LongScreenshot
    ) {}

    override fun restoreNonScrollingUi() {}

    override fun stopInputListening() {}

    override fun requestFocus() {
        view.requestFocus()
    }

    override fun announceForAccessibility(string: String) = view.announceForAccessibility(string)

    override fun prepareEntranceAnimation(runnable: Runnable) {
        view.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    debugLog(DEBUG_WINDOW) { "onPreDraw: startAnimation" }
                    view.viewTreeObserver.removeOnPreDrawListener(this)
                    runnable.run()
                    return true
                }
            }
        )
    }

    private fun addPredictiveBackListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        val onBackInvokedCallback = OnBackInvokedCallback {
            debugLog(DEBUG_INPUT) { "Predictive Back callback dispatched" }
            onDismissRequested.invoke(SCREENSHOT_DISMISSED_OTHER)
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    debugLog(DEBUG_INPUT) { "Registering Predictive Back callback" }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                            onBackInvokedCallback
                        )
                }

                override fun onViewDetachedFromWindow(view: View) {
                    debugLog(DEBUG_INPUT) { "Unregistering Predictive Back callback" }
                    view
                        .findOnBackInvokedDispatcher()
                        ?.unregisterOnBackInvokedCallback(onBackInvokedCallback)
                }
            }
        )
    }
    private fun setOnKeyListener(onDismissRequested: (ScreenshotEvent) -> Unit) {
        view.setOnKeyListener(
            object : View.OnKeyListener {
                override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        debugLog(DEBUG_INPUT) { "onKeyEvent: $keyCode" }
                        onDismissRequested.invoke(SCREENSHOT_DISMISSED_OTHER)
                        return true
                    }
                    return false
                }
            }
        )
    }

    @AssistedFactory
    interface Factory : ScreenshotViewProxy.Factory {
        override fun getProxy(context: Context, displayId: Int): ScreenshotShelfViewProxy
    }

    override fun setPreviewAction(overrideTransition: Boolean, retrieveIntent: (Uri) -> Intent) {
        viewModel.setPreviewAction {
            imageData?.let {
                val intent = retrieveIntent(it.uri)
                debugLog(DEBUG_ACTIONS) { "Preview tapped: $intent" }
                isPendingSharedTransition = true
                callbacks?.onAction(intent, it.owner, overrideTransition)
            }
        }
    }

    override fun addActions(actions: List<ScreenshotActionsProvider.ScreenshotAction>) {
        viewModel.addActions(
            actions.map { action ->
                ActionButtonViewModel(action.icon, action.text, action.description) {
                    val actionRunnable =
                        getActionRunnable(action.retrieveIntent, action.overrideTransition)
                    imageData?.let { actionRunnable(it) }
                        ?: run { runOnImageDataAcquired = actionRunnable }
                }
            }
        )
    }

    private fun getActionRunnable(
        retrieveIntent: (Uri) -> Intent,
        overrideTransition: Boolean
    ): (SavedImageData) -> Unit {
        val onClick: (SavedImageData) -> Unit = {
            val intent = retrieveIntent(it.uri)
            debugLog(DEBUG_ACTIONS) { "Action tapped: $intent" }
            isPendingSharedTransition = true
            callbacks!!.onAction(intent, it.owner, overrideTransition)
        }
        return onClick
    }
}
