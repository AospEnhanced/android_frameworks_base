/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.view.textclassifier.TextClassifier.WidgetType;

import com.android.internal.util.Preconditions;

import java.util.Locale;

/**
 * A representation of the context in which text classification would be performed.
 * @see TextClassificationManager#createTextClassificationSession(TextClassificationContext)
 */
public final class TextClassificationContext implements Parcelable {

    private final String mPackageName;
    private final String mWidgetType;
    @Nullable private final String mWidgetVersion;
    @UserIdInt
    private int mUserId = UserHandle.USER_NULL;

    private TextClassificationContext(
            String packageName,
            String widgetType,
            String widgetVersion) {
        mPackageName = Preconditions.checkNotNull(packageName);
        mWidgetType = Preconditions.checkNotNull(widgetType);
        mWidgetVersion = widgetVersion;
    }

    /**
     * Returns the package name for the calling package.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Sets the id of this context's user.
     * <p>
     * Package-private for SystemTextClassifier's use.
     */
    void setUserId(@UserIdInt int userId) {
        mUserId = userId;
    }

    /**
     * Returns the id of this context's user.
     * @hide
     */
    @UserIdInt
    public int getUserId() {
        return mUserId;
    }

    /**
     * Returns the widget type for this classification context.
     */
    @NonNull
    @WidgetType
    public String getWidgetType() {
        return mWidgetType;
    }

    /**
     * Returns a custom version string for the widget type.
     *
     * @see #getWidgetType()
     */
    @Nullable
    public String getWidgetVersion() {
        return mWidgetVersion;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "TextClassificationContext{"
                + "packageName=%s, widgetType=%s, widgetVersion=%s, userId=%d}",
                mPackageName, mWidgetType, mWidgetVersion, mUserId);
    }

    /**
     * A builder for building a TextClassification context.
     */
    public static final class Builder {

        private final String mPackageName;
        private final String mWidgetType;

        @Nullable private String mWidgetVersion;

        /**
         * Initializes a new builder for text classification context objects.
         *
         * @param packageName the name of the calling package
         * @param widgetType the type of widget e.g. {@link TextClassifier#WIDGET_TYPE_TEXTVIEW}
         *
         * @return this builder
         */
        public Builder(@NonNull String packageName, @NonNull @WidgetType String widgetType) {
            mPackageName = Preconditions.checkNotNull(packageName);
            mWidgetType = Preconditions.checkNotNull(widgetType);
        }

        /**
         * Sets an optional custom version string for the widget type.
         *
         * @return this builder
         */
        public Builder setWidgetVersion(@Nullable String widgetVersion) {
            mWidgetVersion = widgetVersion;
            return this;
        }

        /**
         * Builds the text classification context object.
         *
         * @return the built TextClassificationContext object
         */
        @NonNull
        public TextClassificationContext build() {
            return new TextClassificationContext(mPackageName, mWidgetType, mWidgetVersion);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeString(mWidgetType);
        parcel.writeString(mWidgetVersion);
        parcel.writeInt(mUserId);
    }

    private TextClassificationContext(Parcel in) {
        mPackageName = in.readString();
        mWidgetType = in.readString();
        mWidgetVersion = in.readString();
        mUserId = in.readInt();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<TextClassificationContext> CREATOR =
            new Parcelable.Creator<TextClassificationContext>() {
                @Override
                public TextClassificationContext createFromParcel(Parcel parcel) {
                    return new TextClassificationContext(parcel);
                }

                @Override
                public TextClassificationContext[] newArray(int size) {
                    return new TextClassificationContext[size];
                }
            };
}
