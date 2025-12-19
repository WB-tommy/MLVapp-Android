#include "../mlv_jni_wrapper.h"
#include "../utils.h"
#include <jni.h>

extern "C" {
#include "../../src/mlv/llrawproc/darkframe.h"
#include "../../src/mlv/llrawproc/llrawproc.h"
#include "../../src/mlv/mlv_object.h"
#include "../../src/mlv/video_mlv.h"
}

const char *RAW_TAG = "RawCorrection";

/**
 * Helper to safely get mlvObject_t from handle
 */
static mlvObject_t *getMlvObjectFromHandle(jlong handle) {
    if (handle == 0) {
        return nullptr;
    }
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    return wrapper ? wrapper->mlv_object : nullptr;
}

/**
 * Helper function to convert jstring to C string
 */
static const char *jstring_to_cstr(JNIEnv *env, jstring jstr) {
    if (!jstr)
        return nullptr;
    return env->GetStringUTFChars(jstr, nullptr);
}

static void release_cstr(JNIEnv *env, jstring jstr, const char *cstr) {
    if (jstr && cstr) {
        env->ReleaseStringUTFChars(jstr, cstr);
    }
}

/**
 * Enable/disable all raw corrections
 * JNI: setRawCorrectionEnabled(J, Z)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setRawCorrectionEnabled(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enable) {
    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawCorrectionEnabled: Invalid MLV object or llrawproc");
        return;
    }

    llrpSetFixRawMode(video, enable ? 1 : 0);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dark frame file select
 * JNI: setDarkFrameMode(J, I, Ljava/lang/String;)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkFrameFile(
        JNIEnv *env, jobject /* this */, jlong handle, jint fd) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDarkFrameFile: Invalid MLV object or llrawproc");
        return;
    }

    char mock_filename[1] = "";
    video->llrawproc->dark_frame_fds[0] = fd;
    char err_msg[256];
    llrpValidateExtDarkFrame(video, mock_filename, err_msg);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dark frame subtraction mode
 * JNI: setDarkFrameMode(J, I, Ljava/lang/String;)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkFrameMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDarkFrameMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=External, 2=Internal
    llrpSetDarkFrameMode(video, mode);
    llrpResetBpmStatus(video);
    llrpComputeStripesOn(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set focus dots fix mode
 * JNI: setFocusDotsMode(J, I, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setFocusDotsMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode,
        jint interpolation) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setFocusDotsMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=On, 2=CropRec
    llrpSetFocusPixelMode(video, mode);

    if (mode > 0) llrpSetFocusPixelInterpolationMethod(video, interpolation);
}

/**
 * Set bad pixels fix mode
 * JNI: setBadPixelsMode(J, I, I, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setBadPixelsMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode, jint searchMethod,
        jint interpolation) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setBadPixelsMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=Auto, 2=Force, 3=Map
    llrpSetBadPixelMode(video, mode);

    if (mode > 0) {
        // Search Method: 0=Normal, 1=Aggressive, 2=Edit
        llrpSetBadPixelSearchMethod(video, searchMethod);

        // Interpolation: 0=Method1, 1=Method2, 2=Method3
        llrpSetBadPixelInterpolationMethod(video, interpolation);
    }
}

/**
 * Set chroma smoothing mode
 * JNI: setChromaSmoothMode(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setChromaSmoothMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setChromaSmoothMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=2x2, 2=3x3, 3=5x5
    llrpSetChromaSmoothMode(video, mode);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set vertical stripes fix mode
 * JNI: setVerticalStripesMode(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setVerticalStripesMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setVerticalStripesMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=Normal, 2=Force
    llrpSetVerticalStripeMode(video, mode);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dual ISO mode
 * JNI: setDualIsoMode(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDualIsoMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=On, 2=Preview
    llrpSetDualIsoMode(video, mode);
    // Reset processing black and white levels
    processingSetBlackAndWhiteLevel(video->processing, getMlvBlackLevel(video),
                                    getMlvWhiteLevel(video),
                                    getMlvBitdepth(video));
    // Reset diso levels to mlv raw levels
    llrpResetDngBWLevels(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dual ISO forced mode
 * JNI: setDualIsoForced(J, Z)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDualIsoForced(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean force) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoForced: Invalid MLV object or llrawproc");
        return;
    }

    llrpSetDualIsoValidity(video, force ? 1 : 0);
}

/**
 * Set dual ISO interpolation method
 * JNI: setDualIsoMethod(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDualIsoInterpolation(
        JNIEnv *env, jobject /* this */, jlong handle, jint interpolation) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoMethod: Invalid MLV object or llrawproc");
        return;
    }

    // Interpolation: 0=Amaze, 1=Mean
    llrpSetDualIsoInterpolationMethod(video, interpolation);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dual ISO Alias Map
 * JNI: setDualIsoAliasMap(J, Z)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDualIsoAliasMap(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean isEnabled) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoAliasMap: Invalid MLV object or llrawproc");
        return;
    }

    // Interpolation: 0=Amaze, 1=Mean
    llrpSetDualIsoAliasMapMode(video, isEnabled);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set pattern noise reduction mode
 * JNI: setPatternNoise(J, Z)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setPatternNoise(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enable) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setPatternNoise: Invalid MLV object or llrawproc");
        return;
    }

    llrpSetPatternNoiseMode(video, enable ? 1 : 0);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set RAW black level
 * JNI: setRawBlackLevel(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setRawBlackLevel(
        JNIEnv *env, jobject /* this */, jlong handle, jint level) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawBlackLevel: Invalid MLV object or llrawproc");
        return;
    }

    if (getMlvBitdepth(video) == 0)
        return;
    if (getMlvBitdepth(video) > 16)
        return;

    setMlvBlackLevel(video, level);
    processingSetBlackLevel(video->processing, (float) level,
                            getMlvBitdepth(video));
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set RAW white level
 * JNI: setRawWhiteLevel(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setRawWhiteLevel(
        JNIEnv *env, jobject /* this */, jlong handle, jint level) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawWhiteLevel: Invalid MLV object or llrawproc");
        return;
    }

    if (getMlvBitdepth(video) == 0)
        return;
    if (getMlvBitdepth(video) > 16)
        return;

    setMlvWhiteLevel(video, level);
    processingSetWhiteLevel(video->processing, level, getMlvBitdepth(video));
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}
