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

package com.android.systemui.qs.tiles.impl.inversion.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.data.repository.FakeColorInversionRepository
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.inversion.domain.model.ColorInversionTileModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class ColorInversionUserActionInteractorTest : SysuiTestCase() {

    private val testUser = UserHandle.CURRENT
    private val repository = FakeColorInversionRepository()
    private val inputHandler = FakeQSTileIntentUserInputHandler()

    private val underTest =
        ColorInversionUserActionInteractor(
            repository,
            inputHandler,
        )

    @Test
    fun handleClickWhenEnabled() = runTest {
        val wasEnabled = true
        repository.setIsEnabled(wasEnabled, testUser)

        underTest.handleInput(QSTileInputTestKtx.click(ColorInversionTileModel(wasEnabled)))

        assertThat(repository.isEnabled(testUser).value).isEqualTo(!wasEnabled)
    }

    @Test
    fun handleClickWhenDisabled() = runTest {
        val wasEnabled = false
        repository.setIsEnabled(wasEnabled, testUser)

        underTest.handleInput(QSTileInputTestKtx.click(ColorInversionTileModel(wasEnabled)))

        assertThat(repository.isEnabled(testUser).value).isEqualTo(!wasEnabled)
    }

    @Test
    fun handleLongClickWhenDisabled() = runTest {
        val enabled = false

        underTest.handleInput(QSTileInputTestKtx.longClick(ColorInversionTileModel(enabled)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_COLOR_INVERSION_SETTINGS)
        }
    }

    @Test
    fun handleLongClickWhenEnabled() = runTest {
        val enabled = true

        underTest.handleInput(QSTileInputTestKtx.longClick(ColorInversionTileModel(enabled)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_COLOR_INVERSION_SETTINGS)
        }
    }
}
