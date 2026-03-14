#ifndef MLVAPP_FFMPEG_UTILS_H
#define MLVAPP_FFMPEG_UTILS_H

#include "ffmpeg_presets.h"
#include <jni.h>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
}

// Try to open encoder with fallback
// gamut: GAMUT_* index from raw_processing.h (determines color_primaries + YCbCr matrix)
// tonemap: TONEMAP_* enum from raw_processing.h (determines color_trc)
AVCodecContext *try_open_encoder_with_fallback(const VideoPreset &preset,
                                               int width, int height,
                                               AVRational fps, int thread_count,
                                               AVFormatContext *fmt_ctx,
                                               AVStream *stream,
                                               int gamut = 0,
                                               int tonemap = 0,
                                               const std::string& transfer_function = "");

// Test encoder configuration for diagnostics
// Force enables hardware flags to test initialization
bool test_encoder_configuration(const export_options_t &options);

#endif // MLVAPP_FFMPEG_UTILS_H
