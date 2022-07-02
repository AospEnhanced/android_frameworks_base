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

package com.android.systemui.media.taptotransfer.receiver

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaRoute2Info
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.core.graphics.ColorUtils
import com.android.settingslib.Utils
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.taptotransfer.common.ChipInfoCommon
import com.android.systemui.media.taptotransfer.common.DEFAULT_TIMEOUT_MILLIS
import com.android.systemui.media.taptotransfer.common.MediaTttChipControllerCommon
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.animation.AnimationUtil.Companion.frames
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.view.ViewUtil
import javax.inject.Inject

/**
 * A controller to display and hide the Media Tap-To-Transfer chip on the **receiving** device.
 *
 * This chip is shown when a user is transferring media to/from a sending device and this device.
 */
@SysUISingleton
class MediaTttChipControllerReceiver @Inject constructor(
    commandQueue: CommandQueue,
    context: Context,
    @MediaTttReceiverLogger logger: MediaTttLogger,
    windowManager: WindowManager,
    viewUtil: ViewUtil,
    mainExecutor: DelayableExecutor,
    accessibilityManager: AccessibilityManager,
    tapGestureDetector: TapGestureDetector,
    powerManager: PowerManager,
    @Main private val mainHandler: Handler,
    private val uiEventLogger: MediaTttReceiverUiEventLogger,
) : MediaTttChipControllerCommon<ChipReceiverInfo>(
    context,
    logger,
    windowManager,
    viewUtil,
    mainExecutor,
    accessibilityManager,
    tapGestureDetector,
    powerManager,
    R.layout.media_ttt_chip_receiver
) {
    @SuppressLint("WrongConstant") // We're allowed to use LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    override val windowLayoutParams = commonWindowLayoutParams.apply {
        gravity = Gravity.BOTTOM.or(Gravity.CENTER_HORIZONTAL)
        // Params below are needed for the ripple to work correctly
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0 // Ignore insets from all system bars
        flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private val commandQueueCallbacks = object : CommandQueue.Callbacks {
        override fun updateMediaTapToTransferReceiverDisplay(
            @StatusBarManager.MediaTransferReceiverState displayState: Int,
            routeInfo: MediaRoute2Info,
            appIcon: Icon?,
            appName: CharSequence?
        ) {
            this@MediaTttChipControllerReceiver.updateMediaTapToTransferReceiverDisplay(
                displayState, routeInfo, appIcon, appName
            )
        }
    }

    init {
        commandQueue.addCallback(commandQueueCallbacks)
    }

    private fun updateMediaTapToTransferReceiverDisplay(
        @StatusBarManager.MediaTransferReceiverState displayState: Int,
        routeInfo: MediaRoute2Info,
        appIcon: Icon?,
        appName: CharSequence?
    ) {
        val chipState: ChipStateReceiver? = ChipStateReceiver.getReceiverStateFromId(displayState)
        val stateName = chipState?.name ?: "Invalid"
        logger.logStateChange(stateName, routeInfo.id)

        if (chipState == null) {
            Log.e(RECEIVER_TAG, "Unhandled MediaTransferReceiverState $displayState")
            return
        }
        uiEventLogger.logReceiverStateChange(chipState)

        if (chipState == ChipStateReceiver.FAR_FROM_SENDER) {
            removeChip(removalReason = ChipStateReceiver.FAR_FROM_SENDER::class.simpleName!!)
            return
        }
        if (appIcon == null) {
            displayChip(ChipReceiverInfo(routeInfo, appIconDrawableOverride = null, appName))
            return
        }

        appIcon.loadDrawableAsync(
                context,
                Icon.OnDrawableLoadedListener { drawable ->
                    displayChip(ChipReceiverInfo(routeInfo, drawable, appName))
                },
                // Notify the listener on the main handler since the listener will update
                // the UI.
                mainHandler
        )
    }

    override fun updateChipView(chipInfo: ChipReceiverInfo, currentChipView: ViewGroup) {
        setIcon(
                currentChipView,
                chipInfo.routeInfo.packageName,
                chipInfo.appIconDrawableOverride,
                chipInfo.appNameOverride
        )
    }

    override fun animateChipIn(chipView: ViewGroup) {
        val appIconView = chipView.requireViewById<View>(R.id.app_icon)
        appIconView.animate()
                .translationYBy(-1 * getTranslationAmount().toFloat())
                .setDuration(30.frames)
                .start()
        appIconView.animate()
                .alpha(1f)
                .setDuration(5.frames)
                .start()
        startRipple(chipView.requireViewById(R.id.ripple))
    }

    override fun getIconSize(isAppIcon: Boolean): Int? =
        context.resources.getDimensionPixelSize(
            if (isAppIcon) {
                R.dimen.media_ttt_icon_size_receiver
            } else {
                R.dimen.media_ttt_generic_icon_size_receiver
            }
        )

    /** Returns the amount that the chip will be translated by in its intro animation. */
    private fun getTranslationAmount(): Int {
        return context.resources.getDimensionPixelSize(R.dimen.media_ttt_receiver_vert_translation)
    }

    private fun startRipple(rippleView: ReceiverChipRippleView) {
        if (rippleView.rippleInProgress) {
            // Skip if ripple is still playing
            return
        }
        rippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View?) {}

            override fun onViewAttachedToWindow(view: View?) {
                if (view == null) {
                    return
                }
                val attachedRippleView = view as ReceiverChipRippleView
                layoutRipple(attachedRippleView)
                attachedRippleView.startRipple()
                attachedRippleView.removeOnAttachStateChangeListener(this)
            }
        })
    }

    private fun layoutRipple(rippleView: ReceiverChipRippleView) {
        val windowBounds = windowManager.currentWindowMetrics.bounds
        val height = windowBounds.height()
        val width = windowBounds.width()

        rippleView.radius = height / 5f
        // Center the ripple on the bottom of the screen in the middle.
        rippleView.origin = PointF(/* x= */ width / 2f, /* y= */ height.toFloat())

        val color = Utils.getColorAttrDefaultColor(context, R.attr.wallpaperTextColorAccent)
        val colorWithAlpha = ColorUtils.setAlphaComponent(color, 70)
        rippleView.setColor(colorWithAlpha)
    }
}

data class ChipReceiverInfo(
    val routeInfo: MediaRoute2Info,
    val appIconDrawableOverride: Drawable?,
    val appNameOverride: CharSequence?
) : ChipInfoCommon {
    override fun getTimeoutMs() = DEFAULT_TIMEOUT_MILLIS
}

private const val RECEIVER_TAG = "MediaTapToTransferRcvr"
