//
// Created by Sungmin Choi on 2025. 10. 11..
//

#ifndef MLVAPP_CLIP_JNI_H
#define MLVAPP_CLIP_JNI_H

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <new>

extern "C" {
#include "mlv/mlv_object.h"
#include "mlv/video_mlv.h"
#include "dng/dng.h"
#include "mlv/llrawproc/llrawproc.h"
#include <time.h>

// for debugging
#include <android/log.h>
#include <cinttypes>

// Logging macros
#define LOG_TAG "fm.forum.mlvapp.jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

mlvObject_t *getMlvObject(
        JNIEnv *env,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores,
        bool isFull);

JNIEXPORT jobject JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_openClipForPreview(
        JNIEnv *env, jobject /* this */,
        jint fd,
        jstring fileName, jlong cacheSize,
        jint cores);

JNIEXPORT jobject JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_openClip(
        JNIEnv *env, jobject /* this */,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores);

JNIEXPORT jlongArray JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getVideoFrameTimestamps(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jboolean JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_fillFrame16(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jint frameIndex,
        jint cores,
        jobject dstByteBuffer,
        jint width,
        jint height);

JNIEXPORT jlong JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getAudioBufferSize(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getAudioBytesPerSample(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_readAudioBuffer(
        JNIEnv *env, jobject /* this */,
        jlong handle,
        jlong offsetBytes,
        jint byteCount,
        jobject dstByteBuffer);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_closeClip(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jstring JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getFpmName(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_checkCameraModel(
        JNIEnv *env, jobject thiz,
        jlong handle);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_setBaseDir(
        JNIEnv *env, jobject /* this */, jstring baseDir);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_refreshFocusPixelMap(
        JNIEnv *env, jobject /* this */, jlong handle);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_setFocusPixelMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_setFixRawMode(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enabled);

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_setDebayerMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode);

}
#endif //MLVAPP_CLIP_JNI_H
