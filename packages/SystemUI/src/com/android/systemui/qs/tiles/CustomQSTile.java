/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.systemui.qs.tiles;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.android.systemui.qs.QSDetailItemsList;
import cyanogenmod.app.CustomTile;
import cyanogenmod.app.StatusBarPanelCustomTile;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

import java.util.Arrays;

public class CustomQSTile extends QSTile<QSTile.State> {

    private CustomTile.ExpandedStyle mExpandedStyle;
    private PendingIntent mOnClick;
    private Uri mOnClickUri;
    private int mCurrentUserId;
    private StatusBarPanelCustomTile mTile;
    private CustomQSDetailAdapter mDetailAdapter;

    public CustomQSTile(Host host, StatusBarPanelCustomTile tile) {
        super(host);
        refreshState(tile);
        mDetailAdapter = new CustomQSDetailAdapter();
    }

    @Override
    public DetailAdapter getDetailAdapter() {
        return mDetailAdapter;
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        mCurrentUserId = newUserId;
    }

    public void update(StatusBarPanelCustomTile customTile) {
        refreshState(customTile);
    }

    @Override
    protected void handleLongClick() {
        if (mExpandedStyle != null) {
            showDetail(true);
        }
    }

    @Override
    protected void handleClick() {
        try {
            if (mExpandedStyle != null) {
                showDetail(true);
            }
            if (mOnClick != null) {
                if (mOnClick.isActivity()) {
                    mHost.collapsePanels();
                }
                mOnClick.send();
            } else if (mOnClickUri != null) {
                final Intent intent = new Intent().setData(mOnClickUri);
                mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error sending click intent", t);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (!(arg instanceof StatusBarPanelCustomTile)) return;
        mTile = (StatusBarPanelCustomTile) arg;
        final CustomTile customTile = mTile.getCustomTile();
        state.visible = true;
        state.contentDescription = customTile.contentDescription;
        state.label = customTile.label;
        state.iconId = 0;
        final int iconId = customTile.icon;
        if (iconId != 0) {
            final String iconPackage = mTile.getPackage();
            if (!TextUtils.isEmpty(iconPackage)) {
                state.icon = new ExternalIcon(iconPackage, iconId);
            } else {
                state.iconId = iconId;
            }
        }
        mOnClick = customTile.onClick;
        mOnClickUri = customTile.onClickUri;
        mExpandedStyle = customTile.expandedStyle;
    }

    private class CustomQSDetailAdapter implements DetailAdapter, AdapterView.OnItemClickListener {
        private QSDetailItemsList.QSCustomDetailListAdapter mAdapter;

        @Override
        public int getTitle() {
            return R.string.quick_settings_custom_tile_detail_title;
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }


        @Override
        public Intent getSettingsIntent() {
            return mTile.getCustomTile().onSettingsClick;
        }

        @Override
        public StatusBarPanelCustomTile getCustomTile() {
            return mTile;
        }

        @Override
        public void setToggleState(boolean state) {
            // noop
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            View rootView = null;
            if (mExpandedStyle != null) {
                rootView = (LinearLayout) LayoutInflater.from(context)
                        .inflate(R.layout.qs_custom_detail, parent, false);
                ImageView imageView = (ImageView)
                        rootView.findViewById(R.id.custom_qs_tile_icon);
                TextView customTileTitle = (TextView)
                        rootView.findViewById(R.id.custom_qs_tile_title);
                TextView customTilePkg = (TextView) rootView
                        .findViewById(R.id.custom_qs_tile_package);
                TextView customTileContentDesc = (TextView) rootView
                        .findViewById(R.id.custom_qs_tile_content_description);
                // icon is cached in state, fetch it
                imageView.setImageDrawable(getState().icon.getDrawable(mContext));
                customTileTitle.setText(mTile.getCustomTile().label);
                customTilePkg.setText(mTile.getPackage());
                customTileContentDesc.setText(mTile.getCustomTile().contentDescription);
            } else {
                switch (mExpandedStyle.getStyle()) {
                    case CustomTile.ExpandedStyle.GRID_STYLE:
                        //TODO: Finish grid style
                        //rootView = (LinearLayout) LayoutInflater.from(context)
                        //        .inflate(R.layout.qs_detail_items_list, parent, false);
                        break;
                    case CustomTile.ExpandedStyle.LIST_STYLE:
                    default:
                        rootView = QSDetailItemsList.convertOrInflate(context, convertView, parent);
                        ListView listView = ((QSDetailItemsList) rootView).getListView();
                        listView.setDivider(null);
                        listView.setOnItemClickListener(this);
                        listView.setAdapter(mAdapter =
                                new QSDetailItemsList.QSCustomDetailListAdapter(mTile.getPackage(),
                                        context, Arrays.asList(mExpandedStyle.getExpandedItems())));
                        ((QSDetailItemsList) rootView)
                                .setEmptyState(R.drawable.ic_qs_wifi_detail_empty,
                                R.string.quick_settings_wifi_detail_empty_text);
                        break;
                }
            }
            return rootView;
        }

        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            CustomTile.ExpandedItem item = mAdapter.getItem(i);
            try {
                if (item.mOnClickPendingIntent.isActivity()) {
                    mHost.collapsePanels();
                }
                item.mOnClickPendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                //
            }
        }
    }
}
