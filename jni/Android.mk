LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := emoplus_zygisk
LOCAL_SRC_FILES := zygisk.cpp embedded.S
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti
LOCAL_CFLAGS := -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
