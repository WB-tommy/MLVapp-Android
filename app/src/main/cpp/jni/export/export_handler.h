
#ifndef MLVAPP_EXPORT_HANDLER_H
#define MLVAPP_EXPORT_HANDLER_H

#include "clip/clip_jni.h"
#include "StretchFactors.h"
#include "export_options.h"

#ifdef __cplusplus
extern "C" {
#include "audio_mlv.h"

#endif

JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_exportHandler(
        JNIEnv *env,
        jobject thiz,
        jlong cacheSize,
        jint cores,
        jintArray clipFds,
        jobject exportOptions,
        jobject progressListener,
        jobject fileProvider);

#ifdef __cplusplus
}
#endif

typedef int (*acquire_frame_fd_fn)(void *context, uint32_t frame_index, const char *relative_name);
typedef int (*acquire_container_fd_fn)(void *context, const char *relative_name);
typedef int (*acquire_audio_fd_fn)(void *context, const char *relative_name);

struct export_fd_provider_t {
    acquire_frame_fd_fn acquire_frame_fd = nullptr;
    acquire_container_fd_fn acquire_container_fd = nullptr;
    acquire_audio_fd_fn acquire_audio_fd = nullptr;
    void *ctx = nullptr;
};

int startExportCdng(
        mlvObject_t *mlv,
        const export_options_t &options,
        const export_fd_provider_t &provider,
        void (*progress_callback)(int progress)
);

int startExportPipe(
        mlvObject_t *mlv,
        const export_options_t &options,
        const export_fd_provider_t &provider,
        void (*progress_callback)(int progress));

int startExportJob(
        mlvObject_t *mlv,
        const export_options_t &options,
        const export_fd_provider_t &provider,
        void (*progress_callback)(int progress));

#ifdef __cplusplus
bool is_export_cancelled();
void reset_export_cancel_flag();
#endif

#endif //MLVAPP_EXPORT_HANDLER_H
#ifdef __cplusplus
#include <cstdint>
#endif

#ifdef __cplusplus
constexpr int EXPORT_CANCELLED = -3;
#else
#define EXPORT_CANCELLED -3
#endif
