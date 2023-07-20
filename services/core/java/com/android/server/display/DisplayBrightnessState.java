/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display;

import android.text.TextUtils;

import com.android.server.display.brightness.BrightnessReason;

import java.util.Objects;

/**
 * A state class representing a set of brightness related entities that are decided at runtime by
 * the DisplayBrightnessModeStrategies when updating the brightness.
 */
public final class DisplayBrightnessState {
    private final float mBrightness;
    private final float mSdrBrightness;
    private final BrightnessReason mBrightnessReason;
    private final String mDisplayBrightnessStrategyName;
    private final boolean mShouldUseAutoBrightness;

    private final boolean mIsSlowChange;

    private DisplayBrightnessState(Builder builder) {
        mBrightness = builder.getBrightness();
        mSdrBrightness = builder.getSdrBrightness();
        mBrightnessReason = builder.getBrightnessReason();
        mDisplayBrightnessStrategyName = builder.getDisplayBrightnessStrategyName();
        mShouldUseAutoBrightness = builder.getShouldUseAutoBrightness();
        mIsSlowChange = builder.isSlowChange();
    }

    /**
     * Gets the brightness
     */
    public float getBrightness() {
        return mBrightness;
    }

    /**
     * Gets the sdr brightness
     */
    public float getSdrBrightness() {
        return mSdrBrightness;
    }

    /**
     * Gets the {@link BrightnessReason}
     */
    public BrightnessReason getBrightnessReason() {
        return mBrightnessReason;
    }

    /**
     * Gets the {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}
     * name
     */
    public String getDisplayBrightnessStrategyName() {
        return mDisplayBrightnessStrategyName;
    }

    /**
     * @return {@code true} if the device is set up to run auto-brightness.
     */
    public boolean getShouldUseAutoBrightness() {
        return mShouldUseAutoBrightness;
    }

    /**
     * @return {@code true} if the should transit to new state slowly
     */
    public boolean isSlowChange() {
        return mIsSlowChange;
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("DisplayBrightnessState:");
        stringBuilder.append("\n    brightness:");
        stringBuilder.append(getBrightness());
        stringBuilder.append("\n    sdrBrightness:");
        stringBuilder.append(getSdrBrightness());
        stringBuilder.append("\n    brightnessReason:");
        stringBuilder.append(getBrightnessReason());
        stringBuilder.append("\n    shouldUseAutoBrightness:");
        stringBuilder.append(getShouldUseAutoBrightness());
        stringBuilder.append("\n    isSlowChange:");
        stringBuilder.append(mIsSlowChange);
        return stringBuilder.toString();
    }

    /**
     * Checks whether the two objects have the same values.
     */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof DisplayBrightnessState)) {
            return false;
        }

        DisplayBrightnessState otherState = (DisplayBrightnessState) other;

        return mBrightness == otherState.getBrightness()
                && mSdrBrightness == otherState.getSdrBrightness()
                && mBrightnessReason.equals(otherState.getBrightnessReason())
                && TextUtils.equals(mDisplayBrightnessStrategyName,
                        otherState.getDisplayBrightnessStrategyName())
                && mShouldUseAutoBrightness == otherState.getShouldUseAutoBrightness()
                && mIsSlowChange == otherState.isSlowChange();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mBrightness, mSdrBrightness, mBrightnessReason,
                mShouldUseAutoBrightness, mIsSlowChange);
    }

    /**
     * A DisplayBrightnessState's builder class.
     */
    public static class Builder {
        private float mBrightness;
        private float mSdrBrightness;
        private BrightnessReason mBrightnessReason = new BrightnessReason();
        private String mDisplayBrightnessStrategyName;
        private boolean mShouldUseAutoBrightness;
        private boolean mIsSlowChange;

        /**
         * Create a builder starting with the values from the specified {@link
         * DisplayBrightnessState}.
         *
         * @param state The state from which to initialize.
         */
        public static Builder from(DisplayBrightnessState state) {
            Builder builder = new Builder();
            builder.setBrightness(state.getBrightness());
            builder.setSdrBrightness(state.getSdrBrightness());
            builder.setBrightnessReason(state.getBrightnessReason());
            builder.setDisplayBrightnessStrategyName(state.getDisplayBrightnessStrategyName());
            builder.setShouldUseAutoBrightness(state.getShouldUseAutoBrightness());
            builder.setIsSlowChange(state.isSlowChange());
            return builder;
        }

        /**
         * Gets the brightness
         */
        public float getBrightness() {
            return mBrightness;
        }

        /**
         * Sets the brightness
         *
         * @param brightness The brightness to be associated with DisplayBrightnessState's
         *                   builder
         */
        public Builder setBrightness(float brightness) {
            this.mBrightness = brightness;
            return this;
        }

        /**
         * Gets the sdr brightness
         */
        public float getSdrBrightness() {
            return mSdrBrightness;
        }

        /**
         * Sets the sdr brightness
         *
         * @param sdrBrightness The sdr brightness to be associated with DisplayBrightnessState's
         *                      builder
         */
        public Builder setSdrBrightness(float sdrBrightness) {
            this.mSdrBrightness = sdrBrightness;
            return this;
        }

        /**
         * Gets the {@link BrightnessReason}
         */
        public BrightnessReason getBrightnessReason() {
            return mBrightnessReason;
        }

        /**
         * Sets the {@link BrightnessReason}
         *
         * @param brightnessReason The brightness reason {@link BrightnessReason} to be
         *                         associated with the builder
         */
        public Builder setBrightnessReason(BrightnessReason brightnessReason) {
            this.mBrightnessReason = brightnessReason;
            return this;
        }

        /**
         * Gets the {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}
         * name
         */
        public String getDisplayBrightnessStrategyName() {
            return mDisplayBrightnessStrategyName;
        }

        /**
         * Sets the
         * {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy}'s name
         *
         * @param displayBrightnessStrategyName The name of the
         * {@link com.android.server.display.brightness.strategy.DisplayBrightnessStrategy} being
         *                                      used.
         */
        public Builder setDisplayBrightnessStrategyName(String displayBrightnessStrategyName) {
            this.mDisplayBrightnessStrategyName = displayBrightnessStrategyName;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getShouldUseAutoBrightness}.
         */
        public Builder setShouldUseAutoBrightness(boolean shouldUseAutoBrightness) {
            this.mShouldUseAutoBrightness = shouldUseAutoBrightness;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#getShouldUseAutoBrightness}.
         */
        public boolean getShouldUseAutoBrightness() {
            return mShouldUseAutoBrightness;
        }

        /**
         * See {@link DisplayBrightnessState#isSlowChange()}.
         */
        public Builder setIsSlowChange(boolean shouldUseAutoBrightness) {
            this.mIsSlowChange = shouldUseAutoBrightness;
            return this;
        }

        /**
         * See {@link DisplayBrightnessState#isSlowChange()}.
         */
        public boolean isSlowChange() {
            return mIsSlowChange;
        }

        /**
         * This is used to construct an immutable DisplayBrightnessState object from its builder
         */
        public DisplayBrightnessState build() {
            return new DisplayBrightnessState(this);
        }
    }
}
