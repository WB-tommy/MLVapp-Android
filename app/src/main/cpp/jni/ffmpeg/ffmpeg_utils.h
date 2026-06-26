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

bool encoder_candidate_uses_hw_frames(const EncoderCandidate &candidate);

bool codec_context_uses_hw_frames(const AVCodecContext *codec_ctx);

AVPixelFormat codec_context_upload_format(const AVCodecContext *codec_ctx);

int configure_video_codec_context(AVCodecContext *codec_ctx,
                                  const VideoPreset &preset,
                                  const EncoderCandidate &candidate,
                                  int width, int height, AVRational fps,
                                  int thread_count, AVFormatContext *fmt_ctx,
                                  int gamut, int tonemap,
                                  const std::string &transfer_function);

int prepare_encoder_input_frame(AVCodecContext *codec_ctx,
                                AVFrame *software_frame,
                                AVFrame *hardware_frame,
                                AVFrame **encoder_frame);

// Test encoder configuration for diagnostics
// Force enables hardware flags to test initialization
bool test_encoder_configuration(const export_options_t &options);

bool test_vulkan_hardware_device();

bool test_vulkan_prores_encoding(const export_options_t &options);

bool test_vulkan_hevc_10bit_422_encoding(const export_options_t &options);

#endif // MLVAPP_FFMPEG_UTILS_H
