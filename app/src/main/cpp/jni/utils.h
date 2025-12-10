//
// Created by Sungmin Choi on 2025. 12. 9..
//

#ifndef MLVAPP_UTILS_H
#define MLVAPP_UTILS_H
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, __VA_ARGS__)

#endif //MLVAPP_UTILS_H
