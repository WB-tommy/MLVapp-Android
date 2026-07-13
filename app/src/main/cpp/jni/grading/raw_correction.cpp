#include "../mlv_jni_wrapper.h"
#include "../utils.h"
#include "desktop_processing_mapping.h"
#include "../../src/mlv/video_mlv.h"
#include <jni.h>
#include <mutex>
#include <unistd.h>

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

static JniClipWrapper *getWrapperFromHandle(jlong handle) {
    return handle == 0 ? nullptr : reinterpret_cast<JniClipWrapper *>(handle);
}

static int clampRawSetting(int value, int minimum, int maximum) {
    return value < minimum ? minimum : (value > maximum ? maximum : value);
}

static void resetProcessingLevelsToRaw(mlvObject_t *video) {
    if (!video || !video->processing || !video->llrawproc) return;
    pthread_mutex_lock(&video->processing_mutex);
    processingSetBlackAndWhiteLevel(video->processing,
                                    getMlvBlackLevel(video),
                                    getMlvWhiteLevel(video),
                                    getMlvBitdepth(video));
    llrpResetDngBWLevels(video);
    pthread_mutex_unlock(&video->processing_mutex);
}

static int availableDarkFrameMode(mlvObject_t *video, int requestedMode) {
    const int mode = clampRawSetting(requestedMode, DF_OFF, DF_INT);
    if (mode == DF_EXT &&
        (!video->llrawproc->dark_frame_data ||
         video->llrawproc->dark_frame_data_source != DF_EXT)) {
        return DF_OFF;
    }
    if (mode == DF_INT && !video->DARK.blockType[0]) {
        return DF_OFF;
    }
    return mode;
}

template <typename Update>
static void updateProcessing(jlong handle, const char *action, Update update) {
    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->processing) {
        LOGE(RAW_TAG, "%s: Invalid MLV object or processing", action);
        return;
    }

    // These parameters are applied after the cached RAW/debayer stage. The
    // ViewModel advances processingVersion to redraw the current frame.
    update(video->processing);
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

/** Restore one complete RAW receipt without exposing a partially updated
 * correction stack to playback or the background cache. */
extern "C" JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_applyRawCorrectionSettings(
        JNIEnv * /*env*/, jobject /*this*/, jlong handle, jboolean enabled,
        jint verticalStripes, jint focusPixels, jint fpiMethod,
        jint badPixels, jint bpsMethod, jint bpiMethod, jint chromaSmooth,
        jboolean patternNoise, jint deflickerTarget, jint dualIso,
        jboolean dualIsoForced, jint dualIsoInterpolation,
        jboolean dualIsoAliasMap, jboolean dualIsoFrBlending,
        jint rawBlackLevel, jint rawWhiteLevel, jint darkFrameMode) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "applyRawCorrectionSettings: Invalid wrapper");
        return DF_OFF;
    }

    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc || !video->processing) {
        LOGE(RAW_TAG, "applyRawCorrectionSettings: Invalid MLV processing state");
        return DF_OFF;
    }

    pthread_mutex_lock(&video->processing_mutex);
    llrpSetVerticalStripeMode(video,
            clampRawSetting(verticalStripes, VS_OFF, VS_FORCE));
    llrpSetFocusPixelMode(video,
            clampRawSetting(focusPixels, FP_OFF, FP_CROPREC));
    llrpSetFocusPixelInterpolationMethod(video,
            clampRawSetting(fpiMethod, 0, 2));
    llrpSetBadPixelMode(video, clampRawSetting(badPixels, 0, 3));
    llrpSetBadPixelSearchMethod(video, clampRawSetting(bpsMethod, 0, 2));
    llrpSetBadPixelInterpolationMethod(video,
            clampRawSetting(bpiMethod, 0, 2));
    llrpSetChromaSmoothMode(video,
            clampRawSetting(chromaSmooth, CS_OFF, CS_5x5));
    llrpSetPatternNoiseMode(video, patternNoise == JNI_TRUE ? PN_ON : PN_OFF);
    llrpSetDeflickerTarget(video, deflickerTarget < 0 ? 0 : deflickerTarget);
    llrpSetDualIsoMode(video,
            clampRawSetting(dualIso, DISO_OFF, DISO_FAST));
    llrpSetDualIsoInterpolationMethod(video,
            clampRawSetting(dualIsoInterpolation, DISOI_AMAZE, DISOI_MEAN23));
    llrpSetDualIsoAliasMapMode(video, dualIsoAliasMap == JNI_TRUE ? 1 : 0);
    llrpSetDualIsoFullResBlendingMode(
            video, dualIsoFrBlending == JNI_TRUE ? 1 : 0);
    llrpSetDualIsoValidity(video, dualIsoForced == JNI_TRUE ? 1 : 0);
    const int appliedDarkFrameMode = availableDarkFrameMode(video, darkFrameMode);
    llrpSetDarkFrameMode(video, appliedDarkFrameMode);

    const int bitDepth = getMlvBitdepth(video);
    if (bitDepth > 0 && bitDepth <= 16) {
        const int maximumLevel = static_cast<int>((1u << bitDepth) - 1u);
        if (rawBlackLevel >= 0 && rawWhiteLevel > rawBlackLevel &&
            rawWhiteLevel <= maximumLevel) {
            setMlvBlackLevel(video, rawBlackLevel);
            setMlvWhiteLevel(video, rawWhiteLevel);
            processingSetBlackAndWhiteLevel(video->processing,
                                            rawBlackLevel,
                                            rawWhiteLevel,
                                            bitDepth);
        }
    }

    llrpResetDngBWLevels(video);
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    llrpComputeStripesOn(video);
    llrpSetFixRawMode(video, enabled == JNI_TRUE ? FR_ON : FR_OFF);
    pthread_mutex_unlock(&video->processing_mutex);

    resetMlvCache(video);
    resetMlvCachedFrame(video);
    return appliedDarkFrameMode;
}

