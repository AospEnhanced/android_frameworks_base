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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.data.repository.fakeRemoteInputRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class RemoteInputInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fakeRemoteInputRepository = kosmos.fakeRemoteInputRepository
    private val underTest = kosmos.remoteInputInteractor

    @Test
    fun isRemoteInputActive_true() =
        testScope.runTest {
            val active by collectLastValue(underTest.isRemoteInputActive)

            fakeRemoteInputRepository.isRemoteInputActive.value = true
            runCurrent()

            assertThat(active).isTrue()
        }

    @Test
    fun isRemoteInputActive_false() =
        testScope.runTest {
            val active by collectLastValue(underTest.isRemoteInputActive)

            fakeRemoteInputRepository.isRemoteInputActive.value = false
            runCurrent()

            assertThat(active).isFalse()
        }
}
