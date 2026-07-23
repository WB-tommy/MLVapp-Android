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
#include "../grading/desktop_processing_mapping.h"

extern "C" {
#include "../../src/mlv/llrawproc/darkframe.h"
#include "../../src/mlv/llrawproc/llrawproc.h"
#include "../../src/processing/raw_processing.h"
}

static const int kCdngNamingDefault = 0;
static const int kCdngNamingDaVinci = 1;

namespace {
inline bool approximately(float value, float target, float epsilon = 1e-3f) {
  return std::fabs(value - target) < epsilon;
}

double normalize_dual_iso_ev(float value) {
  if (!std::isfinite(value)) {
    return 1.0;
  }
  return value == 1.0f
             ? 1.0
             : std::clamp(static_cast<double>(value), -6.0, 0.0);
}

int normalize_dual_iso_black_delta(int value) {
  return value == -1 ? -1 : std::clamp(value, 0, 100);
}

void apply_receipt_raw_levels(mlvObject_t *video,
                              const raw_correction_options_t &opts) {
  if (video == nullptr || video->processing == nullptr) {
    return;
  }

  const int bit_depth = getMlvBitdepth(video);
  if (bit_depth <= 0 || bit_depth > 16) {
    return;
  }

  const int maximum_level = static_cast<int>((1u << bit_depth) - 1u);
  if (opts.dual_iso_black < 0 ||
      opts.dual_iso_white <= opts.dual_iso_black ||
      opts.dual_iso_white > maximum_level) {
    return;
  }

  /* These legacy receipt fields are the clip's general RAW levels. Keep RAWI,
   * post-demosaic processing, and per-frame DNG defaults in one transaction so
   * Dual ISO analyzes the same level range used by live preview. */
  pthread_mutex_lock(&video->processing_mutex);
  setMlvBlackLevel(video, opts.dual_iso_black);
  setMlvWhiteLevel(video, opts.dual_iso_white);
  processingSetBlackAndWhiteLevel(video->processing, opts.dual_iso_black,
                                  opts.dual_iso_white, bit_depth);
  llrpResetDngBWLevels(video);
  pthread_mutex_unlock(&video->processing_mutex);
}

int available_export_dark_frame_mode(mlvObject_t *video, int requested_mode) {
  const int mode = std::clamp(
      requested_mode, static_cast<int>(DF_OFF), static_cast<int>(DF_INT));
  if (mode == DF_EXT &&
      (video->llrawproc->dark_frame_data == nullptr ||
       video->llrawproc->dark_frame_data_source != DF_EXT)) {
    /* A display name is not a reopenable SAF source. Never tell Dual ISO that
     * subtraction happened when this export object has no decoded frame. */
    return DF_OFF;
  }
  if (mode == DF_INT && video->DARK.blockType[0] == '\0') {
    return DF_OFF;
  }
  return mode;
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
  llrpComputeStripesOn(video);
  video->current_cached_frame_active = 0;
}

// Apply all raw correction settings from the options struct
void apply_raw_correction(mlvObject_t *video,
                          const raw_correction_options_t &opts) {

  /* Live receipt restore applies these levels even when the correction stack
   * is disabled, because they also drive ordinary image normalization. */
  apply_receipt_raw_levels(video, opts);

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

  // Dark-frame changes re-arm automatic exposure matching. Apply this first
  // so the receipt's finalized Dual ISO values remain authoritative.
  llrpSetDarkFrameMode(
      video, available_export_dark_frame_mode(video, opts.dark_frame_enabled));
  // Note: An external dark-frame path must be supplied through the dedicated
  // SAF descriptor flow; dark_frame_file_name is display metadata only here.

  // Apply the complete upstream Dual ISO strategy in one transaction.  Mode 2
  // existed in older Android receipts as "Preview" even though upstream no
  // longer implements that path; preserve those receipts as HQ mode.
  llrpDualIsoConfig_t dual_iso_config = {};
  dual_iso_config.mode =
      (opts.dual_iso == DISO_20BIT || opts.dual_iso == DISO_FAST)
          ? DISO_20BIT
          : DISO_OFF;
  dual_iso_config.force = opts.dual_iso_forced ? 1 : 0;
  dual_iso_config.pattern = std::clamp(
      opts.dual_iso_pattern, static_cast<int>(DISO_PATTERN_AUTO),
      static_cast<int>(DISO_PATTERN_AUTO_EVERY_FRAME));
  dual_iso_config.match_method =
      opts.dual_iso_match_method == DISO_MATCH_HISTOGRAM
          ? DISO_MATCH_HISTOGRAM
          : DISO_MATCH_ISO;
  dual_iso_config.ev_correction =
      normalize_dual_iso_ev(opts.dual_iso_ev_correction);
  dual_iso_config.black_delta =
      normalize_dual_iso_black_delta(opts.dual_iso_black_delta);
  dual_iso_config.interpolation = std::clamp(
      opts.dual_iso_interpolation, static_cast<int>(DISOI_AMAZE),
      static_cast<int>(DISOI_MEAN23));
  dual_iso_config.alias_map = opts.dual_iso_alias_map ? 1 : 0;
  dual_iso_config.fullres_blending = opts.dual_iso_fr_blending ? 1 : 0;
  if (llrpSetDualIsoConfig(video, &dual_iso_config) != 0) {
    __android_log_print(ANDROID_LOG_ERROR, "ExportHandler",
                        "Rejected normalized Dual ISO export settings");
  }

}

// Apply all color grading settings to the processing engine.
// Order matches live preview: exposure → profile → overrides → matrix/WB →
// EXR/AgX → adjustments
void apply_color_grading(mlvObject_t *video,
                         const color_grading_options_t &opts) {
  processingObject_t *processing = video->processing;
  if (!processing) return;

  // 1. Exposure
  desktop_processing::setExposure(processing, opts.exposure);

  // 2. Image profile (sets gamut, transfer function, tonemap, creative adj)
  //    profile_index 0 = "Select Preset..." (no profile), 1-13 = actual profiles
  if (opts.profile_index > 0) {
    processingSetImageProfile(processing, opts.profile_index - 1);
  }

  // 3. Overrides (applied after profile, in case user changed them independently)
  processingSetTonemappingFunction(processing, opts.tonemap);
  processingSetTransferFunction(processing, const_cast<char*>(opts.transfer_function.c_str()));
  processingSetGamut(processing, opts.gamut);
  if (opts.allow_creative_adjustments) {
    processingAllowCreativeAdjustments(processing);
  } else {
    processingDontAllowCreativeAdjustments(processing);
  }

  // 4. Camera matrix
  switch (opts.cam_matrix_used) {
  case 0:
    processingDontUseCamMatrix(processing);
    break;
  case 1:
    processingUseCamMatrix(processing);
    break;
  case 2:
    processingUseCamMatrixDanne(processing);
    break;
  default:
    break;
  }

  // Gamut and camera-matrix mode both affect the WB-derived matrices.
  desktop_processing::setWhiteBalance(processing, opts.temperature, opts.tint);

  // 5. EXR mode (Cyan Highlight Fix)
  if (opts.exr_mode) {
    processingEnableExr(processing);
  } else {
    processingDisableExr(processing);
  }

  // 6. AgX rendering transform
  if (opts.agx) {
    processingEnableAgX(processing);
  } else {
    processingDisableAgX(processing);
  }

  // 7. Contrast & pivot
  desktop_processing::setContrast(processing, opts.contrast);
  desktop_processing::setPivot(processing, opts.pivot);

  // 8. Saturation & vibrance
  desktop_processing::setSaturation(processing, opts.saturation);
  desktop_processing::setVibrance(processing, opts.vibrance);

  // 9. Clarity
  desktop_processing::setClarity(processing, opts.clarity);

  // 10. Shadows & highlights
  desktop_processing::setShadows(processing, opts.shadows);
  desktop_processing::setHighlights(processing, opts.highlights);

  // 11. Contrast curve parameters (dark/light strength and range)
  desktop_processing::setDarkStrength(processing, opts.ds);
  desktop_processing::setDarkRange(processing, opts.dr);
  desktop_processing::setLightStrength(processing, opts.ls);
  desktop_processing::setLightRange(processing, opts.lr);
  desktop_processing::setLightening(processing, opts.lightening);

  // 12. Sharpening
  processingSetSharpening(processing, (double)opts.sharpen / 100.0);
  processingSetSharpenMasking(processing, opts.sharpen_masking);

  // 13. Chroma blur
  if (opts.chroma_blur > 0) {
    processingEnableChromaSeparation(processing);
    processingSetChromaBlurRadius(processing, opts.chroma_blur);
  }

  // 14. Highlight reconstruction
  desktop_processing::setHighlightReconstruction(
      processing, opts.highlight_reconstruction != 0);

  // 15. Chroma separation
  if (opts.chroma_separation) {
    processingEnableChromaSeparation(processing);
  }
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
  llrpComputeStripesOn(video);
  video->current_cached_frame_active = 0;
  // Apply raw correction settings (replaces enable_raw_fixes check)
  apply_raw_correction(video, options.raw_correction);
  // Apply color grading settings (exposure, WB, profile, etc.)
  apply_color_grading(video, options.color_grading);
  // Reset FPM/BPM status AFTER settings are applied to trigger map loading
  llrpResetFpmStatus(video);
  llrpResetBpmStatus(video);

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

  // Resolve cut range (0-based: [startFrame, endFrame))
  uint32_t startFrame = 0, endFrame = totalFrames;
  resolve_cut_range(options, totalFrames, startFrame, endFrame);
  const uint32_t framesToExport = endFrame - startFrame;

  char relativeName[512] = {0};

  for (uint32_t frame = startFrame; frame < endFrame; frame++) {
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
      progress_callback((int)(100.0f * (frame - startFrame + 1) / framesToExport));
    }

    if (is_export_cancelled()) {
      freeDngObject(cinemaDng);
      return EXPORT_CANCELLED;
    }
  }

