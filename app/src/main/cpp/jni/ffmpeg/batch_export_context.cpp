/*
 * Batch Export Context Implementation
 *
 * Implements encoder caching and reuse logic for batch exports.
 */

#include "batch_export_context.h"
#include "ffmpeg_color_tags.h"
#include "ffmpeg_utils.h"
#include <algorithm>
#include <android/log.h>
#include <cstring>

extern "C" {
#include "libavutil/error.h"
#include "libavutil/opt.h"
}

#define LOG_TAG "BatchExportContext"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

void init_batch_context(BatchExportContext &ctx,
                        const export_options_t &options) {
  // Clear any existing state
  cleanup_batch_context(ctx);

  // Compute preset once for the entire batch
  ctx.preset = select_video_preset(options);
  ctx.preset_initialized = true;
  ctx.active = true;

  LOGI("Batch context initialized with codec_id=%d, container=%s",
       ctx.preset.codec_id, ctx.preset.container_format.c_str());
}

bool can_reuse_codec(const BatchExportContext &ctx, int width, int height,
                     AVRational fps) {
  if (!ctx.codec_ctx) {
    return false;
  }

  // Check if dimensions match
  if (ctx.current_width != width || ctx.current_height != height) {
    LOGI("Dimensions changed: %dx%d -> %dx%d, codec reuse not possible",
         ctx.current_width, ctx.current_height, width, height);
    return false;
  }

  // Check if fps matches (with some tolerance)
  double current_fps = av_q2d(ctx.current_fps);
  double new_fps = av_q2d(fps);
  if (std::abs(current_fps - new_fps) > 0.01) {
    LOGI("FPS changed: %.2f -> %.2f, codec reuse not possible", current_fps,
         new_fps);
    return false;
  }

  LOGI("Codec context can be reused for %dx%d @ %.2f fps", width, height,
       new_fps);
  return true;
}

// Internal: Try to open encoder using cached selection (skip fallback chain)
static AVCodecContext *open_cached_encoder(const BatchExportContext &ctx,
                                           int width, int height,
                                           AVRational fps, int thread_count,
                                           AVFormatContext *fmt_ctx,
                                           AVStream *stream) {

  if (!ctx.cached_encoder.valid) {
    return nullptr;
  }

  const AVCodec *codec =
      avcodec_find_encoder_by_name(ctx.cached_encoder.encoder_name.c_str());
  if (!codec) {
    LOGW("Cached encoder '%s' no longer available",
         ctx.cached_encoder.encoder_name.c_str());
    return nullptr;
  }

  AVCodecContext *codec_ctx = avcodec_alloc_context3(codec);
  if (!codec_ctx) {
    return nullptr;
  }

  const VideoPreset &preset = ctx.preset;

  codec_ctx->codec_id = codec->id;
  EncoderCandidate candidate{ctx.cached_encoder.encoder_name,
                             ctx.cached_encoder.is_hardware,
                             ctx.cached_encoder.hw_device_type,
                             ctx.cached_encoder.hw_pixel_format};
  int cfg_ret = configure_video_codec_context(
      codec_ctx, preset, candidate, width, height, fps, thread_count, fmt_ctx,
      ctx.gamut, ctx.tonemap, ctx.transfer_function);
  if (cfg_ret < 0) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(cfg_ret, errbuf, sizeof(errbuf));
    LOGW("Failed to configure cached encoder '%s': %s",
         ctx.cached_encoder.encoder_name.c_str(), errbuf);
    avcodec_free_context(&codec_ctx);
    return nullptr;
  }

  if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
    LOGW("Failed to open cached encoder '%s'",
         ctx.cached_encoder.encoder_name.c_str());
    avcodec_free_context(&codec_ctx);
    return nullptr;
  }

  if (avcodec_parameters_from_context(stream->codecpar, codec_ctx) < 0) {
    avcodec_free_context(&codec_ctx);
    return nullptr;
  }
  stream->time_base = codec_ctx->time_base;

  LOGI("Opened cached encoder '%s' successfully",
       ctx.cached_encoder.encoder_name.c_str());
  return codec_ctx;
}

