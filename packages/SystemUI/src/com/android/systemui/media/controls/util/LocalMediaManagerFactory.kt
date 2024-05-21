/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.util

import android.content.Context
import android.media.session.MediaSession
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.InfoMediaManager
import com.android.settingslib.media.LocalMediaManager
import javax.inject.Inject

/** Factory to create [LocalMediaManager] objects. */
class LocalMediaManagerFactory
@Inject
constructor(
    private val context: Context,
    private val localBluetoothManager: LocalBluetoothManager?
) {
    /** Creates a [LocalMediaManager] for the given package. */
    fun create(packageName: String?, token: MediaSession.Token? = null): LocalMediaManager {
        // TODO: b/321969740 - Populate the userHandle parameter in InfoMediaManager. The user
        // handle is necessary to disambiguate the same package running on different users.
        return InfoMediaManager.createInstance(
                context,
                packageName,
                null,
                localBluetoothManager,
                token
            )
            .run { LocalMediaManager(context, localBluetoothManager, this, packageName) }
    }
}