/**
 * Enable/disable all raw corrections
 * JNI: setRawCorrectionEnabled(J, Z)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setRawCorrectionEnabled(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enable) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setRawCorrectionEnabled: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawCorrectionEnabled: Invalid MLV object or llrawproc");
        return;
    }

    llrpSetFixRawMode(video, enable ? 1 : 0);
    if (enable != JNI_TRUE) {
        /* Restricted-range Dual ISO may have changed processing levels on a
         * prior frame. Bypassing llrawproc must expose the current RAWI/user
         * levels, not the stale scaled pair. */
        resetProcessingLevelsToRaw(video);
    }
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dark frame file select
 * JNI: setDarkFrameFile(J, I)Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkFrameFile(
        JNIEnv *env, jobject /* this */, jlong handle, jint fd) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDarkFrameFile: Invalid wrapper");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDarkFrameFile: Invalid MLV object or llrawproc");
        return JNI_FALSE;
    }

    char mock_filename[1] = "";
    const int ownedFd = dup(fd);
    if (ownedFd < 0) {
        LOGE(RAW_TAG, "setDarkFrameFile: Could not duplicate descriptor");
        return JNI_FALSE;
    }
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->dark_frame_fds[0] = ownedFd;
    char err_msg[256] = {};
    const int validationResult =
            llrpValidateExtDarkFrame(video, mock_filename, err_msg);
    if (validationResult == 0) {
        /* Commit the new buffer and Ext mode under one render/process lock.
         * Otherwise an Int-mode draw between two JNI calls can replace the
         * one-shot SAF buffer before Ext is enabled. */
        llrpSetDarkFrameMode(video, DF_EXT);
        llrpResetBpmStatus(video);
        llrpComputeStripesOn(video);
    }
    pthread_mutex_unlock(&video->processing_mutex);
    if (validationResult != 0) {
        LOGE(RAW_TAG, "setDarkFrameFile: %s", err_msg);
        return JNI_FALSE;
    }
    resetMlvCache(video);
    resetMlvCachedFrame(video);
    return JNI_TRUE;
}

