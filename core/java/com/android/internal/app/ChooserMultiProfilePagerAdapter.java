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

package com.android.internal.app;

import android.annotation.Nullable;
import android.content.Context;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.PagerAdapter;
import com.android.internal.widget.RecyclerView;

/**
 * A {@link PagerAdapter} which describes the work and personal profile share sheet screens.
 */
@VisibleForTesting
public class ChooserMultiProfilePagerAdapter extends AbstractMultiProfilePagerAdapter {
    private static final int SINGLE_CELL_SPAN_SIZE = 1;

    private final ChooserProfileDescriptor[] mItems;
    private final boolean mIsSendAction;
    private int mBottomOffset;
    private int mMaxTargetsPerRow;

    ChooserMultiProfilePagerAdapter(Context context,
            ChooserActivity.ChooserGridAdapter adapter,
            UserHandle personalProfileUserHandle,
            UserHandle workProfileUserHandle,
            boolean isSendAction, int maxTargetsPerRow) {
        super(context, /* currentPage */ 0, personalProfileUserHandle, workProfileUserHandle);
        mItems = new ChooserProfileDescriptor[] {
                createProfileDescriptor(adapter)
        };
        mIsSendAction = isSendAction;
        mMaxTargetsPerRow = maxTargetsPerRow;
    }

    ChooserMultiProfilePagerAdapter(Context context,
            ChooserActivity.ChooserGridAdapter personalAdapter,
            ChooserActivity.ChooserGridAdapter workAdapter,
            @Profile int defaultProfile,
            UserHandle personalProfileUserHandle,
            UserHandle workProfileUserHandle,
            boolean isSendAction, int maxTargetsPerRow) {
        super(context, /* currentPage */ defaultProfile, personalProfileUserHandle,
                workProfileUserHandle);
        mItems = new ChooserProfileDescriptor[] {
                createProfileDescriptor(personalAdapter),
                createProfileDescriptor(workAdapter)
        };
        mIsSendAction = isSendAction;
        mMaxTargetsPerRow = maxTargetsPerRow;
    }

    private ChooserProfileDescriptor createProfileDescriptor(
            ChooserActivity.ChooserGridAdapter adapter) {
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final ViewGroup rootView =
                (ViewGroup) inflater.inflate(R.layout.chooser_list_per_profile, null, false);
        return new ChooserProfileDescriptor(rootView, adapter);
    }

    RecyclerView getListViewForIndex(int index) {
        return getItem(index).recyclerView;
    }

    @Override
    ChooserProfileDescriptor getItem(int pageIndex) {
        return mItems[pageIndex];
    }

    @Override
    int getItemCount() {
        return mItems.length;
    }

    @Override
    @VisibleForTesting
    public ChooserActivity.ChooserGridAdapter getAdapterForIndex(int pageIndex) {
        return mItems[pageIndex].chooserGridAdapter;
    }

    @Override
    @Nullable
    ChooserListAdapter getListAdapterForUserHandle(UserHandle userHandle) {
        if (getActiveListAdapter().getUserHandle().equals(userHandle)) {
            return getActiveListAdapter();
        } else if (getInactiveListAdapter() != null
                && getInactiveListAdapter().getUserHandle().equals(userHandle)) {
            return getInactiveListAdapter();
        }
        return null;
    }

    @Override
    void setupListAdapter(int pageIndex) {
        final RecyclerView recyclerView = getItem(pageIndex).recyclerView;
        ChooserActivity.ChooserGridAdapter chooserGridAdapter =
                getItem(pageIndex).chooserGridAdapter;
        GridLayoutManager glm = (GridLayoutManager) recyclerView.getLayoutManager();
        glm.setSpanCount(mMaxTargetsPerRow);
        glm.setSpanSizeLookup(
                new GridLayoutManager.SpanSizeLookup() {
                    @Override
                    public int getSpanSize(int position) {
                        return chooserGridAdapter.shouldCellSpan(position)
                                ? SINGLE_CELL_SPAN_SIZE
                                : glm.getSpanCount();
                    }
                });
    }

    @Override
    @VisibleForTesting
    public ChooserListAdapter getActiveListAdapter() {
        return getAdapterForIndex(getCurrentPage()).getListAdapter();
    }

    @Override
    @VisibleForTesting
    public ChooserListAdapter getInactiveListAdapter() {
        if (getCount() == 1) {
            return null;
        }
        return getAdapterForIndex(1 - getCurrentPage()).getListAdapter();
    }

