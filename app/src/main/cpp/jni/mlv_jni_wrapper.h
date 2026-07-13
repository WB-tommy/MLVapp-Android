
#ifndef MLV_JNI_WRAPPER_H
#define MLV_JNI_WRAPPER_H

#include "clip/clip_jni.h" // Includes the original mlv_object_t forward declaration
#include <cstdint>
#include <mutex>

// A wrapper struct to hold the original mlvObject_t handle
// and our JNI-layer reusable buffers.
typedef struct JniClipWrapper {
  mlvObject_t *mlv_object;
  uint16_t *processing_buffer_16bit;
  std::mutex render_mutex; // Protects against concurrent render calls

  // MCRAW decoder timing counters. Access is serialized by render_mutex and
  // the window resets whenever the requested backend changes.
  int mcraw_benchmark_requested_backend = -1;
  int mcraw_benchmark_decoder_threads = -1;
  uint64_t mcraw_benchmark_frames = 0;
  uint64_t mcraw_benchmark_parallel_frames = 0;
  uint64_t mcraw_benchmark_read_ns = 0;
  uint64_t mcraw_benchmark_decode_ns = 0;
  uint64_t mcraw_benchmark_raw_processing_ns = 0;
  uint64_t mcraw_benchmark_total_ns = 0;
  uint64_t mcraw_benchmark_fallbacks = 0;
  // Mirrors the shared native parity state only to avoid duplicate log lines.
  // Decoder selection and fallback live in mlvObject_t for CPU/GPU/export.
  int mcraw_parallel_reported_state = 0;
} JniClipWrapper;

#endif // MLV_JNI_WRAPPER_H
