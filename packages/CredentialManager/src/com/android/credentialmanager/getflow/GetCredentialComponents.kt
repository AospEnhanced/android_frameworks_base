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

package com.android.credentialmanager.getflow

import android.text.TextUtils

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.credentialmanager.R
import com.android.credentialmanager.common.material.ModalBottomSheetLayout
import com.android.credentialmanager.common.material.ModalBottomSheetValue
import com.android.credentialmanager.common.material.rememberModalBottomSheetState
import com.android.credentialmanager.common.ui.CancelButton
import com.android.credentialmanager.jetpack.developer.PublicKeyCredential

@Composable
fun GetCredentialScreen(
  viewModel: GetCredentialViewModel,
) {
  val state = rememberModalBottomSheetState(
    initialValue = ModalBottomSheetValue.Expanded,
    skipHalfExpanded = true
  )
  ModalBottomSheetLayout(
    sheetState = state,
    sheetContent = {
      val uiState = viewModel.uiState
      when (uiState.currentScreenState) {
        GetScreenState.PRIMARY_SELECTION -> PrimarySelectionCard(
          requestDisplayInfo = uiState.requestDisplayInfo,
          providerDisplayInfo = uiState.providerDisplayInfo,
          onEntrySelected = viewModel::onEntrySelected,
          onCancel = viewModel::onCancel,
          onMoreOptionSelected = viewModel::onMoreOptionSelected,
        )
        GetScreenState.ALL_SIGN_IN_OPTIONS -> AllSignInOptionCard(
          providerInfoList = uiState.providerInfoList,
          providerDisplayInfo = uiState.providerDisplayInfo,
          onEntrySelected = viewModel::onEntrySelected,
          onBackButtonClicked = viewModel::onBackToPrimarySelectionScreen,
        )
      }
    },
    scrimColor = Color.Transparent,
    sheetShape = MaterialTheme.shapes.medium,
  ) {}
  LaunchedEffect(state.currentValue) {
    if (state.currentValue == ModalBottomSheetValue.Hidden) {
      viewModel.onCancel()
    }
  }
}

/** Draws the primary credential selection page. */
@Composable
fun PrimarySelectionCard(
  requestDisplayInfo: RequestDisplayInfo,
  providerDisplayInfo: ProviderDisplayInfo,
  onEntrySelected: (EntryInfo) -> Unit,
  onCancel: () -> Unit,
  onMoreOptionSelected: () -> Unit,
) {
  val sortedUserNameToCredentialEntryList = providerDisplayInfo.sortedUserNameToCredentialEntryList
  val authenticationEntryList = providerDisplayInfo.authenticationEntryList
  Card() {
    Column() {
      Text(
        text = stringResource(
          if (sortedUserNameToCredentialEntryList.size == 1) {
            if (sortedUserNameToCredentialEntryList.first().sortedCredentialEntryList
                .first().credentialType == PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL
            )
              R.string.get_dialog_title_use_passkey_for
            else R.string.get_dialog_title_use_sign_in_for
          } else R.string.get_dialog_title_choose_sign_in_for,
          requestDisplayInfo.appDomainName
        ),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(all = 24.dp).align(alignment = Alignment.CenterHorizontally)
      )

      Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          items(sortedUserNameToCredentialEntryList) {
            CredentialEntryRow(
              credentialEntryInfo = it.sortedCredentialEntryList.first(),
              onEntrySelected = onEntrySelected,
            )
          }
          items(authenticationEntryList) {
            AuthenticationEntryRow(
              authenticationEntryInfo = it,
              onEntrySelected = onEntrySelected,
            )
          }
          item {
            SignInAnotherWayRow(onSelect = onMoreOptionSelected)
          }
        }
      }
      Divider(
        thickness = 24.dp,
        color = Color.Transparent
      )
      Row(
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
      ) {
        CancelButton(stringResource(R.string.string_no_thanks), onCancel)
      }
      Divider(
        thickness = 18.dp,
        color = Color.Transparent,
        modifier = Modifier.padding(bottom = 16.dp)
      )
    }
  }
}

