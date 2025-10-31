//
// Created by Sungmin Choi on 2025. 10. 11..
//
#include "clip/clip_jni.h"
#include "mlv_jni_wrapper.h" // Include the wrapper definition
#include <cstdio>
#include <cinttypes>
#include "../src/mlv/llrawproc/pixelproc.h"

extern "C" {

JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_checkCameraModel(
        JNIEnv *env, jobject thiz,
        jlong handle) {
    if (handle == 0) {
        return -1;
    }

    // Correctly cast to the wrapper first, then get the mlv_object
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(static_cast<uintptr_t>(handle));
    if (!wrapper || !wrapper->mlv_object) {
        return -1;
    }
    auto *nativeClip = wrapper->mlv_object;

    return llrpDetectFocusDotFixMode(nativeClip);
}

JNIEXPORT jstring JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_getFpmName(
        JNIEnv *env, jobject /* this */,
        jlong handle) {

    if (handle == 0) {
        return env->NewStringUTF("null handle");
    }

    // Correctly cast to the wrapper first, then get the mlv_object
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(static_cast<uintptr_t>(handle));
    if (!wrapper || !wrapper->mlv_object) {
        return env->NewStringUTF("failed to cast handle");
    }
    auto *nativeClip = wrapper->mlv_object;

    const uint32_t cameraID = nativeClip->IDNT.cameraModel;
    int width = nativeClip->RAWI.raw_info.width;
    int height = nativeClip->RAWI.raw_info.height;
    if (width == 0 || height == 0) {
        width = getMlvWidth(nativeClip);
        height = getMlvHeight(nativeClip);
    }

    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%08X_%dx%d.fpm",
                  cameraID,
                  width,
                  height);

    return env->NewStringUTF(buffer);
}

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_refreshFocusPixelMap(
        JNIEnv *env, jobject /* this */, jlong handle) {
    if (handle == 0) {
        return;
    }
    // Correctly cast to the wrapper first, then get the mlv_object
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(static_cast<uintptr_t>(handle));
    if (!wrapper || !wrapper->mlv_object) {
        return;
    }
    auto *nativeClip = wrapper->mlv_object;

    llrpResetFpmStatus(nativeClip);
    llrpResetBpmStatus(nativeClip);
    resetMlvCache(nativeClip);
    resetMlvCachedFrame(nativeClip);
}

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_setFocusPixelMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {
    if (handle == 0) {
        return;
    }
    // Correctly cast to the wrapper first, then get the mlv_object
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(static_cast<uintptr_t>(handle));
    if (!wrapper || !wrapper->mlv_object) {
        return;
    }
    auto *nativeClip = wrapper->mlv_object;

    llrpSetFocusPixelMode(nativeClip, mode);
}

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_setFixRawMode(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enabled) {
    if (handle == 0) {
        return;
    }
    // Correctly cast to the wrapper first, then get the mlv_object
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(static_cast<uintptr_t>(handle));
    if (!wrapper || !wrapper->mlv_object) {
        return;
    }
    auto *nativeClip = wrapper->mlv_object;

    llrpSetFixRawMode(nativeClip, enabled ? 1 : 0);
}

}