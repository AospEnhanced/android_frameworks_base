/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_INPUT_APPLICATION_HANDLE_H
#define _ANDROID_VIEW_INPUT_APPLICATION_HANDLE_H

#include <string>

#include <gui/InputApplication.h>

#include <nativehelper/JNIHelp.h>
#include "jni.h"

namespace android {

class NativeInputApplicationHandle : public InputApplicationHandle {
public:
    explicit NativeInputApplicationHandle(jweak objWeak);
    ~NativeInputApplicationHandle() override;

    jobject getInputApplicationHandleObjLocalRef(JNIEnv* env);

    bool updateInfo() override;

private:
    jweak mObjWeak;
};

extern std::shared_ptr<InputApplicationHandle> android_view_InputApplicationHandle_getHandle(
        JNIEnv* env, jobject inputApplicationHandleObj);

extern jobject android_view_InputApplicationHandle_fromInputApplicationInfo(
        JNIEnv* env, gui::InputApplicationInfo inputApplicationInfo);

} // namespace android

#endif // _ANDROID_VIEW_INPUT_APPLICATION_HANDLE_H
