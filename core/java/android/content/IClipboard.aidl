/**
 * Copyright (c) 2008, The Android Open Source Project
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

package android.content;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.IOnPrimaryClipChangedListener;

/**
 * Programming interface to the clipboard, which allows copying and pasting
 * between applications.
 * {@hide}
 */
interface IClipboard {
    void setPrimaryClip(in ClipData clip, String callingPackage, String attributionTag, int userId);
    void setPrimaryClipAsPackage(in ClipData clip, String callingPackage, String attributionTag, int userId,
            String sourcePackage);
    void clearPrimaryClip(String callingPackage, String attributionTag, int userId);
    ClipData getPrimaryClip(String pkg, String attributionTag, int userId);
    ClipDescription getPrimaryClipDescription(String callingPackage, String attributionTag, int userId);
    boolean hasPrimaryClip(String callingPackage, String attributionTag, int userId);
    void addPrimaryClipChangedListener(in IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId);
    void removePrimaryClipChangedListener(in IOnPrimaryClipChangedListener listener,
            String callingPackage, String attributionTag, int userId);

    /**
     * Returns true if the clipboard contains text; false otherwise.
     */
    boolean hasClipboardText(String callingPackage, String attributionTag, int userId);

    String getPrimaryClipSource(String callingPackage, String attributionTag, int userId);
}
