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

extern "C" {
#include "../../src/mlv/llrawproc/llrawproc.h"
}

static const int kCdngNamingDefault = 0;
static const int kCdngNamingDaVinci = 1;

namespace {
inline bool approximately(float value, float target, float epsilon = 1e-3f) {
  return std::fabs(value - target) < epsilon;
}

// Helper to apply debayer mode by native ID
void apply_debayer_by_native_id(mlvObject_t *video, int native_id) {
  switch (native_id) {
  case 0: // NONE (monochrome)
    setMlvUseNoneDebayer(video);
    break;
  case 1: // SIMPLE
    setMlvUseSimpleDebayer(video);
    break;
  case 2: // BILINEAR
    setMlvDontAlwaysUseAmaze(video);
    break;
  case 3: // LMMSE
    setMlvUseLmmseDebayer(video);
    break;
  case 4: // IGV
    setMlvUseIgvDebayer(video);
    break;
  case 5: // AMAZE
    setMlvAlwaysUseAmaze(video);
    break;
  case 6: // AHD
    setMlvUseAhdDebayer(video);
    break;
  case 7: // RCD
    setMlvUseRcdDebayer(video);
    break;
  case 8: // DCB
    setMlvUseDcbDebayer(video);
    break;
  default:
    // Default to AMaZE for unknown modes
    setMlvAlwaysUseAmaze(video);
    break;
  }
}

void apply_debayer_mode(mlvObject_t *video, const export_options_t &options) {
  // debayer_quality is the ordinal of DebayerQuality enum:
  // 0 = RECEIPT (use clip's per-clip debayer mode)
  // 1 = Force BILINEAR (native ID 2)
  // 2 = Force LMMSE (native ID 3)
  // 3 = Force IGV (native ID 4)
  // 4 = Force AMAZE (native ID 5)
  switch (options.debayer_quality) {
  case 0: // RECEIPT - use the per-clip debayer mode
    apply_debayer_by_native_id(video, options.clip_debayer_mode);
    break;
  case 1: // Force BILINEAR
    setMlvDontAlwaysUseAmaze(video);
    break;
  case 2: // Force LMMSE
    setMlvUseLmmseDebayer(video);
    break;
  case 3: // Force IGV
    setMlvUseIgvDebayer(video);
    break;
  case 4: // Force AMAZE
    setMlvAlwaysUseAmaze(video);
    break;
  default:
    // Default to AMaZE for unknown modes
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

// Apply all raw correction settings from the options struct
void apply_raw_correction(mlvObject_t *video,
                          const raw_correction_options_t &opts) {

  if (!opts.enabled) {
    video->llrawproc->fix_raw = 0;
    return;
  }

  video->llrawproc->fix_raw = 1;

  // Vertical stripes
  llrpSetVerticalStripeMode(video, opts.vertical_stripes);

  // Focus pixels
  llrpSetFocusPixelMode(video, opts.focus_pixels);
  if (opts.focus_pixels > 0) {
    llrpSetFocusPixelInterpolationMethod(video, opts.fpi_method);
  }

  // Bad pixels
  llrpSetBadPixelMode(video, opts.bad_pixels);
  if (opts.bad_pixels > 0) {
    llrpSetBadPixelSearchMethod(video, opts.bps_method);
    llrpSetBadPixelInterpolationMethod(video, opts.bpi_method);
  }

  // Chroma smooth
  llrpSetChromaSmoothMode(video, opts.chroma_smooth);

  // Pattern noise
  llrpSetPatternNoiseMode(video, opts.pattern_noise);

  // Deflicker
  llrpSetDeflickerTarget(video, opts.deflicker_target);

  // Dual ISO
  llrpSetDualIsoMode(video, opts.dual_iso);
  llrpSetDualIsoValidity(video, opts.dual_iso_forced ? 1 : 0);
  llrpSetDualIsoInterpolationMethod(video, opts.dual_iso_interpolation);
  llrpSetDualIsoAliasMapMode(video, opts.dual_iso_alias_map ? 1 : 0);
  llrpSetDualIsoFullResBlendingMode(video, opts.dual_iso_fr_blending ? 1 : 0);

  //        // Black and white levels
  //        if (opts.dual_iso_white > 0) {
  //            setMlvWhiteLevel(video, opts.dual_iso_white);
  //        }
  //        if (opts.dual_iso_black > 0) {
  //            setMlvBlackLevel(video, opts.dual_iso_black);
  //        }

  // Dark frame
  llrpSetDarkFrameMode(video, opts.dark_frame_enabled);
  // Note: Dark frame file path would need to be applied via
  // llrpSetDarkFrameFile if the file is accessible during export
}
} // namespace

int startExportCdng(mlvObject_t *video, const export_options_t &options,
                    const export_fd_provider_t &provider,
                    void (*progress_callback)(int progress)) {

  if (!provider.acquire_frame_fd) {
    __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                        "Export error: No frame FD provider available");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }
  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

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
  // Apply raw correction settings (replaces enable_raw_fixes check)
  apply_raw_correction(video, options.raw_correction);

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

  if (is_export_cancelled()) {

    return EXPORT_CANCELLED;
  }

  apply_debayer_mode(video, options);
  reset_processing_state(video);
  // Apply raw correction settings (replaces enable_raw_fixes check)
  apply_raw_correction(video, options.raw_correction);

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

    return export_image_sequence(video, options, provider, codec_id, dst_fmt,
                                 ext, progress_callback);
  }