    @Override
    public ResolverListAdapter getPersonalListAdapter() {
        return getAdapterForIndex(PROFILE_PERSONAL).getListAdapter();
    }

    @Override
    @Nullable
    public ResolverListAdapter getWorkListAdapter() {
        return getAdapterForIndex(PROFILE_WORK).getListAdapter();
    }

    @Override
    ChooserActivity.ChooserGridAdapter getCurrentRootAdapter() {
        return getAdapterForIndex(getCurrentPage());
    }

    @Override
    RecyclerView getActiveAdapterView() {
        return getListViewForIndex(getCurrentPage());
    }

    @Override
    @Nullable
    RecyclerView getInactiveAdapterView() {
        if (getCount() == 1) {
            return null;
        }
        return getListViewForIndex(1 - getCurrentPage());
    }

    @Override
    String getMetricsCategory() {
        return ResolverActivity.METRICS_CATEGORY_CHOOSER;
    }

    @Override
    protected void showWorkProfileOffEmptyState(ResolverListAdapter activeListAdapter,
            View.OnClickListener listener) {
        showEmptyState(activeListAdapter,
                R.drawable.ic_work_apps_off,
                R.string.resolver_turn_on_work_apps,
                /* subtitleRes */ 0,
                listener);
    }

    @Override
    protected void showNoPersonalToWorkIntentsEmptyState(ResolverListAdapter activeListAdapter) {
        if (mIsSendAction) {
            showEmptyState(activeListAdapter,
                    R.drawable.ic_sharing_disabled,
                    R.string.resolver_cant_share_with_work_apps,
                    R.string.resolver_cant_share_with_work_apps_explanation);
        } else {
            showEmptyState(activeListAdapter,
                    R.drawable.ic_sharing_disabled,
                    R.string.resolver_cant_access_work_apps,
                    R.string.resolver_cant_access_work_apps_explanation);
        }
    }

    @Override
    protected void showNoWorkToPersonalIntentsEmptyState(ResolverListAdapter activeListAdapter) {
        if (mIsSendAction) {
            showEmptyState(activeListAdapter,
                    R.drawable.ic_sharing_disabled,
                    R.string.resolver_cant_share_with_personal_apps,
                    R.string.resolver_cant_share_with_personal_apps_explanation);
        } else {
            showEmptyState(activeListAdapter,
                    R.drawable.ic_sharing_disabled,
                    R.string.resolver_cant_access_personal_apps,
                    R.string.resolver_cant_access_personal_apps_explanation);
        }
    }

    @Override
    protected void showNoPersonalAppsAvailableEmptyState(ResolverListAdapter listAdapter) {
        if (mIsSendAction) {
            showEmptyState(listAdapter,
                    R.drawable.ic_no_apps,
                    R.string.resolver_no_personal_apps_available_share,
                    /* subtitleRes */ 0);
        } else {
            showEmptyState(listAdapter,
                    R.drawable.ic_no_apps,
                    R.string.resolver_no_personal_apps_available_resolve,
                    /* subtitleRes */ 0);
        }
    }

    @Override
    protected void showNoWorkAppsAvailableEmptyState(ResolverListAdapter listAdapter) {
        if (mIsSendAction) {
            showEmptyState(listAdapter,
                    R.drawable.ic_no_apps,
                    R.string.resolver_no_work_apps_available_share,
                    /* subtitleRes */ 0);
        } else {
            showEmptyState(listAdapter,
                    R.drawable.ic_no_apps,
                    R.string.resolver_no_work_apps_available_resolve,
                    /* subtitleRes */ 0);
        }
    }

    void setEmptyStateBottomOffset(int bottomOffset) {
        mBottomOffset = bottomOffset;
    }

    @Override
    protected void setupContainerPadding(View container) {
        int initialBottomPadding = getContext().getResources().getDimensionPixelSize(
                R.dimen.resolver_empty_state_container_padding_bottom);
        container.setPadding(container.getPaddingLeft(), container.getPaddingTop(),
                container.getPaddingRight(), initialBottomPadding + mBottomOffset);
    }

    class ChooserProfileDescriptor extends ProfileDescriptor {
        private ChooserActivity.ChooserGridAdapter chooserGridAdapter;
        private RecyclerView recyclerView;
        ChooserProfileDescriptor(ViewGroup rootView, ChooserActivity.ChooserGridAdapter adapter) {
            super(rootView);
            chooserGridAdapter = adapter;
            recyclerView = rootView.findViewById(R.id.resolver_list);
        }
    }
}
