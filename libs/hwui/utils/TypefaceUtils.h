/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

#include "SkFontMgr.h"
#include "SkRefCnt.h"

namespace android {

// Return an SkFontMgr which is capable of turning bytes into a SkTypeface using Freetype.
// There are no other fonts inside this SkFontMgr (e.g. no system fonts).
sk_sp<SkFontMgr> FreeTypeFontMgr();

}  // namespace android