  // Video container exports (ProRes/H264/H265)

  return export_video_container(video, options, provider, progress_callback);
}

int startExportJob(mlvObject_t *video, const export_options_t &options,
                   const export_fd_provider_t &provider,
                   void (*progress_callback)(int progress)) {

  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

  // Prepare audio if needed
  export_options_t effectiveOptions = options;
  if (options.include_audio && options.codec != EXPORT_CODEC_AUDIO_ONLY) {
    effectiveOptions.audio_path = write_export_audio(video, options);
  } else if (options.codec == EXPORT_CODEC_AUDIO_ONLY) {
    // Audio-only exports write WAV directly to the provided temp directory
    const std::string audioPath = write_export_audio(video, options);
    if (audioPath.empty()) {
      __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                          "Audio-only export failed: empty audio path");
      return EXPORT_ERROR_GENERIC;
    }

    if (progress_callback) {
      progress_callback(100);
    }
    return EXPORT_SUCCESS;
  }

  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

  int result = EXPORT_ERROR_GENERIC;
  switch (effectiveOptions.codec) {
  case EXPORT_CODEC_CINEMA_DNG:

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

    result =
        startExportPipe(video, effectiveOptions, provider, progress_callback);
    break;
  }

  return result;
}

// Batch export pipe - uses shared encoder context
static int startBatchExportPipe(BatchExportContext &batch_ctx,
                                mlvObject_t *video,
                                const export_options_t &options,
                                const export_fd_provider_t &provider,
                                void (*progress_callback)(int progress)) {

  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

  apply_debayer_mode(video, options);
  reset_processing_state(video);
  apply_raw_correction(video, options.raw_correction);

  if (progress_callback) {
    progress_callback(0);
  }

  // Image sequence exports don't benefit from batch context
  if (options.codec == EXPORT_CODEC_TIFF || options.codec == EXPORT_CODEC_PNG ||
      options.codec == EXPORT_CODEC_JPEG2000) {
    const char *ext;
    AVCodecID codec_id;
    AVPixelFormat dst_fmt;

    if (options.codec == EXPORT_CODEC_TIFF) {
      ext = ".tif";
      codec_id = AV_CODEC_ID_TIFF;
      dst_fmt = AV_PIX_FMT_RGB48LE;
    } else if (options.codec == EXPORT_CODEC_PNG) {
      ext = ".png";
      codec_id = AV_CODEC_ID_PNG;
      dst_fmt = (options.png_bitdepth == PNG_8BIT) ? AV_PIX_FMT_RGB24
                                                   : AV_PIX_FMT_RGB48BE;
    } else {
      ext = ".jp2";
      codec_id = AV_CODEC_ID_JPEG2000;
      dst_fmt = AV_PIX_FMT_YUV444P;
    }

    return export_image_sequence(video, options, provider, codec_id, dst_fmt,
                                 ext, progress_callback);
  }

  // Video container exports - use batch context for encoder caching
  return export_video_container_batch(batch_ctx, video, options, provider,
                                      progress_callback);
}

int startBatchExportJob(BatchExportContext &batch_ctx, mlvObject_t *video,
                        const export_options_t &options,
                        const export_fd_provider_t &provider,
                        void (*progress_callback)(int progress)) {

  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

  // Prepare audio if needed
  export_options_t effectiveOptions = options;
  if (options.include_audio && options.codec != EXPORT_CODEC_AUDIO_ONLY) {
    effectiveOptions.audio_path = write_export_audio(video, options);
  } else if (options.codec == EXPORT_CODEC_AUDIO_ONLY) {
    const std::string audioPath = write_export_audio(video, options);
    if (audioPath.empty()) {
      __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                          "Audio-only export failed: empty audio path");
      return EXPORT_ERROR_GENERIC;
    }
    if (progress_callback) {
      progress_callback(100);
    }
    return EXPORT_SUCCESS;
  }

  if (is_export_cancelled()) {
    return EXPORT_CANCELLED;
  }

  int result = EXPORT_ERROR_GENERIC;
  switch (effectiveOptions.codec) {
  case EXPORT_CODEC_CINEMA_DNG:
    // CDNG doesn't use video codec, so no batch optimization
    result =
        startExportCdng(video, effectiveOptions, provider, progress_callback);
    break;
  case EXPORT_CODEC_AUDIO_ONLY:
    __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                        "Unexpected audio-only route fallthrough");
    result = EXPORT_ERROR_GENERIC;
    break;
  default:
    // Use batch export pipe with encoder caching
    result = startBatchExportPipe(batch_ctx, video, effectiveOptions, provider,
                                  progress_callback);
    break;
  }

  return result;
}
