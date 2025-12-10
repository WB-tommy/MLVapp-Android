#include "../export/export_handler.h"
#include "../ffmpeg/ffmpeg_presets.h"
#include "../utils.h"
#include <algorithm>
#include <jni.h>
#include <string>
#include <vector>

static const char *LOG_TAG = "FFmpegUtils";

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/opt.h"
#include "libavutil/pixdesc.h"
}

// Implementation of try_open_encoder_with_fallback
AVCodecContext *try_open_encoder_with_fallback(const VideoPreset &preset,
                                               int width, int height,
                                               AVRational fps, int thread_count,
                                               AVFormatContext *fmt_ctx,
                                               AVStream *stream) {
  AVCodecContext *codec_ctx = nullptr;
  LOGI(LOG_TAG, "=== Encoder Fallback Chain ===");
  LOGI(LOG_TAG, "Total candidates: %zu", preset.encoder_candidates.size());

  for (size_t i = 0; i < preset.encoder_candidates.size(); ++i) {
    const auto &candidate = preset.encoder_candidates[i];
    LOGI(LOG_TAG, "[%zu/%zu] Attempting encoder: %s (type: %s)", i + 1,
         preset.encoder_candidates.size(), candidate.name.c_str(),
         candidate.is_hardware ? "HARDWARE" : "SOFTWARE");

    const AVCodec *codec = avcodec_find_encoder_by_name(candidate.name.c_str());
    if (!codec) {
      LOGW(LOG_TAG, "[%zu/%zu] Encoder '%s' not found, skipping...", i + 1,
           preset.encoder_candidates.size(), candidate.name.c_str());
      continue;
    }

    // Log supported pixel formats for this encoder
    if (codec->pix_fmts) {
      std::string supported_fmts = "Supported pix_fmts: ";
      for (const AVPixelFormat *p = codec->pix_fmts; *p != AV_PIX_FMT_NONE;
           ++p) {
        const char *name = av_get_pix_fmt_name(*p);
        supported_fmts += (name ? name : "unknown");
        supported_fmts += " ";
      }
      LOGI(LOG_TAG, "%s", supported_fmts.c_str());
    }

    codec_ctx = avcodec_alloc_context3(codec);
    if (!codec_ctx)
      continue;

    codec_ctx->codec_id = codec->id;
    codec_ctx->width = width;
    codec_ctx->height = height;
    codec_ctx->time_base = av_inv_q(fps);
    codec_ctx->framerate = fps;
    codec_ctx->gop_size = preset.gop;
    codec_ctx->max_b_frames = preset.max_b_frames;
    codec_ctx->bit_rate = preset.bit_rate;

    if (codec_ctx->bit_rate == 0 && preset.codec_id == AV_CODEC_ID_DNXHD) {
      if (preset.dnxhd_profile >= 0) {
        codec_ctx->bit_rate = default_dnxhd_bitrate(
            codec_ctx->width, codec_ctx->height, fps, preset.dnxhd_profile);
        LOGI(LOG_TAG, "DNxHD default bitrate: %lld",
             (long long)codec_ctx->bit_rate);
      } else if (!preset.profile_opt.empty()) {
        codec_ctx->bit_rate = default_dnxhr_bitrate(
            codec_ctx->width, codec_ctx->height, preset.profile_opt);
        LOGI(LOG_TAG, "DNxHR default bitrate: %lld",
             (long long)codec_ctx->bit_rate);
      }
    }

    if (candidate.is_hardware) {
      codec_ctx->max_b_frames = 0;
      if (preset.pixel_format == AV_PIX_FMT_YUV420P)
        codec_ctx->pix_fmt = AV_PIX_FMT_NV12;
      if (preset.pixel_format == AV_PIX_FMT_YUV420P10LE)
        codec_ctx->pix_fmt = AV_PIX_FMT_P010LE;
      if (preset.pixel_format == AV_PIX_FMT_YUV444P12LE)
        codec_ctx->pix_fmt = AV_PIX_FMT_MEDIACODEC;
      if (codec_ctx->bit_rate == 0) {
        int64_t pixels =
            static_cast<int64_t>(codec_ctx->width) * codec_ctx->height;
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
      if (codec_ctx->width % 2 != 0)
        codec_ctx->width++;
      if (codec_ctx->height % 2 != 0)
        codec_ctx->height++;
    } else {
      codec_ctx->pix_fmt = preset.pixel_format;
      codec_ctx->thread_count = std::max(1, thread_count);
    }

    if (codec_ctx->pix_fmt == AV_PIX_FMT_P010LE) {
      codec_ctx->profile = AV_PROFILE_HEVC_MAIN_10;
    } else if (preset.profile != AV_PROFILE_UNKNOWN) {
      codec_ctx->profile = preset.profile;
    }
    codec_ctx->color_primaries = AVCOL_PRI_BT709;
    codec_ctx->color_trc = AVCOL_TRC_BT709;
    codec_ctx->colorspace = AVCOL_SPC_BT709;
    codec_ctx->color_range = AVCOL_RANGE_MPEG;

    if (fmt_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
      codec_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if (!candidate.is_hardware) {
      if (!preset.crf.empty())
        av_opt_set(codec_ctx->priv_data, "crf", preset.crf.c_str(), 0);
      if (!preset.preset.empty())
        av_opt_set(codec_ctx->priv_data, "preset", preset.preset.c_str(), 0);
      if (!preset.profile_opt.empty()) {
        av_opt_set(codec_ctx->priv_data, "profile", preset.profile_opt.c_str(),
                   0);
      }
      if (preset.codec_id == AV_CODEC_ID_VP9 && preset.crf == "0") {
        av_opt_set(codec_ctx->priv_data, "lossless", "1", 0);
      }
      // Set x265-params for HEVC encoding based on pixel format
      if (preset.codec_id == AV_CODEC_ID_HEVC) {
        const char *pix_fmt_name = av_get_pix_fmt_name(codec_ctx->pix_fmt);
        LOGI(LOG_TAG, "HEVC encoder: pix_fmt=%s (%d)",
             pix_fmt_name ? pix_fmt_name : "unknown", codec_ctx->pix_fmt);

        // 12-bit formats
        if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV444P12LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=12:profile=main444-12", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main444-12 profile (12-bit 4:4:4)");
        } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV422P12LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=12:profile=main422-12", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main422-12 profile (12-bit 4:2:2)");
        } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV420P12LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=12:profile=main12", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main12 profile (12-bit 4:2:0)");
        }
        // 10-bit formats
        else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV444P10LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=10:profile=main444-10", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main444-10 profile (10-bit 4:4:4)");
        } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV422P10LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=10:profile=main422-10", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main422-10 profile (10-bit 4:2:2)");
        } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV420P10LE) {
          av_opt_set(codec_ctx->priv_data, "x265-params",
                     "output-depth=10:profile=main10", 0);
          LOGI(LOG_TAG,
               "Setting x265-params for main10 profile (10-bit 4:2:0)");
        }
      }
    }

    // Log complete encoder configuration before attempting to open
    LOGI(LOG_TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    LOGI(LOG_TAG, "Attempting to open encoder with configuration:");
    LOGI(LOG_TAG, "  Encoder: %s (%s)", candidate.name.c_str(),
         candidate.is_hardware ? "HARDWARE" : "SOFTWARE");
    LOGI(LOG_TAG, "  Codec ID: %d", codec_ctx->codec_id);
    const char *pix_fmt_name = av_get_pix_fmt_name(codec_ctx->pix_fmt);
    LOGI(LOG_TAG, "  Pixel format: %s (%d)",
         pix_fmt_name ? pix_fmt_name : "unknown", codec_ctx->pix_fmt);
    LOGI(LOG_TAG, "  Resolution: %dx%d", codec_ctx->width, codec_ctx->height);
    LOGI(LOG_TAG, "  Frame rate: %d/%d (%.2f fps)", codec_ctx->framerate.num,
         codec_ctx->framerate.den, av_q2d(codec_ctx->framerate));
    LOGI(LOG_TAG, "  Time base: %d/%d", codec_ctx->time_base.num,
         codec_ctx->time_base.den);
    LOGI(LOG_TAG, "  Bit rate: %lld", (long long)codec_ctx->bit_rate);
    if (candidate.is_hardware) {
      LOGI(LOG_TAG, "  RC max rate: %lld", (long long)codec_ctx->rc_max_rate);
      LOGI(LOG_TAG, "  RC buffer size: %lld",
           (long long)codec_ctx->rc_buffer_size);
    }
    LOGI(LOG_TAG, "  GOP size: %d", codec_ctx->gop_size);
    LOGI(LOG_TAG, "  Max B-frames: %d", codec_ctx->max_b_frames);
    if (preset.profile != AV_PROFILE_UNKNOWN) {
      LOGI(LOG_TAG, "  Profile: %d", codec_ctx->profile);
    }
    LOGI(LOG_TAG, "  Thread count: %d", codec_ctx->thread_count);
    LOGI(LOG_TAG, "  Global header: %s",
         (codec_ctx->flags & AV_CODEC_FLAG_GLOBAL_HEADER) ? "YES" : "NO");
    LOGI(LOG_TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

    int ret = avcodec_open2(codec_ctx, codec, nullptr);
    if (ret < 0) {
      char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
      av_strerror(ret, errbuf, sizeof(errbuf));
      LOGE(LOG_TAG, "╔════════════════════════════════════════════════════╗");
      LOGE(LOG_TAG, "║ ENCODER OPEN FAILED                                ║");
      LOGE(LOG_TAG, "╚════════════════════════════════════════════════════╝");
      LOGE(LOG_TAG, "[%zu/%zu] Failed to open encoder '%s'", i + 1,
           preset.encoder_candidates.size(), candidate.name.c_str());
      LOGE(LOG_TAG, "  Error code: %d", ret);
      LOGE(LOG_TAG, "  Error message: %s", errbuf);
      LOGE(LOG_TAG, "  Encoder type: %s",
           candidate.is_hardware ? "HARDWARE" : "SOFTWARE");
      LOGE(LOG_TAG, "  Encoder profile: %i", codec_ctx->profile);
      LOGE(LOG_TAG, "  Pixel format tried: %s (%d)",
           pix_fmt_name ? pix_fmt_name : "unknown", codec_ctx->pix_fmt);
      LOGE(LOG_TAG, "  Resolution: %dx%d", codec_ctx->width, codec_ctx->height);
      LOGE(LOG_TAG, "  Bit rate: %lld", (long long)codec_ctx->bit_rate);

      // Log possible causes based on error code
      if (ret == -22) { // EINVAL
        LOGE(LOG_TAG, "  → Likely cause: Invalid parameter combination");
        LOGE(LOG_TAG, "    - Check if pixel format is supported by encoder");
        LOGE(LOG_TAG, "    - Verify resolution is valid for this encoder");
        LOGE(LOG_TAG, "    - For hardware: ensure bit rate is set correctly");
      } else if (ret == -1) {
        LOGE(
            LOG_TAG,
            "  → Likely cause: Generic error, check device MediaCodec support");
      }
      LOGE(LOG_TAG, "════════════════════════════════════════════════════");

      avcodec_free_context(&codec_ctx);
      codec_ctx = nullptr;
      continue;
    }

    LOGI(LOG_TAG, "✓ SUCCESS: Encoder '%s' opened successfully",
         candidate.name.c_str());

    if (avcodec_parameters_from_context(stream->codecpar, codec_ctx) < 0) {
      avcodec_free_context(&codec_ctx);
      codec_ctx = nullptr;
      continue;
    }
    stream->time_base = codec_ctx->time_base;
    return codec_ctx;
  }

  // Generic fallback
  LOGW(LOG_TAG, "All candidates failed, trying generic codec");
  const AVCodec *codec = avcodec_find_encoder(preset.codec_id);
  if (codec) {
    codec_ctx = avcodec_alloc_context3(codec);
    if (codec_ctx) {
      codec_ctx->codec_id = codec->id;
      codec_ctx->pix_fmt = preset.pixel_format;
      codec_ctx->width = width;
      codec_ctx->height = height;
      codec_ctx->time_base = av_inv_q(fps);
      codec_ctx->framerate = fps;
      codec_ctx->gop_size = preset.gop;
      codec_ctx->max_b_frames = preset.max_b_frames;
      codec_ctx->bit_rate = preset.bit_rate;
      codec_ctx->thread_count = std::max(1, thread_count);
      if (codec_ctx->bit_rate == 0 && preset.codec_id == AV_CODEC_ID_DNXHD &&
          preset.dnxhd_profile >= 0) {
        codec_ctx->bit_rate = default_dnxhd_bitrate(
            codec_ctx->width, codec_ctx->height, fps, preset.dnxhd_profile);
      }
      if (preset.profile != AV_PROFILE_UNKNOWN)
        codec_ctx->profile = preset.profile;
      if (fmt_ctx->oformat->flags & AVFMT_GLOBALHEADER)
        codec_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
      if (!preset.crf.empty())
        av_opt_set(codec_ctx->priv_data, "crf", preset.crf.c_str(), 0);
      if (!preset.preset.empty())
        av_opt_set(codec_ctx->priv_data, "preset", preset.preset.c_str(), 0);

      if (avcodec_open2(codec_ctx, codec, nullptr) == 0) {
        if (avcodec_parameters_from_context(stream->codecpar, codec_ctx) >= 0) {
          stream->time_base = codec_ctx->time_base;
          return codec_ctx;
        }
      }
      avcodec_free_context(&codec_ctx);
    }
  }
  return nullptr;
}

