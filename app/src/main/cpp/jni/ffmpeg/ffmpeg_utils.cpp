#include "../export/export_handler.h"
#include "../ffmpeg/ffmpeg_presets.h"
#include "../ffmpeg/ffmpeg_color_tags.h"
#include "../utils.h"
#include <algorithm>
#include <cstring>
#include <jni.h>
#include <string>
#include <vector>

static const char *LOG_TAG = "FFmpegUtils";

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/hwcontext.h"
#include "libavutil/opt.h"
#include "libavutil/pixdesc.h"
}

bool encoder_candidate_uses_hw_frames(const EncoderCandidate &candidate) {
    return candidate.hw_device_type != AV_HWDEVICE_TYPE_NONE &&
           candidate.hw_pixel_format != AV_PIX_FMT_NONE;
}

bool codec_context_uses_hw_frames(const AVCodecContext *codec_ctx) {
    return codec_ctx && codec_ctx->hw_frames_ctx != nullptr;
}

AVPixelFormat codec_context_upload_format(const AVCodecContext *codec_ctx) {
    if (!codec_context_uses_hw_frames(codec_ctx)) {
        return codec_ctx ? codec_ctx->pix_fmt : AV_PIX_FMT_NONE;
    }

    auto *frames_ctx =
            reinterpret_cast<AVHWFramesContext *>(codec_ctx->hw_frames_ctx->data);
    return frames_ctx ? frames_ctx->sw_format : AV_PIX_FMT_NONE;
}

static int configure_hw_frames(AVCodecContext *codec_ctx,
                               const EncoderCandidate &candidate,
                               AVPixelFormat sw_format,
                               int width,
                               int height) {
    AVBufferRef *device_ref = nullptr;
    AVBufferRef *frames_ref = nullptr;

    int ret = av_hwdevice_ctx_create(&device_ref, candidate.hw_device_type,
                                     nullptr, nullptr, 0);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGW(LOG_TAG, "Failed to create %s device for '%s': %s",
             av_hwdevice_get_type_name(candidate.hw_device_type),
             candidate.name.c_str(), errbuf);
        return ret;
    }

    frames_ref = av_hwframe_ctx_alloc(device_ref);
    if (!frames_ref) {
        av_buffer_unref(&device_ref);
        return AVERROR(ENOMEM);
    }

    auto *frames_ctx = reinterpret_cast<AVHWFramesContext *>(frames_ref->data);
    frames_ctx->format = candidate.hw_pixel_format;
    frames_ctx->sw_format = sw_format;
    frames_ctx->width = width;
    frames_ctx->height = height;
    frames_ctx->initial_pool_size = 4;

    ret = av_hwframe_ctx_init(frames_ref);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGW(LOG_TAG, "Failed to initialize %s frames for '%s': %s",
             av_hwdevice_get_type_name(candidate.hw_device_type),
             candidate.name.c_str(), errbuf);
        av_buffer_unref(&frames_ref);
        av_buffer_unref(&device_ref);
        return ret;
    }

    codec_ctx->hw_device_ctx = device_ref;
    codec_ctx->hw_frames_ctx = frames_ref;
    return 0;
}

static void apply_software_encoder_options(AVCodecContext *codec_ctx,
                                           const VideoPreset &preset) {
    if (!preset.crf.empty())
        av_opt_set(codec_ctx->priv_data, "crf", preset.crf.c_str(), 0);
    if (!preset.preset.empty())
        av_opt_set(codec_ctx->priv_data, "preset", preset.preset.c_str(), 0);
    if (!preset.profile_opt.empty()) {
        av_opt_set(codec_ctx->priv_data, "profile", preset.profile_opt.c_str(),
                   0);
    }
    if (preset.codec_id == AV_CODEC_ID_VP9 && preset.crf == "0") {
        av_opt_set(codec_ctx->priv_data, "lossless", "1", 0);
    }

    if (preset.codec_id != AV_CODEC_ID_HEVC) {
        return;
    }

    if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV444P12LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=12:profile=main444-12", 0);
    } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV422P12LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=12:profile=main422-12", 0);
    } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV420P12LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=12:profile=main12", 0);
    } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV444P10LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=10:profile=main444-10", 0);
    } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV422P10LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=10:profile=main422-10", 0);
    } else if (codec_ctx->pix_fmt == AV_PIX_FMT_YUV420P10LE) {
        av_opt_set(codec_ctx->priv_data, "x265-params",
                   "output-depth=10:profile=main10", 0);
    }
}

