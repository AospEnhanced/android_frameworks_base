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

package com.android.server.display.brightness;

import static org.junit.Assert.assertEquals;


import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class BrightnessReasonTest {
    private BrightnessReason mBrightnessReason;

    @Before
    public void setUp() {
        mBrightnessReason = getReason(BrightnessReason.REASON_DOZE,
                BrightnessReason.MODIFIER_LOW_POWER);
    }

    @Test
    public void setSetsAppropriateValues() {
        mBrightnessReason.set(null);
        assertEquals(mBrightnessReason.getReason(), BrightnessReason.REASON_UNKNOWN);
        assertEquals(mBrightnessReason.getModifier(), 0);

        mBrightnessReason.set(
                getReason(BrightnessReason.REASON_BOOST, BrightnessReason.MODIFIER_THROTTLED));
        assertEquals(mBrightnessReason.getReason(), BrightnessReason.REASON_BOOST);
        assertEquals(mBrightnessReason.getModifier(), BrightnessReason.MODIFIER_THROTTLED);
    }

    @Test
    public void toStringGeneratesExpectedString() {
        String actualString = mBrightnessReason.toString();
        String expectedString = "doze [ low_pwr ]";
        assertEquals(actualString, expectedString);
    }

    @Test
    public void setModifierDoesntSetIfModifierIsBeyondExtremes() {
        int extremeModifier = 0x40; // equal to BrightnessReason.MODIFIER_MASK * 2

        // reset modifier
        mBrightnessReason.setModifier(0);

        // test extreme
        mBrightnessReason.setModifier(extremeModifier);
        assertEquals(0, mBrightnessReason.getModifier());
    }

    @Test
    public void setReasonDoesntSetIfModifierIsBeyondExtremes() {
        int extremeReason = BrightnessReason.REASON_MAX + 1;
        mBrightnessReason.setReason(extremeReason);
        assertEquals(mBrightnessReason.getReason(), BrightnessReason.REASON_DOZE);

        extremeReason = -1;
        mBrightnessReason.setReason(extremeReason);
        assertEquals(mBrightnessReason.getReason(), BrightnessReason.REASON_DOZE);
    }

    @Test
    public void addModifierWorksAsExpected() {
        mBrightnessReason.addModifier(BrightnessReason.REASON_BOOST);
        assertEquals(mBrightnessReason.getModifier(),
                BrightnessReason.REASON_DOZE | BrightnessReason.REASON_BOOST);
    }

    private BrightnessReason getReason(int reason, int modifier) {
        BrightnessReason brightnessReason = new BrightnessReason();
        brightnessReason.setReason(reason);
        brightnessReason.setModifier(modifier);
        return brightnessReason;
    }
}
