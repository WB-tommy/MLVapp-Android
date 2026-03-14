//
// Gamut-aware FFmpeg color space tag resolution.
//
// Maps the processing pipeline's gamut index (GAMUT_* from raw_processing.h)
// and profile index to the correct FFmpeg color metadata enums and swscale
// YCbCr matrix constant.
//
// SRP: This file is solely responsible for the gamut-to-FFmpeg-enum mapping.
//

#ifndef MLVAPP_FFMPEG_COLOR_TAGS_H
#define MLVAPP_FFMPEG_COLOR_TAGS_H

#include <string>

extern "C" {
#include "libavutil/pixfmt.h"
#include "libswscale/swscale.h"
}

// Includes GAMUT_* constants (0–11)
extern "C" {
#include "../../src/processing/raw_processing.h"
}

/**
 * Resolved FFmpeg color tags for a given gamut + profile combination.
 */
struct ffmpeg_color_tags_t {
  AVColorPrimaries  color_primaries;
  AVColorTransferCharacteristic color_trc;
  AVColorSpace      colorspace;     // YCbCr matrix coefficients
  AVColorRange      color_range;
  int               sws_matrix;     // For sws_getCoefficients()
};

/**
 * Profile indices (matching image_profiles.c array order).
 * Kept for reference but NO LONGER used for TRC resolution.
 */
enum {
  PROFILE_IDX_STANDARD      = 0,
  PROFILE_IDX_TONEMAPPED    = 1,
  PROFILE_IDX_FILM          = 2,
  PROFILE_IDX_ALEXA_LOG     = 3,
  PROFILE_IDX_CINEON_LOG    = 4,
  PROFILE_IDX_SONY_LOG3     = 5,
  PROFILE_IDX_LINEAR        = 6,
  PROFILE_IDX_SRGB          = 7,
  PROFILE_IDX_REC709        = 8,
  PROFILE_IDX_DAVINCI_WG    = 9,
  PROFILE_IDX_FUJI_FLOG     = 10,
  PROFILE_IDX_CANON_LOG     = 11,
  PROFILE_IDX_PANASONIC_VLOG = 12,
};

/**
 * Resolve the transfer characteristic from the actual TONEMAP_* enum.
 * This is the ground truth — it reflects what the processing engine
 * is actually applying, regardless of whether a preset was selected.
 */
inline AVColorTransferCharacteristic resolve_color_trc_from_tonemap(int tonemap, const std::string& transfer_function) {
  if (tonemap == TONEMAP_None) {
    if (transfer_function == "x") {
      return AVCOL_TRC_LINEAR;
    }
    // If not "x", it's either a proprietary log curve (like Fuji F-Log)
    // or a custom user curve. Tag as unspecified to avoid misleading NLEs.
    return AVCOL_TRC_UNSPECIFIED;
  }

  switch (tonemap) {
    // Standard gamma / Reinhard / Tangent — output is BT.709-like
    case TONEMAP_Reinhard:
    case TONEMAP_Tangent:
    case TONEMAP_Reinhard_3_5:
    case TONEMAP_Rec709:
      return AVCOL_TRC_BT709;

    case TONEMAP_sRGB:
      return AVCOL_TRC_IEC61966_2_1;  // sRGB

    case TONEMAP_HLG:
      return AVCOL_TRC_ARIB_STD_B67;  // HLG

    // Log curves — no exact FFmpeg match for proprietary log curves
    case TONEMAP_AlexaLogC:
    case TONEMAP_CineonLog:
    case TONEMAP_SonySLog:
    case TONEMAP_DavinciIntermediate:
    case TONEMAP_CanonLog:
    case TONEMAP_PanasonicVLog:
      return AVCOL_TRC_UNSPECIFIED;

    default:
      return AVCOL_TRC_BT709;
  }
}

/**
 * Resolve all FFmpeg color tags from the processing gamut and tonemap.
 *
 * @param gamut    GAMUT_* index from raw_processing.h (0–11)
 * @param transfer_function The parsed string containing the math evaluated by the engine
 */
inline ffmpeg_color_tags_t resolve_color_tags(int gamut, int tonemap, const std::string& transfer_function) {
  ffmpeg_color_tags_t tags{};
  tags.color_range = AVCOL_RANGE_MPEG;  // Limited range — industry standard for YUV

  // Determine transfer characteristic from the actual tonemap + transfer_function combo
  tags.color_trc = resolve_color_trc_from_tonemap(tonemap, transfer_function);

  // Map gamut to primaries, colorspace (YCbCr matrix), and sws matrix
  switch (gamut) {
    case GAMUT_Rec709:
      tags.color_primaries = AVCOL_PRI_BT709;
      tags.colorspace      = AVCOL_SPC_BT709;
      tags.sws_matrix      = SWS_CS_ITU709;
      break;

    case GAMUT_Rec2020:
      tags.color_primaries = AVCOL_PRI_BT2020;
      tags.colorspace      = AVCOL_SPC_BT2020_NCL;
      tags.sws_matrix      = SWS_CS_BT2020;
      break;

    // Wide gamuts with no exact FFmpeg match:
    // Primaries/colorspace → unspecified (honest metadata).
    // sws matrix → BT.709 (safest default for post-production).
    case GAMUT_ACES_AP0:
    case GAMUT_AdobeRGB:
    case GAMUT_ProPhotoRGB:
    case GAMUT_XYZ:
    case GAMUT_AlexaWideGamutRGB:
    case GAMUT_SonySGamut3:
    case GAMUT_DavinciWideGamut:
    case GAMUT_ACES_AP1:
    case GAMUT_Canon_Cinema:
    case GAMUT_PanasonivV:
      tags.color_primaries = AVCOL_PRI_UNSPECIFIED;
      tags.colorspace      = AVCOL_SPC_UNSPECIFIED;
      tags.sws_matrix      = SWS_CS_ITU709;
      break;

    default:
      // Fallback to BT.709
      tags.color_primaries = AVCOL_PRI_BT709;
      tags.colorspace      = AVCOL_SPC_BT709;
      tags.sws_matrix      = SWS_CS_ITU709;
      break;
  }

  // For unspecified primaries, force TRC to unspecified too
  // (a file with "unknown primaries + BT.709 TRC" is misleading)
  if (tags.color_primaries == AVCOL_PRI_UNSPECIFIED) {
    tags.color_trc = AVCOL_TRC_UNSPECIFIED;
  }

  return tags;
}

/**
 * Apply sws_setColorspaceDetails to set the correct RGB→YUV conversion matrix.
 * Call this immediately after sws_getContext().
 *
 * @param sws_ctx  The SwsContext from sws_getContext()
 * @param tags     Resolved color tags (for sws_matrix)
 */
inline void apply_sws_color_matrix(SwsContext *sws_ctx,
                                   const ffmpeg_color_tags_t &tags) {
  if (!sws_ctx) return;

  const int *dst_coeffs = sws_getCoefficients(tags.sws_matrix);
  // src: RGB is always full range, use default coefficients
  // dst: YUV limited range (MPEG), with the gamut-appropriate matrix
  sws_setColorspaceDetails(sws_ctx,
      sws_getCoefficients(SWS_CS_DEFAULT), 1,   // src: full range
      dst_coeffs, 0,                              // dst: limited range
      0, 1 << 16, 1 << 16);                      // brightness/contrast/saturation defaults
}

#endif // MLVAPP_FFMPEG_COLOR_TAGS_H
