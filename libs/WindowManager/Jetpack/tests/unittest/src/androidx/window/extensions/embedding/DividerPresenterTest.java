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

package androidx.window.extensions.embedding;

import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE;

import static androidx.window.extensions.embedding.DividerPresenter.getBoundsOffsetForDivider;
import static androidx.window.extensions.embedding.DividerPresenter.getInitialDividerPosition;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

/**
 * Test class for {@link DividerPresenter}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:DividerPresenterTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DividerPresenterTest {
    @Rule
    public final SetFlagsRule mSetFlagRule = new SetFlagsRule();

    private static final int MOCK_TASK_ID = 1234;

    @Mock
    private DividerPresenter.Renderer mRenderer;

    @Mock
    private WindowContainerTransaction mTransaction;

    @Mock
    private TaskFragmentParentInfo mParentInfo;

    @Mock
    private TaskContainer mTaskContainer;

    @Mock
    private DividerPresenter.DragEventCallback mDragEventCallback;

    @Mock
    private SplitContainer mSplitContainer;

    @Mock
    private SurfaceControl mSurfaceControl;

    private DividerPresenter mDividerPresenter;

    private final IBinder mPrimaryContainerToken = new Binder();

    private final IBinder mSecondaryContainerToken = new Binder();

    private final IBinder mAnotherContainerToken = new Binder();

    private DividerPresenter.Properties mProperties;

    private static final DividerAttributes DEFAULT_DIVIDER_ATTRIBUTES =
            new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE).build();

    private static final DividerAttributes ANOTHER_DIVIDER_ATTRIBUTES =
            new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                    .setWidthDp(10).build();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mSetFlagRule.enableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_INTERACTIVE_DIVIDER_FLAG);

        when(mTaskContainer.getTaskId()).thenReturn(MOCK_TASK_ID);

        when(mParentInfo.getDisplayId()).thenReturn(Display.DEFAULT_DISPLAY);
        when(mParentInfo.getConfiguration()).thenReturn(new Configuration());
        when(mParentInfo.getDecorSurface()).thenReturn(mSurfaceControl);

        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder()
                        .setDividerAttributes(DEFAULT_DIVIDER_ATTRIBUTES)
                        .build());
        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(
                        mPrimaryContainerToken, new Rect(0, 0, 950, 1000));
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(
                        mSecondaryContainerToken, new Rect(1000, 0, 2000, 1000));
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);

        mProperties = new DividerPresenter.Properties(
                new Configuration(),
                DEFAULT_DIVIDER_ATTRIBUTES,
                mSurfaceControl,
                getInitialDividerPosition(
                        mSplitContainer, true /* isVerticalSplit */, false /* isReversedLayout */),
                true /* isVerticalSplit */,
                false /* isReversedLayout */,
                Display.DEFAULT_DISPLAY,
                false /* isDraggableExpandType */);

        mDividerPresenter = new DividerPresenter(
                MOCK_TASK_ID, mDragEventCallback, mock(Executor.class));
        mDividerPresenter.mProperties = mProperties;
        mDividerPresenter.mRenderer = mRenderer;
        mDividerPresenter.mDecorSurfaceOwner = mPrimaryContainerToken;
    }

    @Test
    public void testUpdateDivider() {
        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder()
                        .setDividerAttributes(ANOTHER_DIVIDER_ATTRIBUTES)
                        .build());
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertNotEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer).update();
        verify(mTransaction, never()).addTaskFragmentOperation(any(), any());
    }

    @Test
    public void testUpdateDivider_updateDecorSurfaceOwnerIfPrimaryContainerChanged() {
        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(
                        mAnotherContainerToken, new Rect(0, 0, 750, 1000));
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(
                        mSecondaryContainerToken, new Rect(800, 0, 2000, 1000));
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertNotEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer).update();
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();
        assertEquals(mAnotherContainerToken, mDividerPresenter.mDecorSurfaceOwner);
        verify(mTransaction).addTaskFragmentOperation(mAnotherContainerToken, operation);
    }

    @Test
    public void testUpdateDivider_noChangeIfPropertiesIdentical() {
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);

        assertEquals(mProperties, mDividerPresenter.mProperties);
        verify(mRenderer, never()).update();
        verify(mTransaction, never()).addTaskFragmentOperation(any(), any());
    }

    @Test
    public void testUpdateDivider_dividerRemovedWhenSplitContainerIsNull() {
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                null /* splitContainer */);
        final TaskFragmentOperation taskFragmentOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();

        verify(mTransaction).addTaskFragmentOperation(
                mPrimaryContainerToken, taskFragmentOperation);
        verify(mRenderer).release();
        assertNull(mDividerPresenter.mRenderer);
        assertNull(mDividerPresenter.mProperties);
        assertNull(mDividerPresenter.mDecorSurfaceOwner);
    }

    @Test
    public void testUpdateDivider_dividerRemovedWhenDividerAttributesIsNull() {
        when(mSplitContainer.getCurrentSplitAttributes()).thenReturn(
                new SplitAttributes.Builder().setDividerAttributes(null).build());
        mDividerPresenter.updateDivider(
                mTransaction,
                mParentInfo,
                mSplitContainer);
        final TaskFragmentOperation taskFragmentOperation = new TaskFragmentOperation.Builder(
                OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();

        verify(mTransaction).addTaskFragmentOperation(
                mPrimaryContainerToken, taskFragmentOperation);
        verify(mRenderer).release();
        assertNull(mDividerPresenter.mRenderer);
        assertNull(mDividerPresenter.mProperties);
        assertNull(mDividerPresenter.mDecorSurfaceOwner);
    }

    @Test
    public void testSanitizeDividerAttributes_setDefaultValues() {
        DividerAttributes attributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE).build();
        DividerAttributes sanitized = DividerPresenter.sanitizeDividerAttributes(attributes);

        assertEquals(DividerAttributes.DIVIDER_TYPE_DRAGGABLE, sanitized.getDividerType());
        assertEquals(DividerPresenter.DEFAULT_DIVIDER_WIDTH_DP, sanitized.getWidthDp());
        assertEquals(DividerPresenter.DEFAULT_MIN_RATIO, sanitized.getPrimaryMinRatio(),
                0.0f /* delta */);
        assertEquals(DividerPresenter.DEFAULT_MAX_RATIO, sanitized.getPrimaryMaxRatio(),
                0.0f /* delta */);
    }

    @Test
    public void testSanitizeDividerAttributes_notChangingValidValues() {
        DividerAttributes attributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                        .setWidthDp(24)
                        .setPrimaryMinRatio(0.3f)
                        .setPrimaryMaxRatio(0.7f)
                        .build();
        DividerAttributes sanitized = DividerPresenter.sanitizeDividerAttributes(attributes);

        assertEquals(attributes, sanitized);
    }

    @Test
    public void testGetBoundsOffsetForDivider_ratioSplitType() {
        final int dividerWidthPx = 100;
        final float splitRatio = 0.25f;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.RatioSplitType(splitRatio);
        final int expectedTopLeftOffset = 25;
        final int expectedBottomRightOffset = 75;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_ratioSplitType_withRounding() {
        final int dividerWidthPx = 101;
        final float splitRatio = 0.25f;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.RatioSplitType(splitRatio);
        final int expectedTopLeftOffset = 25;
        final int expectedBottomRightOffset = 76;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_hingeSplitType() {
        final int dividerWidthPx = 100;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.HingeSplitType(
                        new SplitAttributes.SplitType.RatioSplitType(0.5f));

        final int expectedTopLeftOffset = 50;
        final int expectedBottomRightOffset = 50;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testGetBoundsOffsetForDivider_expandContainersSplitType() {
        final int dividerWidthPx = 100;
        final SplitAttributes.SplitType splitType =
                new SplitAttributes.SplitType.ExpandContainersSplitType();
        // Always return 0 for ExpandContainersSplitType as divider is not needed.
        final int expectedTopLeftOffset = 0;
        final int expectedBottomRightOffset = 0;

        assertDividerOffsetEquals(
                dividerWidthPx, splitType, expectedTopLeftOffset, expectedBottomRightOffset);
    }

    @Test
    public void testCalculateDividerPosition() {
        final MotionEvent event = mock(MotionEvent.class);
        final Rect taskBounds = new Rect(100, 200, 1000, 2000);
        final int dividerWidthPx = 50;
        final DividerAttributes dividerAttributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                        .setPrimaryMinRatio(0.3f)
                        .setPrimaryMaxRatio(0.8f)
                        .build();

        // Left-to-right split
        when(event.getRawX()).thenReturn(500f); // Touch event is in display space
        assertEquals(
                // Touch position is in task space is 400, then minus half of divider width.
                375,
                DividerPresenter.calculateDividerPosition(
                        event,
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        true /* isVerticalSplit */,
                        0 /* minPosition */,
                        900 /* maxPosition */));

        // Top-to-bottom split
        when(event.getRawY()).thenReturn(1000f); // Touch event is in display space
        assertEquals(
                // Touch position is in task space is 800, then minus half of divider width.
                775,
                DividerPresenter.calculateDividerPosition(
                        event,
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        false /* isVerticalSplit */,
                        0 /* minPosition */,
                        900 /* maxPosition */));
    }

    @Test
    public void testCalculateMinPosition() {
        final Rect taskBounds = new Rect(100, 200, 1000, 2000);
        final int dividerWidthPx = 50;
        final DividerAttributes dividerAttributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                        .setPrimaryMinRatio(0.3f)
                        .setPrimaryMaxRatio(0.8f)
                        .build();

        // Left-to-right split
        assertEquals(
                255, /* (1000 - 100 - 50) * 0.3 */
                DividerPresenter.calculateMinPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        true /* isVerticalSplit */,
                        false /* isReversedLayout */));

        // Top-to-bottom split
        assertEquals(
                525, /* (2000 - 200 - 50) * 0.3 */
                DividerPresenter.calculateMinPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        false /* isVerticalSplit */,
                        false /* isReversedLayout */));

        // Right-to-left split
        assertEquals(
                170, /* (1000 - 100 - 50) * (1 - 0.8) */
                DividerPresenter.calculateMinPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        true /* isVerticalSplit */,
                        true /* isReversedLayout */));
    }

    @Test
    public void testCalculateMaxPosition() {
        final Rect taskBounds = new Rect(100, 200, 1000, 2000);
        final int dividerWidthPx = 50;
        final DividerAttributes dividerAttributes =
                new DividerAttributes.Builder(DividerAttributes.DIVIDER_TYPE_DRAGGABLE)
                        .setPrimaryMinRatio(0.3f)
                        .setPrimaryMaxRatio(0.8f)
                        .build();

        // Left-to-right split
        assertEquals(
                680, /* (1000 - 100 - 50) * 0.8 */
                DividerPresenter.calculateMaxPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        true /* isVerticalSplit */,
                        false /* isReversedLayout */));

        // Top-to-bottom split
        assertEquals(
                1400, /* (2000 - 200 - 50) * 0.8 */
                DividerPresenter.calculateMaxPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        false /* isVerticalSplit */,
                        false /* isReversedLayout */));

        // Right-to-left split
        assertEquals(
                595, /* (1000 - 100 - 50) * (1 - 0.3) */
                DividerPresenter.calculateMaxPosition(
                        taskBounds,
                        dividerWidthPx,
                        dividerAttributes,
                        true /* isVerticalSplit */,
                        true /* isReversedLayout */));
    }

    @Test
    public void testCalculateNewSplitRatio_leftToRight() {
        // primary=500px; secondary=500px; divider=100px; total=1100px.
        final Rect taskBounds = new Rect(0, 0, 1100, 2000);
        final Rect primaryBounds = new Rect(0, 0, 500, 2000);
        final Rect secondaryBounds = new Rect(600, 0, 1100, 2000);
        final int dividerWidthPx = 100;

        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(mPrimaryContainerToken, primaryBounds);
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(mSecondaryContainerToken, secondaryBounds);
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);

        // Test the normal case
        int dividerPosition = 300;
        assertEquals(
                0.3f, // Primary is 300px after dragging.
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        true /* isVerticalSplit */,
                        false /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        false /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);

        // Test the case when dragging to fullscreen is allowed and divider is dragged to the edge
        dividerPosition = 0;
        assertEquals(
                DividerPresenter.RATIO_EXPANDED_SECONDARY,
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        true /* isVerticalSplit */,
                        false /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        true /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);

        // Test the case when dragging to fullscreen is not allowed and divider is dragged to the
        // edge.
        dividerPosition = 0;
        assertEquals(
                0.2f, // Adjusted to the minPosition 200
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        true /* isVerticalSplit */,
                        false /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        false /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);
    }

    @Test
    public void testCalculateNewSplitRatio_bottomToTop() {
        // Primary is at bottom. Secondary is at top.
        // primary=500px; secondary=500px; divider=100px; total=1100px.
        final Rect taskBounds = new Rect(0, 0, 2000, 1100);
        final Rect primaryBounds = new Rect(0, 0, 2000, 1100);
        final Rect secondaryBounds = new Rect(0, 0, 2000, 500);
        final int dividerWidthPx = 100;

        final TaskFragmentContainer mockPrimaryContainer =
                createMockTaskFragmentContainer(mPrimaryContainerToken, primaryBounds);
        final TaskFragmentContainer mockSecondaryContainer =
                createMockTaskFragmentContainer(mSecondaryContainerToken, secondaryBounds);
        when(mSplitContainer.getPrimaryContainer()).thenReturn(mockPrimaryContainer);
        when(mSplitContainer.getSecondaryContainer()).thenReturn(mockSecondaryContainer);

        // Test the normal case
        int dividerPosition = 300;
        assertEquals(
                // After dragging, secondary is [0, 0, 2000, 300]. Primary is [0, 400, 2000, 1100].
                0.7f,
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        false /* isVerticalSplit */,
                        true /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        true /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);

        // Test the case when dragging to fullscreen is not allowed and divider is dragged to the
        // edge.
        dividerPosition = 0;
        assertEquals(
                // The primary (bottom) container is expanded
                DividerPresenter.RATIO_EXPANDED_PRIMARY,
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        false /* isVerticalSplit */,
                        true /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        true /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);

        // Test the case when dragging to fullscreen is not allowed and divider is dragged to the
        // edge.
        dividerPosition = 0;
        assertEquals(
                // Adjusted to minPosition 200, so the primary (bottom) container is 800.
                0.8f,
                DividerPresenter.calculateNewSplitRatio(
                        mSplitContainer,
                        dividerPosition,
                        taskBounds,
                        dividerWidthPx,
                        false /* isVerticalSplit */,
                        true /* isReversedLayout */,
                        200 /* minPosition */,
                        1000 /* maxPosition */,
                        false /* isDraggingToFullscreenAllowed */),
                0.0001 /* delta */);
    }

    private TaskFragmentContainer createMockTaskFragmentContainer(
            @NonNull IBinder token, @NonNull Rect bounds) {
        final TaskFragmentContainer container = mock(TaskFragmentContainer.class);
        when(container.getTaskFragmentToken()).thenReturn(token);
        when(container.getLastRequestedBounds()).thenReturn(bounds);
        return container;
    }

    private void assertDividerOffsetEquals(
            int dividerWidthPx,
            @NonNull SplitAttributes.SplitType splitType,
            int expectedTopLeftOffset,
            int expectedBottomRightOffset) {
        int offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_LEFT
        );
        assertEquals(-expectedTopLeftOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_RIGHT
        );
        assertEquals(expectedBottomRightOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_TOP
        );
        assertEquals(-expectedTopLeftOffset, offset);

        offset = getBoundsOffsetForDivider(
                dividerWidthPx,
                splitType,
                CONTAINER_POSITION_BOTTOM
        );
        assertEquals(expectedBottomRightOffset, offset);
    }
}
