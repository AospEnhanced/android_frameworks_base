/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.view;

import android.net.Uri;
import android.os.ResultReceiver;
import android.text.style.SuggestionSpan;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.inputmethod.EditorInfo;
import android.view.InputDevice;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.view.InputBindResult;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;

/**
 * Public interface to the global input method manager, used by all client
 * applications.
 * You need to update BridgeIInputMethodManager.java as well when changing
 * this file.
 */
interface IInputMethodManager {
    // TODO: Use ParceledListSlice instead
    List<InputMethodInfo> getInputMethodList();
    List<InputMethodInfo> getVrInputMethodList();
    // TODO: Use ParceledListSlice instead
    List<InputMethodInfo> getEnabledInputMethodList();
    List<InputMethodSubtype> getEnabledInputMethodSubtypeList(in String imiId,
            boolean allowsImplicitlySelectedSubtypes);
    InputMethodSubtype getLastInputMethodSubtype();
    // TODO: We should change the return type from List to List<Parcelable>
    // Currently there is a bug that aidl doesn't accept List<Parcelable>
    List getShortcutInputMethodsAndSubtypes();
    void addClient(in IInputMethodClient client,
            in IInputContext inputContext, int uid, int pid);
    void removeClient(in IInputMethodClient client);

    void finishInput(in IInputMethodClient client);
    boolean showSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    boolean hideSoftInput(in IInputMethodClient client, int flags,
            in ResultReceiver resultReceiver);
    // If windowToken is null, this just does startInput().  Otherwise this reports that a window
    // has gained focus, and if 'attribute' is non-null then also does startInput.
    // @NonNull
    InputBindResult startInputOrWindowGainedFocus(
            /* @InputMethodClient.StartInputReason */ int startInputReason,
            in IInputMethodClient client, in IBinder windowToken, int controlFlags,
            /* @android.view.WindowManager.LayoutParams.SoftInputModeFlags */ int softInputMode,
            int windowFlags, in EditorInfo attribute, IInputContext inputContext,
            /* @InputConnectionInspector.MissingMethodFlags */ int missingMethodFlags,
            int unverifiedTargetSdkVersion);

    void showInputMethodPickerFromClient(in IInputMethodClient client,
            int auxiliarySubtypeMode);
    void showInputMethodAndSubtypeEnablerFromClient(in IInputMethodClient client, String topId);
    boolean isInputMethodPickerShownForTest();
    void setInputMethod(in IBinder token, String id);
    void setInputMethodAndSubtype(in IBinder token, String id, in InputMethodSubtype subtype);
    void hideMySoftInput(in IBinder token, int flags);
    void showMySoftInput(in IBinder token, int flags);
    void updateStatusIcon(in IBinder token, String packageName, int iconId);
    void setImeWindowStatus(in IBinder token, in IBinder startInputToken, int vis,
            int backDisposition);
    void registerSuggestionSpansForNotification(in SuggestionSpan[] spans);
    boolean notifySuggestionPicked(in SuggestionSpan span, String originalString, int index);
    InputMethodSubtype getCurrentInputMethodSubtype();
    boolean setCurrentInputMethodSubtype(in InputMethodSubtype subtype);
    boolean switchToPreviousInputMethod(in IBinder token);
    boolean switchToNextInputMethod(in IBinder token, boolean onlyCurrentIme);
    boolean shouldOfferSwitchingToNextInputMethod(in IBinder token);
    void setAdditionalInputMethodSubtypes(String id, in InputMethodSubtype[] subtypes);
    int getInputMethodWindowVisibleHeight();
    void clearLastInputMethodWindowForTransition(in IBinder token);

    IInputContentUriToken createInputContentUriToken(in IBinder token, in Uri contentUri,
            in String packageName);

    void reportFullscreenMode(in IBinder token, boolean fullscreen);

    oneway void notifyUserAction(int sequenceNumber);

    void handleInputSourceChange(in InputDevice InputDevice);
    void enableBeyonderSwitchImeNotifier();
    String getCurrentInputMethod();
}
