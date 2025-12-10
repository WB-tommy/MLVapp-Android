#include "export_handler.h"
#include <algorithm>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <fcntl.h>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include "../../src/mlv/macros.h"
#include "../ffmpeg/ffmpeg_handler.h"

static const int kCdngNamingDefault = 0;
static const int kCdngNamingDaVinci = 1;

namespace {
inline bool approximately(float value, float target, float epsilon = 1e-3f) {
  return std::fabs(value - target) < epsilon;
}

void apply_debayer_mode(mlvObject_t *video, const export_options_t &options) {
  switch (options.debayer_quality) {
  case 1:
    setMlvUseLmmseDebayer(video);
    break;
  case 2:
    setMlvUseIgvDebayer(video);
    break;
  default:
    setMlvAlwaysUseAmaze(video);
    break;
  }
}

void reset_processing_state(mlvObject_t *video) {
  llrpResetFpmStatus(video);
  llrpResetBpmStatus(video);
  llrpComputeStripesOn(video);
  video->current_cached_frame_active = 0;
}
} // namespace

int startExportCdng(mlvObject_t *video, const export_options_t &options,
                    const export_fd_provider_t &provider,
                    void (*progress_callback)(int progress)) {
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "=== Starting CinemaDNG Export ===");

  if (!provider.acquire_frame_fd) {
    __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                        "Export error: No frame FD provider available");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }
  if (is_export_cancelled()) {
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Export cancelled before start");
    return EXPORT_CANCELLED;
  }

  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "CinemaDNG settings: variant=%d, naming=%d, base_name=%s",
                      options.cdng_variant, options.naming_scheme,
                      options.source_base_name.c_str());
  float stretchFactorX = options.stretch_factor_x;
  if (stretchFactorX <= 0.0f) {
    stretchFactorX = STRETCH_H_100;
  }

  float stretchFactorY = options.stretch_factor_y;
  if (stretchFactorY <= 0.0f) {
    stretchFactorY = STRETCH_V_100;
  }

  setMlvAlwaysUseAmaze(video);
  llrpResetFpmStatus(video);
  llrpResetBpmStatus(video);
  llrpComputeStripesOn(video);
  video->current_cached_frame_active = 0;
  if (options.enable_raw_fixes) {
    video->llrawproc->fix_raw = 1;
  }

  // Set aspect ratio of the picture
  int32_t picAR[4] = {0};

  // Set horizontal stretch
  if (approximately(stretchFactorX, STRETCH_H_133)) {
    picAR[0] = 4;
    picAR[1] = 3;
  } else if (approximately(stretchFactorX, STRETCH_H_150)) {
    picAR[0] = 3;
    picAR[1] = 2;
  } else if (approximately(stretchFactorX, STRETCH_H_167)) {
    picAR[0] = 5;
    picAR[1] = 3;
  } else if (approximately(stretchFactorX, STRETCH_H_175)) {
    picAR[0] = 7;
    picAR[1] = 4;
  } else if (approximately(stretchFactorX, STRETCH_H_180)) {
    picAR[0] = 9;
    picAR[1] = 5;
  } else if (approximately(stretchFactorX, STRETCH_H_200)) {
    picAR[0] = 2;
    picAR[1] = 1;
  } else {
    picAR[0] = 1;
    picAR[1] = 1;
  }
  // Set vertical stretch
  if (approximately(stretchFactorY, STRETCH_V_167)) {
    picAR[2] = 5;
    picAR[3] = 3;
  } else if (approximately(stretchFactorY, STRETCH_V_300)) {
    picAR[2] = 3;
    picAR[3] = 1;
  } else if (approximately(stretchFactorY, STRETCH_V_033)) {
    picAR[2] = 1;
    picAR[3] = 1;
    picAR[0] *= 3; // Upscale only
  } else {
    picAR[2] = 1;
    picAR[3] = 1;
  }

  int variant = options.cdng_variant;
  if (variant < 0 || variant > 2) {
    variant = 0;
  }

  dngObject_t *cinemaDng =
      initDngObject(video, variant, getMlvFramerate(video), picAR);

  uint32_t frameSize = getMlvWidth(video) * getMlvHeight(video) * 3;
  auto *imgBuffer = (uint16_t *)malloc(frameSize * sizeof(uint16_t));
  getMlvProcessedFrame16(video, 0, imgBuffer, getMlvCpuCores(video));
  free(imgBuffer);

  uint32_t totalFrames = getMlvFrames(video);

  char relativeName[512] = {0};

  for (uint32_t frame = 0; frame < totalFrames; frame++) {
    if (is_export_cancelled()) {
      freeDngObject(cinemaDng);
      return EXPORT_CANCELLED;
    }
    const uint32_t frameNumber = getMlvFrameNumber(video, frame);
    if (options.naming_scheme == kCdngNamingDaVinci) {
      snprintf(relativeName, sizeof(relativeName),
               "%s_1_%02i-%02i-%02i_0001_C0000_%06u.dng",
               options.source_base_name.c_str(), getMlvTmYear(video),
               getMlvTmMonth(video), getMlvTmDay(video), frameNumber);
    } else {
      snprintf(relativeName, sizeof(relativeName), "%s_%06u.dng",
               options.source_base_name.c_str(), frameNumber);
    }

    int fd = provider.acquire_frame_fd(provider.ctx, frame, relativeName);
    if (fd < 0) {
      freeDngObject(cinemaDng);
      return -1;
    }

    if (saveDngFrameFd(video, cinemaDng, frame, fd, nullptr) != 0) {
      freeDngObject(cinemaDng);
      return -1; // Error
    }

    if (progress_callback) {
      progress_callback((int)(100.0f * (frame + 1) / totalFrames));
    }

    if (is_export_cancelled()) {
      freeDngObject(cinemaDng);
      return EXPORT_CANCELLED;
    }
  }

  freeDngObject(cinemaDng);

  return 0; // Success
}