int configure_video_codec_context(AVCodecContext *codec_ctx,
                                  const VideoPreset &preset,
                                  const EncoderCandidate &candidate,
                                  int width, int height, AVRational fps,
                                  int thread_count, AVFormatContext *fmt_ctx,
                                  int gamut, int tonemap,
                                  const std::string &transfer_function) {
    if (!codec_ctx || !fmt_ctx || !fmt_ctx->oformat) {
        return AVERROR(EINVAL);
    }

    auto color_tags = resolve_color_tags(gamut, tonemap, transfer_function);
    codec_ctx->width = width;
    codec_ctx->height = height;
    codec_ctx->time_base = av_inv_q(fps);
    codec_ctx->framerate = fps;
    codec_ctx->gop_size = preset.gop;
    codec_ctx->max_b_frames = preset.max_b_frames;
    codec_ctx->bit_rate = preset.bit_rate;

    if (codec_ctx->bit_rate == 0 && preset.codec_id == AV_CODEC_ID_DNXHD) {
        if (preset.dnxhd_profile >= 0) {
            codec_ctx->bit_rate = default_dnxhd_bitrate(
                    codec_ctx->width, codec_ctx->height, fps, preset.dnxhd_profile);
        } else if (!preset.profile_opt.empty()) {
            codec_ctx->bit_rate = default_dnxhr_bitrate(
                    codec_ctx->width, codec_ctx->height, preset.profile_opt);
        }
    }

    if (encoder_candidate_uses_hw_frames(candidate)) {
        codec_ctx->max_b_frames = 0;
        codec_ctx->pix_fmt = candidate.hw_pixel_format;
        int ret = configure_hw_frames(codec_ctx, candidate, preset.pixel_format,
                                      codec_ctx->width, codec_ctx->height);
        if (ret < 0) {
            return ret;
        }
    } else if (candidate.is_hardware) {
        codec_ctx->max_b_frames = 0;
        if (preset.pixel_format == AV_PIX_FMT_YUV420P)
            codec_ctx->pix_fmt = AV_PIX_FMT_NV12;
        if (preset.pixel_format == AV_PIX_FMT_YUV420P10LE)
            codec_ctx->pix_fmt = AV_PIX_FMT_P010LE;
        if (preset.pixel_format == AV_PIX_FMT_YUV444P12LE)
            codec_ctx->pix_fmt = AV_PIX_FMT_MEDIACODEC;
        if (codec_ctx->pix_fmt == AV_PIX_FMT_NONE)
            codec_ctx->pix_fmt = preset.pixel_format;
        if (codec_ctx->bit_rate == 0) {
            int64_t pixels =
                    static_cast<int64_t>(codec_ctx->width) * codec_ctx->height;
            int64_t base_pixels = 1920LL * 1080;
            double scale_factor = static_cast<double>(pixels) / base_pixels;
            double quality_factor = 1.0;
            if (!preset.crf.empty()) {
                int crf_val = std::stoi(preset.crf);
                quality_factor = (crf_val <= 18) ? 1.5 : 1.0;
            }
            codec_ctx->bit_rate =
                    static_cast<int64_t>(8000000 * scale_factor * quality_factor);
            if (codec_ctx->bit_rate < 1000000)
                codec_ctx->bit_rate = 1000000;
        }
        codec_ctx->rc_max_rate = codec_ctx->bit_rate;
        codec_ctx->rc_buffer_size = codec_ctx->bit_rate;
        if (codec_ctx->width % 2 != 0)
            codec_ctx->width++;
        if (codec_ctx->height % 2 != 0)
            codec_ctx->height++;
    } else {
        codec_ctx->pix_fmt = preset.pixel_format;
        codec_ctx->thread_count = std::max(1, thread_count);
    }

    if (codec_ctx->pix_fmt == AV_PIX_FMT_P010LE) {
        codec_ctx->profile = AV_PROFILE_HEVC_MAIN_10;
    } else if (preset.profile != AV_PROFILE_UNKNOWN) {
        codec_ctx->profile = preset.profile;
    }
    codec_ctx->color_primaries = color_tags.color_primaries;
    codec_ctx->color_trc = color_tags.color_trc;
    codec_ctx->colorspace = color_tags.colorspace;
    codec_ctx->color_range = color_tags.color_range;

    if (fmt_ctx->oformat->flags & AVFMT_GLOBALHEADER) {
        codec_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    if (!candidate.is_hardware) {
        apply_software_encoder_options(codec_ctx, preset);
    }

    return 0;
}

int prepare_encoder_input_frame(AVCodecContext *codec_ctx,
                                AVFrame *software_frame,
                                AVFrame *hardware_frame,
                                AVFrame **encoder_frame) {
    if (!encoder_frame || !software_frame) {
        return AVERROR(EINVAL);
    }

    if (!codec_context_uses_hw_frames(codec_ctx)) {
        *encoder_frame = software_frame;
        return 0;
    }

    if (!hardware_frame) {
        return AVERROR(EINVAL);
    }

    av_frame_unref(hardware_frame);
    int ret = av_hwframe_get_buffer(codec_ctx->hw_frames_ctx, hardware_frame, 0);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Failed to allocate hardware upload frame: %s", errbuf);
        return ret;
    }

    ret = av_hwframe_transfer_data(hardware_frame, software_frame, 0);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Failed to upload frame to hardware encoder: %s", errbuf);
        av_frame_unref(hardware_frame);
        return ret;
    }

    ret = av_frame_copy_props(hardware_frame, software_frame);
    if (ret < 0) {
        av_frame_unref(hardware_frame);
        return ret;
    }

    *encoder_frame = hardware_frame;
    return 0;
}

