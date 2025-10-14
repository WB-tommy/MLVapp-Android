//
// Created by Sungmin Choi on 2025. 10. 11..
//
#include "mlv_jni.h"

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
extern "C" JNIEXPORT jboolean JNICALL
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
        dstBuf[i * 3 + 0] = float_to_half((float) rgbBuf[i * 3 + 0] / 65535.0f); // R
        dstBuf[i * 3 + 1] = float_to_half((float) rgbBuf[i * 3 + 1] / 65535.0f); // G
        dstBuf[i * 3 + 2] = float_to_half((float) rgbBuf[i * 3 + 2] / 65535.0f); // B
    }

    delete[] rgbBuf;

    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getAudioBufferSize(
        JNIEnv *env, jobject /* this */,
        jlong handle) {
    if (handle == 0) {
        return 0;
    }

    auto *nativeClip = reinterpret_cast<mlvObject_t *>(handle);
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
Java_fm_forum_mlvapp_NativeInterface_NativeLib_getAudioBytesPerSample(
        JNIEnv *env, jobject /* this */,
        jlong handle) {
    if (handle == 0) {
        return 0;
    }

    auto *nativeClip = reinterpret_cast<mlvObject_t *>(handle);
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
Java_fm_forum_mlvapp_NativeInterface_NativeLib_readAudioBuffer(
        JNIEnv *env, jobject /* this */,
        jlong handle,
        jlong offsetBytes,
        jint byteCount,
        jobject dstByteBuffer) {
    if (handle == 0 || dstByteBuffer == nullptr || byteCount <= 0) {
        return 0;
    }

    auto *nativeClip = reinterpret_cast<mlvObject_t *>(handle);
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
