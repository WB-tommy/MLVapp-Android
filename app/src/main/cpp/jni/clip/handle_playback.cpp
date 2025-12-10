//
// Created by Sungmin Choi on 2025. 10. 11..
//
#include "clip_jni.h"
#include "mlv_jni_wrapper.h"
#include <algorithm>
#include <thread>
#include <vector>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace {

constexpr float kNormalizationScale = 1.0f / 65535.0f;

inline void convertSamples(
        const uint16_t *src,
        float *dst,
        size_t start,
        size_t end) {
    if (start >= end) {
        return;
    }
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    size_t i = start;
    const float32x4_t scale = vdupq_n_f32(kNormalizationScale);
    for (; i + 8 <= end; i += 8) {
        uint16x8_t vals16 = vld1q_u16(src + i);
        uint32x4_t low32 = vmovl_u16(vget_low_u16(vals16));
        uint32x4_t high32 = vmovl_u16(vget_high_u16(vals16));
        float32x4_t lowf = vmulq_f32(vcvtq_f32_u32(low32), scale);
        float32x4_t highf = vmulq_f32(vcvtq_f32_u32(high32), scale);
        vst1q_f32(dst + i, lowf);
        vst1q_f32(dst + i + 4, highf);
    }
#else
    size_t i = start;
#endif
    for (; i < end; ++i) {
        dst[i] = static_cast<float>(src[i]) * kNormalizationScale;
    }
}

} // namespace

// Fills a direct ByteBuffer with RGB32F (float) pixels.
// Java side must allocate: capacity = width * height * 3 * sizeof(float)
extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillFrame16(
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

    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    mlvObject_t* nativeClip = wrapper->mlv_object;

    auto *dstBuf = reinterpret_cast<float *>(env->GetDirectBufferAddress(dstByteBuffer));
    const jlong cap = env->GetDirectBufferCapacity(dstByteBuffer);
    const size_t needed =
            static_cast<size_t>(width) * static_cast<size_t>(height) * 3u * sizeof(float);

    if (!dstBuf || cap < static_cast<jlong>(needed)) {
        return JNI_FALSE; // buffer too small / not direct
    }

    // Use the pre-allocated buffer from the wrapper
    uint16_t *rgbBuf = wrapper->processing_buffer_16bit;
    if (!rgbBuf) {
        return JNI_FALSE; // buffer not allocated
    }

    // Get the processed 16-bit RGB frame
    getMlvProcessedFrame16(nativeClip, frameIndex, rgbBuf, cores);

    const size_t totalPixels = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t totalSamples = totalPixels * 3u;
    if (totalSamples == 0) {
        return JNI_TRUE;
    }

    int workerCount = cores > 0 ? cores : 1;
    const size_t minSamplesPerThread = 8192;
    int maxUsefulWorkers = static_cast<int>((totalSamples + minSamplesPerThread - 1) / minSamplesPerThread);
    if (maxUsefulWorkers < 1) {
        maxUsefulWorkers = 1;
    }
    workerCount = std::clamp(workerCount, 1, maxUsefulWorkers);

    const uint16_t *srcSamples = rgbBuf;
    float *dstSamples = dstBuf;

    size_t chunk = (totalSamples + static_cast<size_t>(workerCount) - 1) / static_cast<size_t>(workerCount);
    if (chunk == 0) {
        chunk = totalSamples;
    }

    std::vector<std::thread> workers;
    if (workerCount > 1) {
        workers.reserve(static_cast<size_t>(workerCount - 1));
    }

    size_t start = 0;
    for (int w = 0; w < workerCount - 1; ++w) {
        size_t end = std::min(totalSamples, start + chunk);
        const size_t localStart = start;
        const size_t localEnd = end;
        workers.emplace_back([srcSamples, dstSamples, localStart, localEnd]() {
            convertSamples(srcSamples, dstSamples, localStart, localEnd);
        });
        start = end;
    }
    convertSamples(srcSamples, dstSamples, start, totalSamples);

    for (auto &worker : workers) {
        worker.join();
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getAudioBufferSize(
        JNIEnv *env, jobject /* this */,
        jlong handle) {
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    auto *nativeClip = wrapper->mlv_object;
    if (!nativeClip || !doesMlvHaveAudio(nativeClip)) {
        return 0;
    }

    const uint8_t *audioData = getMlvAudioData(nativeClip);
    if (!audioData) {
        return 0;
    }

    return static_cast<jlong>(getMlvAudioSize(nativeClip));
}

extern "C" JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getAudioBytesPerSample(
        JNIEnv *env, jobject /* this */,
        jlong handle) {
    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    auto *nativeClip = wrapper->mlv_object;
    if (!nativeClip || !doesMlvHaveAudio(nativeClip)) {
        return 0;
    }

    const int bitsPerSample = getMlvAudioBitsPerSample(nativeClip);
    const int channels = getMlvAudioChannels(nativeClip);
    if (bitsPerSample <= 0 || channels <= 0) {
        return 0;
    }

    const int bytesPerSample = (bitsPerSample / 8) * channels;
    return bytesPerSample > 0 ? bytesPerSample : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_readAudioBuffer(
        JNIEnv *env, jobject /* this */,
        jlong handle,
        jlong offsetBytes,
        jint byteCount,
        jobject dstByteBuffer) {
    if (handle == 0 || dstByteBuffer == nullptr || byteCount <= 0) {
        return 0;
    }

    auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
    auto *nativeClip = wrapper->mlv_object;
    if (!doesMlvHaveAudio(nativeClip)) {
        return 0;
    }

    auto *audioData = getMlvAudioData(nativeClip);
    const uint64_t audioSize = getMlvAudioSize(nativeClip);
    if (!audioData || audioSize == 0) {
        return 0;
    }

    const jlong capacity = env->GetDirectBufferCapacity(dstByteBuffer);
    auto *dst = reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(dstByteBuffer));
    if (!dst || capacity <= 0) {
        return 0;
    }

    uint64_t clampedOffset = offsetBytes < 0 ? 0 : static_cast<uint64_t>(offsetBytes);
    if (clampedOffset >= audioSize) {
        return 0;
    }

    uint64_t remaining = audioSize - clampedOffset;
    uint64_t requested = static_cast<uint64_t>(byteCount);
    uint64_t writable = static_cast<uint64_t>(capacity);

    uint64_t toCopy = requested;
    if (toCopy > remaining) {
        toCopy = remaining;
    }
    if (toCopy > writable) {
        toCopy = writable;
    }

    if (toCopy == 0) {
        return 0;
    }

    memcpy(dst, audioData + clampedOffset, toCopy);
    return static_cast<jint>(toCopy);
}