// Implementation of try_open_encoder_with_fallback
AVCodecContext *try_open_encoder_with_fallback(const VideoPreset &preset,
                                               int width, int height,
                                               AVRational fps, int thread_count,
                                               AVFormatContext *fmt_ctx,
                                               AVStream *stream,
                                               int gamut,
                                               int tonemap,
                                               const std::string& transfer_function) {
    AVCodecContext *codec_ctx = nullptr;
    for (size_t i = 0; i < preset.encoder_candidates.size(); ++i) {
        const auto &candidate = preset.encoder_candidates[i];

        const AVCodec *codec = avcodec_find_encoder_by_name(candidate.name.c_str());
        if (!codec) {
            LOGW(LOG_TAG, "[%zu/%zu] Encoder '%s' not found, skipping...", i + 1,
                 preset.encoder_candidates.size(), candidate.name.c_str());
            continue;
        }

        codec_ctx = avcodec_alloc_context3(codec);
        if (!codec_ctx)
            continue;

        codec_ctx->codec_id = codec->id;
        int cfg_ret = configure_video_codec_context(
                codec_ctx, preset, candidate, width, height, fps, thread_count,
                fmt_ctx, gamut, tonemap, transfer_function);
        if (cfg_ret < 0) {
            char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
            av_strerror(cfg_ret, errbuf, sizeof(errbuf));
            LOGW(LOG_TAG, "[%zu/%zu] Encoder '%s' configuration failed: %s",
                 i + 1, preset.encoder_candidates.size(), candidate.name.c_str(),
                 errbuf);
            avcodec_free_context(&codec_ctx);
            codec_ctx = nullptr;
            continue;
        }

        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        int ret = avcodec_open2(codec_ctx, codec, nullptr);
        if (ret < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE(LOG_TAG, "✗ FAILED: Encoder '%s' failed to open: %s (code: %d)",
                 candidate.name.c_str(), errbuf, ret);
            // Log possible causes based on error code
            if (ret == -22) { // EINVAL
                LOGE(LOG_TAG,
                     "  Possible cause: Invalid argument - check pixel format, "
                     "resolution, or profile compatibility");
            } else if (ret == -1) {
                LOGE(LOG_TAG, "  Possible cause: Operation not permitted or hardware "
                              "encoder not available");
            }
            avcodec_free_context(&codec_ctx);
            codec_ctx = nullptr;
            continue;
        }

        if (avcodec_parameters_from_context(stream->codecpar, codec_ctx) < 0) {
            avcodec_free_context(&codec_ctx);
            codec_ctx = nullptr;
            continue;
        }
        stream->time_base = codec_ctx->time_base;
        return codec_ctx;
    }

    if (!preset.allow_generic_fallback) {
        return nullptr;
    }

    // Generic fallback
    const AVCodec *fallback_codec = avcodec_find_encoder(preset.codec_id);
    if (fallback_codec) {
        codec_ctx = avcodec_alloc_context3(fallback_codec);
        if (codec_ctx) {
            EncoderCandidate fallback_candidate{fallback_codec->name, false};
            codec_ctx->codec_id = fallback_codec->id;
            int cfg_ret = configure_video_codec_context(
                    codec_ctx, preset, fallback_candidate, width, height, fps,
                    thread_count, fmt_ctx, gamut, tonemap, transfer_function);
            if (cfg_ret < 0) {
                avcodec_free_context(&codec_ctx);
                return nullptr;
            }

            if (avcodec_open2(codec_ctx, fallback_codec, nullptr) == 0) {
                if (avcodec_parameters_from_context(stream->codecpar, codec_ctx) >= 0) {
                    stream->time_base = codec_ctx->time_base;
                    return codec_ctx;
                }
            }
            avcodec_free_context(&codec_ctx);
        }
    }
    return nullptr;
}

