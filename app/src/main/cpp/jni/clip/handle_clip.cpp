#include "clip_jni.h"
#include "mlv_jni_wrapper.h"
#include "jni_cache.h"
#include "../export/StretchFactors.h"
#include "../src/mlv/llrawproc/pixelproc.h"
#include <android/bitmap.h>
#include <android/log.h>
#include <chrono>
#include <vector>
#include <algorithm>
#include <limits>
#include <cstring>

extern "C" {
void get_mlv_processed_thumbnail_8(mlvObject_t *video,
                                   int frame_index, int downscale_factor,
                                   int cpu_cores,
                                   unsigned char *out_buffer);
}

namespace {
constexpr const char *kJniTag = "MLVApp-JNI";

inline void resolveStretchFactors(mlvObject_t *clip, float &stretchX, float &stretchY) {
    stretchX = STRETCH_H_100;
    stretchY = STRETCH_V_100;

    if (!clip) {
        return;
    }

    float ratioV = getMlvAspectRatio(clip);
    if (ratioV == 0.0f) {
        ratioV = 1.0f;
    }

    if (ratioV > 0.9f && ratioV < 1.1f) {
        stretchY = STRETCH_V_100;
    } else if (ratioV > 1.6f && ratioV < 1.7f) {
        stretchY = STRETCH_V_167;
    } else if (ratioV > 2.9f && ratioV < 3.1f) {
        stretchY = STRETCH_V_300;
    } else {
        stretchY = STRETCH_V_033;
    }
}
}

mlvObject_t *getMlvObject(
        JNIEnv *env,
        jintArray fds,
        jstring fileName,
        jlong cacheSize,
        jint cores,
        bool isFull) {
    int mlvErr = MLV_ERR_NONE;
    char mlvErrMsg[256] = {0};

    mlvObject_t *nativeClip = nullptr;
    const char *filePath = env->GetStringUTFChars(fileName, nullptr);

    int openMode = isFull ? MLV_OPEN_FULL : MLV_OPEN_PREVIEW;

    if (filePath != nullptr) { // Always check for null after GetStringUTFChars
        size_t len = strlen(filePath);

        bool isMlv = (len >= 4) &&
                     ((strncmp(filePath + len - 4, ".mlv", 4) == 0) ||
                      (strncmp(filePath + len - 4, ".MLV", 4) == 0));

        jint *fdArray = env->GetIntArrayElements(fds, nullptr);
        jsize numFds = env->GetArrayLength(fds);

        if (isMlv) {
            nativeClip = initMlvObjectWithClip(
                    fdArray,
                    (int) numFds,
                    (char *) filePath,
                    openMode,
                    &mlvErr,
                    mlvErrMsg);

            env->ReleaseIntArrayElements(fds, fdArray, JNI_ABORT);
        } else {
            nativeClip = initMlvObjectWithMcrawClip(
                    fdArray[0],
                    (char *) filePath,
                    openMode,
                    &mlvErr,
                    mlvErrMsg);
        }
    }

    if (!nativeClip || mlvErr != MLV_ERR_NONE) { /* handle error */
        if (nativeClip) freeMlvObject(nativeClip);
        if (filePath) env->ReleaseStringUTFChars(fileName, filePath);
        return nullptr;
    }

    env->ReleaseStringUTFChars(fileName, filePath);

    nativeClip->processing = initProcessingObject();
    setMlvRawCacheLimitMegaBytes(nativeClip, cacheSize);
    setMlvCpuCores(nativeClip, cores);

    return nativeClip;
}


