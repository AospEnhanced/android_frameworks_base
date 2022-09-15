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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.compose.navigator
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetSwitchPreference
import com.android.settingslib.spa.widget.scaffold.RegularScaffold
import kotlinx.coroutines.delay

private const val TITLE = "Sample TwoTargetSwitchPreference"

object TwoTargetSwitchPreferencePageProvider : SettingsPageProvider {
    override val name = "TwoTargetSwitchPreference"

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val owner = SettingsPage.create(name)
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            SettingsEntryBuilder.create( "TwoTargetSwitchPreference", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleTwoTargetSwitchPreference()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "TwoTargetSwitchPreference with summary", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleTwoTargetSwitchPreferenceWithSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "TwoTargetSwitchPreference with async summary", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleTwoTargetSwitchPreferenceWithAsyncSummary()
                }.build()
        )
        entryList.add(
            SettingsEntryBuilder.create( "TwoTargetSwitchPreference not changeable", owner)
                .setIsAllowSearch(true)
                .setUiLayoutFn {
                    SampleNotChangeableTwoTargetSwitchPreference()
                }.build()
        )

        return entryList
    }

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = SettingsPage.create(name))
            .setIsAllowSearch(true)
            .setUiLayoutFn {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val onClick = navigator(name)
                })
            }
    }

    @Composable
    override fun Page(arguments: Bundle?) {
        RegularScaffold(title = TITLE) {
            for (entry in buildEntry(arguments)) {
                entry.UiLayout()
            }
        }
    }
}

@Composable
private fun SampleTwoTargetSwitchPreference() {
    val checked = rememberSaveable { mutableStateOf(false) }
    TwoTargetSwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "TwoTargetSwitchPreference"
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    }) {}
}

@Composable
private fun SampleTwoTargetSwitchPreferenceWithSummary() {
    val checked = rememberSaveable { mutableStateOf(true) }
    TwoTargetSwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "TwoTargetSwitchPreference"
            override val summary = stateOf("With summary")
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    }) {}
}

@Composable
private fun SampleTwoTargetSwitchPreferenceWithAsyncSummary() {
    val checked = rememberSaveable { mutableStateOf(true) }
    val summary = produceState(initialValue = " ") {
        delay(1000L)
        value = "Async summary"
    }
    TwoTargetSwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "TwoTargetSwitchPreference"
            override val summary = summary
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    }) {}
}

@Composable
private fun SampleNotChangeableTwoTargetSwitchPreference() {
    val checked = rememberSaveable { mutableStateOf(true) }
    TwoTargetSwitchPreference(remember {
        object : SwitchPreferenceModel {
            override val title = "TwoTargetSwitchPreference"
            override val summary = stateOf("Not changeable")
            override val changeable = stateOf(false)
            override val checked = checked
            override val onCheckedChange = { newChecked: Boolean -> checked.value = newChecked }
        }
    }) {}
}

@Preview(showBackground = true)
@Composable
private fun TwoTargetSwitchPreferencePagePreview() {
    SettingsTheme {
        TwoTargetSwitchPreferencePageProvider.Page(null)
    }
}
