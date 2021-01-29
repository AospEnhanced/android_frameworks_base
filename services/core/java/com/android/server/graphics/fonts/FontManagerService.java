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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.SystemFonts;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.util.AndroidException;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.security.FileIntegrityService;
import com.android.server.security.VerityUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/** A service for managing system fonts. */
// TODO(b/173619554): Add API to update fonts.
public final class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";

    private static final String FONT_FILES_DIR = "/data/fonts/files";

    @Override
    public FontConfig getFontConfig() throws RemoteException {
        return getSystemFontConfig();
    }

    /* package */ static class SystemFontException extends AndroidException {
        private final int mErrorCode;

        SystemFontException(@FontManager.ErrorCode int errorCode, String msg, Throwable cause) {
            super(msg, cause);
            mErrorCode = errorCode;
        }

        SystemFontException(int errorCode, String msg) {
            super(msg);
            mErrorCode = errorCode;
        }

        @FontManager.ErrorCode int getErrorCode() {
            return mErrorCode;
        }
    }

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new FontManagerService(context);
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            if (!Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                                return null;
                            }
                            return mService.getCurrentFontMap();
                        }
                    });
            publishBinderService(Context.FONT_SERVICE, mService);
        }
    }

    /* package */ static class OtfFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public String getPostScriptName(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                return FontFileUtil.getPostScriptName(buffer, 0);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }

        @Override
        public long getRevision(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                return FontFileUtil.getRevision(buffer, 0);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }

        private static ByteBuffer mmap(File file) throws IOException {
            try (FileInputStream in = new FileInputStream(file)) {
                FileChannel fileChannel = in.getChannel();
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
            }
        }
    }

    private static class FsverityUtilImpl implements UpdatableFontDir.FsverityUtil {
        @Override
        public boolean hasFsverity(String filePath) {
            return VerityUtils.hasFsverity(filePath);
        }

        @Override
        public void setUpFsverity(String filePath, byte[] pkcs7Signature) throws IOException {
            VerityUtils.setUpFsverity(filePath, pkcs7Signature);
        }

        @Override
        public boolean rename(File src, File dest) {
            // rename system call preserves fs-verity bit.
            return src.renameTo(dest);
        }
    }

    @Nullable
    private final UpdatableFontDir mUpdatableFontDir;

    @GuardedBy("FontManagerService.this")
    @Nullable
    private SharedMemory mSerializedFontMap = null;

    private FontManagerService(Context context) {
        mContext = context;
        mUpdatableFontDir = createUpdatableFontDir();
    }

    @Nullable
    private static UpdatableFontDir createUpdatableFontDir() {
        // If apk verity is supported, fs-verity should be available.
        if (!FileIntegrityService.isApkVeritySupported()) return null;
        return new UpdatableFontDir(new File(FONT_FILES_DIR),
                Arrays.asList(new File(SystemFonts.SYSTEM_FONT_DIR),
                        new File(SystemFonts.OEM_FONT_DIR)),
                new OtfFontFileParser(), new FsverityUtilImpl());
    }


    @NonNull
    private final Context mContext;

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @NonNull /* package */ SharedMemory getCurrentFontMap() {
        synchronized (FontManagerService.this) {
            if (mSerializedFontMap == null) {
                mSerializedFontMap = buildNewSerializedFontMap();
            }
            return mSerializedFontMap;
        }
    }

    /* package */ void installFontFile(FileDescriptor fd, byte[] pkcs7Signature)
            throws SystemFontException {
        if (mUpdatableFontDir == null) {
            throw new SystemFontException(
                    FontManager.ERROR_CODE_FONT_UPDATER_DISABLED,
                    "The font updater is disabled.");
        }
        synchronized (FontManagerService.this) {
            mUpdatableFontDir.installFontFile(fd, pkcs7Signature);
            // Create updated font map in the next getSerializedSystemFontMap() call.
            mSerializedFontMap = null;
        }
    }

    /* package */ void clearUpdates() throws SystemFontException {
        if (mUpdatableFontDir == null) {
            throw new SystemFontException(
                    FontManager.ERROR_CODE_FONT_UPDATER_DISABLED,
                    "The font updater is disabled.");
        }
        mUpdatableFontDir.clearUpdates();
    }

    /* package */ Map<String, File> getFontFileMap() {
        if (mUpdatableFontDir == null) {
            return Collections.emptyMap();
        } else {
            return mUpdatableFontDir.getFontFileMap();
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        new FontManagerShellCommand(this).dumpAll(new IndentingPrintWriter(writer, "  "));
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver result) throws RemoteException {
        new FontManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    /**
     * Returns an active system font configuration.
     */
    public @NonNull FontConfig getSystemFontConfig() {
        if (mUpdatableFontDir != null) {
            return mUpdatableFontDir.getSystemFontConfig();
        } else {
            return SystemFonts.getSystemPreinstalledFontConfig();
        }
    }

    /**
     * Make new serialized font map data.
     */
    public @Nullable SharedMemory buildNewSerializedFontMap() {
        try {
            final FontConfig fontConfig = getSystemFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);

            return Typeface.serializeFontMap(typefaceMap);
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to serialize updatable font map. "
                    + "Retrying with system image fonts.", e);
        }

        try {
            final FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);

            return Typeface.serializeFontMap(typefaceMap);
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
        }
        return null;
    }

}
