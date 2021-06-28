/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.hardware.camera2.extension;

import android.hardware.camera2.extension.IAdvancedExtenderImpl;
import android.hardware.camera2.extension.IPreviewExtenderImpl;
import android.hardware.camera2.extension.IImageCaptureExtenderImpl;
import android.hardware.camera2.extension.IInitializeSessionCallback;

/** @hide */
interface ICameraExtensionsProxyService
{
    long registerClient();
    void unregisterClient(long clientId);
    boolean advancedExtensionsSupported();
    void initializeSession(in IInitializeSessionCallback cb);
    void releaseSession();
    @nullable IPreviewExtenderImpl initializePreviewExtension(int extensionType);
    @nullable IImageCaptureExtenderImpl initializeImageExtension(int extensionType);
    @nullable IAdvancedExtenderImpl initializeAdvancedExtension(int extensionType);
}