// Test encoder configuration for diagnostics
bool test_encoder_configuration(const export_options_t &originalOptions) {
  // Make a copy and force enable hardware
  export_options_t options = originalOptions;

  LOGI(LOG_TAG, "Running encoder test with forced hardware flags...");

  VideoPreset preset = select_video_preset(options);

  // Setup Dummy Context
  AVFormatContext *fmt_ctx = avformat_alloc_context();
  if (!fmt_ctx)
    return false;

  const char *container_format = preset.container_format.c_str();
  // Fallback if empty
  if (preset.container_format.empty())
    container_format = "mp4";

  fmt_ctx->oformat = av_guess_format(container_format, NULL, NULL);
  if (!fmt_ctx->oformat) {
    // Try generic mpeg fallback
    fmt_ctx->oformat = av_guess_format("mp4", NULL, NULL);
  }

  if (!fmt_ctx->oformat) {
    LOGE(LOG_TAG, "Failed to guess output format for container: %s",
         container_format);
    avformat_free_context(fmt_ctx);
    return false;
  }

  AVStream *stream = avformat_new_stream(fmt_ctx, NULL);
  if (!stream) {
    LOGE(LOG_TAG, "Failed to create stream");
    avformat_free_context(fmt_ctx);
    return false;
  }

  // Dummy parameters
  int width = 1920;
  int height = 1080;
  AVRational fps = {30, 1};
  int thread_count = 4;

  AVCodecContext *ctx = try_open_encoder_with_fallback(
      preset, width, height, fps, thread_count, fmt_ctx, stream);

  bool success = (ctx != nullptr);

  if (success) {
    LOGI(LOG_TAG, "Test Successful! Encoder opened.");
    avcodec_free_context(&ctx);
  } else {
    LOGE(LOG_TAG, "Test Failed! Could not open encoder.");
  }

  avformat_free_context(fmt_ctx);
  return success;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_testEncoderConfiguration(
    JNIEnv *env, jobject /* thiz */, jobject exportOptions) {

  export_options_t options = parse_export_options(env, exportOptions);
  return test_encoder_configuration(options);
}