AVCodecContext *get_batch_codec_context(BatchExportContext &ctx, int width,
                                        int height, AVRational fps,
                                        int thread_count,
                                        AVFormatContext *fmt_ctx,
                                        AVStream *stream, int gamut,
                                        int tonemap,
                                        const std::string& transfer_function) {

  // Store gamut/tonemap so cached encoder uses correct tags
  ctx.gamut = gamut;
  ctx.tonemap = tonemap;
  ctx.transfer_function = transfer_function;

  // If we can reuse existing context, return it
  // Note: For video container export, each clip needs its own output file,
  // so we can't truly reuse the context across files. But we can skip probing.
  // The codec_ctx itself must be recreated per output file.

  AVCodecContext *codec_ctx = nullptr;

  // Try cached encoder first (skip fallback chain)
  if (ctx.cached_encoder.valid) {
    codec_ctx = open_cached_encoder(ctx, width, height, fps, thread_count,
                                    fmt_ctx, stream);
    if (codec_ctx) {
      ctx.current_width = width;
      ctx.current_height = height;
      ctx.current_fps = fps;
      return codec_ctx;
    }
    // Cache invalid, clear it
    ctx.cached_encoder.valid = false;
  }

  // Fall back to full probe (first clip or cache miss)
  LOGI("Probing encoders for %dx%d @ %.2f fps", width, height, av_q2d(fps));
  codec_ctx = try_open_encoder_with_fallback(ctx.preset, width, height, fps,
                                              thread_count, fmt_ctx, stream,
                                              gamut, tonemap, transfer_function);

  if (codec_ctx) {
    // Cache the actual working encoder (not the default for this codec_id)
    const AVCodec *codec = codec_ctx->codec;
    if (codec) {
      ctx.cached_encoder.encoder_name = codec->name;
      ctx.cached_encoder.is_hardware = false;
      ctx.cached_encoder.hw_device_type = AV_HWDEVICE_TYPE_NONE;
      ctx.cached_encoder.hw_pixel_format = AV_PIX_FMT_NONE;

      for (const auto &candidate : ctx.preset.encoder_candidates) {
        if (candidate.name == codec->name) {
          ctx.cached_encoder.is_hardware = candidate.is_hardware;
          ctx.cached_encoder.hw_device_type = candidate.hw_device_type;
          ctx.cached_encoder.hw_pixel_format = candidate.hw_pixel_format;
          break;
        }
      }

      if (!ctx.cached_encoder.is_hardware) {
        ctx.cached_encoder.is_hardware =
            (strstr(codec->name, "mediacodec") != nullptr ||
             strstr(codec->name, "videotoolbox") != nullptr ||
             strstr(codec->name, "nvenc") != nullptr ||
             strstr(codec->name, "qsv") != nullptr ||
             strstr(codec->name, "vulkan") != nullptr);
      }
      ctx.cached_encoder.valid = true;
      LOGI("Cached working encoder: '%s' (hardware=%d)",
           ctx.cached_encoder.encoder_name.c_str(),
           ctx.cached_encoder.is_hardware);
    }

    ctx.current_width = width;
    ctx.current_height = height;
    ctx.current_fps = fps;
  }

  return codec_ctx;
}

void cleanup_batch_context(BatchExportContext &ctx) {
  if (ctx.codec_ctx) {
    avcodec_free_context(&ctx.codec_ctx);
    ctx.codec_ctx = nullptr;
  }

  ctx.cached_encoder.valid = false;
  ctx.cached_encoder.encoder_name.clear();
  ctx.cached_encoder.is_hardware = false;
  ctx.cached_encoder.hw_device_type = AV_HWDEVICE_TYPE_NONE;
  ctx.cached_encoder.hw_pixel_format = AV_PIX_FMT_NONE;
  ctx.preset_initialized = false;
  ctx.current_width = 0;
  ctx.current_height = 0;
  ctx.current_fps = {0, 1};
  ctx.active = false;

  LOGI("Batch context cleaned up");
}
