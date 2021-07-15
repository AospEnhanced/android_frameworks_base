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

package androidx.window.extensions.organizer;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.window.TaskFragmentCreationParams;
import android.window.WindowContainerTransaction;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.ExtensionSplitPairRule;

import java.util.concurrent.Executor;

/**
 * Controls the visual presentation of the splits according to the containers formed by
 * {@link SplitController}.
 */
class SplitPresenter extends JetpackTaskFragmentOrganizer {
    private static final int POSITION_LEFT = 0;
    private static final int POSITION_RIGHT = 1;
    private static final int POSITION_FILL = 2;

    @IntDef(value = {
            POSITION_LEFT,
            POSITION_RIGHT,
            POSITION_FILL,
    })
    private @interface Position {}

    private final SplitController mController;

    SplitPresenter(@NonNull Executor executor, SplitController controller) {
        super(executor, controller);
        mController = controller;
        registerOrganizer();
    }

    /**
     * Updates the presentation of the provided container.
     */
    void updateContainer(TaskFragmentContainer container) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mController.updateContainer(wct, container);
        applyTransaction(wct);
    }

    /**
     * Deletes the provided container and updates the presentation if necessary.
     */
    void deleteContainer(TaskFragmentContainer container) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        deleteTaskFragment(wct, container.getTaskFragmentToken());

        final TaskFragmentContainer newTopContainer = mController.getTopActiveContainer();
        if (newTopContainer != null) {
            mController.updateContainer(wct, newTopContainer);
        }

        applyTransaction(wct);
    }

    /**
     * Creates a new split container with the two provided activities.
     * @param primaryActivity An activity that should be in the primary container. If it is not
     *                        currently in an existing container, a new one will be created and the
     *                        activity will be re-parented to it.
     * @param secondaryActivity An activity that should be in the secondary container. If it is not
     *                          currently in an existing container, or if it is currently in the
     *                          same container as the primary activity, a new container will be
     *                          created and the activity will be re-parented to it.
     * @param rule The split rule to be applied to the container.
     */
    void createNewSplitContainer(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity, @NonNull ExtensionSplitPairRule rule) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final Rect parentBounds = getParentContainerBounds(primaryActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_LEFT, parentBounds, rule);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_RIGHT, parentBounds, rule);

        TaskFragmentContainer primaryContainer = mController.getContainerWithActivity(
                primaryActivity.getActivityToken());
        if (primaryContainer == null) {
            primaryContainer = mController.newContainer(primaryActivity);

            final TaskFragmentCreationParams fragmentOptions =
                    createFragmentOptions(
                            primaryContainer.getTaskFragmentToken(),
                            primaryActivity.getActivityToken(),
                            primaryRectBounds,
                            WINDOWING_MODE_MULTI_WINDOW);
            wct.createTaskFragment(fragmentOptions);

            wct.reparentActivityToTaskFragment(primaryContainer.getTaskFragmentToken(),
                    primaryActivity.getActivityToken());
        } else {
            resizeTaskFragmentIfRegistered(wct, primaryContainer, primaryRectBounds);
        }

        TaskFragmentContainer secondaryContainer = mController.getContainerWithActivity(
                secondaryActivity.getActivityToken());
        if (secondaryContainer == null || secondaryContainer == primaryContainer) {
            secondaryContainer = mController.newContainer(secondaryActivity);

            final TaskFragmentCreationParams fragmentOptions =
                    createFragmentOptions(
                            secondaryContainer.getTaskFragmentToken(),
                            secondaryActivity.getActivityToken(),
                            secondaryRectBounds,
                            WINDOWING_MODE_MULTI_WINDOW);
            wct.createTaskFragment(fragmentOptions);

            wct.reparentActivityToTaskFragment(secondaryContainer.getTaskFragmentToken(),
                    secondaryActivity.getActivityToken());
        } else {
            resizeTaskFragmentIfRegistered(wct, secondaryContainer, secondaryRectBounds);
        }

        // TODO(b/190433398): The primary container and the secondary container should also be set
        // as adjacent (WCT#setAdjacentRoots) to make activities behind invisible.
        applyTransaction(wct);

        mController.registerSplit(primaryContainer, primaryActivity, secondaryContainer, rule);
    }

    /**
     * Starts a new activity to the side, creating a new split container. A new container will be
     * created for the activity that will be started.
     * @param launchingActivity An activity that should be in the primary container. If it is not
     *                          currently in an existing container, a new one will be created and
     *                          the activity will be re-parented to it.
     * @param activityIntent The intent to start the new activity.
     * @param activityOptions The options to apply to new activity start.
     * @param rule The split rule to be applied to the container.
     */
    void startActivityToSide(@NonNull Activity launchingActivity, @NonNull Intent activityIntent,
            @Nullable Bundle activityOptions, @NonNull ExtensionSplitPairRule rule) {
        final Rect parentBounds = getParentContainerBounds(launchingActivity);
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_LEFT, parentBounds, rule);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_RIGHT, parentBounds, rule);

        TaskFragmentContainer primaryContainer = mController.getContainerWithActivity(
                launchingActivity.getActivityToken());
        if (primaryContainer == null) {
            primaryContainer = mController.newContainer(launchingActivity);
        }

        TaskFragmentContainer secondaryContainer = mController.newContainer(null);
        startActivityToSide(
                primaryContainer.getTaskFragmentToken(),
                primaryRectBounds,
                launchingActivity,
                secondaryContainer.getTaskFragmentToken(),
                secondaryRectBounds,
                activityIntent,
                activityOptions);

        // TODO(b/190433398): The primary container and the secondary container should also be set
        // as adjacent (WCT#setAdjacentRoots) to make activities behind invisible.

        mController.registerSplit(primaryContainer, launchingActivity, secondaryContainer,
                rule);
    }

    /**
     * Updates the positions of containers in an existing split.
     * @param splitContainer The split container to be updated.
     * @param updatedContainer The task fragment that was updated and caused this split update.
     * @param wct WindowContainerTransaction that this update should be performed with.
     */
    void updateSplitContainer(@NonNull SplitContainer splitContainer,
            @NonNull TaskFragmentContainer updatedContainer,
            @NonNull WindowContainerTransaction wct) {
        // Getting the parent bounds using the updated container - it will have the recent value.
        final Rect parentBounds = getParentContainerBounds(updatedContainer);
        final ExtensionSplitPairRule rule = splitContainer.getSplitPairRule();
        final Rect primaryRectBounds = getBoundsForPosition(POSITION_LEFT, parentBounds, rule);
        final Rect secondaryRectBounds = getBoundsForPosition(POSITION_RIGHT, parentBounds, rule);

        // TODO(b/190433398): Check if the bounds actually changed.
        // If the task fragments are not registered yet, the positions will be updated after they
        // are created again.
        resizeTaskFragmentIfRegistered(wct, splitContainer.getPrimaryContainer(),
                primaryRectBounds);
        resizeTaskFragmentIfRegistered(wct, splitContainer.getSecondaryContainer(),
                secondaryRectBounds);
    }

    /**
     * Resizes the task fragment if it was already registered. Skips the operation if the container
     * creation has not been reported from the server yet.
     */
    // TODO(b/190433398): Handle resize if the fragment hasn't appeared yet.
    void resizeTaskFragmentIfRegistered(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container,
            @Nullable Rect bounds) {
        if (container.getInfo() == null) {
            return;
        }
        // TODO(b/190433398): Check if the bounds actually changed.
        resizeTaskFragment(wct, container.getTaskFragmentToken(), bounds);
    }

    boolean shouldShowSideBySide(@NonNull SplitContainer splitContainer) {
        final Rect parentBounds = getParentContainerBounds(splitContainer.getPrimaryContainer());
        return shouldShowSideBySide(parentBounds, splitContainer.getSplitPairRule());
    }

    boolean shouldShowSideBySide(@Nullable Rect parentBounds,
            @NonNull ExtensionSplitPairRule rule) {
        return parentBounds != null && parentBounds.width() >= rule.minWidth
                // TODO(b/190433398): Consider proper smallest width computation.
                && Math.min(parentBounds.width(), parentBounds.height()) >= rule.minSmallestWidth;
    }

    @NonNull
    private Rect getBoundsForPosition(@Position int position, @NonNull Rect parentBounds,
            @NonNull ExtensionSplitPairRule rule) {
        if (!shouldShowSideBySide(parentBounds, rule)) {
            return new Rect();
        }

        float splitRatio = rule.splitRatio;
        switch (position) {
            case POSITION_LEFT:
                return new Rect(
                        parentBounds.left,
                        parentBounds.top,
                        (int) (parentBounds.left + parentBounds.width() * splitRatio),
                        parentBounds.bottom);
            case POSITION_RIGHT:
                return new Rect(
                        (int) (parentBounds.left + parentBounds.width() * splitRatio),
                        parentBounds.top,
                        parentBounds.right,
                        parentBounds.bottom);
            case POSITION_FILL:
                return parentBounds;
        }
        return parentBounds;
    }

    @NonNull
    Rect getParentContainerBounds(@NonNull TaskFragmentContainer container) {
        final Configuration parentConfig = mFragmentParentConfigs.get(
                container.getTaskFragmentToken());
        if (parentConfig != null) {
            return parentConfig.windowConfiguration.getBounds();
        }

        // If there is no parent yet - then assuming that activities are running in full task bounds
        final Activity topActivity = container.getTopNonFinishingActivity();
        final Rect bounds = topActivity != null ? getParentContainerBounds(topActivity) : null;

        if (bounds == null) {
            throw new IllegalStateException("Unknown parent bounds");
        }
        return bounds;
    }

    @NonNull
    Rect getParentContainerBounds(@NonNull Activity activity) {
        final TaskFragmentContainer container = mController.getContainerWithActivity(
                activity.getActivityToken());
        if (container != null) {
            final Configuration parentConfig = mFragmentParentConfigs.get(
                    container.getTaskFragmentToken());
            if (parentConfig != null) {
                return parentConfig.windowConfiguration.getBounds();
            }
        }

        // TODO(b/190433398): Check if the client-side available info about parent bounds is enough.
        if (!activity.isInMultiWindowMode()) {
            // In fullscreen mode the max bounds should correspond to the task bounds.
            return activity.getResources().getConfiguration().windowConfiguration.getMaxBounds();
        }
        return activity.getResources().getConfiguration().windowConfiguration.getBounds();
    }
}
