
#ifndef MLV_JNI_WRAPPER_H
#define MLV_JNI_WRAPPER_H

#include "clip/clip_jni.h" // Includes the original mlv_object_t forward declaration
#include <cstdint>
#include <mutex>

// A wrapper struct to hold the original mlvObject_t handle
// and our JNI-layer reusable buffers.
typedef struct {
  mlvObject_t *mlv_object;
  uint16_t *processing_buffer_16bit;
  std::mutex render_mutex; // Protects against concurrent render calls
} JniClipWrapper;

#endif // MLV_JNI_WRAPPER_H
