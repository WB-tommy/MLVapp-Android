
#ifndef MLV_JNI_WRAPPER_H
#define MLV_JNI_WRAPPER_H

#include "clip/clip_jni.h" // Includes the original mlv_object_t forward declaration
#include <cstdint>

// A wrapper struct to hold the original mlvObject_t handle
// and our JNI-layer reusable buffers.
typedef struct {
    mlvObject_t* mlv_object;
    uint16_t* processing_buffer_16bit;
} JniClipWrapper;

#endif //MLV_JNI_WRAPPER_H
