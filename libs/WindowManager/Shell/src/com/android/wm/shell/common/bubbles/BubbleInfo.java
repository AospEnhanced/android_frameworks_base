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
 */

package com.android.wm.shell.common.bubbles;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Contains information necessary to present a bubble.
 */
public class BubbleInfo implements Parcelable {

    // TODO(b/269672147): needs a title string for a11y & that comes from notification
    // TODO(b/269671451): needs whether the bubble is an 'important person' or not

    private String mKey; // Same key as the Notification
    private int mFlags;  // Flags from BubbleMetadata
    private String mShortcutId;
    private int mUserId;
    private String mPackageName;
    /**
     * All notification bubbles require a shortcut to be set on the notification, however, the
     * app could still specify an Icon and PendingIntent to use for the bubble. In that case
     * this icon will be populated. If the bubble is entirely shortcut based, this will be null.
     */
    @Nullable
    private Icon mIcon;

    public BubbleInfo(String key, int flags, String shortcutId, @Nullable Icon icon,
            int userId, String packageName) {
        mKey = key;
        mFlags = flags;
        mShortcutId = shortcutId;
        mIcon = icon;
        mUserId = userId;
        mPackageName = packageName;
    }

    public BubbleInfo(Parcel source) {
        mKey = source.readString();
        mFlags = source.readInt();
        mShortcutId = source.readString();
        mIcon = source.readTypedObject(Icon.CREATOR);
        mUserId = source.readInt();
        mPackageName = source.readString();
    }

    public String getKey() {
        return mKey;
    }

    public String getShortcutId() {
        return mShortcutId;
    }

    public Icon getIcon() {
        return mIcon;
    }

    public int getFlags() {
        return mFlags;
    }

    public int getUserId() {
        return mUserId;
    }

    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Whether this bubble is currently being hidden from the stack.
     */
    public boolean isBubbleSuppressed() {
        return (mFlags & Notification.BubbleMetadata.FLAG_SUPPRESS_BUBBLE) != 0;
    }

    /**
     * Whether this bubble is able to be suppressed (i.e. has the developer opted into the API
     * to
     * hide the bubble when in the same content).
     */
    public boolean isBubbleSuppressable() {
        return (mFlags & Notification.BubbleMetadata.FLAG_SUPPRESSABLE_BUBBLE) != 0;
    }

    /**
     * Whether the notification for this bubble is hidden from the shade.
     */
    public boolean isNotificationSuppressed() {
        return (mFlags & Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BubbleInfo)) return false;
        BubbleInfo bubble = (BubbleInfo) o;
        return Objects.equals(mKey, bubble.mKey);
    }

    @Override
    public int hashCode() {
        return mKey.hashCode();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mKey);
        parcel.writeInt(mFlags);
        parcel.writeString(mShortcutId);
        parcel.writeTypedObject(mIcon, flags);
        parcel.writeInt(mUserId);
        parcel.writeString(mPackageName);
    }

    @NonNull
    public static final Creator<BubbleInfo> CREATOR =
            new Creator<BubbleInfo>() {
                public BubbleInfo createFromParcel(Parcel source) {
                    return new BubbleInfo(source);
                }

                public BubbleInfo[] newArray(int size) {
                    return new BubbleInfo[size];
                }
            };
}
