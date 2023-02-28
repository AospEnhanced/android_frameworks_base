/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * A request to cancel any ongoing UI matching this request.
 *
 * @hide
 */
public final class CancelUiRequest implements Parcelable {

    /**
     * The intent extra key for the {@code CancelUiRequest} object when launching the UX
     * activities.
     */
    @NonNull public static final String EXTRA_CANCEL_UI_REQUEST =
            "android.credentials.ui.extra.EXTRA_CANCEL_UI_REQUEST";

    @NonNull
    private final IBinder mToken;

    /** Returns the request token matching the user request that should be cancelled. */
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    public CancelUiRequest(@NonNull IBinder token) {
        mToken = token;
    }

    private CancelUiRequest(@NonNull Parcel in) {
        mToken = in.readStrongBinder();
        AnnotationValidations.validate(NonNull.class, null, mToken);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull public static final Creator<CancelUiRequest> CREATOR = new Creator<>() {
        @Override
        public CancelUiRequest createFromParcel(@NonNull Parcel in) {
            return new CancelUiRequest(in);
        }

        @Override
        public CancelUiRequest[] newArray(int size) {
            return new CancelUiRequest[size];
        }
    };
}
