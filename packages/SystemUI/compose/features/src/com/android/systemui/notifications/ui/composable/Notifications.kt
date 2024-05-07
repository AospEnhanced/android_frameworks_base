/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.notifications.ui.composable

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.NestedScrollBehavior
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.height
import com.android.systemui.common.ui.compose.windowinsets.LocalRawScreenHeight
import com.android.systemui.common.ui.compose.windowinsets.LocalScreenCornerRadius
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.ui.composable.ShadeHeader
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimRounding
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_MAX_CORNER_RADIUS
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationTransitionThresholds.EXPANSION_FOR_MAX_SCRIM_ALPHA
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

object Notifications {
    object Elements {
        val NotificationScrim = ElementKey("NotificationScrim")
        val NotificationStackPlaceholder = ElementKey("NotificationStackPlaceholder")
        val HeadsUpNotificationPlaceholder = ElementKey("HeadsUpNotificationPlaceholder")
        val ShelfSpace = ElementKey("ShelfSpace")
    }

    // Expansion fraction thresholds (between 0-1f) at which the corresponding value should be
    // at its maximum, given they are at their minimum value at expansion = 0f.
    object TransitionThresholds {
        const val EXPANSION_FOR_MAX_CORNER_RADIUS = 0.1f
        const val EXPANSION_FOR_MAX_SCRIM_ALPHA = 0.3f
    }
}

/**
 * Adds the space where heads up notifications can appear in the scene. This should generally be the
 * entire size of the scene.
 */
@Composable
fun SceneScope.HeadsUpNotificationSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
    isPeekFromBottom: Boolean = false,
) {
    val headsUpHeight = viewModel.headsUpHeight.collectAsState()

    Element(
        Notifications.Elements.HeadsUpNotificationPlaceholder,
        modifier =
            modifier
                .height { headsUpHeight.value.roundToInt() }
                .fillMaxWidth()
                .debugBackground(viewModel, DEBUG_HUN_COLOR)
                .onGloballyPositioned { coordinates: LayoutCoordinates ->
                    val boundsInWindow = coordinates.boundsInWindow()
                    debugLog(viewModel) {
                        "HUNS onGloballyPositioned:" +
                            " size=${coordinates.size}" +
                            " bounds=$boundsInWindow"
                    }
                    viewModel.onHeadsUpTopChanged(boundsInWindow.top)
                }
    ) {
        content {}
    }
}

