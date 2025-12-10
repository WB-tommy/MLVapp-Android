#ifndef MLVAPP_FFMPEG_UTILS_H
#define MLVAPP_FFMPEG_UTILS_H

#include "ffmpeg_presets.h"
#include <jni.h>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
}

// Try to open encoder with fallback
AVCodecContext *try_open_encoder_with_fallback(const VideoPreset &preset,
                                               int width, int height,
                                               AVRational fps, int thread_count,
                                               AVFormatContext *fmt_ctx,
                                               AVStream *stream);

// Test encoder configuration for diagnostics
// Force enables hardware flags to test initialization
bool test_encoder_configuration(const export_options_t &options);

#endif // MLVAPP_FFMPEG_UTILS_H
