LOCAL_PATH:= $(call my-dir)

# merge all required services into one jar
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE := services
LOCAL_DEX_PREOPT_APP_IMAGE := true
LOCAL_DEX_PREOPT_GENERATE_PROFILE := true
LOCAL_DEX_PREOPT_PROFILE_CLASS_LISTING := $(LOCAL_PATH)/profile-classes

LOCAL_SRC_FILES := $(call all-java-files-under,java)

# EventLogTags files.
LOCAL_SRC_FILES += \
        core/java/com/android/server/EventLogTags.logtags

# Uncomment to enable output of certain warnings (deprecated, unchecked)
# LOCAL_JAVACFLAGS := -Xlint

# Services that will be built as part of services.jar
# These should map to directory names relative to this
# Android.mk.
services := \
    core \
    accessibility \
    appwidget \
    autofill \
    backup \
    companion \
    coverage\
    devicepolicy \
    midi \
    net \
    print \
    restrictions \
    retaildemo \
    usage \
    usb \
    voiceinteraction

# The convention is to name each service module 'services.$(module_name)'
LOCAL_STATIC_JAVA_LIBRARIES := $(addprefix services.,$(services)) \
    android.hidl.base-V1.0-java-static \
    android.hardware.biometrics.fingerprint-V2.1-java-static

LOCAL_JAVA_LIBRARIES += org.lineageos.platform.internal

ifeq ($(EMMA_INSTRUMENT_FRAMEWORK),true)
LOCAL_EMMA_INSTRUMENT := true
endif

include $(BUILD_JAVA_LIBRARY)

# native library
# =============================================================

include $(CLEAR_VARS)

LOCAL_SRC_FILES :=
LOCAL_SHARED_LIBRARIES :=

# include all the jni subdirs to collect their sources
include $(wildcard $(LOCAL_PATH)/*/jni/Android.mk)

LOCAL_CFLAGS += -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES

LOCAL_MODULE:= libandroid_servers

include $(BUILD_SHARED_LIBRARY)

# =============================================================

ifeq (,$(ONE_SHOT_MAKEFILE))
# A full make is happening, so make everything.
include $(call all-makefiles-under,$(LOCAL_PATH))
else
# If we ran an mm[m] command, we still want to build the individual
# services that we depend on. This differs from the above condition
# by only including service makefiles and not any tests or other
# modules.
include $(patsubst %,$(LOCAL_PATH)/%/Android.mk,$(services))
endif

