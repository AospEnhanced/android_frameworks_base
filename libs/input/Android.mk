# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    PointerController.cpp \
    SpriteController.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    liblog \
    libutils \
    libskia \
    libgui \
    libui \
	libinput \
	libinputflinger

LOCAL_C_INCLUDES := \
    frameworks/native/services


LOCAL_CFLAGS += -Wno-unused-parameter

ifeq ($(TARGET_DISABLE_CURSOR_LAYER),true)
	LOCAL_CFLAGS += -DDISABLE_CURSOR_LAYER
endif

LOCAL_MODULE:= libinputservice

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)


# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