#ifdef __cplusplus
extern "C" {
JNIEXPORT jobject JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_openClipForPreview(
        JNIEnv *env, jobject /* this */,
        jint fd,
        jstring fileName, jlong cacheSize,
        jint cores) {

    if (!EnsureJniCacheInitialized(env)) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "JNI cache not initialized");
        return nullptr;
    }

    const JniCache &cache = GetJniCache();

    uint64_t finalGuid = 0;

    jintArray fdArray = env->NewIntArray(1);
    if (!fdArray) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to allocate fdArray");
        return nullptr;
    }
    env->SetIntArrayRegion(fdArray, 0, 1, &fd);

    mlvObject_t *nativeClip = nullptr;
    jobject bitmap = nullptr;
    jobject result = nullptr;
    void *pixels = nullptr;
    bool pixelsLocked = false;
    int width = 0;
    int height = 0;
    const int targetHeight = 192;
    int downscaleFactor = 1;
    int thumbW = 0;
    int thumbH = 0;
    AndroidBitmapInfo info{};
    float stretchFactorX = 0.0f;
    float stretchFactorY = 0.0f;
    int cameraModelId = 0;
    jstring focusPixelMapNameJ = nullptr;

    nativeClip = getMlvObject(env, fdArray, fileName, cacheSize, cores, false);
    if (!nativeClip) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to open clip for preview");
        goto cleanup;
    }

    setMlvProcessing(nativeClip, nativeClip->processing);
    nativeClip->llrawproc->fix_raw = 0;

    width = getMlvWidth(nativeClip);
    height = getMlvHeight(nativeClip);

    resolveStretchFactors(nativeClip, stretchFactorX, stretchFactorY);

    if (height > targetHeight) {
        downscaleFactor = height / targetHeight;
    }
    thumbW = width / downscaleFactor;
    thumbH = height / downscaleFactor;

    bitmap = env->CallStaticObjectMethod(
            cache.bitmapClass,
            cache.bitmapCreateMethod,
            thumbW,
            thumbH,
            cache.bitmapConfigArgb8888);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (!bitmap) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to create preview bitmap");
        goto cleanup;
    }

    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Unexpected bitmap format for preview");
        goto cleanup;
    }

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Unable to lock bitmap pixels");
        goto cleanup;
    }
    pixelsLocked = true;

    get_mlv_processed_thumbnail_8(
            nativeClip,
            0,
            downscaleFactor,
            cores,
            static_cast<unsigned char *>(pixels));

    AndroidBitmap_unlockPixels(env, bitmap);
    pixelsLocked = false;

    finalGuid = nativeClip->MLVI.fileGuid;
    if (finalGuid == 0) {
        const auto cameraName = getMlvCamera(nativeClip);
        const auto focalLen = getMlvFocalLength(nativeClip);
        uint64_t hash = 5381;

        auto hash_value = [&](auto value) {
            const auto *bytes = reinterpret_cast<const unsigned char *>(&value);
            for (size_t i = 0; i < sizeof(value); ++i) {
                hash = ((hash << 5) + hash) + bytes[i];
            }
        };

        auto hash_string = [&](const char *value) {
            if (!value) return;
            while (*value) {
                hash = ((hash << 5) + hash) + static_cast<unsigned char>(*value++);
            }
        };

        hash_value(width);
        hash_value(height);
        hash_value(focalLen);
        hash_string(reinterpret_cast<const char *>(cameraName));
        hash_value(getMlvTmYear(nativeClip));
        hash_value(getMlvTmMonth(nativeClip));
        hash_value(getMlvTmDay(nativeClip));
        hash_value(getMlvTmHour(nativeClip));
        hash_value(getMlvTmMin(nativeClip));
        hash_value(getMlvTmSec(nativeClip));

        finalGuid = hash;
    }

    cameraModelId = nativeClip->IDNT.cameraModel;
    char focusPixelMapName[128];
    focusPixelMapName[0] = '\0';
    if (cameraModelId != 0) {
        const int focusMode = llrpDetectFocusDotFixMode(nativeClip);
        if (focusMode != 0) {
            llrpSetFixRawMode(nativeClip, 1);
            llrpSetFocusPixelMode(nativeClip, focusMode);
        }

        int mapWidth = nativeClip->RAWI.raw_info.width;
        int mapHeight = nativeClip->RAWI.raw_info.height;
        if (mapWidth == 0 || mapHeight == 0) {
            mapWidth = getMlvWidth(nativeClip);
            mapHeight = getMlvHeight(nativeClip);
        }

        std::snprintf(
                focusPixelMapName,
                sizeof(focusPixelMapName),
                "%08X_%dx%d.fpm",
                static_cast<unsigned int>(cameraModelId),
                mapWidth,
                mapHeight);
        focusPixelMapName[sizeof(focusPixelMapName) - 1] = '\0';
    }
    focusPixelMapNameJ = env->NewStringUTF(focusPixelMapName);
    if (!focusPixelMapNameJ) {
        focusPixelMapNameJ = env->NewStringUTF("");
    }

    freeProcessingObject(nativeClip->processing);
    freeMlvObject(nativeClip);
    nativeClip = nullptr;

    result = env->NewObject(
            cache.clipPreviewDataClass,
            cache.clipPreviewCtor,
            width,
            height,
            bitmap,
            static_cast<jlong>(finalGuid),
            stretchFactorX,
            stretchFactorY,
            cameraModelId,
            focusPixelMapNameJ);
    if (!result) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to instantiate ClipPreviewData");
    }

