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
    int dual_iso = 0;                 // 0=Off, 1=HQ; legacy 2 is normalized to HQ
    bool dual_iso_forced = false;     // Override missing DISO metadata
    int dual_iso_pattern = 0;         // 0=Auto, 1..4=fixed, 5=Auto every frame
    int dual_iso_match_method = 1;    // 1=ISO metadata, 2=Histogram
    float dual_iso_ev_correction = 1.0f; // 1=Auto; manual EV from -6.0 to 0.0
    int dual_iso_black_delta = -1;    // -1=Auto; manual 0..100
    int dual_iso_interpolation = 0;   // 0=AMaZE-edge, 1=Mean23
    bool dual_iso_alias_map = false;  // Upstream default; preserve explicit receipts
    bool dual_iso_fr_blending = true; // Full-resolution blending on/off
    int dual_iso_white = 65013;       // Legacy raw white level, not black delta
    int dual_iso_black = 4096;        // Legacy raw black level, not black delta
    std::string dark_frame_file_name; // Dark frame file path
    int dark_frame_enabled = 0;       // 0=Off, 1=Ext, 2=Int
};

#endif // MLVAPP_RAW_CORRECTION_OPTIONS_H
