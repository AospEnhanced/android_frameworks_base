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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityThread;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.window.TaskFragmentAppearedInfo;
import android.window.TaskFragmentInfo;
import android.window.WindowContainerTransaction;

import androidx.window.extensions.ExtensionInterface.SplitOrganizerCallback;
import androidx.window.extensions.ExtensionSplitActivityRule;
import androidx.window.extensions.ExtensionSplitInfo;
import androidx.window.extensions.ExtensionSplitPairRule;
import androidx.window.extensions.ExtensionSplitRule;
import androidx.window.extensions.ExtensionTaskFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Main controller class that manages split states and presentation.
 */
public class SplitController implements JetpackTaskFragmentOrganizer.TaskFragmentCallback {

    private final SplitPresenter mPresenter;

    // Currently applied split configuration.
    private final List<ExtensionSplitRule> mSplitRules = new ArrayList<>();
    private final List<TaskFragmentContainer> mContainers = new ArrayList<>();
    private final List<SplitContainer> mSplitContainers = new ArrayList<>();

    // Callback to Jetpack to notify about changes to split states.
    private SplitOrganizerCallback mSplitOrganizerCallback;

    public SplitController() {
        mPresenter = new SplitPresenter(ActivityThread.currentActivityThread().getExecutor(),
                this);
        // Register a callback to be notified about activities being created.
        ActivityThread.currentActivityThread().getApplication().registerActivityLifecycleCallbacks(
                new LifecycleCallbacks());
    }

    public void setSplitRules(@NonNull List<ExtensionSplitRule> splitRules) {
        mSplitRules.clear();
        mSplitRules.addAll(splitRules);
    }

    @NonNull
    public List<ExtensionSplitRule> getSplitRules() {
        return mSplitRules;
    }

    /**
     * Starts an activity to side of the launchingActivity with the provided split config.
     */
    public void startActivityToSide(@NonNull Activity launchingActivity, @NonNull Intent intent,
            @Nullable Bundle options, @NonNull ExtensionSplitPairRule splitPairRule,
            int startRequestId) {
        try {
            mPresenter.startActivityToSide(launchingActivity, intent, options, splitPairRule);
        } catch (Exception e) {
            if (mSplitOrganizerCallback != null && startRequestId != -1) {
                mSplitOrganizerCallback.onActivityFailedToStartInContainer(startRequestId, e);
            }
        }
    }

    /**
     * Registers the split organizer callback to notify about changes to active splits.
     */
    public void setSplitOrganizerCallback(@NonNull SplitOrganizerCallback callback) {
        mSplitOrganizerCallback = callback;
        updateCallbackIfNecessary();
    }

    @Override
    public void onTaskFragmentAppeared(@NonNull TaskFragmentAppearedInfo taskFragmentAppearedInfo) {
        for (TaskFragmentContainer container : mContainers) {
            if (container.getTaskFragmentToken().equals(
                    taskFragmentAppearedInfo.getTaskFragmentInfo().getFragmentToken())) {
                container.setInfo(taskFragmentAppearedInfo.getTaskFragmentInfo());
                return;
            }
        }
    }

    @Override
    public void onTaskFragmentInfoChanged(@NonNull TaskFragmentInfo taskFragmentInfo) {
        for (TaskFragmentContainer container : mContainers) {
            if (container.getTaskFragmentToken().equals(taskFragmentInfo.getFragmentToken())) {
                container.setInfo(taskFragmentInfo);

                if (taskFragmentInfo.isEmpty()) {
                    cleanupContainer(container, true /* shouldFinishDependent */);
                    updateCallbackIfNecessary();
                }
                return;
            }
        }
    }

    @Override
    public void onTaskFragmentVanished(@NonNull TaskFragmentInfo taskFragmentInfo) {
        for (TaskFragmentContainer container : mContainers) {
            if (container.getTaskFragmentToken().equals(taskFragmentInfo.getFragmentToken())) {
                cleanupContainer(container, true /* shouldFinishDependent */);
                updateCallbackIfNecessary();
                return;
            }
        }
    }

    @Override
    public void onTaskFragmentParentInfoChanged(@NonNull IBinder fragmentToken,
            @NonNull Configuration parentConfig) {
        TaskFragmentContainer container = getContainer(fragmentToken);
        if (container != null) {
            mPresenter.updateContainer(container);
            updateCallbackIfNecessary();
        }
    }

