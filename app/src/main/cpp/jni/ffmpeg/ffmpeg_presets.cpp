#include "ffmpeg_presets.h"
#include "../utils.h"
#include <algorithm>
#include <cmath>

const char *LOG_TAG = "FFmpegPresets";

extern "C" {
#include "libavutil/opt.h"
}

inline bool approximately(float value, float target, float epsilon = 1e-3f) {
  return std::fabs(value - target) < epsilon;
}

// Provide a default DNxHR bitrate (in bits per second) when none is supplied.
int64_t default_dnxhr_bitrate(int width, int height,
                              const std::string &profile) {
  const int64_t base_pixels = 1920LL * 1080;
  int64_t base_bps = 0;

  if (profile == "dnxhr_lb") {
    base_bps = 36000000;
  } else if (profile == "dnxhr_sq") {
    base_bps = 90000000;
  } else if (profile == "dnxhr_hq") {
    base_bps = 176000000;
  } else if (profile == "dnxhr_hqx") {
    base_bps = 220000000;
  } else if (profile == "dnxhr_444") {
    base_bps = 330000000;
  } else {
    // Default to HQ
    base_bps = 176000000;
  }

  const int64_t pixels =
      static_cast<int64_t>(std::max(1, width)) * std::max(1, height);
  double scale = static_cast<double>(pixels) / static_cast<double>(base_pixels);
  int64_t scaled = static_cast<int64_t>(base_bps * scale);

  const int64_t min_bps = 10000000;
  if (scaled < min_bps)
    scaled = min_bps;
  return scaled;
}

const int64_t kDnxhdBitrates[] = {36000000,  45000000,  50000000,  75000000,
                                  90000000,  100000000, 115000000, 120000000,
                                  145000000, 175000000, 185000000, 220000000,
                                  240000000, 290000000, 365000000, 440000000};

int64_t snap_to_closest_dnxhd_bitrate(int64_t target_bps) {
  int64_t closest = kDnxhdBitrates[0];
  int64_t min_diff = std::abs(target_bps - closest);
  for (int64_t bitrate : kDnxhdBitrates) {
    int64_t diff = std::abs(target_bps - bitrate);
    if (diff < min_diff) {
      min_diff = diff;
      closest = bitrate;
    }
  }
  return closest;
}

int64_t default_dnxhd_bitrate(int width, int height, AVRational fps,
                              int dnxhd_profile) {
  const int64_t base_pixels_1080 = 1920LL * 1080;
  const int64_t base_pixels_720 = 1280LL * 720;
  const double fps_val = av_q2d(fps);
  const bool is_1080 = (width >= 1920 || height >= 1080);

  int64_t base_bps = 0;
  if (is_1080) {
    if (dnxhd_profile == DNXHD_1080P_10BIT)
      base_bps = 185000000;
    else if (dnxhd_profile == DNXHD_1080P_8BIT)
      base_bps = 120000000;
    else
      base_bps = 120000000;
  } else {
    if (dnxhd_profile == DNXHD_720P_10BIT)
      base_bps = 90000000;
    else
      base_bps = 60000000;
  }

  const int64_t ref_pixels = is_1080 ? base_pixels_1080 : base_pixels_720;
  double scale = static_cast<double>(std::max(1, width) * std::max(1, height)) /
                 static_cast<double>(ref_pixels);
  double fps_scale = fps_val > 0.0 ? (fps_val / 25.0) : 1.0;
  int64_t target = static_cast<int64_t>(base_bps * scale * fps_scale);
  return snap_to_closest_dnxhd_bitrate(target);
}

// Standard DNxHD frame rates as exact AVRationals
static const AVRational kDnxhdFrameRates[] = {
    {24000, 1001}, // 23.976
    {24, 1},       // 24
    {25, 1},       // 25
    {30000, 1001}, // 29.97
    {30, 1},       // 30
    {50, 1},       // 50
    {60000, 1001}, // 59.94
    {60, 1},       // 60
};

static AVRational snap_to_dnxhd_framerate(double fps) {
  double min_diff = 1000.0;
  AVRational closest = {25, 1};
  for (const auto &rate : kDnxhdFrameRates) {
    double rate_fps = av_q2d(rate);
    double diff = std::fabs(fps - rate_fps);
    if (diff < min_diff) {
      min_diff = diff;
      closest = rate;
    }
  }

  return closest;
}

