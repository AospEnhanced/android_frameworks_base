/*
 * Copyright (C) 2015 The CyanogenMod Project
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

#define LOG_TAG "CmhwService"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <stdlib.h>
#include <utils/misc.h>
#include <utils/Log.h>
#include <hardware/hardware.h>

namespace android {

static jlong halOpen(JNIEnv *env, jobject obj) {
    //TODO
    return 0;
}

static JNINativeMethod method_table[] = {
    { "halOpen", "()J", (void *)halOpen },
};

int register_android_server_CmhwService(JNIEnv *env) {
    return jniRegisterNativeMethods(env, "com/android/server/CmhwService",
            method_table, NELEM(method_table));
}

};
