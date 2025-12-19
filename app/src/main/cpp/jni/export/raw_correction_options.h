/**
 * Raw correction options for export
 * Mirrors Kotlin's RawCorrectionSettings data class
 */

#ifndef MLVAPP_RAW_CORRECTION_OPTIONS_H
#define MLVAPP_RAW_CORRECTION_OPTIONS_H

#include <string>

struct raw_correction_options_t {
    bool enabled = true;
    int vertical_stripes = 0;         // 0=Off, 1=Normal, 2=Force
    int focus_pixels = 0;             // 0=Off, 1=On, 2=CropRec
    int fpi_method = 0;               // Focus pixel interpolation method
    int bad_pixels = 0;               // 0=Off, 1=Auto, 2=Force, 3=Map
    int bps_method = 0;               // Bad pixel search method
    int bpi_method = 0;               // Bad pixel interpolation method
    int chroma_smooth = 0;            // 0=Off, 2=2x2, 3=3x3, 5=5x5
    int pattern_noise = 0;            // Fix pattern noise (0, 1)
    int deflicker_target = 0;         // Deflicker value
    int dual_iso = 0;                 // 0=Off, 1=On, 2=Preview
    bool dual_iso_forced = false;     // Override ISO detection
    int dual_iso_interpolation = 0;   // 0=Amaze, 1=Mean23
    bool dual_iso_alias_map = true;   // Alias map on/off
    bool dual_iso_fr_blending = true; // Fullres blending on/off
    int dual_iso_white = 65013;       // Dual ISO white level
    int dual_iso_black = 4096;        // Dual ISO black level
    std::string dark_frame_file_name; // Dark frame file path
    int dark_frame_enabled = 0;       // 0=Off, 1=Ext, 2=Int
};

#endif // MLVAPP_RAW_CORRECTION_OPTIONS_H
