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

package com.android.settingslib.spa.gallery.home

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.gallery.page.ArgumentPageModel
import com.android.settingslib.spa.gallery.page.ArgumentPageProvider
import com.android.settingslib.spa.gallery.page.FooterPageProvider
import com.android.settingslib.spa.gallery.page.IllustrationPageProvider
import com.android.settingslib.spa.gallery.page.SettingsPagerPageProvider
import com.android.settingslib.spa.gallery.page.SliderPageProvider
import com.android.settingslib.spa.gallery.preference.PreferenceMainPageProvider
import com.android.settingslib.spa.gallery.ui.SpinnerPageProvider
import com.android.settingslib.spa.widget.scaffold.HomeScaffold

object HomePageProvider : SettingsPageProvider {
    override val name = "Home"

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = SettingsPage.create(name)
        return listOf(
            PreferenceMainPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            ArgumentPageProvider.buildInjectEntry("foo")!!.setLink(fromPage = owner).build(),
            SliderPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SpinnerPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            SettingsPagerPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            FooterPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
            IllustrationPageProvider.buildInjectEntry().setLink(fromPage = owner).build(),
        )
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        HomeScaffold(title = stringResource(R.string.app_name)) {
            for (entry in buildEntry(arguments)) {
                if (entry.name.startsWith(ArgumentPageModel.name)) {
                    entry.UiLayout(ArgumentPageModel.buildArgument(intParam = 0))
                } else {
                    entry.UiLayout()
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SettingsTheme {
        HomePageProvider.Page(null)
    }
}