// Test encoder configuration for diagnostics
bool test_encoder_configuration(const export_options_t &originalOptions) {
    // Make a copy and force enable hardware
    export_options_t options = originalOptions;

    LOGI(LOG_TAG, "Running encoder test with forced hardware flags...");

    VideoPreset preset = select_video_preset(options);

    // Setup Dummy Context
    AVFormatContext *fmt_ctx = avformat_alloc_context();
    if (!fmt_ctx)
        return false;

    const char *container_format = preset.container_format.c_str();
    // Fallback if empty
    if (preset.container_format.empty())
        container_format = "mp4";

    fmt_ctx->oformat = av_guess_format(container_format, NULL, NULL);
    if (!fmt_ctx->oformat) {
        // Try generic mpeg fallback
        fmt_ctx->oformat = av_guess_format("mp4", NULL, NULL);
    }

    if (!fmt_ctx->oformat) {
        LOGE(LOG_TAG, "Failed to guess output format for container: %s",
             container_format);
        avformat_free_context(fmt_ctx);
        return false;
    }

    AVStream *stream = avformat_new_stream(fmt_ctx, NULL);
    if (!stream) {
        LOGE(LOG_TAG, "Failed to create stream");
        avformat_free_context(fmt_ctx);
        return false;
    }

    // Dummy parameters
    int width = 1920;
    int height = 1080;
    AVRational fps = {30, 1};
    int thread_count = 4;

    AVCodecContext *ctx = try_open_encoder_with_fallback(
            preset, width, height, fps, thread_count, fmt_ctx, stream,
            0 /* gamut: Rec709 */, 0 /* tonemap: None */, "x" /* tf: Linear */);

    bool success = (ctx != nullptr);

    if (success) {
        LOGI(LOG_TAG, "Test Successful! Encoder opened.");
        avcodec_free_context(&ctx);
    } else {
        LOGE(LOG_TAG, "Test Failed! Could not open encoder.");
    }

    avformat_free_context(fmt_ctx);
    return success;
}