cleanup:
    if (pixelsLocked) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }
    if (bitmap) {
        env->DeleteLocalRef(bitmap);
        bitmap = nullptr;
    }
    if (focusPixelMapNameJ) {
        env->DeleteLocalRef(focusPixelMapNameJ);
    }
    if (nativeClip) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
    }
    env->DeleteLocalRef(fdArray);
    return result;
}

JNIEXPORT jobject JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_openClip(
        JNIEnv *env, jobject /* this */,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores) {

    if (!EnsureJniCacheInitialized(env)) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "JNI cache not initialized");
        return nullptr;
    }

    const JniCache &cache = GetJniCache();

    mlvObject_t *nativeClip = nullptr;
    JniClipWrapper *wrapper = nullptr;
    jobject metadata = nullptr;

    nativeClip = getMlvObject(env, fds, fileName, cacheSize, cores, true);
    if (!nativeClip) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to open clip");
        goto cleanup;
    }

    wrapper = new(std::nothrow) JniClipWrapper();
    if (!wrapper) {
        __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to allocate clip wrapper");
        goto cleanup;
    }
    wrapper->mlv_object = nativeClip;
    wrapper->processing_buffer_16bit = nullptr;

    // This block isolates the variable initializations to prevent the 'goto' error.
    {
        const int width = getMlvWidth(nativeClip);
        const int height = getMlvHeight(nativeClip);
        const size_t rgbSize = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
        wrapper->processing_buffer_16bit = new(std::nothrow) uint16_t[rgbSize];
        if (!wrapper->processing_buffer_16bit) {
            __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to allocate RGB buffer");
            goto cleanup;
        }

        setMlvProcessing(nativeClip, nativeClip->processing);
        disableMlvCaching(nativeClip);

        const float fps = getMlvFramerate(nativeClip);
        const int frames = static_cast<int>(getMlvFrames(nativeClip));
        const char *camera = reinterpret_cast<const char *>(getMlvCamera(nativeClip));
        const char *lens = reinterpret_cast<const char *>(getMlvLens(nativeClip));
        const int focalLengthMm = static_cast<int>(getMlvFocalLength(nativeClip));
        const int shutterUs = static_cast<int>(getMlvShutter(nativeClip));
        const int apertureHundredths = static_cast<int>(getMlvAperture(nativeClip));
        const int iso = static_cast<int>(getMlvIso(nativeClip));
        const int disoVal = static_cast<int>(getMlv2ndIso(nativeClip));
        const bool dualIsoValid = (llrpGetDualIsoValidity(nativeClip) == DISO_VALID);
        const int losslessBpp = static_cast<int>(getLosslessBpp(nativeClip));
        const char *compression = reinterpret_cast<const char *>(getMlvCompression(nativeClip));
        const int year = static_cast<int>(getMlvTmYear(nativeClip));
        const int month = static_cast<int>(getMlvTmMonth(nativeClip));
        const int day = static_cast<int>(getMlvTmDay(nativeClip));
        const int hour = static_cast<int>(getMlvTmHour(nativeClip));
        const int min = static_cast<int>(getMlvTmMin(nativeClip));
        const int sec = static_cast<int>(getMlvTmSec(nativeClip));
        const bool hasAudio = doesMlvHaveAudio(nativeClip);
        const int audioChannels = hasAudio ? static_cast<int>(getMlvAudioChannels(nativeClip)) : 0;
        const int audioSampleRate = hasAudio ? static_cast<int>(getMlvSampleRate(nativeClip)) : 0;

        jstring jCamera = env->NewStringUTF(camera ? camera : "");
        jstring jLens = env->NewStringUTF(lens ? lens : "");
        jstring jCompression = env->NewStringUTF(compression ? compression : "");

        if ((camera && !jCamera) || (lens && !jLens) || (compression && !jCompression)) {
            __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to allocate metadata strings");
            if (jCamera) env->DeleteLocalRef(jCamera);
            if (jLens) env->DeleteLocalRef(jLens);
            if (jCompression) env->DeleteLocalRef(jCompression);
            goto cleanup;
        }

        metadata = env->NewObject(
                cache.clipMetaDataClass,
                cache.clipMetaDataCtor,
                reinterpret_cast<jlong>(wrapper),
                jCamera,
                jLens,
                frames,
                fps,
                focalLengthMm,
                shutterUs,
                apertureHundredths,
                iso,
                disoVal,
                dualIsoValid,
                losslessBpp,
                jCompression,
                year,
                month,
                day,
                hour,
                min,
                sec,
                hasAudio,
                audioChannels,
                audioSampleRate,
                isMcrawLoaded(nativeClip)
        );

        env->DeleteLocalRef(jCamera);
        env->DeleteLocalRef(jLens);
        env->DeleteLocalRef(jCompression);

        if (!metadata) {
            __android_log_print(ANDROID_LOG_ERROR, kJniTag, "Failed to instantiate ClipMetaData");
            goto cleanup;
        }
    }