static std::string write_export_audio(mlvObject_t *video,
                                      const export_options_t &options) {
  if (!options.include_audio || options.audio_temp_dir.empty()) {
    return {};
  }

  std::string wavPath = options.audio_temp_dir;
  if (!wavPath.empty() && wavPath.back() != '/') {
    wavPath.push_back('/');
  }

  if (options.naming_scheme == kCdngNamingDaVinci) {
    char buf[512];
    snprintf(buf, sizeof(buf), "%s_1_%02i-%02i-%02i_0001_C0000.wav",
             options.source_base_name.c_str(), getMlvTmYear(video),
             getMlvTmMonth(video), getMlvTmDay(video));
    wavPath.append(buf);
  } else {
    wavPath.append(options.source_base_name);
    wavPath.append(".wav");
  }

  writeMlvAudioToWave(video, const_cast<char *>(wavPath.c_str()));
  return wavPath;
}

int startExportPipe(mlvObject_t *video, const export_options_t &options,
                    const export_fd_provider_t &provider,
                    void (*progress_callback)(int progress)) {
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "=== Starting Video Export ===");

  if (is_export_cancelled()) {
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Export cancelled before start");
    return EXPORT_CANCELLED;
  }

  __android_log_print(
      ANDROID_LOG_INFO, "ExportHandler",
      "Export settings: codec=%d, debayer_quality=%d, raw_fixes=%d",
      options.codec, options.debayer_quality, options.enable_raw_fixes);

  apply_debayer_mode(video, options);
  reset_processing_state(video);
  if (options.enable_raw_fixes) {
    video->llrawproc->fix_raw = 1;
  }

  if (progress_callback) {
    progress_callback(0);
  }

  // Image sequence exports (TIFF/PNG/JPEG2000)
  if (options.codec == EXPORT_CODEC_TIFF || options.codec == EXPORT_CODEC_PNG ||
      options.codec == EXPORT_CODEC_JPEG2000) {
    const char *ext;
    AVCodecID codec_id;
    AVPixelFormat dst_fmt;

    if (options.codec == EXPORT_CODEC_TIFF) {
      ext = ".tif";
      codec_id = AV_CODEC_ID_TIFF;
      dst_fmt = AV_PIX_FMT_RGB48LE; // TIFF always 16-bit
    } else if (options.codec == EXPORT_CODEC_PNG) {
      ext = ".png";
      codec_id = AV_CODEC_ID_PNG;
      // PNG can be 8-bit or 16-bit based on option
      dst_fmt = (options.png_bitdepth == PNG_8BIT) ? AV_PIX_FMT_RGB24
                                                   : AV_PIX_FMT_RGB48BE;
    } else {
      ext = ".jp2";
      codec_id = AV_CODEC_ID_JPEG2000;
      dst_fmt = AV_PIX_FMT_YUV444P; // JPEG2000 uses YUV444
    }

    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Exporting image sequence: format=%s, pixel_fmt=%d",
                        ext, dst_fmt);
    return export_image_sequence(video, options, provider, codec_id, dst_fmt,
                                 ext, progress_callback);
  }

  // Video container exports (ProRes/H264/H265)
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "Exporting video container");
  return export_video_container(video, options, provider, progress_callback);
}

int startExportJob(mlvObject_t *video, const export_options_t &options,
                   const export_fd_provider_t &provider,
                   void (*progress_callback)(int progress)) {
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "======================================");
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "Starting export job: codec=%d, frames=%d", options.codec,
                      getMlvFrames(video));

  if (is_export_cancelled()) {
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Export cancelled before start");
    return EXPORT_CANCELLED;
  }

  // Prepare audio if needed
  export_options_t effectiveOptions = options;
  if (options.include_audio && options.codec != EXPORT_CODEC_AUDIO_ONLY) {
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Extracting audio...");
    effectiveOptions.audio_path = write_export_audio(video, options);
    if (!effectiveOptions.audio_path.empty()) {
      __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                          "Audio extracted to: %s",
                          effectiveOptions.audio_path.c_str());
    }
  } else if (options.codec == EXPORT_CODEC_AUDIO_ONLY) {
    // Audio-only exports write WAV directly to the provided temp directory
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Extracting audio (audio-only)...");
    const std::string audioPath = write_export_audio(video, options);
    if (audioPath.empty()) {
      __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                          "Audio-only export failed: empty audio path");
      return EXPORT_ERROR_GENERIC;
    }
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Audio-only export wrote: %s", audioPath.c_str());
    if (progress_callback) {
      progress_callback(100);
    }
    return EXPORT_SUCCESS;
  }

  if (is_export_cancelled()) {
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Export cancelled after audio extraction");
    return EXPORT_CANCELLED;
  }

  int result = EXPORT_ERROR_GENERIC;
  switch (effectiveOptions.codec) {
  case EXPORT_CODEC_CINEMA_DNG:
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Route: CinemaDNG export");
    result =
        startExportCdng(video, effectiveOptions, provider, progress_callback);
    break;
  case EXPORT_CODEC_AUDIO_ONLY:
    // Handled above; should not reach here
    __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                        "Unexpected audio-only route fallthrough");
    result = EXPORT_ERROR_GENERIC;
    break;
  default:
    __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                        "Route: Video container export");
    result =
        startExportPipe(video, effectiveOptions, provider, progress_callback);
    break;
  }

  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "Export job completed with result: %d", result);
  __android_log_print(ANDROID_LOG_INFO, "ExportHandler",
                      "======================================");

  return result;
}