AVRational select_fps(const export_options_t &options, double source_fps) {
  double fps = (options.frame_rate_override && options.frame_rate_value > 0.0f)
                   ? options.frame_rate_value
                   : (source_fps > 0.0 ? source_fps : 25.0);

  // DNxHD requires exact frame rates - snap to closest valid rate
  if (options.codec == EXPORT_CODEC_DNXHD) {
    return snap_to_dnxhd_framerate(fps);
  }

  AVRational rational = av_d2q(fps, 100000);
  if (rational.num <= 0 || rational.den <= 0) {
    rational.num = 25;
    rational.den = 1;
  }
  return rational;
}

void compute_dimensions(const export_options_t &options, int src_width,
                        int src_height, int &width, int &height) {
  width = src_width;
  height = src_height;

  if (options.resize_enabled && options.resize_width > 0 &&
      options.resize_height > 0) {
    width = options.resize_width;
    height = options.resize_height;
  } else {
    float stretchX = options.stretch_factor_x > 0.0f ? options.stretch_factor_x
                                                     : STRETCH_H_100;
    float stretchY = options.stretch_factor_y > 0.0f ? options.stretch_factor_y
                                                     : STRETCH_V_100;
    if (approximately(stretchY, STRETCH_V_033)) {
      width = static_cast<int>(width * 3.0f + 0.5f);
    } else {
      width = static_cast<int>(width * stretchX + 0.5f);
      height = static_cast<int>(height * stretchY + 0.5f);
    }
  }

  width = std::max(16, width);
  height = std::max(16, height);

  // DNxHD requires exact 1920x1080 or 1280x720 resolution
  if (options.codec == EXPORT_CODEC_DNXHD) {
    bool is_720p = (options.dnxhd_profile == DNXHD_720P_8BIT ||
                    options.dnxhd_profile == DNXHD_720P_10BIT);
    if (is_720p) {
      width = 1280;
      height = 720;

    } else {
      width = 1920;
      height = 1080;
    }
  }
}

int select_scale_flags(int algorithmOrdinal) {
  switch (algorithmOrdinal) {
  case 1:
    return SWS_BILINEAR;
  case 2:
    return SWS_SINC;
  case 3:
    return SWS_LANCZOS;
  case 4:
    return SWS_SPLINE;
  default:
    return SWS_BICUBIC;
  }
}

