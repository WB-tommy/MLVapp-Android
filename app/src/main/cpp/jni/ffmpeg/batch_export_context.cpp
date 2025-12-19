/*
 * Batch Export Context Implementation
 *
 * Implements encoder caching and reuse logic for batch exports.
 */

#include "batch_export_context.h"
#include "ffmpeg_utils.h"
#include <algorithm>
#include <android/log.h>

extern "C" {
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
  codec_ctx->width = width;
  codec_ctx->height = height;
  codec_ctx->time_base = av_inv_q(fps);
  codec_ctx->framerate = fps;
  codec_ctx->gop_size = preset.gop;
  codec_ctx->max_b_frames = preset.max_b_frames;
  codec_ctx->bit_rate = preset.bit_rate;

  if (ctx.cached_encoder.is_hardware) {
    codec_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
    if (codec_ctx->bit_rate == 0) {
      int64_t pixels = static_cast<int64_t>(width) * height;
      int64_t base_pixels = 1920LL * 1080;
      double scale_factor = static_cast<double>(pixels) / base_pixels;
      double quality_factor = 1.0;
      if (!preset.crf.empty()) {
        int crf_val = std::stoi(preset.crf);
        quality_factor = (crf_val <= 18) ? 1.5 : 1.0;
      }
      codec_ctx->bit_rate =
          static_cast<int64_t>(8000000 * scale_factor * quality_factor);
      if (codec_ctx->bit_rate < 1000000)
        codec_ctx->bit_rate = 1000000;
    }
    codec_ctx->rc_max_rate = codec_ctx->bit_rate;
    codec_ctx->rc_buffer_size = codec_ctx->bit_rate;
  } else {
    codec_ctx->pix_fmt = preset.pixel_format;
    codec_ctx->thread_count = std::max(1, thread_count);
  }

  if (preset.profile != AV_PROFILE_UNKNOWN) {
    codec_ctx->profile = preset.profile;
  }
  codec_ctx->color_primaries = AVCOL_PRI_BT709;
  codec_ctx->color_trc = AVCOL_TRC_BT709;
  codec_ctx->colorspace = AVCOL_SPC_BT709;
  codec_ctx->color_range = AVCOL_RANGE_MPEG;

  if (fmt_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
    codec_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
  }

  if (!ctx.cached_encoder.is_hardware) {
    if (!preset.crf.empty())
      av_opt_set(codec_ctx->priv_data, "crf", preset.crf.c_str(), 0);
    if (!preset.preset.empty())
      av_opt_set(codec_ctx->priv_data, "preset", preset.preset.c_str(), 0);
    if (preset.codec_id == AV_CODEC_ID_VP9 && preset.crf == "0") {
      av_opt_set(codec_ctx->priv_data, "lossless", "1", 0);
    }
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
                                        AVStream *stream) {

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
                                             thread_count, fmt_ctx, stream);

  if (codec_ctx) {
    // Cache the working encoder for future clips
    const AVCodec *codec = avcodec_find_encoder(codec_ctx->codec_id);
    if (codec) {
      ctx.cached_encoder.encoder_name = codec->name;
      // Determine if hardware by checking against known hardware encoder names
      ctx.cached_encoder.is_hardware =
          (strstr(codec->name, "mediacodec") != nullptr ||
           strstr(codec->name, "videotoolbox") != nullptr ||
           strstr(codec->name, "nvenc") != nullptr ||
           strstr(codec->name, "qsv") != nullptr);
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
  ctx.preset_initialized = false;
  ctx.current_width = 0;
  ctx.current_height = 0;
  ctx.current_fps = {0, 1};
  ctx.active = false;

  LOGI("Batch context cleaned up");
}
