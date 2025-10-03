#include <jni.h>
#include <android/bitmap.h>
#include <cstring>
#include <unistd.h>
#include <string>
#include <algorithm>
#include <new>
#include <cstdint>
#include <cstring>

// for debugging
#include <android/log.h>
#include <cinttypes>

extern "C" {
#include "../src/mlv/mlv_object.h"
#include "../src/mlv/video_mlv.h"
#include "../src/dng/dng.h"
#include "../src/mlv/llrawproc/llrawproc.h"
#include <time.h>
}

// Logging macros
#define LOG_TAG "fm.forum.mlvapp.jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)


static mlvObject_t *getMlvObject(
        JNIEnv *env,
        jintArray fds,
        jstring fileName, jlong cacheSize,
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
Java_fm_forum_mlvapp_NativeInterface_NativeLib_openClipForPreview(
        JNIEnv *env, jobject /* this */,
        jint fd,
        jstring fileName, jlong cacheSize,
        jint cores) {

    using Clock = std::chrono::steady_clock;
    auto start = Clock::now();

    jintArray fdArray = env->NewIntArray(1);
    jint tempFd = fd;
    env->SetIntArrayRegion(fdArray, 0, 1, &tempFd);

    auto elapsedMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();

    LOGD("making jthing %lld ms", static_cast<long long>(elapsedMs));
    start = Clock::now();

    mlvObject_t *nativeClip = getMlvObject(env, fdArray, fileName, cacheSize, cores, false);

    elapsedMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();

    LOGD("get mlv object: %lld ms", static_cast<long long>(elapsedMs));

    env->DeleteLocalRef(fdArray);

    if (!nativeClip) return nullptr;

    setMlvProcessing(nativeClip, nativeClip->processing);
    disableMlvCaching(nativeClip);
    // Disable low level raw fixes for preview
    nativeClip->llrawproc->fix_raw = 0;

    // Dimensions (original clip resolution)
    const int width = getMlvWidth(nativeClip);
    const int height = getMlvHeight(nativeClip);

    // Thumbnail -> Android Bitmap (ARGB_8888). Generate a reduced-size preview directly from RAW
    // to avoid full-resolution 16-bit processing. Uses a very simple RGGB 2x2 demosaic on a
    // downsampled grid; good enough for tiny previews.

    // Downscale to a fixed target height, which is more efficient for the 96.dp tall UI list item.
    const int targetHeight = 288; // 96dp * 3x density
    int downscaleFactor = 1;
    if (height > targetHeight) {
        downscaleFactor = height / targetHeight;
    }
    if (downscaleFactor < 1) {
        downscaleFactor = 1;
    }

    const int thumbW = width / downscaleFactor;
    const int thumbH = height / downscaleFactor;

    start = Clock::now();
    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapCls || !configCls) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }

    jfieldID fidArgb8888 = env->GetStaticFieldID(configCls, "ARGB_8888",
                                                 "Landroid/graphics/Bitmap$Config;");
    if (!fidArgb8888) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }
    jobject argb8888 = env->GetStaticObjectField(configCls, fidArgb8888);

    jmethodID midCreateBitmap = env->GetStaticMethodID(
            bitmapCls,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (!midCreateBitmap) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }
    jobject bitmap = env->CallStaticObjectMethod(bitmapCls, midCreateBitmap, thumbW, thumbH,
                                                 argb8888);
    if (!bitmap) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !pixels) {
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }
    // Use library's processed 8-bit frame (simple debayer) and downscale to the smaller bitmap
    auto *rgb888 = new(std::nothrow) unsigned char[width * height * 3];
    if (!rgb888) {
        AndroidBitmap_unlockPixels(env, bitmap);
        freeProcessingObject(nativeClip->processing);
        freeMlvObject(nativeClip);
        return nullptr;
    }

    auto ss = Clock::now();
    // Force fast preview demosaic
    setMlvUseSimpleDebayer(nativeClip);
    getMlvProcessedFrame8(nativeClip, 0, rgb888, cores);

    auto eM = std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - ss).count();

    LOGD("getMlvProcessedFrame8: %lld ms\n", static_cast<long long>(eM));

    auto *dst = static_cast<unsigned char *>(pixels);
    const int dstStride = static_cast<int>(info.stride);
    for (int y = 0; y < thumbH; ++y) {
        unsigned char *dstRow = dst + y * dstStride;
        const int srcY = static_cast<int>((static_cast<long long>(y) * height) / thumbH);
        const unsigned char *srcRow = rgb888 + srcY * width * 3;
        for (int x = 0; x < thumbW; ++x) {
            const int srcX = static_cast<int>((static_cast<long long>(x) * width) / thumbW);
            const int di = x * 4;
            const int si = srcX * 3;
            dstRow[di + 0] = srcRow[si + 0]; // R
            dstRow[di + 1] = srcRow[si + 1]; // G
            dstRow[di + 2] = srcRow[si + 2]; // B
            dstRow[di + 3] = 255;            // A
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    delete[] rgb888;

    elapsedMs =
            std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();

    LOGD("creating thumbnail: %lld ms\n", static_cast<long long>(elapsedMs));

    // GUID - if not present (e.g. for mcraw), generate a stable hash from the header block
    uint64_t finalGuid = nativeClip->MLVI.fileGuid;
        if (finalGuid == 0) {
            const auto cameraName = getMlvCamera(nativeClip);
            const auto focalLen = getMlvFocalLength(nativeClip);
            uint64_t hash = 5381;

            // Helper to hash a value using djb2
            auto hash_value = [&](auto value) {
                const auto* bytes = reinterpret_cast<const unsigned char*>(&value);
                for (size_t i = 0; i < sizeof(value); ++i) {
                    hash = ((hash << 5) + hash) + bytes[i]; // hash * 33 + c
                }
            };

            // Hash all the unique properties
            hash_value(width);
            hash_value(height);
            hash_value(focalLen);
            hash_value(cameraName);
            hash_value(getMlvTmYear(nativeClip));
            hash_value(getMlvTmMonth(nativeClip));
            hash_value(getMlvTmDay(nativeClip));
            hash_value(getMlvTmHour(nativeClip));
            hash_value(getMlvTmMin(nativeClip));
            hash_value(getMlvTmSec(nativeClip));

            finalGuid = hash;
        }

    // Release native clip (preview path does not retain handle)
    freeProcessingObject(nativeClip->processing);
    freeMlvObject(nativeClip);

    // Build fm.forum.mlvapp.data.MlvPreview
    jclass cls = env->FindClass("fm/forum/mlvapp/data/ClipPreviewData");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(
            cls,
            "<init>",
            "(IILandroid/graphics/Bitmap;J)V"
    );
    if (!ctor) return nullptr;

    jobject result = env->NewObject(
            cls, ctor,
            width,
            height,
            bitmap,
            static_cast<jlong>(finalGuid)
    );

    return result;
}

JNIEXPORT jobject JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_openClip(
        JNIEnv *env, jobject /* this */,
        jintArray fds,
        jstring fileName, jlong cacheSize,
        jint cores) {

    mlvObject_t *nativeClip = getMlvObject(env, fds, fileName, cacheSize, cores, true);
    if (!nativeClip) return nullptr;

    setMlvProcessing(nativeClip, nativeClip->processing);
    disableMlvCaching(nativeClip);

    // --- Metadata (mirror MainWindow.cpp updateMetadata sources) ---
    // FPS and frames
    const float fps = getMlvFramerate(nativeClip);
    const int frames = (int) getMlvFrames(nativeClip);

    // Lens, Camera
    const char *camera = (const char *) getMlvCamera(nativeClip);
    const char *lens = (const char *) getMlvLens(nativeClip);

    // Focal, shutter, aperture, ISO
    const int focalLengthMm = (int) getMlvFocalLength(nativeClip);
    const int shutterUs = (int) getMlvShutter(nativeClip);
    const int apertureHundredths = (int) getMlvAperture(nativeClip);
    const int iso = (int) getMlvIso(nativeClip);
    const int disoVal = (int) getMlv2ndIso(nativeClip);
    const bool dualIsoValid = (llrpGetDualIsoValidity(nativeClip) == DISO_VALID);

    // Compression / bit depth
    const int losslessBpp = (int) getLosslessBpp(nativeClip);
    const char *compression = (const char *) getMlvCompression(nativeClip);

    // Date/time
    const int year = (int) getMlvTmYear(nativeClip);
    const int month = (int) getMlvTmMonth(nativeClip);
    const int day = (int) getMlvTmDay(nativeClip);
    const int hour = (int) getMlvTmHour(nativeClip);
    const int min = (int) getMlvTmMin(nativeClip);
    const int sec = (int) getMlvTmSec(nativeClip);

    // Audio
    const bool hasAudio = doesMlvHaveAudio(nativeClip);
    const int audioChannels = hasAudio ? (int) getMlvAudioChannels(nativeClip) : 0;
    const int audioSampleRate = hasAudio ? (int) getMlvSampleRate(nativeClip) : 0;

    // Build fm.forum.mlvapp.data.ClipMetadata
    jclass cls = env->FindClass("fm/forum/mlvapp/data/ClipMetaData");

    if (!cls) return nullptr;

    jmethodID ctor = env->GetMethodID(
            cls,
            "<init>",
            "(JLjava/lang/String;Ljava/lang/String;IFIIIIIZILjava/lang/String;IIIIIIZII)V"
    );

    if (!ctor) return nullptr;

    jstring jCamera = env->NewStringUTF(camera ? camera : "");
    jstring jLens = env->NewStringUTF(lens ? lens : "");
    jstring jCompression = env->NewStringUTF(compression ? compression : "");

    jobject metadata = env->NewObject(
            cls, ctor,
            reinterpret_cast<jlong>(nativeClip),
            jCamera,
            jLens,
            frames,
            fps,
            focalLengthMm,
            shutterUs,
            apertureHundredths,
            iso,
            disoVal,
            (jboolean) dualIsoValid,
            losslessBpp,
            jCompression,
            year, month, day,
            hour, min, sec,
            (jboolean) hasAudio,
            audioChannels,
            audioSampleRate
    );

    // Local refs cleanup
    env->DeleteLocalRef(jCamera);
    env->DeleteLocalRef(jLens);
    env->DeleteLocalRef(jCompression);

    return metadata;
}

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_closeClip(
        JNIEnv *env, jobject /* this */,
        jlong handle
) {
    if (handle == 0) {
        return;
    }
    auto *nativeClip = reinterpret_cast<mlvObject_t *>(handle);
    freeProcessingObject(nativeClip->processing);
    freeMlvObject(nativeClip);
}

// Helper to convert a 32-bit float to a 16-bit half-float (IEEE 754)
uint16_t float_to_half(float f) {
    uint32_t x;
    memcpy(&x, &f, sizeof(f));

    uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = ((x >> 23) & 0xff) - 127;
    uint32_t mant = x & 0x7fffff;

    if (exp > 15) { return sign | 0x7c00; } // Inf/overflow
    if (exp < -14) { return sign; } // Zero/underflow

    exp += 15;
    mant >>= 13;

    return sign | (exp << 10) | mant;
}

// Fills a direct ByteBuffer with RGB16F (half-float) pixels.
// Java side must allocate: capacity = width * height * 3 * sizeof(uint16_t)
JNIEXPORT jboolean JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_fillFrame16(
        JNIEnv *env, jclass /*clazz*/,
        jlong handle,
        jint frameIndex,
        jint cores,
        jobject dstByteBuffer,
        jint width,
        jint height) {

    if (handle == 0 || dstByteBuffer == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    auto *nativeClip = reinterpret_cast<mlvObject_t *>(handle);

    auto *dstBuf = reinterpret_cast<uint16_t *>(env->GetDirectBufferAddress(dstByteBuffer));
    const jlong cap = env->GetDirectBufferCapacity(dstByteBuffer);
    const size_t needed =
            static_cast<size_t>(width) * static_cast<size_t>(height) * 3u * sizeof(uint16_t);

    if (!dstBuf || cap < static_cast<jlong>(needed)) {
        return JNI_FALSE; // buffer too small / not direct
    }

    // Allocate a temporary buffer for the 16-bit integer RGB data
    const size_t rgbSize = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    auto *rgbBuf = new(std::nothrow) uint16_t[rgbSize];
    if (!rgbBuf) {
        return JNI_FALSE; // out of memory
    }

    // Get the processed 16-bit RGB frame
    getMlvProcessedFrame16(nativeClip, frameIndex, rgbBuf, cores);

    // Convert and pack into destination RGB half-float buffer
    for (size_t i = 0; i < static_cast<size_t>(width) * static_cast<size_t>(height); ++i) {
        dstBuf[i * 3 + 0] = float_to_half((float)rgbBuf[i * 3 + 0] / 65535.0f); // R
        dstBuf[i * 3 + 1] = float_to_half((float)rgbBuf[i * 3 + 1] / 65535.0f); // G
        dstBuf[i * 3 + 2] = float_to_half((float)rgbBuf[i * 3 + 2] / 65535.0f); // B
    }

    delete[] rgbBuf;

    return JNI_TRUE;
}
}

#endif