cleanup:
    if (!metadata) { // If we failed at any point
        if (wrapper) {
            delete[] wrapper->processing_buffer_16bit;
            delete wrapper;
        }
        if (nativeClip) {
            freeProcessingObject(nativeClip->processing);
            freeMlvObject(nativeClip);
        }
    }

    return metadata;
}

JNIEXPORT jlongArray JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getVideoFrameTimestamps(
        JNIEnv *env, jobject /* this */,
        jlong handle) {
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    auto *nativeClip = wrapper->mlv_object;
    if (!nativeClip || nativeClip->frames == 0 || nativeClip->video_index == nullptr) {
        return nullptr;
    }

    const uint32_t frameCount = nativeClip->frames;
    if (frameCount == 0 || frameCount > static_cast<uint32_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }

    jlongArray result = env->NewLongArray(static_cast<jsize>(frameCount));
    if (!result) {
        return nullptr;
    }

    const bool isMcraw = isMcrawLoaded(nativeClip);
    const double fpsSource = (nativeClip->frame_rate > 0.0)
        ? nativeClip->frame_rate
        : nativeClip->real_frame_rate;
    const jlong frameDurationUs = static_cast<jlong>(
            fpsSource > 0.0 ? std::max(1.0, 1'000'000.0 / fpsSource) : 41'667.0);

    auto buildSyntheticTimeline = [&](jlong startTimestampUs) {
        std::vector<jlong> synthetic(frameCount, 0);
        const jlong durationUs = frameDurationUs > 0 ? frameDurationUs : 41'667L;
        for (uint32_t i = 0; i < frameCount; ++i) {
            synthetic[i] = startTimestampUs + static_cast<jlong>(i) * durationUs;
        }
        return synthetic;
    };

    std::vector<jlong> timestamps(frameCount, 0);
    bool needsFallback = false;

    if (nativeClip->video_index == nullptr) {
        needsFallback = true;
    } else if (isMcraw) {
        // mcraw audio timestamp is stored in 0 not like MLV file.
        // Mixing the raw values with audio timestamps
        // would drift the playback clock, so we normalize the timeline to an evenly spaced
        // synthetic sequence and let audio sync derive from that canonical cadence.
        needsFallback = true;
    } else {
        bool hasNonZero = false;
        for (uint32_t i = 0; i < frameCount; ++i) {
            const uint32_t frameNumber = nativeClip->video_index[i].frame_number;
            if (frameNumber >= frameCount) {
                continue;
            }
            jlong timestamp = static_cast<jlong>(nativeClip->video_index[i].frame_time);
            timestamps[frameNumber] = timestamp;
            if (timestamp != 0) {
                hasNonZero = true;
            }
        }

        if (!hasNonZero) {
            needsFallback = true;
        } else {
            const jlong minExpectedDelta = frameDurationUs > 0
                ? std::max<jlong>(1L, frameDurationUs / 5)
                : 1L;
            const jlong maxExpectedDelta = frameDurationUs > 0
                ? std::max<jlong>(minExpectedDelta, frameDurationUs * 5)
                : std::numeric_limits<jlong>::max();

            jlong prev = timestamps[0];
            if (prev < 0) {
                needsFallback = true;
            } else {
                for (uint32_t i = 1; i < frameCount; ++i) {
                    const jlong ts = timestamps[i];
                    if (ts <= prev) {
                        needsFallback = true;
                        break;
                    }
                    const jlong delta = ts - prev;
                    if (delta < minExpectedDelta || delta > maxExpectedDelta) {
                        needsFallback = true;
                        break;
                    }
                    prev = ts;
                }
            }
        }
    }

    if (needsFallback) {
        timestamps = buildSyntheticTimeline(0);
    }

    env->SetLongArrayRegion(result, 0, static_cast<jsize>(frameCount), timestamps.data());
    return result;
}

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_closeClip(
        JNIEnv *env, jobject /* this */,
        jlong handle
) {
    if (handle == 0) {
        return;
    }
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    if (wrapper->mlv_object) {
        freeMlvObject(wrapper->mlv_object);
    }
    delete[] wrapper->processing_buffer_16bit;
    delete wrapper;
}

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setDebayerMode(
        JNIEnv *env, jobject /* this */,
        jlong handle,
        jint mode
) {
    if (handle == 0) {
        return;
    }

    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    auto *nativeClip = wrapper->mlv_object;
    bool enableCache = false;

    switch (mode) {
        case 0:
        setMlvUseNoneDebayer(nativeClip);
            break;
        case 1:
        setMlvUseSimpleDebayer(nativeClip);
            break;
        case 2:
        setMlvDontAlwaysUseAmaze(nativeClip);
            break;
        case 3:
        setMlvUseLmmseDebayer(nativeClip);
            break;
        case 4:
        setMlvUseIgvDebayer(nativeClip);
            break;
        case 5:
        setMlvUseAhdDebayer(nativeClip);
            break;
        case 6:
        setMlvUseRcdDebayer(nativeClip);
            break;
        case 7:
        setMlvUseDcbDebayer(nativeClip);
            break;
        case 8:
        setMlvAlwaysUseAmaze(nativeClip);
            break;
        case 9:
        setMlvAlwaysUseAmaze(nativeClip);
            enableCache = true;
            break;
        default:
        setMlvAlwaysUseAmaze(nativeClip);
            break;
    }

    if (enableCache) {
        enableMlvCaching(nativeClip);
    } else {
        disableMlvCaching(nativeClip);
    }
}


}
#endif
