/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.view.accessibility.data.repository

import android.view.accessibility.CaptioningManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@Suppress("UnspecifiedRegisterReceiverFlag")
@RunWith(AndroidJUnit4::class)
class CaptioningRepositoryTest {

    @Captor
    private lateinit var listenerCaptor: ArgumentCaptor<CaptioningManager.CaptioningChangeListener>

    @Mock private lateinit var captioningManager: CaptioningManager

    private lateinit var underTest: CaptioningRepository

    private val testScope = TestScope()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            CaptioningRepositoryImpl(
                captioningManager,
                testScope.testScheduler,
                testScope.backgroundScope
            )
    }

    @Test
    fun isSystemAudioCaptioningEnabled_change_repositoryEmits() {
        testScope.runTest {
            `when`(captioningManager.isEnabled).thenReturn(false)
            val isSystemAudioCaptioningEnabled = mutableListOf<Boolean>()
            underTest.isSystemAudioCaptioningEnabled
                .onEach { isSystemAudioCaptioningEnabled.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            triggerOnSystemAudioCaptioningChange()
            runCurrent()

            assertThat(isSystemAudioCaptioningEnabled)
                .containsExactlyElementsIn(listOf(false, true))
                .inOrder()
        }
    }

    @Test
    fun isSystemAudioCaptioningUiEnabled_change_repositoryEmits() {
        testScope.runTest {
            `when`(captioningManager.isSystemAudioCaptioningUiEnabled).thenReturn(false)
            val isSystemAudioCaptioningUiEnabled = mutableListOf<Boolean>()
            underTest.isSystemAudioCaptioningUiEnabled
                .onEach { isSystemAudioCaptioningUiEnabled.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            triggerSystemAudioCaptioningUiChange()
            runCurrent()

            assertThat(isSystemAudioCaptioningUiEnabled)
                .containsExactlyElementsIn(listOf(false, true))
                .inOrder()
        }
    }

    private fun triggerSystemAudioCaptioningUiChange(enabled: Boolean = true) {
        verify(captioningManager).addCaptioningChangeListener(listenerCaptor.capture())
        listenerCaptor.value.onSystemAudioCaptioningUiChanged(enabled)
    }

    private fun triggerOnSystemAudioCaptioningChange(enabled: Boolean = true) {
        verify(captioningManager).addCaptioningChangeListener(listenerCaptor.capture())
        listenerCaptor.value.onSystemAudioCaptioningChanged(enabled)
    }
}