/** Draws the secondary credential selection page, where all sign-in options are listed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSignInOptionCard(
  providerInfoList: List<ProviderInfo>,
  providerDisplayInfo: ProviderDisplayInfo,
  onEntrySelected: (EntryInfo) -> Unit,
  onBackButtonClicked: () -> Unit,
) {
  val sortedUserNameToCredentialEntryList = providerDisplayInfo.sortedUserNameToCredentialEntryList
  val authenticationEntryList = providerDisplayInfo.authenticationEntryList
  Card() {
    Column() {
      TopAppBar(
        colors = TopAppBarDefaults.smallTopAppBarColors(
          containerColor = Color.Transparent,
        ),
        title = {
          Text(
            text = stringResource(R.string.get_dialog_title_sign_in_options),
            style = MaterialTheme.typography.titleMedium
          )
        },
        navigationIcon = {
          IconButton(onClick = onBackButtonClicked) {
            Icon(
              Icons.Filled.ArrowBack,
              contentDescription = stringResource(R.string.accessibility_back_arrow_button))
          }
        },
        modifier = Modifier.padding(top = 12.dp)
      )

      Card(
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
          .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
          .align(alignment = Alignment.CenterHorizontally)
      ) {
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // For username
          items(sortedUserNameToCredentialEntryList) { item ->
            PerUserNameCredentials(
              perUserNameCredentialEntryList = item,
              onEntrySelected = onEntrySelected,
            )
          }
          // Locked password manager
          item {
            if (!authenticationEntryList.isEmpty()) {
              LockedCredentials(
                authenticationEntryList = authenticationEntryList,
                onEntrySelected = onEntrySelected,
              )
            }
          }
          // TODO: Remote action
          // Manage sign-ins
          item {
            ActionChips(providerInfoList = providerInfoList, onEntrySelected = onEntrySelected)
          }
        }
      }
    }
  }
}

// TODO: create separate rows for primary and secondary pages.
// TODO: reuse rows and columns across types.

@Composable
fun ActionChips(
  providerInfoList: List<ProviderInfo>,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  val actionChips = providerInfoList.flatMap { it.actionEntryList }
  if (actionChips.isEmpty()) {
    return
  }

  Text(
    text = stringResource(R.string.get_dialog_heading_manage_sign_ins),
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(vertical = 8.dp)
  )
  // TODO: tweak padding.
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    actionChips.forEach {
      ActionEntryRow(it, onEntrySelected)
    }
  }
}

@Composable
fun LockedCredentials(
  authenticationEntryList: List<AuthenticationEntryInfo>,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  Text(
    text = stringResource(R.string.get_dialog_heading_locked_password_managers),
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(vertical = 8.dp)
  )
  authenticationEntryList.forEach {
    AuthenticationEntryRow(it, onEntrySelected)
  }
}

@Composable
fun PerUserNameCredentials(
  perUserNameCredentialEntryList: PerUserNameCredentialEntryList,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  Text(
    text = stringResource(
      R.string.get_dialog_heading_for_username, perUserNameCredentialEntryList.userName),
    style = MaterialTheme.typography.labelLarge,
    modifier = Modifier.padding(vertical = 8.dp)
  )
  perUserNameCredentialEntryList.sortedCredentialEntryList.forEach {
    CredentialEntryRow(it, onEntrySelected)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialEntryRow(
  credentialEntryInfo: CredentialEntryInfo,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onEntrySelected(credentialEntryInfo)},
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = credentialEntryInfo.icon.toBitmap().asImageBitmap(),
        // TODO: add description.
        contentDescription = "")
    },
    shape = MaterialTheme.shapes.large,
    label = {
      Column() {
        // TODO: fix the text values.
        Text(
          text = credentialEntryInfo.userName,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(top = 16.dp)
        )
        Text(
          text =
          if (TextUtils.isEmpty(credentialEntryInfo.displayName))
            credentialEntryInfo.credentialTypeDisplayName
          else
            credentialEntryInfo.credentialTypeDisplayName +
                    stringResource(R.string.get_dialog_sign_in_type_username_separator) +
                    credentialEntryInfo.displayName,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 16.dp)
        )
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticationEntryRow(
  authenticationEntryInfo: AuthenticationEntryInfo,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = {onEntrySelected(authenticationEntryInfo)},
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = authenticationEntryInfo.icon.toBitmap().asImageBitmap(),
        // TODO: add description.
        contentDescription = "")
    },
    shape = MaterialTheme.shapes.large,
    label = {
      Column() {
        // TODO: fix the text values.
        Text(
          text = authenticationEntryInfo.title,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(top = 16.dp)
        )
        Text(
          text = stringResource(R.string.locked_credential_entry_label_subtext),
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 16.dp)
        )
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEntryRow(
  actionEntryInfo: ActionEntryInfo,
  onEntrySelected: (EntryInfo) -> Unit,
) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = { onEntrySelected(actionEntryInfo) },
    icon = {
      Image(modifier = Modifier.size(24.dp, 24.dp).padding(start = 10.dp),
        bitmap = actionEntryInfo.icon.toBitmap().asImageBitmap(),
        // TODO: add description.
        contentDescription = "")
    },
    shape = MaterialTheme.shapes.large,
    label = {
      Column() {
        Text(
          text = actionEntryInfo.title,
          style = MaterialTheme.typography.titleLarge,
        )
        if (actionEntryInfo.subTitle != null) {
          Text(
            text = actionEntryInfo.subTitle,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInAnotherWayRow(onSelect: () -> Unit) {
  SuggestionChip(
    modifier = Modifier.fillMaxWidth(),
    onClick = onSelect,
    shape = MaterialTheme.shapes.large,
    label = {
      Text(
        text = stringResource(R.string.get_dialog_use_saved_passkey_for),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 16.dp)
      )
    }
  )
}
