/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.service.voice;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.voice.flags.Flags;

import com.android.internal.util.DataClass;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a result supporting the visual query attention.
 *
 * @hide
 */
@DataClass(
        genConstructor = false,
        genBuilder = true,
        genEqualsHashCode = true,
        genHiddenConstDefs = true,
        genParcelable = true,
        genToString = true
)
@SystemApi
@FlaggedApi(Flags.FLAG_ALLOW_VARIOUS_ATTENTION_TYPES)
public final class VisualQueryAttentionResult implements Parcelable {

    /** Intention type to allow the system to listen to audio-visual query interactions. */
    public static final int INTERACTION_INTENTION_AUDIO_VISUAL = 0;

    /** Intention type to allow the system to listen to visual accessibility query interactions. */
    public static final int INTERACTION_INTENTION_VISUAL_ACCESSIBILITY = 1;

    /**
     * Intention of interaction associated with the attention result that the device should listen
     * to after the attention signal is gained.
     */
    private final @InteractionIntention int mInteractionIntention;

    private static @InteractionIntention int defaultInteractionIntention() {
        return INTERACTION_INTENTION_AUDIO_VISUAL;
    }

    /**
     * Integer value denoting the level of user engagement of the attention. System will
     * also use this to adjust the intensity of UI indicators.
     *
     * The value can be between 1 and 100 (inclusive). The default value is set to be 100 which is
     * defined as a complete engagement, which leads to the same UI result as the legacy
     * {@link VisualQueryDetectionService#gainedAttention()}.
     *
     * Different values of engagement level corresponds to various SysUI effects. Within the same
     * interaction intention, higher value of engagement level will lead to stronger visual
     * presentation of the device attention UI.
     */
    @IntRange(from = 1, to = 100)
    private final int mEngagementLevel;

    private static int defaultEngagementLevel() {
        return 100;
    }

