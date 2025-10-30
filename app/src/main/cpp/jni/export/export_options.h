#ifndef MLVAPP_EXPORT_OPTIONS_H
#define MLVAPP_EXPORT_OPTIONS_H

#include <string>

enum export_codec_t {
    EXPORT_CODEC_CINEMA_DNG = 0,
    EXPORT_CODEC_PRORES = 1,
    EXPORT_CODEC_H264 = 2,
    EXPORT_CODEC_H265 = 3,
    EXPORT_CODEC_TIFF = 4,
    EXPORT_CODEC_PNG = 5,
    EXPORT_CODEC_AUDIO_ONLY = 6
};

struct export_options_t {
    int codec = EXPORT_CODEC_CINEMA_DNG;
    int codec_option = 0;
    int naming_scheme = 0;
    int cdng_variant = 0;
    bool include_audio = true;
    bool enable_raw_fixes = true;
    bool frame_rate_override = false;
    float frame_rate_value = 0.f;
    std::string source_file_name;
    std::string source_base_name;
    std::string clip_uri_path;
    std::string audio_temp_dir;
    float stretch_factor_x = 1.0f;
    float stretch_factor_y = 1.0f;
};

#endif // MLVAPP_EXPORT_OPTIONS_H
