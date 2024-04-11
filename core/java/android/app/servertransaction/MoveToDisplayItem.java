/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;
import android.window.ActivityWindowInfo;

import java.util.Objects;

/**
 * Activity move to a different display message.
 * @hide
 */
public class MoveToDisplayItem extends ActivityTransactionItem {

    private int mTargetDisplayId;
    private Configuration mConfiguration;
    private ActivityWindowInfo mActivityWindowInfo;

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        CompatibilityInfo.applyOverrideScaleIfNeeded(mConfiguration);
        // Notify the client of an upcoming change in the token configuration. This ensures that
        // batches of config change items only process the newest configuration.
        client.updatePendingActivityConfiguration(getActivityToken(), mConfiguration);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityMovedToDisplay");
        client.handleActivityConfigurationChanged(r, mConfiguration, mTargetDisplayId,
                mActivityWindowInfo);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    // ObjectPoolItem implementation

    private MoveToDisplayItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static MoveToDisplayItem obtain(@NonNull IBinder activityToken, int targetDisplayId,
            @NonNull Configuration configuration, @NonNull ActivityWindowInfo activityWindowInfo) {
        MoveToDisplayItem instance = ObjectPool.obtain(MoveToDisplayItem.class);
        if (instance == null) {
            instance = new MoveToDisplayItem();
        }
        instance.setActivityToken(activityToken);
        instance.mTargetDisplayId = targetDisplayId;
        instance.mConfiguration = new Configuration(configuration);
        instance.mActivityWindowInfo = new ActivityWindowInfo(activityWindowInfo);

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mTargetDisplayId = 0;
        mConfiguration = null;
        mActivityWindowInfo = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mTargetDisplayId);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeTypedObject(mActivityWindowInfo, flags);
    }

    /** Read from Parcel. */
    private MoveToDisplayItem(@NonNull Parcel in) {
        super(in);
        mTargetDisplayId = in.readInt();
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
        mActivityWindowInfo = in.readTypedObject(ActivityWindowInfo.CREATOR);
    }

    public static final @NonNull Creator<MoveToDisplayItem> CREATOR = new Creator<>() {
        public MoveToDisplayItem createFromParcel(@NonNull Parcel in) {
            return new MoveToDisplayItem(in);
        }

        public MoveToDisplayItem[] newArray(int size) {
            return new MoveToDisplayItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final MoveToDisplayItem other = (MoveToDisplayItem) o;
        return mTargetDisplayId == other.mTargetDisplayId
                && Objects.equals(mConfiguration, other.mConfiguration)
                && Objects.equals(mActivityWindowInfo, other.mActivityWindowInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + mTargetDisplayId;
        result = 31 * result + mConfiguration.hashCode();
        result = 31 * result + Objects.hashCode(mActivityWindowInfo);
        return result;
    }

    @Override
    public String toString() {
        return "MoveToDisplayItem{" + super.toString()
                + ",targetDisplayId=" + mTargetDisplayId
                + ",configuration=" + mConfiguration
                + ",activityWindowInfo=" + mActivityWindowInfo + "}";
    }
}