VideoPreset select_video_preset(const export_options_t &options) {
  VideoPreset preset{};

  switch (options.codec) {
  case EXPORT_CODEC_PRORES: {
    preset.codec_id = AV_CODEC_ID_PRORES;
    bool is_4444_profile = (options.prores_profile >= 4);
    preset.pixel_format =
        is_4444_profile ? AV_PIX_FMT_YUV444P10LE : AV_PIX_FMT_YUV422P10LE;
    if (!is_4444_profile && options.prores_encoder == 1) {
      preset.encoder_candidates.push_back({"prores_aw", false});
    }
    preset.encoder_candidates.push_back({"prores_ks", false});
    switch (options.prores_profile) {
    case 0:
      preset.profile = AV_PROFILE_PRORES_PROXY;
      break;
    case 1:
      preset.profile = AV_PROFILE_PRORES_LT;
      break;
    case 2:
      preset.profile = AV_PROFILE_PRORES_STANDARD;
      break;
    case 3:
      preset.profile = AV_PROFILE_PRORES_HQ;
      break;
    case 4:
      preset.profile = AV_PROFILE_PRORES_4444;
      break;
    case 5:
      preset.profile = AV_PROFILE_PRORES_XQ;
      break;
    default:
      preset.profile = AV_PROFILE_PRORES_HQ;
      break;
    }

    break;
  }
  case EXPORT_CODEC_H264: {
    preset.codec_id = AV_CODEC_ID_H264;
    preset.pixel_format = AV_PIX_FMT_YUV420P;
    preset.requires_even_dimensions = true;
    preset.max_b_frames = 2;
    preset.preset = "medium";
    preset.crf = (options.h264_quality == H264_QUALITY_HIGH) ? "14" : "24";
    switch (options.h264_container) {
    case H264_CONTAINER_MP4:
      preset.container_format = "mp4";
      preset.extension = ".mp4";
      break;
    case H264_CONTAINER_MKV:
      preset.container_format = "matroska";
      preset.extension = ".mkv";
      break;
    default:
      preset.container_format = "mov";
      preset.extension = ".mov";
      break;
    }
    preset.encoder_candidates.push_back({"h264_mediacodec", true});
    preset.encoder_candidates.push_back({"libx264", false});

    break;
  }
  case EXPORT_CODEC_H265: {
    preset.codec_id = AV_CODEC_ID_HEVC;
    preset.requires_even_dimensions = true;
    preset.max_b_frames = 2;
    preset.preset = "medium";
    switch (options.h265_bitdepth) {
    case H265_8BIT:
      preset.pixel_format = AV_PIX_FMT_YUV420P;
      break;
    case H265_12BIT:
      preset.pixel_format = AV_PIX_FMT_YUV444P12LE;
      break;
    default:
      preset.pixel_format = AV_PIX_FMT_YUV420P10LE;
      break;
    }
    preset.crf = (options.h265_quality == H265_QUALITY_HIGH) ? "18" : "24";
    switch (options.h265_container) {
    case H265_CONTAINER_MP4:
      preset.container_format = "mp4";
      preset.extension = ".mp4";
      break;
    case H265_CONTAINER_MKV:
      preset.container_format = "matroska";
      preset.extension = ".mkv";
      break;
    default:
      preset.container_format = "mov";
      preset.extension = ".mov";
      break;
    }
    preset.encoder_candidates.push_back({"hevc_mediacodec", true});
    preset.encoder_candidates.push_back({"libx265", false});

    break;
  }
  case EXPORT_CODEC_DNXHR: {
    preset.codec_id = AV_CODEC_ID_DNXHD;
    preset.container_format = "mov";
    preset.extension = ".mov";
    preset.requires_even_dimensions = true;
    switch (options.dnxhr_profile) {
    case DNXHR_LB:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.profile_opt = "dnxhr_lb";
      break;
    case DNXHR_SQ:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.profile_opt = "dnxhr_sq";
      break;
    case DNXHR_HQ:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.profile_opt = "dnxhr_hq";
      break;
    case DNXHR_HQX:
      preset.pixel_format = AV_PIX_FMT_YUV422P10LE;
      preset.profile_opt = "dnxhr_hqx";
      break;
    case DNXHR_444:
      preset.pixel_format = AV_PIX_FMT_YUV444P10LE;
      preset.profile_opt = "dnxhr_444";
      break;
    default:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.profile_opt = "dnxhr_hq";
      break;
    }
    preset.encoder_candidates.push_back({"dnxhd", false});

    break;
  }
  case EXPORT_CODEC_DNXHD: {
    preset.codec_id = AV_CODEC_ID_DNXHD;
    preset.container_format = "mov";
    preset.extension = ".mov";
    preset.requires_even_dimensions = true;
    switch (options.dnxhd_profile) {
    case DNXHD_1080P_10BIT:
      preset.pixel_format = AV_PIX_FMT_YUV422P10LE;
      preset.dnxhd_profile = DNXHD_1080P_10BIT;
      break;
    case DNXHD_1080P_8BIT:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.dnxhd_profile = DNXHD_1080P_8BIT;
      break;
    case DNXHD_720P_10BIT:
      preset.pixel_format = AV_PIX_FMT_YUV422P10LE;
      preset.dnxhd_profile = DNXHD_720P_10BIT;
      break;
    default:
      preset.pixel_format = AV_PIX_FMT_YUV422P;
      preset.dnxhd_profile = DNXHD_720P_8BIT;
      break;
    }
    preset.encoder_candidates.push_back({"dnxhd", false});

    break;
  }
  case EXPORT_CODEC_VP9: {
    preset.codec_id = AV_CODEC_ID_VP9;
    preset.container_format = "webm";
    preset.extension = ".webm";
    preset.pixel_format = AV_PIX_FMT_YUV420P;
    if (options.vp9_quality == VP9_QUALITY_LOSSLESS) {
      preset.crf = "0";
    } else {
      preset.crf = "18";
    }
    preset.encoder_candidates.push_back({"vp9_mediacodec", true});
    preset.encoder_candidates.push_back({"libvpx-vp9", false});

    break;
  }
  default:
    break;
  }

  // BENCHMARK/DIAGNOSTIC LOGIC: Filter candidates based on force flags
  if (options.force_hardware) {
    LOGW(LOG_TAG, "Forcing HARDWARE encoding (removing software candidates)");
    auto &c = preset.encoder_candidates;
    c.erase(std::remove_if(
                c.begin(), c.end(),
                [](const EncoderCandidate &ec) { return !ec.is_hardware; }),
            c.end());
  } else if (options.force_software) {
    LOGW(LOG_TAG, "Forcing SOFTWARE encoding (removing hardware candidates)");
    auto &c = preset.encoder_candidates;
    c.erase(std::remove_if(
                c.begin(), c.end(),
                [](const EncoderCandidate &ec) { return ec.is_hardware; }),
            c.end());
  }

  return preset;
}
