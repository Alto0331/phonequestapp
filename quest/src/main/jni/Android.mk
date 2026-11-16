LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := core
LOCAL_SRC_FILES := core.c
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
