//
// Created by Sungmin Choi on 2025. 10. 11..
//
#include "clip_jni.h"
#include "mlv_jni_wrapper.h"
#include <cstring>

// Fills a direct ByteBuffer with raw uint16_t RGB pixels (Split-Byte / GL_RG8 path).
// The 16-bit value for each channel is stored as 2 bytes in little-endian order,
// which maps directly to GL_RG8 (low byte = .r, high byte = .g) in the shader.
// Java side must allocate: capacity = width * height * 3 * sizeof(uint16_t) = 6 bytes/px
extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillFrame16(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jint frameIndex, jint cores,
    jobject dstByteBuffer, jint width, jint height) {

  if (handle == 0 || dstByteBuffer == nullptr || width <= 0 || height <= 0) {
    return JNI_FALSE;
  }

  auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);

  // Try to acquire the mutex without blocking - if we can't, another render is
  // in progress. This prevents deadlocks and crashes during rapid view
  // transitions.
  std::unique_lock<std::mutex> lock(wrapper->render_mutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    return JNI_FALSE;
  }

  mlvObject_t *nativeClip = wrapper->mlv_object;
  if (!nativeClip) {
    return JNI_FALSE;
  }

  auto *dstBuf =
      reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(dstByteBuffer));
  const jlong cap = env->GetDirectBufferCapacity(dstByteBuffer);
  // 6 bytes per pixel: 3 channels × 2 bytes (uint16_t)
  const size_t needed = static_cast<size_t>(width) *
                        static_cast<size_t>(height) * 3u * sizeof(uint16_t);

  if (!dstBuf || cap < static_cast<jlong>(needed)) {
    return JNI_FALSE;
  }

  uint16_t *rgbBuf = wrapper->processing_buffer_16bit;
  if (!rgbBuf) {
    return JNI_FALSE;
  }

  // Decode the frame into the wrapper's 16-bit RGB buffer
  getMlvProcessedFrame16(nativeClip, frameIndex, rgbBuf, cores);

  // Zero-cost upload: raw uint16_t bytes are already in the correct layout
  // for GL_RG8 (little-endian: low byte first, high byte second per texel).
  memcpy(dstBuf, rgbBuf, needed);

  return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_getAudioBufferSize(
    JNIEnv *env, jobject /* this */, jlong handle) {
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
    JNIEnv *env, jobject /* this */, jlong handle) {
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
    JNIEnv *env, jobject /* this */, jlong handle, jlong offsetBytes,
    jint byteCount, jobject dstByteBuffer) {
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
  auto *dst =
      reinterpret_cast<uint8_t *>(env->GetDirectBufferAddress(dstByteBuffer));
  if (!dst || capacity <= 0) {
    return 0;
  }

  uint64_t clampedOffset =
      offsetBytes < 0 ? 0 : static_cast<uint64_t>(offsetBytes);
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
