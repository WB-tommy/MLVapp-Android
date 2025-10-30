#include "export_handler.h"
#include <string>
#include <filesystem>
#include <iostream>
#include <iomanip>
#include <sstream>
#include <cmath>
#include "../../src/mlv/macros.h"

namespace fs = std::filesystem;

static const int kCdngNamingDefault = 0;
static const int kCdngNamingDaVinci = 1;

namespace {
inline bool approximately(float value, float target, float epsilon = 1e-3f) {
    return std::fabs(value - target) < epsilon;
}
}

int startExportCdng(
        mlvObject_t *video,
        const export_options_t &options,
        const export_fd_provider_t &provider,
        void (*progress_callback)(int progress)
) {
    if (!provider.acquire_frame_fd) {
        return -1;
    }
    if (is_export_cancelled()) {
        return EXPORT_CANCELLED;
    }
    float stretchFactorX = options.stretch_factor_x;
    if (!(stretchFactorX > 0.0f)) {
        stretchFactorX = STRETCH_H_100;
    }

    float stretchFactorY = options.stretch_factor_y;
    if (!(stretchFactorY > 0.0f)) {
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

    //Set aspect ratio of the picture
    int32_t picAR[4] = {0};

    //Set horizontal stretch
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
    //Set vertical stretch
    if (approximately(stretchFactorY, STRETCH_V_167)) {
        picAR[2] = 5;
        picAR[3] = 3;
    } else if (approximately(stretchFactorY, STRETCH_V_300)) {
        picAR[2] = 3;
        picAR[3] = 1;
    } else if (approximately(stretchFactorY, STRETCH_V_033)) {
        picAR[2] = 1;
        picAR[3] = 1;
        picAR[0] *= 3; //Upscale only
    } else {
        picAR[2] = 1;
        picAR[3] = 1;
    }


    int variant = options.cdng_variant;
    if (variant < 0 || variant > 2) {
        variant = 0;
    }

    dngObject_t *cinemaDng = initDngObject(video, variant, getMlvFramerate(video), picAR);

    uint32_t frameSize = getMlvWidth(video) * getMlvHeight(video) * 3;
    auto *imgBuffer = (uint16_t *) malloc(frameSize * sizeof(uint16_t));
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
            snprintf(
                    relativeName,
                    sizeof(relativeName),
                    "%s_1_%02i-%02i-%02i_0001_C0000_%06u.dng",
                    options.source_base_name.c_str(),
                    getMlvTmYear(video),
                    getMlvTmMonth(video),
                    getMlvTmDay(video),
                    frameNumber
            );
        } else {
            snprintf(
                    relativeName,
                    sizeof(relativeName),
                    "%s_%06u.dng",
                    options.source_base_name.c_str(),
                    frameNumber
            );
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
            progress_callback((int) (100.0f * (frame + 1) / totalFrames));
        }

        if (is_export_cancelled()) {
            freeDngObject(cinemaDng);
            return EXPORT_CANCELLED;
        }
    }

    freeDngObject(cinemaDng);

    return 0; // Success
}

static int write_export_audio(mlvObject_t *video, const export_options_t &options)
{
    if (!options.include_audio || options.audio_temp_dir.empty()) {
        return 0;
    }

    std::string wavPath = options.audio_temp_dir;
    if (!wavPath.empty() && wavPath.back() != '/') {
        wavPath.push_back('/');
    }

    if (options.naming_scheme == kCdngNamingDaVinci) {
        char buf[512];
        snprintf(buf, sizeof(buf), "%s_1_%02i-%02i-%02i_0001_C0000.wav",
                 options.source_base_name.c_str(),
                 getMlvTmYear(video), getMlvTmMonth(video), getMlvTmDay(video));
        wavPath.append(buf);
    } else {
        wavPath.append(options.source_base_name);
        wavPath.append(".wav");
    }

    writeMlvAudioToWave(video, const_cast<char *>(wavPath.c_str()));
    return 0;
}

int startExportPipe(
        mlvObject_t * /*video*/,
        const export_options_t &options,
        const export_fd_provider_t & /*provider*/,
        void (*progress_callback)(int progress)
) {
    __android_log_print(ANDROID_LOG_WARN,
                        "MLVApp-JNI",
                        "TODO: ffmpeg pipeline for codec %d option %d",
                        options.codec, options.codec_option);
    if (progress_callback) {
        progress_callback(0);
        progress_callback(100);
    }
    return -2;
}

int startExportJob(
        mlvObject_t *video,
        const export_options_t &options,
        const export_fd_provider_t &provider,
        void (*progress_callback)(int progress)
) {
    if (is_export_cancelled()) {
        return EXPORT_CANCELLED;
    }

    write_export_audio(video, options);

    if (is_export_cancelled()) {
        return EXPORT_CANCELLED;
    }

    switch (options.codec) {
        case EXPORT_CODEC_CINEMA_DNG:
            return startExportCdng(video, options, provider, progress_callback);
        case EXPORT_CODEC_AUDIO_ONLY:
            if (progress_callback) progress_callback(100);
            return 0;
        default:
            return startExportPipe(video, options, provider, progress_callback);
    }
}
