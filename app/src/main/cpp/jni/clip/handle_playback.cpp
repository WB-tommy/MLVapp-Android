//
// Created by Sungmin Choi on 2025. 10. 11..
//
#include "clip_jni.h"
#include "mlv_jni_wrapper.h"
#include "mlv/mcraw/mcraw.h"
#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <limits>

namespace {

constexpr size_t kGpuPreviewParamCount = 16;
constexpr size_t kGpuPreviewParamsBytes =
    kGpuPreviewParamCount * sizeof(float);
constexpr size_t kGpuPreviewToneLutEntries = 65536;
constexpr size_t kGpuPreviewToneLutBytes =
    kGpuPreviewToneLutEntries * sizeof(uint16_t);
constexpr uint64_t kMcrawDecoderBenchmarkWindow = 120;
constexpr jint kRawGpuDecodeTransient = -1;
constexpr jint kRawGpuDecodeHardFailure = -2;
constexpr jint kRawGpuBackendClassicMlv = 2;

enum RawCfa : int {
  kCfaRggb = 0,
  kCfaGbrg = 1,
  kCfaBggr = 2,
  kCfaGrbg = 3,
};

int rawCfaToGpuEnum(uint32_t cfaPattern) {
  switch (cfaPattern) {
  case 0u: // Legacy MLV files may omit the canonical RGGB value.
  case 0x02010100u:
    return kCfaRggb;
  case 0x01000201u:
    return kCfaGbrg;
  case 0x00010102u:
    return kCfaBggr;
  case 0x01020001u:
    return kCfaGrbg;
  default:
    return -1;
  }
}

bool isUsableRawClip(const mlvObject_t *clip) {
  if (clip == nullptr || !isMlvActive(clip)) {
    return false;
  }
  const bool isMcraw =
      (clip->MLVI.videoClass & MLV_VIDEO_CLASS_FLAG_MCRAW) != 0;
  const bool isClassicRaw =
      clip->MLVI.videoClass == MLV_VIDEO_CLASS_RAW ||
      clip->MLVI.videoClass ==
          (MLV_VIDEO_CLASS_RAW | MLV_VIDEO_CLASS_FLAG_LJ92);
  return (isMcraw || isClassicRaw) && clip->filenum > 0 &&
         clip->frames > 0 && clip->video_index != nullptr &&
         clip->file != nullptr && clip->main_file_mutex != nullptr;
}

const char *mcrawDecoderBackendName(int backend) {
  return backend == MCRAW_DECODER_ROW_PARALLEL ? "motioncam-row-parallel"
                                                : "current";
}

void resetMcrawDecoderBenchmark(JniClipWrapper *wrapper,
                                int requestedBackend,
                                int decoderThreads,
                                int compressionType) {
  wrapper->mcraw_benchmark_requested_backend = requestedBackend;
  wrapper->mcraw_benchmark_decoder_threads = decoderThreads;
  wrapper->mcraw_benchmark_frames = 0;
  wrapper->mcraw_benchmark_parallel_frames = 0;
  wrapper->mcraw_benchmark_read_ns = 0;
  wrapper->mcraw_benchmark_decode_ns = 0;
  wrapper->mcraw_benchmark_total_ns = 0;
  wrapper->mcraw_benchmark_fallbacks = 0;

  __android_log_print(
      ANDROID_LOG_INFO, "MCRAWDecoder",
      "Timing window started: requested=%s, threads=%d, compression=%d",
      mcrawDecoderBackendName(requestedBackend), decoderThreads,
      compressionType);
}

void recordMcrawDecoderBenchmark(JniClipWrapper *wrapper,
                                 const mcraw_decode_metrics_t &metrics,
                                 uint64_t totalNs,
                                 int compressionType) {
  if (wrapper->mcraw_benchmark_requested_backend !=
          metrics.requested_backend ||
      wrapper->mcraw_benchmark_decoder_threads != metrics.decoder_threads) {
    resetMcrawDecoderBenchmark(wrapper, metrics.requested_backend,
                               metrics.decoder_threads,
                               compressionType);
  }

  wrapper->mcraw_benchmark_frames++;
  wrapper->mcraw_benchmark_parallel_frames +=
      metrics.actual_backend == MCRAW_DECODER_ROW_PARALLEL ? 1u : 0u;
  wrapper->mcraw_benchmark_read_ns += metrics.read_ns;
  wrapper->mcraw_benchmark_decode_ns += metrics.decode_ns;
  wrapper->mcraw_benchmark_total_ns += totalNs;
  wrapper->mcraw_benchmark_fallbacks +=
      static_cast<uint64_t>(metrics.fallback_count);

  if (wrapper->mcraw_benchmark_frames < kMcrawDecoderBenchmarkWindow) {
    return;
  }

  const uint64_t frames = wrapper->mcraw_benchmark_frames;
  __android_log_print(
      ANDROID_LOG_INFO, "MCRAWDecoder",
      "Decode averages: requested=%s, threads=%d, parallel-frames=%" PRIu64 "/%" PRIu64
      ", read=%" PRIu64 "us, payload-decode=%" PRIu64
      "us, JNI-total=%" PRIu64 "us, fallbacks=%" PRIu64,
      mcrawDecoderBackendName(metrics.requested_backend),
      metrics.decoder_threads,
      wrapper->mcraw_benchmark_parallel_frames, frames,
      wrapper->mcraw_benchmark_read_ns / frames / 1000u,
      wrapper->mcraw_benchmark_decode_ns / frames / 1000u,
      wrapper->mcraw_benchmark_total_ns / frames / 1000u,
      wrapper->mcraw_benchmark_fallbacks);

  resetMcrawDecoderBenchmark(wrapper, metrics.requested_backend,
                             metrics.decoder_threads,
                             compressionType);
}

} // namespace

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

  const uint32_t frameCount = getMlvFrames(nativeClip);
  if (frameIndex < 0 || static_cast<uint32_t>(frameIndex) >= frameCount) {
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

extern "C" JNIEXPORT jint JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillRawBayer16(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jint frameIndex,
    jobject dstByteBuffer, jint decoderBackend, jint decoderThreads) {
  if (handle == 0 || dstByteBuffer == nullptr || frameIndex < 0 ||
      (decoderBackend != MCRAW_DECODER_BASELINE &&
       decoderBackend != MCRAW_DECODER_ROW_PARALLEL) ||
      decoderThreads <= 0) {
    return kRawGpuDecodeHardFailure;
  }

  auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
  std::unique_lock<std::mutex> lock(wrapper->render_mutex, std::try_to_lock);
  if (!lock.owns_lock()) {
    return kRawGpuDecodeTransient;
  }

  mlvObject_t *nativeClip = wrapper->mlv_object;
  if (!isUsableRawClip(nativeClip) ||
      static_cast<uint32_t>(frameIndex) >= getMlvFrames(nativeClip)) {
    return kRawGpuDecodeHardFailure;
  }

  const size_t width = static_cast<size_t>(getMlvWidth(nativeClip));
  const size_t height = static_cast<size_t>(getMlvHeight(nativeClip));
  if (width == 0 || height == 0 ||
      width > std::numeric_limits<size_t>::max() / height) {
    return kRawGpuDecodeHardFailure;
  }

  const size_t pixels = width * height;
  if (pixels > std::numeric_limits<size_t>::max() / sizeof(uint16_t)) {
    return kRawGpuDecodeHardFailure;
  }
  const size_t needed = pixels * sizeof(uint16_t);

  const jlong capacity = env->GetDirectBufferCapacity(dstByteBuffer);
  auto *dst =
      reinterpret_cast<uint16_t *>(env->GetDirectBufferAddress(dstByteBuffer));
  if (dst == nullptr || capacity < 0 ||
      static_cast<uint64_t>(capacity) < static_cast<uint64_t>(needed)) {
    return kRawGpuDecodeHardFailure;
  }

  const bool isMcraw =
      (nativeClip->MLVI.videoClass & MLV_VIDEO_CLASS_FLAG_MCRAW) != 0;

  const int effectiveDecoderBackend =
      isMcraw ? decoderBackend : MCRAW_DECODER_BASELINE;
  const int effectiveDecoderThreads =
      isMcraw && decoderBackend == MCRAW_DECODER_ROW_PARALLEL
          ? std::min<int>(decoderThreads, MCRAW_PARALLEL_MAX_THREADS)
          : 1;

  mcraw_decode_metrics_t metrics = {};
  const auto totalStart = std::chrono::steady_clock::now();
  const int result = getRawGpuFrameUint16(
      nativeClip, static_cast<uint64_t>(frameIndex), dst,
      effectiveDecoderBackend, effectiveDecoderThreads, &metrics);
  const uint64_t totalNs = static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(
          std::chrono::steady_clock::now() - totalStart)
          .count());
  if (result != 0) {
    if (!isMcraw) {
      __android_log_print(
          ANDROID_LOG_ERROR, "RawGpuDecode",
          "Classic MLV decode failed: frame=%d, class=0x%x, payload=%u, RAWI=%zux%zu",
          frameIndex, nativeClip->MLVI.videoClass,
          nativeClip->video_index[frameIndex].frame_size, width, height);
    }
    return kRawGpuDecodeHardFailure;
  }
  if (!isMcraw) {
    return kRawGpuBackendClassicMlv;
  }
  // Shared native decode has already parity-checked one complete frame and
  // permanently latched baseline on a mismatch, allocation failure, unsupported
  // type, or decode failure. JNI only mirrors that state for one-time logging.
  if (decoderBackend == MCRAW_DECODER_ROW_PARALLEL &&
      metrics.actual_backend == MCRAW_DECODER_BASELINE &&
      metrics.fallback_count > 0) {
    const int sharedState =
        getMlvMcrawParallelValidationState(nativeClip);
    if (sharedState < 0 && wrapper->mcraw_parallel_reported_state >= 0) {
      wrapper->mcraw_parallel_reported_state = -1;
      __android_log_print(
          ANDROID_LOG_WARN, "MCRAWDecoder",
          "Parallel decoder unavailable or parity failed; using baseline for clip");
    }
    recordMcrawDecoderBenchmark(wrapper, metrics, totalNs,
                                nativeClip->compression_type);
    return MCRAW_DECODER_BASELINE;
  }

  if (metrics.actual_backend == MCRAW_DECODER_ROW_PARALLEL &&
      wrapper->mcraw_parallel_reported_state == 0 &&
      getMlvMcrawParallelValidationState(nativeClip) > 0) {
      wrapper->mcraw_parallel_reported_state = 1;
      __android_log_print(
          ANDROID_LOG_INFO, "MCRAWDecoder",
          "Shared parallel parity check passed: frame=%d, pixels=%zu",
          frameIndex, pixels);
  }

  recordMcrawDecoderBenchmark(wrapper, metrics, totalNs,
                              nativeClip->compression_type);
  return metrics.actual_backend;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_fillRawGpuPreviewState(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jobject paramsByteBuffer,
    jobject toneLutByteBuffer) {
  if (handle == 0 || paramsByteBuffer == nullptr ||
      toneLutByteBuffer == nullptr) {
    return JNI_FALSE;
  }

  auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
  // State refresh happens only when processingVersion changes. Wait for an
  // in-flight frame/cache transition so JNI false means invalid/unsupported
  // state, not transient contention that could permanently disable GPU mode.
  std::lock_guard<std::mutex> lock(wrapper->render_mutex);

  mlvObject_t *nativeClip = wrapper->mlv_object;
  if (!isUsableRawClip(nativeClip) || nativeClip->processing == nullptr) {
    return JNI_FALSE;
  }

  const jlong paramsCapacity =
      env->GetDirectBufferCapacity(paramsByteBuffer);
  const jlong toneLutCapacity =
      env->GetDirectBufferCapacity(toneLutByteBuffer);
  auto *paramsDst = reinterpret_cast<uint8_t *>(
      env->GetDirectBufferAddress(paramsByteBuffer));
  auto *toneLutDst = reinterpret_cast<uint8_t *>(
      env->GetDirectBufferAddress(toneLutByteBuffer));
  if (paramsDst == nullptr || toneLutDst == nullptr || paramsCapacity < 0 ||
      toneLutCapacity < 0 ||
      static_cast<uint64_t>(paramsCapacity) < kGpuPreviewParamsBytes ||
      static_cast<uint64_t>(toneLutCapacity) < kGpuPreviewToneLutBytes) {
    return JNI_FALSE;
  }

  float params[kGpuPreviewParamCount] = {};
  pthread_mutex_lock(&nativeClip->processing_mutex);
  processingObject_t *processing = nativeClip->processing;
  const int bitDepth = getMlvBitdepth(nativeClip);
  if (bitDepth <= 0 || bitDepth > 16) {
    pthread_mutex_unlock(&nativeClip->processing_mutex);
    return JNI_FALSE;
  }

  const float nativeScale = static_cast<float>(1u << (16 - bitDepth));
  params[0] = processing->black_level / nativeScale;
  params[1] = static_cast<float>(processing->white_level) / nativeScale;
  params[2] = static_cast<float>(processing->final_matrix[0]);
  params[3] = static_cast<float>(processing->final_matrix[4]);
  params[4] = static_cast<float>(processing->final_matrix[8]);
  const int gpuCfa = rawCfaToGpuEnum(nativeClip->RAWI.raw_info.cfa_pattern);
  if (gpuCfa < 0) {
    pthread_mutex_unlock(&nativeClip->processing_mutex);
    return JNI_FALSE;
  }
  params[5] = static_cast<float>(gpuCfa);

  if (processing->use_cam_matrix > 0) {
    for (size_t i = 0; i < 9; ++i) {
      params[6 + i] = static_cast<float>(processing->proper_wb_matrix[i]);
    }
  } else {
    params[6] = 1.0f;
    params[10] = 1.0f;
    params[14] = 1.0f;
  }
  // Bit 0 asks the GPU to reproduce the CPU AgX compression -> tone curve ->
  // inverse-compression sandwich.
  params[15] = processing->AgX != 0 ? 1.0f : 0.0f;
  memcpy(toneLutDst, processing->pre_calc_gamma, kGpuPreviewToneLutBytes);
  pthread_mutex_unlock(&nativeClip->processing_mutex);

  memcpy(paramsDst, params, kGpuPreviewParamsBytes);
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setRawGpuPreviewCaching(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle, jboolean enabled) {
  if (handle == 0) {
    return JNI_FALSE;
  }

  auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
  // Cache control is dispatched off the UI/GL threads and must not be lost to
  // a transient in-flight render. Wait for that render to finish, then make
  // the requested benchmark state deterministic.
  std::lock_guard<std::mutex> lock(wrapper->render_mutex);

  mlvObject_t *nativeClip = wrapper->mlv_object;
  if (!isUsableRawClip(nativeClip)) {
    return JNI_FALSE;
  }

  const bool cachingEnabled = nativeClip->stop_caching == 0;
  const bool shouldEnable = enabled == JNI_TRUE;
  if (cachingEnabled != shouldEnable) {
    if (shouldEnable) {
      enableMlvCaching(nativeClip);
    } else {
      disableMlvCaching(nativeClip);
    }
  }

  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_setMcrawParallelDecoder(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle, jboolean enabled) {
  if (handle == 0) {
    return JNI_FALSE;
  }

  auto *wrapper = reinterpret_cast<JniClipWrapper *>(handle);
  std::lock_guard<std::mutex> lock(wrapper->render_mutex);
  mlvObject_t *nativeClip = wrapper->mlv_object;
  if (nativeClip == nullptr || !isMlvActive(nativeClip)) {
    return JNI_FALSE;
  }

  setMlvMcrawDecoder(
      nativeClip,
      enabled == JNI_TRUE ? MCRAW_DECODER_ROW_PARALLEL
                          : MCRAW_DECODER_BASELINE,
      getMlvCpuCores(nativeClip));
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
