/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.search;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Represents a searchable action info that can be called from another process
 * or within the client process.
 *
 * @hide
 */
@SystemApi
public final class SearchAction implements Parcelable {

    private static final String TAG = "SearchAction";

    @NonNull
    private String mId;

    @Nullable
    private final Icon mIcon;

    @NonNull
    private final CharSequence mTitle;

    @Nullable
    private final CharSequence mSubtitle;

    @Nullable
    private final CharSequence mContentDescription;

    @Nullable
    private final PendingIntent mPendingIntent;

    @Nullable
    private final Intent mIntent;

    @Nullable
    private final UserHandle mUserHandle;

    @Nullable
    private final Bundle mExtras;

    SearchAction(Parcel in) {
        mId = in.readString();
        mTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mIcon = in.readTypedObject(Icon.CREATOR);
        mSubtitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
        mIntent = in.readTypedObject(Intent.CREATOR);
        mUserHandle = in.readTypedObject(UserHandle.CREATOR);
        mExtras = in.readTypedObject(Bundle.CREATOR);
    }

    private SearchAction(
            @NonNull String id,
            @NonNull CharSequence title,
            @Nullable Icon icon,
            @Nullable CharSequence subtitle,
            @Nullable CharSequence contentDescription,
            @Nullable PendingIntent pendingIntent,
            @Nullable Intent intent,
            @Nullable UserHandle userHandle,
            @Nullable Bundle extras) {
        mId = Objects.requireNonNull(id);
        mTitle = Objects.requireNonNull(title);
        mIcon = icon;
        mSubtitle = subtitle;
        mContentDescription = contentDescription;
        mPendingIntent = pendingIntent;
        mIntent = intent;
        mUserHandle = userHandle;
        mExtras = extras != null ? extras : new Bundle();

        if (mPendingIntent == null && mIntent == null) {
            throw new IllegalStateException("At least one type of intent should be available.");
        }
        if (mPendingIntent != null && mIntent != null) {
            throw new IllegalStateException("Only one type of intent should be available.");
        }
    }

    /**
     * Returns the unique id of this object.
     */
    public @NonNull String getId() {
        return mId;
    }

    /**
     * Returns an icon representing the action.
     */
    public @Nullable Icon getIcon() {
        return mIcon;
    }

    /**
     * Returns a title representing the action.
     */
    public @NonNull CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Returns a subtitle representing the action.
     */
    public @Nullable CharSequence getSubtitle() {
        return mSubtitle;
    }

    /**
     * Returns a content description representing the action.
     */
    public @Nullable CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Returns the action intent.
     */
    public @Nullable PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    /**
     * Returns the intent.
     */
    public @Nullable Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns the user handle.
     */
    public @Nullable UserHandle getUserHandle() {
        return mUserHandle;
    }

    /**
     * Returns the extra bundle for this object.
     */
    @SuppressLint("NullableCollection")
    public @Nullable Bundle getExtras() {
        return mExtras;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SearchAction)) return false;
        SearchAction that = (SearchAction) o;
        return mId.equals(that.mId) && mTitle.equals(that.mTitle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mTitle);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeString(mId);
        TextUtils.writeToParcel(mTitle, out, flags);
        out.writeTypedObject(mIcon, flags);
        TextUtils.writeToParcel(mSubtitle, out, flags);
        TextUtils.writeToParcel(mContentDescription, out, flags);
        out.writeTypedObject(mPendingIntent, flags);
        out.writeTypedObject(mIntent, flags);
        out.writeTypedObject(mUserHandle, flags);
        out.writeTypedObject(mExtras, flags);
    }

    @Override
    public String toString() {
        String str = "id=" + mId
                + " title=" + mTitle
                + " contentDescription=" + mContentDescription
                + " subtitle=" + mSubtitle
                + " icon=" + mIcon
                + " pendingIntent=" + (mPendingIntent == null ? "" : mPendingIntent.getIntent())
                + " intent=" + mIntent
                + " userHandle=" + mUserHandle;
        return str;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<SearchAction> CREATOR =
            new Parcelable.Creator<SearchAction>() {
                public SearchAction createFromParcel(Parcel in) {
                    return new SearchAction(in);
                }
                public SearchAction[] newArray(int size) {
                    return new SearchAction[size];
                }
            };

    /**
     * A builder for search action object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {
        @NonNull
        private String mId;

        @NonNull
        private CharSequence mTitle;

        @Nullable
        private Icon mIcon;

        @Nullable
        private CharSequence mSubtitle;

        @Nullable
        private CharSequence mContentDescription;

        @Nullable
        private PendingIntent mPendingIntent;

        @Nullable
        private Intent mIntent;

        @Nullable
        private UserHandle mUserHandle;

        @Nullable
        private Bundle mExtras;

        public Builder(@NonNull String id, @NonNull String title) {
            mId = Objects.requireNonNull(id);
            mTitle = Objects.requireNonNull(title);
        }

        /**
         * Sets the subtitle.
         */
        @NonNull
        public SearchAction.Builder setIcon(
                @Nullable Icon icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the subtitle.
         */
        @NonNull
        public SearchAction.Builder setSubtitle(
                @Nullable CharSequence subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Sets the content description.
         */
        @NonNull
        public SearchAction.Builder setContentDescription(
                @Nullable CharSequence contentDescription) {
            mContentDescription = contentDescription;
            return this;
        }

        /**
         * Sets the pending intent.
         */
        @NonNull
        public SearchAction.Builder setPendingIntent(@Nullable PendingIntent pendingIntent) {
            mPendingIntent = pendingIntent;
            return this;
        }

        /**
         * Sets the user handle.
         */
        @NonNull
        public SearchAction.Builder setUserHandle(@Nullable UserHandle userHandle) {
            mUserHandle = userHandle;
            return this;
        }

        /**
         * Sets the intent.
         */
        @NonNull
        public SearchAction.Builder setIntent(@Nullable Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Sets the extra.
         */
        @NonNull
        public SearchAction.Builder setExtras(
                @SuppressLint("NullableCollection") @Nullable Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds a new SearchAction instance.
         *
         * @throws IllegalStateException if no target is set
         */
        @NonNull
        public SearchAction build() {
            return new SearchAction(mId, mTitle, mIcon, mSubtitle, mContentDescription,
                    mPendingIntent, mIntent, mUserHandle, mExtras);
        }
    }
}
