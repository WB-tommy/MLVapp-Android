/*
 * Created by Sungmin Choi on 2025. 11. 12..
 *
 * FFmpeg-related encoding/decoding functions extracted from export_handler.
 * This file contains video/audio encoding logic using FFmpeg libraries.
 *
 * Refactored to split presets/audio logic into separate files.
 */

#ifndef MLVAPP_FFMPEG_HANDLER_H
#define MLVAPP_FFMPEG_HANDLER_H

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "ffmpeg_audio.h"
#include "ffmpeg_presets.h"
#include "ffmpeg_utils.h"

extern "C" {
#include "../../include/libavcodec/avcodec.h"
#include "../../include/libavformat/avformat.h"
#include "../../include/libavutil/channel_layout.h"
#include "../../include/libavutil/imgutils.h"
#include "../../include/libavutil/mathematics.h"
#include "../../include/libavutil/opt.h"
#include "../../include/libswresample/swresample.h"
#include "../../include/libswscale/swscale.h"
#include "../../src/mlv/mlv_object.h"
}

#include "../export/export_options.h"

// Forward declarations
struct export_fd_provider_t;

// File descriptor I/O context for FFmpeg custom I/O
struct FdIoContext {
  int fd = -1;
  AVIOContext *ctx = nullptr;
  uint8_t *buffer = nullptr;
};

// FdIoContext functions
std::unique_ptr<FdIoContext> make_fd_io(int fd);
void free_fd_io(std::unique_ptr<FdIoContext> &io);

// Image sequence export (TIFF/PNG)
int export_image_sequence(mlvObject_t *video, const export_options_t &options,
                          const export_fd_provider_t &provider,
                          AVCodecID codec_id, AVPixelFormat dst_format,
                          const char *extension,
                          void (*progress_callback)(int progress));

// Video container export (ProRes/H264/H265)
int export_video_container(mlvObject_t *video, const export_options_t &options,
                           const export_fd_provider_t &provider,
                           void (*progress_callback)(int progress));

#endif // MLVAPP_FFMPEG_HANDLER_H