/** Adds the space where notification stack should appear in the scene. */
@Composable
fun SceneScope.ConstrainedNotificationStack(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.onSizeChanged { viewModel.onConstrainedAvailableSpaceChanged(it.height) }
    ) {
        NotificationPlaceholder(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
        HeadsUpNotificationSpace(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * Adds the space where notification stack should appear in the scene, with a scrim and nested
 * scrolling.
 */
@Composable
fun SceneScope.NotificationScrollingStack(
    viewModel: NotificationsPlaceholderViewModel,
    maxScrimTop: () -> Float,
    shouldPunchHoleBehindScrim: Boolean,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenCornerRadius = LocalScreenCornerRadius.current
    val scrimCornerRadius = dimensionResource(R.dimen.notification_scrim_corner_radius)
    val scrollState = rememberScrollState()
    val syntheticScroll = viewModel.syntheticScroll.collectAsState(0f)
    val isCurrentGestureOverscroll = viewModel.isCurrentGestureOverscroll.collectAsState(false)
    val expansionFraction by viewModel.expandFraction.collectAsState(0f)

    val navBarHeight =
        with(density) { WindowInsets.systemBars.asPaddingValues().calculateBottomPadding().toPx() }
    val screenHeight = LocalRawScreenHeight.current

    val stackHeight = viewModel.stackHeight.collectAsState()

    val scrimRounding = viewModel.shadeScrimRounding.collectAsState(ShadeScrimRounding())

    // the offset for the notifications scrim. Its upper bound is 0, and its lower bound is
    // calculated in minScrimOffset. The scrim is the same height as the screen minus the
    // height of the Shade Header, and at rest (scrimOffset = 0) its top bound is at maxScrimStartY.
    // When fully expanded (scrimOffset = minScrimOffset), its top bound is at minScrimStartY,
    // which is equal to the height of the Shade Header. Thus, when the scrim is fully expanded, the
    // entire height of the scrim is visible on screen.
    val scrimOffset = remember { Animatable(0f) }

    // set the bounds to null when the scrim disappears
    DisposableEffect(Unit) { onDispose { viewModel.onScrimBoundsChanged(null) } }

    val minScrimTop = with(density) { ShadeHeader.Dimensions.CollapsedHeight.toPx() }

    // The minimum offset for the scrim. The scrim is considered fully expanded when it
    // is at this offset.
    val minScrimOffset: () -> Float = { minScrimTop - maxScrimTop() }

    // The height of the scrim visible on screen when it is in its resting (collapsed) state.
    val minVisibleScrimHeight: () -> Float = { screenHeight - maxScrimTop() }

    // we are not scrolled to the top unless the scrim is at its maximum offset.
    LaunchedEffect(viewModel, scrimOffset) {
        snapshotFlow { scrimOffset.value >= 0f }
            .collect { isScrolledToTop -> viewModel.setScrolledToTop(isScrolledToTop) }
    }

    // if contentHeight drops below minimum visible scrim height while scrim is
    // expanded, reset scrim offset.
    LaunchedEffect(stackHeight, scrimOffset) {
        snapshotFlow { stackHeight.value < minVisibleScrimHeight() && scrimOffset.value < 0f }
            .collect { shouldCollapse -> if (shouldCollapse) scrimOffset.snapTo(0f) }
    }

    // if we receive scroll delta from NSSL, offset the scrim and placeholder accordingly.
    LaunchedEffect(syntheticScroll, scrimOffset, scrollState) {
        snapshotFlow { syntheticScroll.value }
            .collect { delta ->
                val minOffset = minScrimOffset()
                if (scrimOffset.value > minOffset) {
                    val remainingDelta = (minOffset - (scrimOffset.value - delta)).coerceAtLeast(0f)
                    scrimOffset.snapTo((scrimOffset.value - delta).coerceAtLeast(minOffset))
                    if (remainingDelta > 0f) {
                        scrollState.scrollBy(remainingDelta)
                    }
                } else {
                    scrollState.scrollTo(delta.roundToInt())
                }
            }
    }

    Box(
        modifier =
            modifier
                .element(Notifications.Elements.NotificationScrim)
                .offset {
                    // if scrim is expanded while transitioning to Gone scene, increase the offset
                    // in step with the transition so that it is 0 when it completes.
                    if (
                        scrimOffset.value < 0 &&
                            layoutState.isTransitioning(from = Scenes.Shade, to = Scenes.Gone) ||
                            layoutState.isTransitioning(from = Scenes.Shade, to = Scenes.Lockscreen)
                    ) {
                        IntOffset(x = 0, y = (scrimOffset.value * expansionFraction).roundToInt())
                    } else {
                        IntOffset(x = 0, y = scrimOffset.value.roundToInt())
                    }
                }
                .graphicsLayer {
                    shape =
                        calculateCornerRadius(
                                scrimCornerRadius,
                                screenCornerRadius,
                                { expansionFraction },
                                shouldPunchHoleBehindScrim,
                            )
                            .let { scrimRounding.value.toRoundedCornerShape(it) }
                    clip = true
                }
                .onGloballyPositioned { coordinates ->
                    val boundsInWindow = coordinates.boundsInWindow()
                    debugLog(viewModel) {
                        "SCRIM onGloballyPositioned:" +
                            " size=${coordinates.size}" +
                            " bounds=$boundsInWindow"
                    }
                    viewModel.onScrimBoundsChanged(
                        ShadeScrimBounds(
                            left = boundsInWindow.left,
                            top = boundsInWindow.top,
                            right = boundsInWindow.right,
                            bottom = boundsInWindow.bottom,
                        )
                    )
                }
    ) {
        // Creates a cutout in the background scrim in the shape of the notifications scrim.
        // Only visible when notif scrim alpha < 1, during shade expansion.
        if (shouldPunchHoleBehindScrim) {
            Spacer(
                modifier =
                    Modifier.fillMaxSize().drawBehind {
                        drawRect(Color.Black, blendMode = BlendMode.DstOut)
                    }
            )
        }
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha =
                            if (shouldPunchHoleBehindScrim) {
                                (expansionFraction / EXPANSION_FOR_MAX_SCRIM_ALPHA).coerceAtMost(1f)
                            } else 1f
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .debugBackground(viewModel, DEBUG_BOX_COLOR)
        ) {
            NotificationPlaceholder(
                viewModel = viewModel,
                modifier =
                    Modifier.verticalNestedScrollToScene(
                            topBehavior = NestedScrollBehavior.EdgeWithPreview,
                            isExternalOverscrollGesture = { isCurrentGestureOverscroll.value }
                        )
                        .nestedScroll(
                            remember(
                                scrimOffset,
                                maxScrimTop,
                                minScrimTop,
                                isCurrentGestureOverscroll,
                            ) {
                                NotificationScrimNestedScrollConnection(
                                    scrimOffset = { scrimOffset.value },
                                    snapScrimOffset = { value ->
                                        coroutineScope.launch { scrimOffset.snapTo(value) }
                                    },
                                    animateScrimOffset = { value ->
                                        coroutineScope.launch { scrimOffset.animateTo(value) }
                                    },
                                    minScrimOffset = minScrimOffset,
                                    maxScrimOffset = 0f,
                                    contentHeight = { stackHeight.value },
                                    minVisibleScrimHeight = minVisibleScrimHeight,
                                    isCurrentGestureOverscroll = {
                                        isCurrentGestureOverscroll.value
                                    },
                                )
                            }
                        )
                        .verticalScroll(scrollState)
                        .fillMaxWidth()
                        .height { (stackHeight.value + navBarHeight).roundToInt() },
            )
        }
        HeadsUpNotificationSpace(viewModel = viewModel)
    }
}

/**
 * This may be added to the lockscreen to provide a space to the start of the lock icon where the
 * short shelf has room to flow vertically below the lock icon, but to its start, allowing more
 * notifications to fit in the stack itself. (see: b/213934746)
 *
 * NOTE: this is totally unused for now; it is here to clarify the future plan
 */
@Composable
fun SceneScope.NotificationShelfSpace(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Shelf Space",
        modifier
            .element(key = Notifications.Elements.ShelfSpace)
            .fillMaxWidth()
            .onPlaced { coordinates: LayoutCoordinates ->
                debugLog(viewModel) {
                    ("SHELF onPlaced:" +
                        " size=${coordinates.size}" +
                        " bounds=${coordinates.boundsInWindow()}")
                }
            }
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

@Composable
private fun SceneScope.NotificationPlaceholder(
    viewModel: NotificationsPlaceholderViewModel,
    modifier: Modifier = Modifier,
) {
    Element(
        Notifications.Elements.NotificationStackPlaceholder,
        modifier =
            modifier
                .debugBackground(viewModel, DEBUG_STACK_COLOR)
                .onSizeChanged { size -> debugLog(viewModel) { "STACK onSizeChanged: size=$size" } }
                .onGloballyPositioned { coordinates: LayoutCoordinates ->
                    val positionInWindow = coordinates.positionInWindow()
                    debugLog(viewModel) {
                        "STACK onGloballyPositioned:" +
                            " size=${coordinates.size}" +
                            " position=$positionInWindow" +
                            " bounds=${coordinates.boundsInWindow()}"
                    }
                    // NOTE: positionInWindow.y scrolls off screen, but boundsInWindow.top will not
                    viewModel.onStackBoundsChanged(
                        top = positionInWindow.y,
                        bottom = positionInWindow.y + coordinates.size.height,
                    )
                }
    ) {
        content {}
    }
}

private fun calculateCornerRadius(
    scrimCornerRadius: Dp,
    screenCornerRadius: Dp,
    expansionFraction: () -> Float,
    transitioning: Boolean,
): Dp {
    return if (transitioning) {
        lerp(
                start = screenCornerRadius.value,
                stop = scrimCornerRadius.value,
                fraction = (expansionFraction() / EXPANSION_FOR_MAX_CORNER_RADIUS).coerceIn(0f, 1f),
            )
            .dp
    } else {
        scrimCornerRadius
    }
}

private inline fun debugLog(
    viewModel: NotificationsPlaceholderViewModel,
    msg: () -> Any,
) {
    if (viewModel.isDebugLoggingEnabled) {
        Log.d(TAG, msg().toString())
    }
}

private fun Modifier.debugBackground(
    viewModel: NotificationsPlaceholderViewModel,
    color: Color,
): Modifier =
    if (viewModel.isVisualDebuggingEnabled) {
        background(color)
    } else {
        this
    }

private fun ShadeScrimRounding.toRoundedCornerShape(radius: Dp): RoundedCornerShape {
    val topRadius = if (isTopRounded) radius else 0.dp
    val bottomRadius = if (isBottomRounded) radius else 0.dp
    return RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius,
    )
}

private const val TAG = "FlexiNotifs"
private val DEBUG_STACK_COLOR = Color(1f, 0f, 0f, 0.2f)
private val DEBUG_HUN_COLOR = Color(0f, 0f, 1f, 0.2f)
private val DEBUG_BOX_COLOR = Color(0f, 1f, 0f, 0.2f)
