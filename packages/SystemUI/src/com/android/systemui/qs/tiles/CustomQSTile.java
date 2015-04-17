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
import android.content.Intent;
import android.os.UserHandle;
import org.cyanogenmod.internal.statusbar.StatusBarPanelCustomTile;
import android.text.TextUtils;
import android.util.Log;

import org.cyanogenmod.platform.app.CustomTile;

import com.android.systemui.qs.QSTile;

public class CustomQSTile extends QSTile<QSTile.State> {

    private PendingIntent mOnClick;
    private String mOnClickUri;
    private int mCurrentUserId;

    public CustomQSTile(Host host, StatusBarPanelCustomTile tile) {
        super(host);
        refreshState(tile);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
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
    protected void handleClick() {
        try {
            if (mOnClick != null) {
                mOnClick.send();
            } else if (mOnClickUri != null) {
                final Intent intent = Intent.parseUri(mOnClickUri, Intent.URI_INTENT_SCHEME);
                mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error sending click intent", t);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (!(arg instanceof StatusBarPanelCustomTile)) return;
        final StatusBarPanelCustomTile tile = (StatusBarPanelCustomTile) arg;
        final CustomTile customTile = tile.getCustomTile();
        state.visible = customTile.visibility;
        state.contentDescription = customTile.contentDescription;
        state.label = customTile.label;
        state.iconId = 0;
        final int iconId = customTile.icon;
        if (iconId != 0) {
            final String iconPackage = tile.getPackage();
            if (!TextUtils.isEmpty(iconPackage)) {
                state.icon = getPackageDrawable(iconPackage, iconId);
            } else {
                state.iconId = iconId;
            }
        }
        mOnClick = customTile.onClick;
        mOnClickUri = customTile.onClickUri;
    }

    private Drawable getPackageDrawable(String pkg, int id) {
        try {
            return mContext.createPackageContext(pkg, 0).getResources().getDrawable(id);
        } catch (Throwable t) {
            Log.w(TAG, "Error loading package drawable pkg=" + pkg + " id=" + id, t);
            return null;
        }
    }
}
