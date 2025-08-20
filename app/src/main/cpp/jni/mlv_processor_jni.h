//
// Created by Sungmin Choi on 2025. 7. 20..
//

#ifndef MLVAPP_MLV_PROCESSOR_JNI_H
#define MLVAPP_MLV_PROCESSOR_JNI_H
#include <jni.h>
#include <android/log.h>

// Include MLV headers (you'll need to copy these from the original project)
extern "C" {
#include "../src/mlv/mlv_object.h"
#include "../src/mlv/video_mlv.h"
#include "../src/dng/dng.h"
}

// Logging macros
#define LOG_TAG "MLVProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
}
#endif

#endif //MLVAPP_MLV_PROCESSOR_JNI_H
