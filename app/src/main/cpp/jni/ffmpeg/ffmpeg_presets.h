#ifndef MLVAPP_FFMPEG_PRESETS_H
#define MLVAPP_FFMPEG_PRESETS_H

#include <string>
#include <vector>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libswscale/swscale.h"
}

#include "../export/StretchFactors.h"
#include "../export/export_options.h"

// Encoder candidate for hardware/software fallback
struct EncoderCandidate {
  std::string name;         // Encoder name (e.g., "h264_mediacodec", "libx264")
  bool is_hardware = false; // True if this is a hardware encoder
};

// Video encoding preset configuration
struct VideoPreset {
  std::string container_format = "mov";
  std::string extension = ".mov";
  AVCodecID codec_id = AV_CODEC_ID_NONE;
  AVPixelFormat pixel_format = AV_PIX_FMT_YUV420P;
  std::string crf;
  std::string preset = "medium";
  int bit_rate = 0;
  int gop = 12;
  int max_b_frames = 0;
  int profile = AV_PROFILE_UNKNOWN;
  // Optional string profile option (e.g. for DNxHR which uses "dnxhr_hq" etc)
  std::string profile_opt;
  bool requires_even_dimensions = false;
  // Optional DNxHD profile tag (not an FFmpeg profile constant)
  int dnxhd_profile = -1;
  std::vector<EncoderCandidate>
      encoder_candidates; // Ordered: hardware first, then software
};

// Video preset selection with hardware/software fallback candidates
VideoPreset select_video_preset(const export_options_t &options);

// Helper to calculate default DNxHD bitrate
int64_t default_dnxhd_bitrate(int width, int height, AVRational fps,
                              int dnxhd_profile);

// Helper to calculate default DNxHR bitrate
int64_t default_dnxhr_bitrate(int width, int height,
                              const std::string &profile);

// Frame rate selection
AVRational select_fps(const export_options_t &options, double source_fps);

// Scaling algorithm selection
int select_scale_flags(int algorithmOrdinal);

// Dimension computation for export
void compute_dimensions(const export_options_t &options, int src_width,
                        int src_height, int &width, int &height);

#endif // MLVAPP_FFMPEG_PRESETS_H