    /**
     * Checks if the activity start should be routed to a particular container. It can create a new
     * container for the activity and a new split container if necessary.
     */
    void onActivityCreated(@NonNull Activity launchedActivity) {
        final ComponentName componentName = launchedActivity.getComponentName();

        final List<ExtensionSplitRule> splitRules = getSplitRules();
        final TaskFragmentContainer currentContainer = getContainerWithActivity(
                launchedActivity.getActivityToken());

        // Check if the activity is configured to always be expanded.
        if (shouldExpand(componentName, splitRules)) {
            if (shouldContainerBeExpanded(currentContainer)) {
                // Make sure that the existing container is expanded
                mPresenter.expandTaskFragment(currentContainer.getTaskFragmentToken());
            } else {
                // Put activity into a new expanded container
                final TaskFragmentContainer newContainer = newContainer(launchedActivity);
                mPresenter.expandActivity(newContainer.getTaskFragmentToken(),
                        launchedActivity);
            }
            return;
        }

        // Check if activity requires a placeholder
        if (launchPlaceholderIfNecessary(launchedActivity)) {
            return;
        }

        // TODO(b/190433398): Check if it is a placeholder and there is already another split
        // created by the primary activity. This is necessary for the case when the primary activity
        // launched another secondary in the split, but the placeholder was still launched by the
        // logic above. We didn't prevent the placeholder launcher because we didn't know that
        // another secondary activity is coming up.

        // Check if the activity should form a split with the activity below in the same task
        // fragment.
        Activity activityBelow = null;
        if (currentContainer != null) {
            final List<Activity> containerActivities = currentContainer.collectActivities();
            final int index = containerActivities.indexOf(launchedActivity);
            if (index > 0) {
                activityBelow = containerActivities.get(index - 1);
            }
        }
        if (activityBelow == null) {
            IBinder belowToken = ActivityClient.getInstance().getActivityTokenBelow(
                    launchedActivity.getActivityToken());
            if (belowToken != null) {
                activityBelow = ActivityThread.currentActivityThread().getActivity(belowToken);
            }
        }
        if (activityBelow == null) {
            return;
        }

        final ExtensionSplitPairRule splitPairRule = getSplitRule(
                activityBelow.getComponentName(), componentName, splitRules);
        if (splitPairRule == null) {
            return;
        }

        mPresenter.createNewSplitContainer(activityBelow, launchedActivity,
                splitPairRule);

        updateCallbackIfNecessary();
    }

