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
const char *const TAG = "fm.magiclantern.forum.jni";

mlvObject_t *getMlvObject(
        JNIEnv *env,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores,
        bool useParallelMcrawDecoder,
        bool isFull);

JNIEXPORT jobject JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_openClipForPreview(
        JNIEnv *env, jobject /* this */,
        jint fd,
        jstring fileName, jlong cacheSize,
        jint cores,
        jboolean useParallelMcrawDecoder);

JNIEXPORT jlong JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_probeMlvGuid(
        JNIEnv *env, jobject /* this */,
        jint fd,
        jstring fileName);

JNIEXPORT jobject JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_openClip(
        JNIEnv *env, jobject /* this */,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores,
        jboolean useParallelMcrawDecoder);

JNIEXPORT jlongArray JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getVideoFrameTimestamps(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillFrame16(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jint frameIndex,
        jint cores,
        jobject dstByteBuffer,
        jint width,
        jint height);

JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillRawBayer16(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jint frameIndex,
        jobject dstByteBuffer,
        jint decoderBackend,
        jint decoderThreads);

JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillRawGpuPreviewState(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jobject paramsByteBuffer,
        jobject toneLutByteBuffer);

JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setRawGpuPreviewCaching(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jboolean enabled);

JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setMcrawParallelDecoder(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jboolean enabled);

JNIEXPORT jlong JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getAudioBufferSize(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getAudioBytesPerSample(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_readAudioBuffer(
        JNIEnv *env, jobject /* this */,
        jlong handle,
        jlong offsetBytes,
        jint byteCount,
        jobject dstByteBuffer);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_closeClip(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jstring JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getFpmName(
        JNIEnv *env, jobject /* this */,
        jlong handle);

JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_checkCameraModel(
        JNIEnv *env, jobject thiz,
        jlong handle);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setBaseDir(
        JNIEnv *env, jobject /* this */, jstring baseDir);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_refreshFocusPixelMap(
        JNIEnv *env, jobject /* this */, jlong handle);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setFocusPixelMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setFixRawMode(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enabled);

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setDebayerMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode);

}
#endif //MLVAPP_CLIP_JNI_H
