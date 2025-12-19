
#ifndef MLVAPP_EXPORT_HANDLER_H
#define MLVAPP_EXPORT_HANDLER_H

#include "StretchFactors.h"
#include "clip/clip_jni.h"
#include "export_options.h"

#ifdef __cplusplus
extern "C" {
#include "audio_mlv.h"

#endif

JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_exportHandler(
    JNIEnv *env, jobject thiz, jlong cacheSize, jint cores, jintArray clipFds,
    jobject exportOptions, jobject progressListener, jobject fileProvider);

#ifdef __cplusplus
}
#endif

typedef int (*acquire_frame_fd_fn)(void *context, uint32_t frame_index,
                                   const char *relative_name);
typedef int (*acquire_container_fd_fn)(void *context,
                                       const char *relative_name);
typedef int (*acquire_audio_fd_fn)(void *context, const char *relative_name);

export_options_t parse_export_options(JNIEnv *env, jobject exportOptions);

struct export_fd_provider_t {
  acquire_frame_fd_fn acquire_frame_fd = nullptr;
  acquire_container_fd_fn acquire_container_fd = nullptr;
  acquire_audio_fd_fn acquire_audio_fd = nullptr;
  void *ctx = nullptr;
};

int startExportCdng(mlvObject_t *mlv, const export_options_t &options,
                    const export_fd_provider_t &provider,
                    void (*progress_callback)(int progress));

int startExportPipe(mlvObject_t *mlv, const export_options_t &options,
                    const export_fd_provider_t &provider,
                    void (*progress_callback)(int progress));

int startExportJob(mlvObject_t *mlv, const export_options_t &options,
                   const export_fd_provider_t &provider,
                   void (*progress_callback)(int progress));

// Forward declaration for batch export context
struct BatchExportContext;

// Batch export functions - use these for exporting multiple clips with same
// settings
int startBatchExportJob(BatchExportContext &batch_ctx, mlvObject_t *mlv,
                        const export_options_t &options,
                        const export_fd_provider_t &provider,
                        void (*progress_callback)(int progress));

#ifdef __cplusplus
bool is_export_cancelled();
void reset_export_cancel_flag();
#endif

// Export error codes
#ifdef __cplusplus
constexpr int EXPORT_SUCCESS = 0;
constexpr int EXPORT_ERROR_GENERIC = -1;
constexpr int EXPORT_ERROR_IO = -2;
constexpr int EXPORT_CANCELLED = -3;
constexpr int EXPORT_ERROR_CODEC_UNAVAILABLE = -4;
constexpr int EXPORT_ERROR_INSUFFICIENT_MEMORY = -5;
constexpr int EXPORT_ERROR_INVALID_PARAMETERS = -6;
constexpr int EXPORT_ERROR_ENCODER_INIT_FAILED = -7;
constexpr int EXPORT_ERROR_FRAME_PROCESSING_FAILED = -8;
#else
#define EXPORT_SUCCESS 0
#define EXPORT_ERROR_GENERIC -1
#define EXPORT_ERROR_IO -2
#define EXPORT_CANCELLED -3
#define EXPORT_ERROR_CODEC_UNAVAILABLE -4
#define EXPORT_ERROR_INSUFFICIENT_MEMORY -5
#define EXPORT_ERROR_INVALID_PARAMETERS -6
#define EXPORT_ERROR_ENCODER_INIT_FAILED -7
#define EXPORT_ERROR_FRAME_PROCESSING_FAILED -8
#endif

#endif // MLVAPP_EXPORT_HANDLER_H
#ifdef __cplusplus
#include <cstdint>
#endif