/**
 * Set dark frame subtraction mode
 * JNI: setDarkFrameMode(J, I)Z
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkFrameMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDarkFrameMode: Invalid wrapper");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDarkFrameMode: Invalid MLV object or llrawproc");
        return JNI_FALSE;
    }

    // Mode: 0=Off, 1=External, 2=Internal
    const int requestedMode = clampRawSetting(mode, DF_OFF, DF_INT);
    const int appliedMode = availableDarkFrameMode(video, requestedMode);
    if (appliedMode != requestedMode) {
        LOGE(RAW_TAG, "setDarkFrameMode: requested source is unavailable");
        return JNI_FALSE;
    }
    llrpSetDarkFrameMode(video, appliedMode);
    llrpResetBpmStatus(video);
    llrpComputeStripesOn(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
    return JNI_TRUE;
}

/**
 * Set focus dots fix mode
 * JNI: setFocusDotsMode(J, I, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setFocusDotsMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode,
        jint interpolation) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setFocusDotsMode: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setFocusDotsMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=On, 2=CropRec
    llrpSetFocusPixelMode(video, mode);

    if (mode > 0) llrpSetFocusPixelInterpolationMethod(video, interpolation);

    // Trigger FPM reload and invalidate cached frame so the change is visible immediately
    llrpResetFpmStatus(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set bad pixels fix mode
 * JNI: setBadPixelsMode(J, I, I, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setBadPixelsMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode, jint searchMethod,
        jint interpolation) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setBadPixelsMode: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    llrpResetBpmStatus(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set chroma smoothing mode
 * JNI: setChromaSmoothMode(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setChromaSmoothMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setChromaSmoothMode: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setVerticalStripesMode: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDualIsoMode: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoMode: Invalid MLV object or llrawproc");
        return;
    }

    // Mode: 0=Off, 1=On, 2=Preview
    llrpSetDualIsoMode(video, mode);
    resetProcessingLevelsToRaw(video);
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDualIsoForced: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setDualIsoForced: Invalid MLV object or llrawproc");
        return;
    }

    llrpSetDualIsoValidity(video, force ? 1 : 0);
    /* A forced restricted-range frame can scale the processing levels. When
     * force is removed from a clip without valid DISO metadata, reset them
     * immediately so corrected-Bayer metadata cannot remain stale. */
    resetProcessingLevelsToRaw(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set dual ISO interpolation method
 * JNI: setDualIsoMethod(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDualIsoInterpolation(
        JNIEnv *env, jobject /* this */, jlong handle, jint interpolation) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDualIsoInterpolation: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setDualIsoAliasMap: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setPatternNoise: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setRawBlackLevel: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawBlackLevel: Invalid MLV object or llrawproc");
        return;
    }

    const int bitDepth = getMlvBitdepth(video);
    if (bitDepth <= 0 || bitDepth > 16)
        return;
    const int maximumLevel = static_cast<int>((1u << bitDepth) - 1u);
    if (level < 0 || level >= getMlvWhiteLevel(video) || level > maximumLevel)
        return;

    pthread_mutex_lock(&video->processing_mutex);
    setMlvBlackLevel(video, level);
    processingSetBlackLevel(video->processing, (float) level,
                            bitDepth);
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    pthread_mutex_unlock(&video->processing_mutex);
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
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    if (!wrapper) {
        LOGE(RAW_TAG, "setRawWhiteLevel: Invalid wrapper");
        return;
    }
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    mlvObject_t *video = wrapper->mlv_object;
    if (!video || !video->llrawproc) {
        LOGE(RAW_TAG, "setRawWhiteLevel: Invalid MLV object or llrawproc");
        return;
    }

    const int bitDepth = getMlvBitdepth(video);
    if (bitDepth <= 0 || bitDepth > 16)
        return;
    const int maximumLevel = static_cast<int>((1u << bitDepth) - 1u);
    if (level <= getMlvBlackLevel(video) || level > maximumLevel)
        return;

    pthread_mutex_lock(&video->processing_mutex);
    setMlvWhiteLevel(video, level);
    processingSetWhiteLevel(video->processing, level, bitDepth);
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    pthread_mutex_unlock(&video->processing_mutex);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
}

/**
 * Set White Balance Temperature (Kelvin)
 * JNI: setWhiteBalanceTemperature(J, I)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setWhiteBalanceTemperature(
        JNIEnv *env, jobject /* this */, jlong handle, jint kelvin) {
    updateProcessing(handle, "setWhiteBalanceTemperature",
                     [kelvin](processingObject_t *processing) {
        processingSetWhiteBalanceKelvin(
                processing,
                desktop_processing::clampSlider(kelvin, 2000.0, 10000.0));
    });
}

