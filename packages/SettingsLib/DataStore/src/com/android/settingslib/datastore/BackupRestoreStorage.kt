/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.datastore

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.collection.MutableScatterMap
import com.google.common.io.ByteStreams
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

internal const val LOG_TAG = "BackupRestoreStorage"

/**
 * Storage with backup and restore support. Subclass must implement either [Observable] or
 * [KeyedObservable] interface.
 *
 * The storage is identified by a unique string [name] and data set is split into entities
 * ([BackupRestoreEntity]).
 */
abstract class BackupRestoreStorage : BackupHelper {
    /**
     * A unique string used to disambiguate the various storages within backup agent.
     *
     * It will be used as the `keyPrefix` of [BackupAgentHelper.addHelper].
     */
    abstract val name: String

    private val entities: List<BackupRestoreEntity> by lazy { createBackupRestoreEntities() }

    /**
     * Checksum of the data.
     *
     * Always call [java.util.zip.Checksum.reset] before using it.
     */
    protected val checksum = CRC32()

    /**
     * Entity states represented by checksum.
     *
     * Map key is the entity key, map value is the checksum of backup data.
     */
    protected val entityStates = MutableScatterMap<String, Long>()

    /** Entities to back up and restore. */
    abstract fun createBackupRestoreEntities(): List<BackupRestoreEntity>

    /** Default codec used to encode/decode the entity data. */
    open fun defaultCodec(): BackupCodec = BackupZipCodec.BEST_COMPRESSION

    override fun performBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        oldState.readEntityStates(entityStates)
        val backupContext = BackupContext(data)
        if (!enableBackup(backupContext)) {
            Log.i(LOG_TAG, "[$name] Backup disabled")
            return
        }
        Log.i(LOG_TAG, "[$name] Backup start")
        for (entity in entities) {
            val key = entity.key
            val outputStream = ByteArrayOutputStream()
            checksum.reset()
            val checkedOutputStream = CheckedOutputStream(outputStream, checksum)
            val codec = entity.codec() ?: defaultCodec()
            val result =
                try {
                    entity.backup(backupContext, wrapBackupOutputStream(codec, checkedOutputStream))
                } catch (exception: Exception) {
                    Log.e(LOG_TAG, "[$name] Fail to backup entity $key", exception)
                    continue
                }
            when (result) {
                EntityBackupResult.UPDATE -> {
                    if (updateEntityState(key)) {
                        val payload = outputStream.toByteArray()
                        val size = payload.size
                        data.writeEntityHeader(key, size)
                        data.writeEntityData(payload, size)
                        Log.i(LOG_TAG, "[$name] Backup entity $key: $size bytes")
                    } else {
                        Log.i(
                            LOG_TAG,
                            "[$name] Backup entity $key unchanged: ${outputStream.size()} bytes"
                        )
                    }
                }
                EntityBackupResult.INTACT -> {
                    Log.i(LOG_TAG, "[$name] Backup entity $key intact")
                }
                EntityBackupResult.DELETE -> {
                    entityStates.remove(key)
                    data.writeEntityHeader(key, -1)
                    Log.i(LOG_TAG, "[$name] Backup entity $key deleted")
                }
            }
        }
        newState.writeEntityStates(entityStates)
        Log.i(LOG_TAG, "[$name] Backup end")
    }

    private fun updateEntityState(key: String): Boolean {
        val value = checksum.value
        return entityStates.put(key, value) != value
    }

    /** Returns if backup is enabled. */
    open fun enableBackup(backupContext: BackupContext): Boolean = true

    open fun wrapBackupOutputStream(codec: BackupCodec, outputStream: OutputStream): OutputStream {
        // write a codec id header for safe restore
        outputStream.write(codec.id.toInt())
        return codec.encode(outputStream)
    }

    override fun restoreEntity(data: BackupDataInputStream) {
        val key = data.key
        if (!enableRestore()) {
            Log.i(LOG_TAG, "[$name] Restore disabled, ignore entity $key")
            return
        }
        val entity = entities.firstOrNull { it.key == key }
        if (entity == null) {
            Log.w(LOG_TAG, "[$name] Cannot find handler for entity $key")
            return
        }
        Log.i(LOG_TAG, "[$name] Restore $key: ${data.size()} bytes")
        val restoreContext = RestoreContext(key)
        val codec = entity.codec() ?: defaultCodec()
        val inputStream = LimitedNoCloseInputStream(data)
        checksum.reset()
        val checkedInputStream = CheckedInputStream(inputStream, checksum)
        try {
            entity.restore(restoreContext, wrapRestoreInputStream(codec, checkedInputStream))
            entityStates[key] = checksum.value
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "[$name] Fail to restore entity $key", exception)
        }
    }

    /** Returns if restore is enabled. */
    open fun enableRestore(): Boolean = true

    open fun wrapRestoreInputStream(
        codec: BackupCodec,
        inputStream: InputStream,
    ): InputStream {
        // read the codec id first to check if it is expected codec
        val id = inputStream.read()
        val expectedId = codec.id.toInt()
        if (id == expectedId) return codec.decode(inputStream)
        Log.i(LOG_TAG, "Expect codec id $expectedId but got $id")
        return BackupCodec.fromId(id.toByte()).decode(inputStream)
    }

    final override fun writeNewStateDescription(newState: ParcelFileDescriptor) {
        newState.writeEntityStates(entityStates)
        onRestoreFinished()
    }

    /** Callbacks when restore finished. */
    open fun onRestoreFinished() {}

    private fun ParcelFileDescriptor?.readEntityStates(state: MutableScatterMap<String, Long>) {
        state.clear()
        if (this == null) return
        // do not close the streams
        val fileInputStream = FileInputStream(fileDescriptor)
        val dataInputStream = DataInputStream(fileInputStream)
        try {
            val version = dataInputStream.readByte()
            if (version != STATE_VERSION) {
                Log.w(
                    LOG_TAG,
                    "[$name] Unexpected state version, read:$version, expected:$STATE_VERSION"
                )
                return
            }
            var count = dataInputStream.readInt()
            while (count-- > 0) {
                val key = dataInputStream.readUTF()
                val checksum = dataInputStream.readLong()
                state[key] = checksum
            }
        } catch (exception: Exception) {
            if (exception is EOFException) {
                Log.d(LOG_TAG, "[$name] Hit EOF when read state file")
            } else {
                Log.e(LOG_TAG, "[$name] Fail to read state file", exception)
            }
            state.clear()
        }
    }

    private fun ParcelFileDescriptor.writeEntityStates(state: MutableScatterMap<String, Long>) {
        // do not close the streams
        val fileOutputStream = FileOutputStream(fileDescriptor)
        val dataOutputStream = DataOutputStream(fileOutputStream)
        try {
            dataOutputStream.writeByte(STATE_VERSION.toInt())
            dataOutputStream.writeInt(state.size)
            state.forEach { key, value ->
                dataOutputStream.writeUTF(key)
                dataOutputStream.writeLong(value)
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "[$name] Fail to write state file", exception)
        }
    }

    companion object {
        private const val STATE_VERSION: Byte = 0
    }
}

/**
 * Wrapper of [BackupDataInputStream], limiting the number of bytes that can be read and make
 * [close] no-op.
 */
class LimitedNoCloseInputStream(inputStream: BackupDataInputStream) :
    FilterInputStream(ByteStreams.limit(inputStream, inputStream.size().toLong())) {
    override fun close() {
        // do not close original input stream
    }
}