    /**
     * Provides an instance of {@link Builder} with state corresponding to this instance.
     *
     * @hide
     */
    public Builder buildUpon() {
        return new Builder()
                .setInteractionIntention(mInteractionIntention)
                .setEngagementLevel(mEngagementLevel);
    }




    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/service/voice/VisualQueryAttentionResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(prefix = "INTERACTION_INTENTION_", value = {
        INTERACTION_INTENTION_AUDIO_VISUAL,
        INTERACTION_INTENTION_VISUAL_ACCESSIBILITY
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface InteractionIntention {}

    /** @hide */
    @DataClass.Generated.Member
    public static String interactionIntentionToString(@InteractionIntention int value) {
        switch (value) {
            case INTERACTION_INTENTION_AUDIO_VISUAL:
                    return "INTERACTION_INTENTION_AUDIO_VISUAL";
            case INTERACTION_INTENTION_VISUAL_ACCESSIBILITY:
                    return "INTERACTION_INTENTION_VISUAL_ACCESSIBILITY";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ VisualQueryAttentionResult(
            @InteractionIntention int interactionIntention,
            @IntRange(from = 1, to = 100) int engagementLevel) {
        this.mInteractionIntention = interactionIntention;

        if (!(mInteractionIntention == INTERACTION_INTENTION_AUDIO_VISUAL)
                && !(mInteractionIntention == INTERACTION_INTENTION_VISUAL_ACCESSIBILITY)) {
            throw new java.lang.IllegalArgumentException(
                    "interactionIntention was " + mInteractionIntention + " but must be one of: "
                            + "INTERACTION_INTENTION_AUDIO_VISUAL(" + INTERACTION_INTENTION_AUDIO_VISUAL + "), "
                            + "INTERACTION_INTENTION_VISUAL_ACCESSIBILITY(" + INTERACTION_INTENTION_VISUAL_ACCESSIBILITY + ")");
        }

        this.mEngagementLevel = engagementLevel;
        com.android.internal.util.AnnotationValidations.validate(
                IntRange.class, null, mEngagementLevel,
                "from", 1,
                "to", 100);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Intention of interaction associated with the attention result that the device should listen
     * to after the attention signal is gained.
     */
    @DataClass.Generated.Member
    public @InteractionIntention int getInteractionIntention() {
        return mInteractionIntention;
    }

    /**
     * Integer value denoting the level of user engagement of the attention. System will
     * also use this to adjust the intensity of UI indicators.
     *
     * The value can be between 1 and 100 (inclusive). The default value is set to be 100 which is
     * defined as a complete engagement, which leads to the same UI result as the legacy
     * {@link VisualQueryDetectionService#gainedAttention()}.
     *
     * Different values of engagement level corresponds to various SysUI effects. Within the same
     * interaction intention, higher value of engagement level will lead to stronger visual
     * presentation of the device attention UI.
     */
    @DataClass.Generated.Member
    public @IntRange(from = 1, to = 100) int getEngagementLevel() {
        return mEngagementLevel;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "VisualQueryAttentionResult { " +
                "interactionIntention = " + interactionIntentionToString(mInteractionIntention) + ", " +
                "engagementLevel = " + mEngagementLevel +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(VisualQueryAttentionResult other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        VisualQueryAttentionResult that = (VisualQueryAttentionResult) o;
        //noinspection PointlessBooleanExpression
        return true
                && mInteractionIntention == that.mInteractionIntention
                && mEngagementLevel == that.mEngagementLevel;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mInteractionIntention;
        _hash = 31 * _hash + mEngagementLevel;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mInteractionIntention);
        dest.writeInt(mEngagementLevel);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ VisualQueryAttentionResult(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int interactionIntention = in.readInt();
        int engagementLevel = in.readInt();

        this.mInteractionIntention = interactionIntention;

        if (!(mInteractionIntention == INTERACTION_INTENTION_AUDIO_VISUAL)
                && !(mInteractionIntention == INTERACTION_INTENTION_VISUAL_ACCESSIBILITY)) {
            throw new java.lang.IllegalArgumentException(
                    "interactionIntention was " + mInteractionIntention + " but must be one of: "
                            + "INTERACTION_INTENTION_AUDIO_VISUAL(" + INTERACTION_INTENTION_AUDIO_VISUAL + "), "
                            + "INTERACTION_INTENTION_VISUAL_ACCESSIBILITY(" + INTERACTION_INTENTION_VISUAL_ACCESSIBILITY + ")");
        }

        this.mEngagementLevel = engagementLevel;
        com.android.internal.util.AnnotationValidations.validate(
                IntRange.class, null, mEngagementLevel,
                "from", 1,
                "to", 100);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<VisualQueryAttentionResult> CREATOR
            = new Parcelable.Creator<VisualQueryAttentionResult>() {
        @Override
        public VisualQueryAttentionResult[] newArray(int size) {
            return new VisualQueryAttentionResult[size];
        }

        @Override
        public VisualQueryAttentionResult createFromParcel(@NonNull Parcel in) {
            return new VisualQueryAttentionResult(in);
        }
    };

    /**
     * A builder for {@link VisualQueryAttentionResult}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder {

        private @InteractionIntention int mInteractionIntention;
        private @IntRange(from = 1, to = 100) int mEngagementLevel;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Intention of interaction associated with the attention result that the device should listen
         * to after the attention signal is gained.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setInteractionIntention(@InteractionIntention int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mInteractionIntention = value;
            return this;
        }

        /**
         * Integer value denoting the level of user engagement of the attention. System will
         * also use this to adjust the intensity of UI indicators.
         *
         * The value can be between 1 and 100 (inclusive). The default value is set to be 100 which is
         * defined as a complete engagement, which leads to the same UI result as the legacy
         * {@link VisualQueryDetectionService#gainedAttention()}.
         *
         * Different values of engagement level corresponds to various SysUI effects. Within the same
         * interaction intention, higher value of engagement level will lead to stronger visual
         * presentation of the device attention UI.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setEngagementLevel(@IntRange(from = 1, to = 100) int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mEngagementLevel = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull VisualQueryAttentionResult build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mInteractionIntention = defaultInteractionIntention();
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mEngagementLevel = defaultEngagementLevel();
            }
            VisualQueryAttentionResult o = new VisualQueryAttentionResult(
                    mInteractionIntention,
                    mEngagementLevel);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1707773691880L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/service/voice/VisualQueryAttentionResult.java",
            inputSignatures = "public static final  int INTERACTION_INTENTION_AUDIO_VISUAL\npublic static final  int INTERACTION_INTENTION_VISUAL_ACCESSIBILITY\nprivate final @android.service.voice.VisualQueryAttentionResult.InteractionIntention int mInteractionIntention\nprivate final @android.annotation.IntRange int mEngagementLevel\nprivate static @android.service.voice.VisualQueryAttentionResult.InteractionIntention int defaultInteractionIntention()\nprivate static  int defaultEngagementLevel()\npublic  android.service.voice.VisualQueryAttentionResult.Builder buildUpon()\nclass VisualQueryAttentionResult extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=true, genEqualsHashCode=true, genHiddenConstDefs=true, genParcelable=true, genToString=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
