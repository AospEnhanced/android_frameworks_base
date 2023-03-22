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

package com.android.systemui.biometrics

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shade.ShadeExpansionStateManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
class AuthDialogPanelInteractionDetectorTest : SysuiTestCase() {

    private lateinit var shadeExpansionStateManager: ShadeExpansionStateManager
    private lateinit var detector: AuthDialogPanelInteractionDetector

    @Mock private lateinit var action: Runnable

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        shadeExpansionStateManager = ShadeExpansionStateManager()
        detector =
            AuthDialogPanelInteractionDetector(shadeExpansionStateManager, mContext.mainExecutor)
    }

    @Test
    fun testEnableDetector_shouldPostRunnable() {
        detector.enable(action)
        // simulate notification expand
        shadeExpansionStateManager.onPanelExpansionChanged(5566f, true, true, 5566f)
        verify(action, timeout(5000).times(1)).run()
    }

    @Test
    fun testEnableDetector_shouldNotPostRunnable() {
        var detector =
            AuthDialogPanelInteractionDetector(shadeExpansionStateManager, mContext.mainExecutor)
        detector.enable(action)
        detector.disable()
        shadeExpansionStateManager.onPanelExpansionChanged(5566f, true, true, 5566f)
        verifyZeroInteractions(action)
    }
}
