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

package com.android.server.inputmethod.multisessiontest;

final class TestRequestConstants {
    private TestRequestConstants() {
    }

    public static final String KEY_REQUEST_CODE = "key_request_code";
    public static final String KEY_RESULT_CODE = "key_result_code";

    public static final int REQUEST_IME_STATUS = 1;
    public static final int REPLY_IME_SHOWN = 2;
    public static final int REPLY_IME_HIDDEN = 3;
}
