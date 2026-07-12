
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

  // Experimental MCRAW decoder A/B counters. Access is serialized by
  // render_mutex and the window resets whenever the requested backend changes.
  int mcraw_benchmark_requested_backend = -1;
  int mcraw_benchmark_decoder_threads = -1;
  uint64_t mcraw_benchmark_frames = 0;
  uint64_t mcraw_benchmark_parallel_frames = 0;
  uint64_t mcraw_benchmark_read_ns = 0;
  uint64_t mcraw_benchmark_decode_ns = 0;
  uint64_t mcraw_benchmark_total_ns = 0;
  uint64_t mcraw_benchmark_fallbacks = 0;
  // 0=pending, 1=bit-exact, -1=disabled after mismatch/failure.
  int mcraw_parallel_validation_state = 0;
} JniClipWrapper;

#endif // MLV_JNI_WRAPPER_H
