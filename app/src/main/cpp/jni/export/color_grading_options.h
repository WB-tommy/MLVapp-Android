//
// Created by Sungmin Choi on 2026. 3. 10..
//
// Color grading options for export.
// Mirrors Kotlin's ColorGradingSettings data class.
//

#ifndef MLVAPP_COLOR_GRADING_OPTIONS_H
#define MLVAPP_COLOR_GRADING_OPTIONS_H

#include <string>

struct color_grading_options_t {
    // Basic adjustments
    float exposure = 0.0f;
    int contrast = 0;
    int pivot = 75;
    int temperature = 6500;
    int tint = 0;
    int saturation = 0;
    int vibrance = 0;
    int clarity = 0;

    // Shadows/Highlights
    int shadows = 0;
    int highlights = 0;
    int ds = 20;        // Dark strength (0-100)
    int dr = 70;        // Dark range (0-100)
    int ls = 0;         // Light strength (0-100)
    int lr = 50;        // Light range (0-100)
    int lightening = 0;

    // Processing options
    int sharpen = 0;
    int sharpen_masking = 0;
    int chroma_blur = 0;
    int highlight_reconstruction = 0;
    int cam_matrix_used = 0;
    int chroma_separation = 0;

    // Profile preset (0 = "Select Preset...", 1-12 = actual presets)
    int profile_index = 0;

    // Tone mapping
    int tonemap = 1;
    std::string transfer_function = "(x < 0.0) ? 0 : pow(x / (1.0 + x), 1/3.15)";
    int gamut = 0;
    int gamma = 315;
    int allow_creative_adjustments = 1;

    // Advanced
    int exr_mode = 0;
    int agx = 1;
};

#endif //MLVAPP_COLOR_GRADING_OPTIONS_H