  freeDngObject(cinemaDng);

  return 0; // Success
}

static bool has_exportable_audio(mlvObject_t *video) {
  return doesMlvHaveAudio(video) && getMlvAudioData(video) != nullptr &&
         getMlvAudioSize(video) > 0;
}

static std::string write_export_audio(mlvObject_t *video,
                                      const export_options_t &options) {
  if (!options.include_audio || options.audio_temp_dir.empty() ||
      !has_exportable_audio(video)) {
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

  // Never treat a stale artifact from an earlier attempt as this export's audio.
  unlink(wavPath.c_str());

  // Normalize cut markers using the same logic as video export
  uint32_t startFrame, endFrame;
  resolve_cut_range(options, getMlvFrames(video), startFrame, endFrame);
  const bool hasCutMarks = (startFrame > 0) || (endFrame < getMlvFrames(video));
  if (hasCutMarks) {
    // Convert back to 1-based for writeMlvAudioToWaveCut
    writeMlvAudioToWaveCut(video, const_cast<char *>(wavPath.c_str()),
                           startFrame + 1, endFrame);
  } else {
    writeMlvAudioToWave(video, const_cast<char *>(wavPath.c_str()));
  }

  struct stat audioStat = {};
  if (stat(wavPath.c_str(), &audioStat) != 0 || audioStat.st_size <= 0) {
    unlink(wavPath.c_str());
    return {};
  }
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
  // Apply color grading settings (exposure, WB, profile, etc.)
  apply_color_grading(video, options.color_grading);
  // Reset FPM/BPM status AFTER settings are applied to trigger map loading
  llrpResetFpmStatus(video);
  llrpResetBpmStatus(video);

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
    if (doesMlvHaveAudio(video) && effectiveOptions.audio_path.empty()) {
      return EXPORT_ERROR_IO;
    }
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
  // Apply color grading settings (exposure, WB, profile, etc.)
  apply_color_grading(video, options.color_grading);
  // Reset FPM/BPM status AFTER settings are applied to trigger map loading
  llrpResetFpmStatus(video);
  llrpResetBpmStatus(video);

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
    if (doesMlvHaveAudio(video) && effectiveOptions.audio_path.empty()) {
      return EXPORT_ERROR_IO;
    }
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