bool test_vulkan_hardware_device() {
    AVBufferRef *device_ref = nullptr;
    int ret = av_hwdevice_ctx_create(&device_ref, AV_HWDEVICE_TYPE_VULKAN,
                                     nullptr, nullptr, 0);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Vulkan device creation failed: %s", errbuf);
        return false;
    }

    LOGI(LOG_TAG, "Vulkan device creation succeeded.");
    av_buffer_unref(&device_ref);
    return true;
}

static void fill_test_yuv_frame(AVFrame *frame) {
    const auto format = static_cast<AVPixelFormat>(frame->format);
    const bool high_depth =
            format == AV_PIX_FMT_YUV420P10LE ||
            format == AV_PIX_FMT_YUV422P10LE ||
            format == AV_PIX_FMT_YUV444P10LE;
    for (int plane = 0; plane < AV_NUM_DATA_POINTERS; ++plane) {
        if (!frame->data[plane] || frame->linesize[plane] <= 0) {
            continue;
        }
        int plane_height = frame->height;
        if (plane > 0 &&
            (format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUV420P10LE)) {
            plane_height = (frame->height + 1) / 2;
        }
        for (int y = 0; y < plane_height; ++y) {
            if (high_depth) {
                auto *row = reinterpret_cast<uint16_t *>(
                        frame->data[plane] + y * frame->linesize[plane]);
                const int samples = frame->linesize[plane] /
                                    static_cast<int>(sizeof(uint16_t));
                const uint16_t value = plane == 0 ? 64 : 512;
                std::fill(row, row + samples, value);
            } else {
                memset(frame->data[plane] + y * frame->linesize[plane],
                       plane == 0 ? 0x40 : 0x80,
                       static_cast<size_t>(frame->linesize[plane]));
            }
        }
    }
}

static bool test_one_frame_video_encoding(const VideoPreset &preset,
                                          int width,
                                          int height,
                                          AVRational fps,
                                          const char *label) {
    AVFormatContext *fmt_ctx = avformat_alloc_context();
    if (!fmt_ctx) {
        return false;
    }

    fmt_ctx->oformat = av_guess_format(preset.container_format.c_str(), nullptr,
                                       nullptr);
    if (!fmt_ctx->oformat) {
        avformat_free_context(fmt_ctx);
        return false;
    }

    AVStream *stream = avformat_new_stream(fmt_ctx, nullptr);
    if (!stream) {
        avformat_free_context(fmt_ctx);
        return false;
    }

    AVCodecContext *ctx = try_open_encoder_with_fallback(
            preset, width, height, fps, 1, fmt_ctx, stream,
            0 /* gamut: Rec709 */, 0 /* tonemap: None */, "x" /* tf: Linear */);
    if (!ctx) {
        LOGE(LOG_TAG, "%s diagnostic failed to open encoder.", label);
        avformat_free_context(fmt_ctx);
        return false;
    }

    AVFrame *software_frame = av_frame_alloc();
    AVFrame *hardware_frame = codec_context_uses_hw_frames(ctx) ? av_frame_alloc()
                                                                : nullptr;
    AVPacket *pkt = av_packet_alloc();
    if (!software_frame || !pkt ||
        (codec_context_uses_hw_frames(ctx) && !hardware_frame)) {
        av_packet_free(&pkt);
        av_frame_free(&hardware_frame);
        av_frame_free(&software_frame);
        avcodec_free_context(&ctx);
        avformat_free_context(fmt_ctx);
        return false;
    }

    software_frame->format = codec_context_upload_format(ctx);
    software_frame->width = width;
    software_frame->height = height;
    int ret = av_frame_get_buffer(software_frame, 32);
    if (ret >= 0) {
        ret = av_frame_make_writable(software_frame);
    }
    if (ret < 0) {
        av_packet_free(&pkt);
        av_frame_free(&hardware_frame);
        av_frame_free(&software_frame);
        avcodec_free_context(&ctx);
        avformat_free_context(fmt_ctx);
        return false;
    }

    fill_test_yuv_frame(software_frame);
    software_frame->pts = 0;
    software_frame->color_primaries = ctx->color_primaries;
    software_frame->color_trc = ctx->color_trc;
    software_frame->colorspace = ctx->colorspace;
    software_frame->color_range = ctx->color_range;

    AVFrame *encoder_frame = nullptr;
    ret = prepare_encoder_input_frame(ctx, software_frame, hardware_frame,
                                      &encoder_frame);
    if (ret >= 0) {
        ret = avcodec_send_frame(ctx, encoder_frame);
    }

    bool got_packet = false;
    if (ret >= 0) {
        while (true) {
            ret = avcodec_receive_packet(ctx, pkt);
            if (ret == 0) {
                got_packet = true;
                av_packet_unref(pkt);
                continue;
            }
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            break;
        }
    }

    if (ret == AVERROR(EAGAIN) || ret == 0) {
        ret = avcodec_send_frame(ctx, nullptr);
        if (ret >= 0 || ret == AVERROR_EOF) {
            while (true) {
                ret = avcodec_receive_packet(ctx, pkt);
                if (ret == 0) {
                    got_packet = true;
                    av_packet_unref(pkt);
                    continue;
                }
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                }
                got_packet = false;
                break;
            }
        }
    }

    if (got_packet) {
        LOGI(LOG_TAG, "%s one-frame encode succeeded.", label);
    } else {
        char errbuf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "%s one-frame encode failed: %s", label, errbuf);
    }

    av_packet_free(&pkt);
    av_frame_free(&hardware_frame);
    av_frame_free(&software_frame);
    avcodec_free_context(&ctx);
    avformat_free_context(fmt_ctx);
    return got_packet;
}