    /**
     * Returns a container that this activity is registered with. An activity can only belong to one
     * container, or no container at all.
     */
    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull IBinder activityToken) {
        for (TaskFragmentContainer container : mContainers) {
            if (container.hasActivity(activityToken)) {
                return container;
            }
        }

        return null;
    }

    /**
     * Creates and registers a new organized container with an optional activity that will be
     * re-parented to it in a WCT.
     */
    TaskFragmentContainer newContainer(@Nullable Activity activity) {
        TaskFragmentContainer container = new TaskFragmentContainer(activity);
        mContainers.add(container);
        return container;
    }

    /**
     * Creates and registers a new split with the provided containers and configuration.
     */
    void registerSplit(@NonNull TaskFragmentContainer primaryContainer,
            @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull ExtensionSplitPairRule splitPairRule) {
        SplitContainer splitContainer = new SplitContainer(primaryContainer, primaryActivity,
                secondaryContainer, splitPairRule);
        mSplitContainers.add(splitContainer);
    }

    void cleanupContainer(@NonNull TaskFragmentContainer container, boolean shouldFinishDependent) {
        if (container.isFinished()) {
            return;
        }

        container.finish(shouldFinishDependent);

        // Remove all split containers that included this one
        mContainers.remove(container);
        List<SplitContainer> containersToRemove = new ArrayList<>();
        for (SplitContainer splitContainer : mSplitContainers) {
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                containersToRemove.add(splitContainer);
            }
        }
        mSplitContainers.removeAll(containersToRemove);

        mPresenter.deleteContainer(container);
    }

    /**
     * Returns the topmost not finished container.
     */
    @Nullable
    TaskFragmentContainer getTopActiveContainer() {
        for (int i = mContainers.size() - 1; i >= 0; i--) {
            TaskFragmentContainer container = mContainers.get(i);
            if (!container.isFinished()) {
                return container;
            }
        }
        return null;
    }

    /**
     * Updates the presentation of the container. If the container is part of the split or should
     * have a placeholder, it will also update the other part of the split.
     */
    void updateContainer(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        if (launchPlaceholderIfNecessary(container)) {
            // Placeholder was launched, the positions will be updated when the activity is added
            // to the secondary container.
            return;
        }
        if (shouldContainerBeExpanded(container)) {
            if (container.getInfo() != null) {
                mPresenter.expandTaskFragment(wct, container.getTaskFragmentToken());
            }
            // If the info is not available yet the task fragment will be expanded when it's ready
            return;
        }
        SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer == null) {
            return;
        }
        if (splitContainer != mSplitContainers.get(mSplitContainers.size() - 1)) {
            // Skip position update - it isn't the topmost split.
            return;
        }
        if (splitContainer.getPrimaryContainer().isEmpty()
                || splitContainer.getSecondaryContainer().isEmpty()) {
            // Skip position update - one or both containers are empty.
            return;
        }
        if (dismissPlaceholderIfNecessary(splitContainer)) {
            // Placeholder was finished, the positions will be updated when its container is emptied
            return;
        }
        mPresenter.updateSplitContainer(splitContainer, container, wct);
    }

    /**
     * Returns the top active split container that has the provided container.
     */
    @Nullable
    private SplitContainer getActiveSplitForContainer(@NonNull TaskFragmentContainer container) {
        for (int i = mSplitContainers.size() - 1; i >= 0; i--) {
            SplitContainer splitContainer = mSplitContainers.get(i);
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Checks if the container requires a placeholder and launches it if necessary.
     */
    private boolean launchPlaceholderIfNecessary(@NonNull TaskFragmentContainer container) {
        final Activity topActivity = container.getTopNonFinishingActivity();
        if (topActivity == null) {
            return false;
        }

        return launchPlaceholderIfNecessary(topActivity);
    }

    boolean launchPlaceholderIfNecessary(@NonNull Activity activity) {
        final  TaskFragmentContainer container = getContainerWithActivity(
                activity.getActivityToken());

        SplitContainer splitContainer = container != null ? getActiveSplitForContainer(container)
                : null;
        if (splitContainer != null && container.equals(splitContainer.getPrimaryContainer())) {
            // Don't launch placeholder in primary split container
            return false;
        }

        // Check if there is enough space for launch
        final ExtensionSplitPairRule placeholderRule = getPlaceholderRule(
                activity.getComponentName());
        if (placeholderRule == null || !mPresenter.shouldShowSideBySide(
                mPresenter.getParentContainerBounds(activity), placeholderRule)) {
            return false;
        }

        Intent placeholderIntent = new Intent();
        placeholderIntent.setComponent(placeholderRule.secondaryActivityName);
        // TODO(b/190433398): Handle failed request
        startActivityToSide(activity, placeholderIntent, null, placeholderRule, -1);
        return true;
    }

    private boolean dismissPlaceholderIfNecessary(@NonNull SplitContainer splitContainer) {
        if (!splitContainer.getSplitPairRule().useAsPlaceholder) {
            return false;
        }

        if (mPresenter.shouldShowSideBySide(splitContainer)) {
            return false;
        }

        cleanupContainer(splitContainer.getSecondaryContainer(),
                false /* shouldFinishDependent */);
        return true;
    }

    /**
     * Returns the rule to launch a placeholder for the activity with the provided component name
     * if it is configured in the split config.
     */
    private ExtensionSplitPairRule getPlaceholderRule(@NonNull ComponentName componentName) {
        for (ExtensionSplitRule rule : mSplitRules) {
            if (!(rule instanceof ExtensionSplitPairRule)) {
                continue;
            }
            ExtensionSplitPairRule pairRule = (ExtensionSplitPairRule) rule;
            if (componentName.equals(pairRule.primaryActivityName)
                    && pairRule.useAsPlaceholder) {
                return pairRule;
            }
        }
        return null;
    }

    /**
     * Notifies listeners about changes to split states if necessary.
     */
    private void updateCallbackIfNecessary() {
        if (mSplitOrganizerCallback == null) {
            return;
        }
        // TODO(b/190433398): Check if something actually changed
        mSplitOrganizerCallback.onSplitInfoChanged(getActiveSplitStates());
    }

    /**
     * Returns a list of descriptors for currently active split states.
     */
    private List<ExtensionSplitInfo> getActiveSplitStates() {
        List<ExtensionSplitInfo> splitStates = new ArrayList<>();
        for (SplitContainer container : mSplitContainers) {
            ExtensionTaskFragment primaryContainer =
                    new ExtensionTaskFragment(
                            container.getPrimaryContainer().collectActivities());
            ExtensionTaskFragment secondaryContainer =
                    new ExtensionTaskFragment(
                            container.getSecondaryContainer().collectActivities());
            ExtensionSplitInfo splitState = new ExtensionSplitInfo(primaryContainer,
                    secondaryContainer, container.getSplitPairRule().splitRatio);
            splitStates.add(splitState);
        }
        return splitStates;
    }

    /**
     * Returns {@code true} if the container is expanded to occupy full task size.
     * Returns {@code false} if the container is included in an active split.
     */
    boolean shouldContainerBeExpanded(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return false;
        }
        for (SplitContainer splitContainer : mSplitContainers) {
            if (container.equals(splitContainer.getPrimaryContainer())
                    || container.equals(splitContainer.getSecondaryContainer())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a split rule for the provided pair of component names if available.
     */
    @Nullable
    private static ExtensionSplitPairRule getSplitRule(@NonNull ComponentName primaryActivityName,
            @NonNull ComponentName secondaryActivityName,
            @NonNull List<ExtensionSplitRule> splitRules) {
        if (splitRules == null || primaryActivityName == null || secondaryActivityName == null) {
            return null;
        }

        for (ExtensionSplitRule rule : splitRules) {
            if (!(rule instanceof ExtensionSplitPairRule)) {
                continue;
            }
            ExtensionSplitPairRule pairRule = (ExtensionSplitPairRule) rule;
            if (match(secondaryActivityName, pairRule.secondaryActivityName)
                    && match(primaryActivityName, pairRule.primaryActivityName)) {
                return pairRule;
            }
        }
        return null;
    }

    @Nullable
    private TaskFragmentContainer getContainer(@NonNull IBinder fragmentToken) {
        for (TaskFragmentContainer container : mContainers) {
            if (container.getTaskFragmentToken().equals(fragmentToken)) {
                return container;
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if an Activity with the provided component name should always be
     * expanded to occupy full task bounds. Such activity must not be put in a split.
     */
    private static boolean shouldExpand(@NonNull ComponentName componentName,
            List<ExtensionSplitRule> splitRules) {
        if (splitRules == null) {
            return false;
        }
        for (ExtensionSplitRule rule : splitRules) {
            if (!(rule instanceof ExtensionSplitActivityRule)) {
                continue;
            }
            ExtensionSplitActivityRule activityRule = (ExtensionSplitActivityRule) rule;
            if (match(componentName, activityRule.activityName)
                    && activityRule.alwaysExpand) {
                return true;
            }
        }
        return false;
    }

    /** Match check allowing wildcards for activity class name but not package name. */
    private static boolean match(@NonNull ComponentName activityComponent,
            @NonNull ComponentName ruleComponent) {
        if (activityComponent.toString().contains("*")) {
            throw new IllegalArgumentException("Wildcard can only be part of the rule.");
        }
        final boolean packagesMatch =
                activityComponent.getPackageName().equals(ruleComponent.getPackageName());
        final boolean classesMatch =
                activityComponent.getClassName().equals(ruleComponent.getClassName());
        return packagesMatch && (classesMatch
                || wildcardMatch(activityComponent.getClassName(), ruleComponent.getClassName()));
    }

    /**
     * Checks if the provided name matches the pattern.
     */
    private static boolean wildcardMatch(@NonNull String name, @NonNull String pattern) {
        if (!pattern.contains("*")) {
            return false;
        }
        if (pattern.equals("*")) {
            return true;
        }
        if (pattern.indexOf("*") != pattern.lastIndexOf("*") || !pattern.endsWith("*")) {
            throw new IllegalArgumentException(
                    "Name pattern with a wildcard must only contain a single * in the end");
        }
        return name.startsWith(pattern.substring(0, pattern.length() - 1));
    }

    private final class LifecycleCallbacks implements ActivityLifecycleCallbacks {

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            // Calling after Activity#onCreate is complete to allow the app launch something
            // first. In case of a configured placeholder activity we want to make sure
            // that we don't launch it if an activity itself already requested something to be
            // launched to side.
            SplitController.this.onActivityCreated(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }
}
