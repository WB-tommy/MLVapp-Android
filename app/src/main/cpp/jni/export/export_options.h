#ifndef MLVAPP_EXPORT_OPTIONS_H
#define MLVAPP_EXPORT_OPTIONS_H

#include <string>

// Codec types - matches Kotlin ExportCodec enum ordinals
enum export_codec_t {
  EXPORT_CODEC_CINEMA_DNG = 0,
  EXPORT_CODEC_PRORES = 1,
  EXPORT_CODEC_H264 = 2,
  EXPORT_CODEC_H265 = 3,
  EXPORT_CODEC_TIFF = 4,
  EXPORT_CODEC_PNG = 5,
  EXPORT_CODEC_JPEG2000 = 6,
  EXPORT_CODEC_DNXHR = 7,
  EXPORT_CODEC_DNXHD = 8,
  EXPORT_CODEC_VP9 = 9,
  EXPORT_CODEC_AUDIO_ONLY = 10
};

// H.264 quality options
enum h264_quality_t {
  H264_QUALITY_HIGH = 0,  // CRF 14
  H264_QUALITY_MEDIUM = 1 // CRF 24
};

// H.264 container options
enum h264_container_t {
  H264_CONTAINER_MOV = 0,
  H264_CONTAINER_MP4 = 1,
  H264_CONTAINER_MKV = 2
};

// H.265 bit depth options
enum h265_bitdepth_t { H265_8BIT = 0, H265_10BIT = 1, H265_12BIT = 2 };

// H.265 quality options
enum h265_quality_t {
  H265_QUALITY_HIGH = 0,  // CRF 18
  H265_QUALITY_MEDIUM = 1 // CRF 24
};

// H.265 container options
enum h265_container_t {
  H265_CONTAINER_MOV = 0,
  H265_CONTAINER_MP4 = 1,
  H265_CONTAINER_MKV = 2
};

// PNG bit depth options
enum png_bitdepth_t { PNG_16BIT = 0, PNG_8BIT = 1 };

// DNxHR profile options
enum dnxhr_profile_t {
  DNXHR_LB = 0,  // Low Bandwidth
  DNXHR_SQ = 1,  // Standard Quality
  DNXHR_HQ = 2,  // High Quality
  DNXHR_HQX = 3, // High Quality 10-bit
  DNXHR_444 = 4  // 4:4:4 10-bit
};

// DNxHD profile options (mirrors desktop presets)
enum dnxhd_profile_t {
  DNXHD_1080P_10BIT = 0,
  DNXHD_1080P_8BIT = 1,
  DNXHD_720P_10BIT = 2,
  DNXHD_720P_8BIT = 3
};

// VP9 quality options
enum vp9_quality_t {
  VP9_QUALITY_GOOD = 0,    // CRF 18
  VP9_QUALITY_LOSSLESS = 1 // Lossless
};

struct export_options_t {
  int codec = EXPORT_CODEC_CINEMA_DNG;
  int codec_option = 0;
  int naming_scheme = 0;
  int cdng_variant = 0;
  int prores_profile = 3;
  int prores_encoder = 0;

  // H.264 specific
  int h264_quality = H264_QUALITY_HIGH;
  int h264_container = H264_CONTAINER_MOV;

  // H.265 specific
  int h265_bitdepth = H265_10BIT;
  int h265_quality = H265_QUALITY_HIGH;
  int h265_container = H265_CONTAINER_MOV;

  // PNG specific
  int png_bitdepth = PNG_16BIT;

  // DNxHR specific
  int dnxhr_profile = DNXHR_HQ;
  // DNxHD specific
  int dnxhd_profile = DNXHD_1080P_10BIT;

  // VP9 specific
  int vp9_quality = VP9_QUALITY_GOOD;

  int debayer_quality = 0;
  int smoothing = 0;
  bool include_audio = true;
  bool enable_raw_fixes = true;
  bool hdr_blending = false;
  bool anti_aliasing = false;
  bool frame_rate_override = false;
  float frame_rate_value = 0.f;
  bool resize_enabled = false;
  int resize_width = 0;
  int resize_height = 0;
  bool resize_lock_aspect = true;
  int resize_algorithm = 0;
  std::string source_file_name;
  std::string source_base_name;
  std::string clip_uri_path;
  std::string audio_temp_dir;
  std::string audio_path;
  float stretch_factor_x = 1.0f;
  float stretch_factor_y = 1.0f;

  // Benchmark / Diagnostics flags
  bool force_hardware = false;
  bool force_software = false;
};

#endif // MLVAPP_EXPORT_OPTIONS_H
