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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DozeParametersTest extends SysuiTestCase {
    private DozeParameters mDozeParameters;

    @Mock Resources mResources;
    @Mock private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    @Mock private PowerManager mPowerManager;
    @Mock private TunerService mTunerService;
    @Mock private BatteryController mBatteryController;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private DumpManager mDumpManager;
    @Mock private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;

    /**
     * The current value of PowerManager's dozeAfterScreenOff property.
     *
     * This property controls whether System UI is controlling the screen off animation. If it's
     * false (PowerManager should not doze after screen off) then System UI is controlling the
     * animation. If true, we're not controlling it and PowerManager will doze immediately.
     */
    private boolean mPowerManagerDozeAfterScreenOff;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // Save the current value set for dozeAfterScreenOff so we can make assertions. This method
        // is only called if the value changes, which makes it difficult to check that it was set
        // correctly in tests.
        doAnswer(invocation -> {
            mPowerManagerDozeAfterScreenOff = invocation.getArgument(0);
            return mPowerManagerDozeAfterScreenOff;
        }).when(mPowerManager).setDozeAfterScreenOff(anyBoolean());

        mDozeParameters = new DozeParameters(
            mResources,
            mAmbientDisplayConfiguration,
            mAlwaysOnDisplayPolicy,
            mPowerManager,
            mBatteryController,
            mTunerService,
            mDumpManager,
            mFeatureFlags,
            mUnlockedScreenOffAnimationController,
            mKeyguardUpdateMonitor,
            mConfigurationController,
            mStatusBarStateController
        );

        when(mFeatureFlags.useNewLockscreenAnimations()).thenReturn(true);

        setAodEnabledForTest(true);
        setShouldControlUnlockedScreenOffForTest(true);
        setDisplayNeedsBlankingForTest(false);
    }

    @Test
    public void testSetControlScreenOffAnimation_setsDozeAfterScreenOff_correctly() {
        // If we want to control screen off, we do NOT want PowerManager to doze after screen off.
        // Obviously.
        mDozeParameters.setControlScreenOffAnimation(true);
        assertFalse(mPowerManagerDozeAfterScreenOff);

        // If we don't want to control screen off, PowerManager is free to doze after screen off if
        // that's what'll make it happy.
        mDozeParameters.setControlScreenOffAnimation(false);
        assertTrue(mPowerManagerDozeAfterScreenOff);
    }

    @Test
    public void testGetWallpaperAodDuration_when_shouldControlScreenOff() {
        mDozeParameters.setControlScreenOffAnimation(true);
        Assert.assertEquals(
                "wallpaper hides faster when controlling screen off",
                mDozeParameters.getWallpaperAodDuration(),
                DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY);
    }

    @Test
    public void testGetAlwaysOn() {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        assertThat(mDozeParameters.getAlwaysOn()).isTrue();
    }

    @Test
    public void testGetAlwaysOn_whenBatterySaver() {
        when(mBatteryController.isAodPowerSave()).thenReturn(true);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "1");

        assertThat(mDozeParameters.getAlwaysOn()).isFalse();
    }

    /**
     * PowerManager.setDozeAfterScreenOff(true) means we are not controlling screen off, and calling
     * it with false means we are. Confusing, but sure - make sure that we call PowerManager with
     * the correct value depending on whether we want to control screen off.
     */
    @Test
    public void testControlUnlockedScreenOffAnimation_dozeAfterScreenOff_false() {
        // If AOD is disabled, we shouldn't want to control screen off. Also, let's double check
        // that when that value is updated, we called through to PowerManager.
        setAodEnabledForTest(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
        assertTrue(mPowerManagerDozeAfterScreenOff);

        // And vice versa...
        setAodEnabledForTest(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
        assertFalse(mPowerManagerDozeAfterScreenOff);
    }

    @Test
    public void testControlUnlockedScreenOffAnimationDisabled_dozeAfterScreenOff() {
        setShouldControlUnlockedScreenOffForTest(true);
        when(mFeatureFlags.useNewLockscreenAnimations()).thenReturn(false);

        assertFalse(mDozeParameters.shouldControlUnlockedScreenOff());

        // Trigger the setter for the current value.
        mDozeParameters.setControlScreenOffAnimation(mDozeParameters.shouldControlScreenOff());
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }

    @Test
    public void propagatesAnimateScreenOff_noAlwaysOn() {
        setAodEnabledForTest(false);
        setDisplayNeedsBlankingForTest(false);

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }

    @Test
    public void propagatesAnimateScreenOff_alwaysOn() {
        setAodEnabledForTest(true);
        setDisplayNeedsBlankingForTest(false);
        setShouldControlUnlockedScreenOffForTest(false);

        // Take over when the keyguard is visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());

        // Do not animate screen-off when keyguard isn't visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void neverAnimateScreenOff_whenNotSupported() {
        setDisplayNeedsBlankingForTest(true);

        // Never animate if display doesn't support it.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertFalse(mDozeParameters.shouldControlScreenOff());
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void controlScreenOffTrueWhenKeyguardNotShowingAndControlUnlockedScreenOff() {
        setShouldControlUnlockedScreenOffForTest(true);

        // Tell doze that keyguard is not visible.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(
                false /* showing */);

        // Since we're controlling the unlocked screen off animation, verify that we've asked to
        // control the screen off animation despite being unlocked.
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }


    @Test
    public void keyguardVisibility_changesControlScreenOffAnimation() {
        setShouldControlUnlockedScreenOffForTest(false);

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertFalse(mDozeParameters.shouldControlScreenOff());
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }

    @Test
    public void keyguardVisibility_changesControlScreenOffAnimation_respectsUnlockedScreenOff() {
        setShouldControlUnlockedScreenOffForTest(true);

        // Even if the keyguard is gone, we should control screen off if we can control unlocked
        // screen off.
        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(false);
        assertTrue(mDozeParameters.shouldControlScreenOff());

        mDozeParameters.mKeyguardVisibilityCallback.onKeyguardVisibilityChanged(true);
        assertTrue(mDozeParameters.shouldControlScreenOff());
    }

    private void setDisplayNeedsBlankingForTest(boolean needsBlanking) {
        when(mResources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze)).thenReturn(
                        needsBlanking);
    }

    private void setAodEnabledForTest(boolean enabled) {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(enabled);
        mDozeParameters.onTuningChanged(Settings.Secure.DOZE_ALWAYS_ON, "");
    }

    private void setShouldControlUnlockedScreenOffForTest(boolean shouldControl) {
        when(mUnlockedScreenOffAnimationController.shouldPlayUnlockedScreenOffAnimation())
                .thenReturn(shouldControl);
    }
}