bool test_vulkan_prores_encoding(const export_options_t &originalOptions) {
    export_options_t options = originalOptions;
    options.codec = EXPORT_CODEC_PRORES;
    options.force_hardware = true;
    options.force_software = false;
    options.include_audio = false;

    VideoPreset preset = select_video_preset(options);
    AVRational fps = {24, 1};
    return test_one_frame_video_encoding(preset, 128, 72, fps, "Vulkan ProRes");
}

bool test_vulkan_hevc_10bit_422_encoding(
        const export_options_t &originalOptions) {
    (void) originalOptions;

    VideoPreset preset;
    preset.container_format = "mp4";
    preset.extension = ".mp4";
    preset.codec_id = AV_CODEC_ID_HEVC;
    preset.pixel_format = AV_PIX_FMT_YUV422P10LE;
    preset.bit_rate = 12000000;
    preset.gop = 1;
    preset.max_b_frames = 0;
    preset.profile = AV_PROFILE_HEVC_REXT;
    preset.requires_even_dimensions = true;
    preset.allow_generic_fallback = false;
    preset.encoder_candidates.push_back({"hevc_vulkan", true,
                                         AV_HWDEVICE_TYPE_VULKAN,
                                         AV_PIX_FMT_VULKAN});

    AVRational fps = {30, 1};
    return test_one_frame_video_encoding(
            preset, 1920, 1080, fps, "Vulkan HEVC 10-bit 4:2:2");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_testEncoderConfiguration(
        JNIEnv *env, jobject /* thiz */, jobject exportOptions) {

    export_options_t options = parse_export_options(env, exportOptions);
    return test_encoder_configuration(options);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_testVulkanHardwareDevice(
        JNIEnv *, jobject /* thiz */) {
    return test_vulkan_hardware_device();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_testVulkanProResEncoding(
        JNIEnv *env, jobject /* thiz */, jobject exportOptions) {
    export_options_t options = parse_export_options(env, exportOptions);
    return test_vulkan_prores_encoding(options);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_testVulkanHevc10Bit422Encoding(
        JNIEnv *env, jobject /* thiz */, jobject exportOptions) {
    export_options_t options = parse_export_options(env, exportOptions);
    return test_vulkan_hevc_10bit_422_encoding(options);
}