/**
 * Set White Balance Tint
 * JNI: setWhiteBalanceTint(J, F)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setWhiteBalanceTint(
        JNIEnv *env, jobject /* this */, jlong handle, jfloat tint) {
    updateProcessing(handle, "setWhiteBalanceTint",
                     [tint](processingObject_t *processing) {
        processingSetWhiteBalanceTint(
                processing,
                desktop_processing::clampSlider(tint, -100.0, 100.0) / 10.0);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setWhiteBalance(
        JNIEnv *env, jobject /* this */, jlong handle, jint kelvin, jint tint) {
    updateProcessing(handle, "setWhiteBalance",
                     [kelvin, tint](processingObject_t *processing) {
        desktop_processing::setWhiteBalance(processing, kelvin, tint);
    });
}

/** Pick neutral-grey or skin white balance from one source-frame coordinate. */
extern "C" JNIEXPORT jintArray JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_pickWhiteBalance(
        JNIEnv *env, jobject /* this */, jlong handle, jint frameIndex,
        jint posX, jint posY, jint mode) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    mlvObject_t *video = wrapper ? wrapper->mlv_object : nullptr;
    if (!video || !video->processing || !wrapper->processing_buffer_16bit) {
        LOGE(RAW_TAG, "pickWhiteBalance: Invalid clip or processing buffer");
        return nullptr;
    }

    const int width = getMlvWidth(video);
    const int height = getMlvHeight(video);
    const uint32_t frameCount = getMlvFrames(video);
    if (frameIndex < 0 || static_cast<uint32_t>(frameIndex) >= frameCount ||
        posX < 0 || posX >= width || posY < 0 || posY >= height) {
        LOGE(RAW_TAG, "pickWhiteBalance: Coordinate or frame is out of range");
        return nullptr;
    }

    int temperature = 0;
    int tint = 0;
    {
        // The picker temporarily changes WB matrices while searching. Serialize
        // it with rendering and reuse the clip's RGB16 work buffer.
        std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
        getMlvRawFrameDebayered(
                video, static_cast<uint64_t>(frameIndex),
                wrapper->processing_buffer_16bit);
        pthread_mutex_lock(&video->processing_mutex);
        processingFindWhiteBalance(
                video->processing, width, height,
                wrapper->processing_buffer_16bit, posX, posY,
                &temperature, &tint, mode == 1 ? 1 : 0);
        pthread_mutex_unlock(&video->processing_mutex);
    }

    jintArray result = env->NewIntArray(2);
    if (result == nullptr) return nullptr;
    const jint values[2] = {temperature, tint};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

/**
 * Set exposure stops
 * JNI: setExposureStops(J, F)V
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setExposureStops(
        JNIEnv *env, jobject /* this */, jlong handle, jfloat exposure) {
    updateProcessing(handle, "setExposureStops", [exposure](processingObject_t *processing) {
        desktop_processing::setExposure(processing, exposure);
    });
}

/** Apply all controls in the desktop Processing group, excluding curves. */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_applyProcessingSettings(
        JNIEnv *env, jobject /* this */, jlong handle, jfloat exposure,
        jint contrast, jint pivot, jint temperature, jint tint, jint clarity,
        jint vibrance, jint saturation, jint darkStrength, jint darkRange,
        jint lightStrength, jint lightRange, jint lightening, jint shadows,
        jint highlights, jboolean highlightReconstruction,
        jboolean allowCreativeAdjustments, jint profileIndex, jint tonemap,
        jstring transferFunction, jint gamut, jint camMatrixUsed,
        jboolean exrMode, jboolean agx) {
    JniClipWrapper *wrapper = getWrapperFromHandle(handle);
    mlvObject_t *video = wrapper ? wrapper->mlv_object : nullptr;
    if (!video || !video->processing) {
        LOGE(RAW_TAG, "applyProcessingSettings: Invalid MLV object or processing");
        return;
    }

    const char *transfer = jstring_to_cstr(env, transferFunction);

    // Keep a renderer from observing only part of a restored per-clip receipt.
    std::lock_guard<std::mutex> renderLock(wrapper->render_mutex);
    processingObject_t *processing = video->processing;

    // Restore the profile bundle first, then its independently editable
    // overrides. White balance is applied after gamut and matrix selection.
    if (profileIndex >= 1 && profileIndex <= 13) {
        processingSetImageProfile(processing, profileIndex - 1);
    }
    processingSetTonemappingFunction(processing, tonemap);
    if (transfer != nullptr) {
        processingSetTransferFunction(processing, const_cast<char *>(transfer));
    }
    processingSetGamut(processing, gamut);
    desktop_processing::setCameraMatrix(processing, camMatrixUsed);
    desktop_processing::setCreativeAdjustments(
            processing, allowCreativeAdjustments == JNI_TRUE);
    desktop_processing::setExr(processing, exrMode == JNI_TRUE);
    desktop_processing::setAgx(processing, agx == JNI_TRUE);

    desktop_processing::setWhiteBalance(processing, temperature, tint);
    desktop_processing::setExposure(processing, exposure);
    desktop_processing::setContrast(processing, contrast);
    desktop_processing::setPivot(processing, pivot);
    desktop_processing::setClarity(processing, clarity);
    desktop_processing::setVibrance(processing, vibrance);
    desktop_processing::setSaturation(processing, saturation);
    desktop_processing::setDarkStrength(processing, darkStrength);
    desktop_processing::setDarkRange(processing, darkRange);
    desktop_processing::setLightStrength(processing, lightStrength);
    desktop_processing::setLightRange(processing, lightRange);
    desktop_processing::setLightening(processing, lightening);
    desktop_processing::setShadows(processing, shadows);
    desktop_processing::setHighlights(processing, highlights);
    desktop_processing::setHighlightReconstruction(
            processing, highlightReconstruction == JNI_TRUE);

    release_cstr(env, transferFunction, transfer);
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setContrast(
        JNIEnv *env, jobject /* this */, jlong handle, jint contrast) {
    updateProcessing(handle, "setContrast", [contrast](processingObject_t *processing) {
        desktop_processing::setContrast(processing, contrast);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setPivot(
        JNIEnv *env, jobject /* this */, jlong handle, jint pivot) {
    updateProcessing(handle, "setPivot", [pivot](processingObject_t *processing) {
        desktop_processing::setPivot(processing, pivot);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setClarity(
        JNIEnv *env, jobject /* this */, jlong handle, jint clarity) {
    updateProcessing(handle, "setClarity", [clarity](processingObject_t *processing) {
        desktop_processing::setClarity(processing, clarity);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setVibrance(
        JNIEnv *env, jobject /* this */, jlong handle, jint vibrance) {
    updateProcessing(handle, "setVibrance", [vibrance](processingObject_t *processing) {
        desktop_processing::setVibrance(processing, vibrance);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setSaturation(
        JNIEnv *env, jobject /* this */, jlong handle, jint saturation) {
    updateProcessing(handle, "setSaturation", [saturation](processingObject_t *processing) {
        desktop_processing::setSaturation(processing, saturation);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkStrength(
        JNIEnv *env, jobject /* this */, jlong handle, jint strength) {
    updateProcessing(handle, "setDarkStrength", [strength](processingObject_t *processing) {
        desktop_processing::setDarkStrength(processing, strength);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setDarkRange(
        JNIEnv *env, jobject /* this */, jlong handle, jint range) {
    updateProcessing(handle, "setDarkRange", [range](processingObject_t *processing) {
        desktop_processing::setDarkRange(processing, range);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setLightStrength(
        JNIEnv *env, jobject /* this */, jlong handle, jint strength) {
    updateProcessing(handle, "setLightStrength", [strength](processingObject_t *processing) {
        desktop_processing::setLightStrength(processing, strength);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setLightRange(
        JNIEnv *env, jobject /* this */, jlong handle, jint range) {
    updateProcessing(handle, "setLightRange", [range](processingObject_t *processing) {
        desktop_processing::setLightRange(processing, range);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setLightening(
        JNIEnv *env, jobject /* this */, jlong handle, jint lightening) {
    updateProcessing(handle, "setLightening", [lightening](processingObject_t *processing) {
        desktop_processing::setLightening(processing, lightening);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setShadows(
        JNIEnv *env, jobject /* this */, jlong handle, jint shadows) {
    updateProcessing(handle, "setShadows", [shadows](processingObject_t *processing) {
        desktop_processing::setShadows(processing, shadows);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setHighlights(
        JNIEnv *env, jobject /* this */, jlong handle, jint highlights) {
    updateProcessing(handle, "setHighlights", [highlights](processingObject_t *processing) {
        desktop_processing::setHighlights(processing, highlights);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setHighlightReconstruction(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enabled) {
    updateProcessing(handle, "setHighlightReconstruction",
                     [enabled](processingObject_t *processing) {
        desktop_processing::setHighlightReconstruction(
                processing, enabled == JNI_TRUE);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setTonemappingFunction(
        JNIEnv *env, jobject /* this */, jlong handle, jint tonemap) {
    updateProcessing(handle, "setTonemappingFunction",
                     [tonemap](processingObject_t *processing) {
        processingSetTonemappingFunction(processing, tonemap);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setTransferFunction(
        JNIEnv *env, jobject /* this */, jlong handle, jstring transferFunction) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->processing) {
        LOGE(RAW_TAG, "setTransferFunction: Invalid MLV object or processing");
        return;
    }

    char *function_c_str = const_cast<char *>(jstring_to_cstr(env, transferFunction));
    if (!function_c_str) {
        LOGE(RAW_TAG, "setTransferFunction: Invalid transfer function string");
        return;
    }

    processingSetTransferFunction(video->processing, function_c_str);
    release_cstr(env, transferFunction, function_c_str);
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setGamut(
        JNIEnv *env, jobject /* this */, jlong handle, jint gamut) {
    updateProcessing(handle, "setGamut", [gamut](processingObject_t *processing) {
        processingSetGamut(processing, gamut);
    });
}

/**
 * Set image profile preset
 * JNI: setImageProfile(J, I)V
 * Matches desktop: processingSetImageProfile + side effects from
 * on_comboBoxProfile_currentIndexChanged
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setImageProfile(
        JNIEnv *env, jobject /* this */, jlong handle, jint profileIndex) {

    mlvObject_t *video = getMlvObjectFromHandle(handle);
    if (!video || !video->processing) {
        LOGE(RAW_TAG, "setImageProfile: Invalid MLV object or processing");
        return;
    }

    processingSetImageProfile(video->processing, profileIndex);
}

/**
 * Set camera matrix mode
 * JNI: setCamMatrixMode(J, I)V
 * Matches desktop: on_comboBoxUseCameraMatrix_currentIndexChanged
 * Mode: 0=Don't use, 1=Use Camera Matrix, 2=Uncolorscience Fix (Danne)
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setCamMatrixMode(
        JNIEnv *env, jobject /* this */, jlong handle, jint mode) {
    updateProcessing(handle, "setCamMatrixMode", [mode](processingObject_t *processing) {
        desktop_processing::setCameraMatrix(processing, mode);
        // Matrix selection changes the WB-derived final/proper matrices.
        processingSetWhiteBalance(
                processing,
                processingGetWhiteBalanceKelvin(processing),
                processingGetWhiteBalanceTint(processing));
    });
}

/**
 * Set creative adjustments allowed
 * JNI: setCreativeAdjustments(J, Z)V
 * Matches desktop: on_checkBoxCreativeAdjustments_toggled
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setCreativeAdjustments(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean allow) {
    updateProcessing(handle, "setCreativeAdjustments",
                     [allow](processingObject_t *processing) {
        desktop_processing::setCreativeAdjustments(
                processing, allow == JNI_TRUE);
    });
}

/**
 * Set EXR mode (Cyan Highlight Fix)
 * JNI: setExrMode(J, Z)V
 * Matches desktop: on_checkBoxExrMode_toggled
 * Note: Desktop has inverted logic (checked=disabled), we use natural logic here
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setExrMode(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enable) {
    updateProcessing(handle, "setExrMode", [enable](processingObject_t *processing) {
        desktop_processing::setExr(processing, enable == JNI_TRUE);
    });
}

/**
 * Set AgX rendering transform
 * JNI: setAgX(J, Z)V
 * Matches desktop: on_checkBoxAgX_toggled
 */
extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_RawCorrectionNative_setAgX(
        JNIEnv *env, jobject /* this */, jlong handle, jboolean enable) {
    updateProcessing(handle, "setAgX", [enable](processingObject_t *processing) {
        desktop_processing::setAgx(processing, enable == JNI_TRUE);
    });
}
