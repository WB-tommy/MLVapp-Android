#include "ffmpeg_audio.h"
#include "../utils.h"
#include <android/log.h>

static const char *LOG_TAG = "FFmpegAudio";

int init_audio_copy(const std::string &audio_path, AVFormatContext *output_fmt,
                    AudioCopyContext &ctx) {
    if (audio_path.empty()) {
        return 0;
    }
    if (avformat_open_input(&ctx.input_ctx, audio_path.c_str(), nullptr,
                            nullptr) < 0) {
        return -1;
    }
    if (avformat_find_stream_info(ctx.input_ctx, nullptr) < 0) {
        return -1;
    }
    ctx.stream_index = av_find_best_stream(ctx.input_ctx, AVMEDIA_TYPE_AUDIO, -1,
                                           -1, nullptr, 0);
    if (ctx.stream_index < 0) {
        return -1;
    }
    ctx.input_stream = ctx.input_ctx->streams[ctx.stream_index];
    ctx.output_stream = avformat_new_stream(output_fmt, nullptr);
    if (!ctx.output_stream) {
        return -1;
    }
    if (avcodec_parameters_copy(ctx.output_stream->codecpar,
                                ctx.input_stream->codecpar) < 0) {
        return -1;
    }
    ctx.output_stream->time_base = ctx.input_stream->time_base;
    ctx.output_stream->codecpar->codec_tag = 0;
    return 0;
}

int copy_audio_packets(AudioCopyContext &ctx, AVFormatContext *output_fmt) {
    if (!ctx.input_ctx || ctx.stream_index < 0 || !ctx.output_stream) {
        return 0;
    }
    AVPacket *pkt = av_packet_alloc();
    if (!pkt)
        return AVERROR(ENOMEM);

    int ret = 0;

    while (av_read_frame(ctx.input_ctx, pkt) >= 0) {
        if (pkt->stream_index == ctx.stream_index) {
            pkt->stream_index = ctx.output_stream->index;
            pkt->pts = av_rescale_q(pkt->pts, ctx.input_stream->time_base,
                                    ctx.output_stream->time_base);
            pkt->dts = av_rescale_q(pkt->dts, ctx.input_stream->time_base,
                                    ctx.output_stream->time_base);
            pkt->duration = av_rescale_q(pkt->duration, ctx.input_stream->time_base,
                                         ctx.output_stream->time_base);
            pkt->pos = -1;
            ret = av_interleaved_write_frame(output_fmt, pkt);
            av_packet_unref(pkt);
            if (ret < 0)
                goto end;
        } else {
            av_packet_unref(pkt);
        }
    }
    return 0;
    end:
    av_packet_free(&pkt);
    return ret;
}

void cleanup_audio_copy(AudioCopyContext &ctx) {
    if (ctx.input_ctx) {
        avformat_close_input(&ctx.input_ctx);
        ctx.input_ctx = nullptr;
    }
    ctx.stream_index = -1;
    ctx.input_stream = nullptr;
    ctx.output_stream = nullptr;
}

namespace {
    const AVCodec *find_audio_encoder(bool prefer_opus, bool prefer_aac) {

        if (prefer_aac) {
            const AVCodec *aac = avcodec_find_encoder_by_name("aac");
            if (!aac) {
                aac = avcodec_find_encoder(AV_CODEC_ID_AAC);
            }
            if (aac) {
                return aac;
            }
        }
        if (prefer_opus) {
            const AVCodec *opus = avcodec_find_encoder_by_name("libopus");
            if (!opus) {
                opus = avcodec_find_encoder(AV_CODEC_ID_OPUS);
            }
            if (opus) {
                return opus;
            }
            const AVCodec *vorbis = avcodec_find_encoder_by_name("libvorbis");
            if (!vorbis) {
                vorbis = avcodec_find_encoder(AV_CODEC_ID_VORBIS);
            }
            if (vorbis) {
                return vorbis;
            }
        }
        // Fallback order: AAC -> Opus -> Vorbis
        const AVCodec *aac = avcodec_find_encoder_by_name("aac");
        if (!aac) {
            aac = avcodec_find_encoder(AV_CODEC_ID_AAC);
        }
        if (aac) {
            return aac;
        }
        const AVCodec *opus = avcodec_find_encoder_by_name("libopus");
        if (!opus) {
            opus = avcodec_find_encoder(AV_CODEC_ID_OPUS);
        }
        if (opus) {
            return opus;
        }
        const AVCodec *vorbis = avcodec_find_encoder_by_name("libvorbis");
        if (!vorbis) {
            vorbis = avcodec_find_encoder(AV_CODEC_ID_VORBIS);
        }
        if (vorbis) {
            return vorbis;
        }
        LOGE(LOG_TAG, "No audio encoder found!");
        return nullptr;
    }

    AVSampleFormat select_sample_format(const AVCodec *codec) {
        if (!codec) {
            LOGE(LOG_TAG, "select_sample_format: codec is null");
            return AV_SAMPLE_FMT_FLTP; // Default fallback
        }

        // Try modern API first (FFmpeg 7.x+)
        const AVSampleFormat *fmts = nullptr;
        int ret = avcodec_get_supported_config(
                nullptr, codec, AV_CODEC_CONFIG_SAMPLE_FORMAT, 0,
                reinterpret_cast<const void **>(&fmts), nullptr);

        if (ret >= 0 && fmts) {
            // Prefer FLTP, then first available
            for (const AVSampleFormat *fmt = fmts; *fmt != AV_SAMPLE_FMT_NONE; ++fmt) {
                if (*fmt == AV_SAMPLE_FMT_FLTP) {
                    return *fmt;
                }
            }

            return fmts[0];
        }

        // Fallback to deprecated API
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
        if (codec->sample_fmts) {
            for (const AVSampleFormat *fmt = codec->sample_fmts;
                 *fmt != AV_SAMPLE_FMT_NONE; ++fmt) {
                if (*fmt == AV_SAMPLE_FMT_FLTP) {
                    return *fmt;
                }
            }
            return codec->sample_fmts[0];
        }
#pragma GCC diagnostic pop

        LOGW(LOG_TAG, "select_sample_format: no formats found, defaulting to FLTP");
        return AV_SAMPLE_FMT_FLTP;
    }

    int select_sample_rate(const AVCodec *codec, int fallback_rate) {
        if (!codec) {
            return fallback_rate > 0 ? fallback_rate : 48000;
        }

        // Try modern API first (FFmpeg 7.x+)
        const int *rates = nullptr;
        int ret = avcodec_get_supported_config(
                nullptr, codec, AV_CODEC_CONFIG_SAMPLE_RATE, 0,
                reinterpret_cast<const void **>(&rates), nullptr);

        if (ret >= 0 && rates) {
            for (const int *rate = rates; *rate; ++rate) {
                if (*rate == 48000) {
                    return 48000;
                }
            }
            if (fallback_rate > 0) {
                for (const int *rate = rates; *rate; ++rate) {
                    if (*rate == fallback_rate) {
                        return fallback_rate;
                    }
                }
            }
            return rates[0] > 0 ? rates[0] : 48000;
        }

        // Fallback to deprecated API
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
        if (codec->supported_samplerates) {
            int chosen = 0;
            for (const int *rate = codec->supported_samplerates; *rate; ++rate) {
                if (*rate == 48000)
                    return 48000;
                if (!chosen)
                    chosen = *rate;
                if (fallback_rate > 0 && *rate == fallback_rate)
                    return fallback_rate;
            }
            return chosen > 0 ? chosen : 48000;
        }
#pragma GCC diagnostic pop

        return fallback_rate > 0 ? fallback_rate : 48000;
    }

    AVChannelLayout ensure_channel_layout(const AVCodecParameters *par,
                                          const AVCodecContext *dec_ctx,
                                          int channels) {
        AVChannelLayout layout{};
        if (par && par->ch_layout.nb_channels > 0) {
            av_channel_layout_copy(&layout, &par->ch_layout);
            return layout;
        }
        if (dec_ctx && dec_ctx->ch_layout.nb_channels > 0) {
            av_channel_layout_copy(&layout, &dec_ctx->ch_layout);
            return layout;
        }
        if (channels <= 0) {
            channels = 2; // default to stereo
        }
        av_channel_layout_default(&layout, channels);
        return layout;
    }
} // namespace

int init_audio_transcode(const std::string &audio_path,
                         AVFormatContext *output_fmt,
                         AudioTranscodeContext &ctx, bool prefer_opus,
                         bool prefer_aac) {
    if (audio_path.empty()) {

        return 0;
    }

    int ret =
            avformat_open_input(&ctx.input_ctx, audio_path.c_str(), nullptr, nullptr);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to open input '%s': %s",
             audio_path.c_str(), errbuf);
        return -1;
    }

    ret = avformat_find_stream_info(ctx.input_ctx, nullptr);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to find stream info: %s", errbuf);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ctx.stream_index = av_find_best_stream(ctx.input_ctx, AVMEDIA_TYPE_AUDIO, -1,
                                           -1, nullptr, 0);
    if (ctx.stream_index < 0) {
        LOGE(LOG_TAG, "Audio transcode: no audio stream found in input");
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ctx.input_stream = ctx.input_ctx->streams[ctx.stream_index];

    const AVCodec *decoder =
            avcodec_find_decoder(ctx.input_stream->codecpar->codec_id);
    if (!decoder) {
        LOGE(LOG_TAG, "Audio transcode: no decoder found for codec_id=%d",
             ctx.input_stream->codecpar->codec_id);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ctx.decoder_ctx = avcodec_alloc_context3(decoder);
    if (!ctx.decoder_ctx) {
        LOGE(LOG_TAG, "Audio transcode: failed to allocate decoder context");
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ret = avcodec_parameters_to_context(ctx.decoder_ctx,
                                        ctx.input_stream->codecpar);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to copy decoder parameters: %s",
             errbuf);
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ret = avcodec_open2(ctx.decoder_ctx, decoder, nullptr);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to open decoder: %s", errbuf);
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }
    LOGI(LOG_TAG, "Audio transcode: decoder opened, sample_rate=%d, channels=%d",
         ctx.decoder_ctx->sample_rate, ctx.decoder_ctx->ch_layout.nb_channels);

    const AVCodec *encoder = find_audio_encoder(prefer_opus, prefer_aac);
    if (!encoder) {
        LOGE(LOG_TAG, "Audio transcode: no suitable encoder found");
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ctx.encoder_ctx = avcodec_alloc_context3(encoder);
    if (!ctx.encoder_ctx) {
        LOGE(LOG_TAG, "Audio transcode: failed to allocate encoder context");
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    int input_channels = ctx.decoder_ctx->ch_layout.nb_channels;
    if (input_channels <= 0 && ctx.input_stream->codecpar) {
        input_channels = ctx.input_stream->codecpar->ch_layout.nb_channels;
    }
    if (input_channels <= 0) {
        input_channels = 2; // sensible default if metadata is missing
    }
    LOGI(LOG_TAG, "Audio transcode: input_channels=%d", input_channels);

    const AVChannelLayout dec_layout = ensure_channel_layout(
            ctx.input_stream->codecpar, ctx.decoder_ctx, input_channels);

    // For encoder, use default stereo layout (more compatible with AAC)
    AVChannelLayout enc_layout{};
    av_channel_layout_default(&enc_layout, input_channels);
    av_channel_layout_copy(&ctx.encoder_ctx->ch_layout, &enc_layout);
    av_channel_layout_uninit(&enc_layout);

    ctx.encoder_ctx->sample_fmt = select_sample_format(encoder);
    ctx.encoder_ctx->sample_rate =
            select_sample_rate(encoder, ctx.decoder_ctx->sample_rate);
    ctx.encoder_ctx->time_base = AVRational{1, ctx.encoder_ctx->sample_rate};
    ctx.encoder_ctx->bit_rate = 192000; // reasonable default for Opus/Vorbis

    // Native AAC encoder requires experimental compliance
    if (encoder->id == AV_CODEC_ID_AAC) {
        ctx.encoder_ctx->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
        LOGI(LOG_TAG, "Audio transcode: set AAC to experimental compliance");
    }

    LOGI(LOG_TAG,
         "Audio transcode: encoder config: rate=%d, channels=%d, sample_fmt=%d",
         ctx.encoder_ctx->sample_rate, ctx.encoder_ctx->ch_layout.nb_channels,
         ctx.encoder_ctx->sample_fmt);

    if (output_fmt->oformat->flags & AVFMT_GLOBALHEADER) {
        ctx.encoder_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }

    ret = avcodec_open2(ctx.encoder_ctx, encoder, nullptr);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to open encoder '%s': %s",
             encoder->name, errbuf);
        avcodec_free_context(&ctx.encoder_ctx);
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        return -1;
    }

    ret = swr_alloc_set_opts2(
            &ctx.swr_ctx, &ctx.encoder_ctx->ch_layout, ctx.encoder_ctx->sample_fmt,
            ctx.encoder_ctx->sample_rate, &dec_layout, ctx.decoder_ctx->sample_fmt,
            ctx.decoder_ctx->sample_rate, 0, nullptr);
    if (ret < 0 || !ctx.swr_ctx) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to create resampler: %s", errbuf);
        swr_free(&ctx.swr_ctx);
        avcodec_free_context(&ctx.encoder_ctx);
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        ctx.output_stream = nullptr;
        return -1;
    }

    ret = swr_init(ctx.swr_ctx);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to init resampler: %s", errbuf);
        swr_free(&ctx.swr_ctx);
        avcodec_free_context(&ctx.encoder_ctx);
        avcodec_free_context(&ctx.decoder_ctx);
        avformat_close_input(&ctx.input_ctx);
        ctx.output_stream = nullptr;
        return -1;
    }

    ctx.output_stream = avformat_new_stream(output_fmt, encoder);
    if (!ctx.output_stream) {
        LOGE(LOG_TAG, "Audio transcode: failed to create output stream");
        cleanup_audio_transcode(ctx);
        return -1;
    }

    ret = avcodec_parameters_from_context(ctx.output_stream->codecpar,
                                          ctx.encoder_ctx);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG, "Audio transcode: failed to copy encoder parameters: %s",
             errbuf);
        cleanup_audio_transcode(ctx);
        ctx.output_stream = nullptr;
        return -1;
    }
    ctx.output_stream->time_base = ctx.encoder_ctx->time_base;

    ctx.resampled_frame = av_frame_alloc();
    if (!ctx.resampled_frame) {
        LOGE(LOG_TAG, "Audio transcode: failed to allocate resampled frame");
        cleanup_audio_transcode(ctx);
        return -1;
    }

    // Create FIFO buffer for accumulating samples (needed for fixed frame_size
    // encoders like AAC)
    ctx.fifo = av_audio_fifo_alloc(ctx.encoder_ctx->sample_fmt,
                                   ctx.encoder_ctx->ch_layout.nb_channels,
                                   ctx.encoder_ctx->frame_size * 2);
    if (!ctx.fifo) {
        LOGE(LOG_TAG, "Audio transcode: failed to allocate audio FIFO");
        cleanup_audio_transcode(ctx);
        return -1;
    }

    return 0;
}

int transcode_audio_packets(AudioTranscodeContext &ctx,
                            AVFormatContext *output_fmt) {
    if (!ctx.input_ctx || !ctx.decoder_ctx || !ctx.encoder_ctx ||
        !ctx.output_stream || !ctx.fifo) {
        return 0;
    }

    const int frame_size = ctx.encoder_ctx->frame_size;

    // Helper: encode and write frames from FIFO when we have enough samples
    auto encode_from_fifo = [&](bool flush) -> int {
        while (av_audio_fifo_size(ctx.fifo) >= frame_size ||
               (flush && av_audio_fifo_size(ctx.fifo) > 0)) {
            const int available = av_audio_fifo_size(ctx.fifo);
            const int samples_to_encode = flush ? available : frame_size;

            // Prepare output frame
            av_frame_unref(ctx.resampled_frame);
            ctx.resampled_frame->nb_samples = samples_to_encode;
            av_channel_layout_copy(&ctx.resampled_frame->ch_layout,
                                   &ctx.encoder_ctx->ch_layout);
            ctx.resampled_frame->format = ctx.encoder_ctx->sample_fmt;
            ctx.resampled_frame->sample_rate = ctx.encoder_ctx->sample_rate;

            int alloc_ret = av_frame_get_buffer(ctx.resampled_frame, 0);
            if (alloc_ret < 0) {
                LOGE(LOG_TAG,
                     "Audio transcode: failed to allocate encoder frame buffer");
                return alloc_ret;
            }

            // Read from FIFO into frame
            int read = av_audio_fifo_read(
                    ctx.fifo, reinterpret_cast<void **>(ctx.resampled_frame->data),
                    samples_to_encode);
            if (read < samples_to_encode) {
                LOGE(LOG_TAG, "Audio transcode: FIFO read returned %d, expected %d",
                     read, samples_to_encode);
                return -1;
            }
            ctx.resampled_frame->nb_samples = read;
            ctx.resampled_frame->pts = ctx.next_pts;
            ctx.next_pts += read;

            // Send to encoder
            int ret = avcodec_send_frame(ctx.encoder_ctx, ctx.resampled_frame);
            if (ret < 0) {
                char errbuf[AV_ERROR_MAX_STRING_SIZE];
                av_strerror(ret, errbuf, sizeof(errbuf));
                LOGE(LOG_TAG, "Audio transcode: avcodec_send_frame failed: %s", errbuf);
                return ret;
            }

            AVPacket *out_pkt = av_packet_alloc();
            if (!out_pkt)
                return AVERROR(ENOMEM);

            // Receive encoded packets
            while (true) {
                ret = avcodec_receive_packet(ctx.encoder_ctx, out_pkt);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                }
                if (ret < 0) {
                    av_packet_unref(out_pkt);
                    av_packet_free(&out_pkt);
                    return ret;
                }

                out_pkt->stream_index = ctx.output_stream->index;
                av_packet_rescale_ts(out_pkt, ctx.encoder_ctx->time_base,
                                     ctx.output_stream->time_base);
                out_pkt->pos = -1;

                int write_ret = av_interleaved_write_frame(output_fmt, out_pkt);
                av_packet_unref(out_pkt);
                if (write_ret < 0) {
                    char errbuf[AV_ERROR_MAX_STRING_SIZE];
                    av_strerror(write_ret, errbuf, sizeof(errbuf));
                    LOGE(LOG_TAG,
                         "Audio transcode: av_interleaved_write_frame failed: %s",
                         errbuf);
                    return write_ret;
                }
            }
            av_packet_free(&out_pkt);
        }
        return 0;
    };

    // Helper: resample decoded frame and add to FIFO
    auto add_samples_to_fifo = [&](AVFrame *decoded_frame) -> int {
        // Calculate destination sample count
        int dst_nb_samples = av_rescale_rnd(
                swr_get_delay(ctx.swr_ctx, decoded_frame->sample_rate) +
                decoded_frame->nb_samples,
                ctx.encoder_ctx->sample_rate, decoded_frame->sample_rate, AV_ROUND_UP);

        // Allocate temporary buffer for resampled data
        uint8_t **converted_data = nullptr;
        int ret = av_samples_alloc_array_and_samples(
                &converted_data, nullptr, ctx.encoder_ctx->ch_layout.nb_channels,
                dst_nb_samples, ctx.encoder_ctx->sample_fmt, 0);
        if (ret < 0) {
            LOGE(LOG_TAG, "Audio transcode: failed to allocate samples buffer");
            return ret;
        }

        // Resample
        int converted =
                swr_convert(ctx.swr_ctx, converted_data, dst_nb_samples,
                            const_cast<const uint8_t **>(decoded_frame->extended_data),
                            decoded_frame->nb_samples);
        if (converted < 0) {
            av_freep(&converted_data[0]);
            av_freep(&converted_data);
            char errbuf[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(converted, errbuf, sizeof(errbuf));
            LOGE(LOG_TAG, "Audio transcode: swr_convert failed: %s", errbuf);
            return converted;
        }

        // Ensure FIFO has enough space
        int fifo_size = av_audio_fifo_size(ctx.fifo);
        if (av_audio_fifo_realloc(ctx.fifo, fifo_size + converted) < 0) {
            av_freep(&converted_data[0]);
            av_freep(&converted_data);
            LOGE(LOG_TAG, "Audio transcode: failed to realloc FIFO");
            return -1;
        }

        // Write to FIFO
        int written = av_audio_fifo_write(
                ctx.fifo, reinterpret_cast<void **>(converted_data), converted);
        av_freep(&converted_data[0]);
        av_freep(&converted_data);

        if (written < converted) {
            LOGE(LOG_TAG, "Audio transcode: FIFO write returned %d, expected %d",
                 written, converted);
            return -1;
        }

        return 0;
    };

    AVFrame *decoded = av_frame_alloc();
    if (!decoded) {
        return -1;
    }

    int ret = 0;
    AVPacket *pkt = av_packet_alloc(); // Allocates the structure
    if (!pkt)
        return AVERROR(ENOMEM);

    // Main decode loop
    while (av_read_frame(ctx.input_ctx, pkt) >= 0) {
        if (pkt->stream_index != ctx.stream_index) {
            av_packet_unref(pkt);
            continue;
        }

        ret = avcodec_send_packet(ctx.decoder_ctx, pkt);
        av_packet_unref(pkt);
        if (ret < 0) {
            break;
        }

        while (true) {
            ret = avcodec_receive_frame(ctx.decoder_ctx, decoded);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                ret = 0;
                break;
            }
            if (ret < 0) {
                break;
            }

            ret = add_samples_to_fifo(decoded);
            av_frame_unref(decoded);
            if (ret < 0) {
                break;
            }

            // Encode from FIFO when we have enough samples
            ret = encode_from_fifo(false);
            if (ret < 0) {
                break;
            }
        }

        if (ret < 0) {
            break;
        }
    }

    av_packet_free(&pkt);

    // Flush decoder
    if (ret == 0) {
        avcodec_send_packet(ctx.decoder_ctx, nullptr);
        while (true) {
            ret = avcodec_receive_frame(ctx.decoder_ctx, decoded);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                ret = 0;
                break;
            }
            if (ret < 0) {
                break;
            }

            int add_ret = add_samples_to_fifo(decoded);
            av_frame_unref(decoded);
            if (add_ret < 0) {
                ret = add_ret;
                break;
            }
        }
    }

    // Encode remaining samples from FIFO (flush mode)
    if (ret == 0) {
        ret = encode_from_fifo(true);
    }

    // Flush encoder
    if (ret == 0) {
        avcodec_send_frame(ctx.encoder_ctx, nullptr);
        AVPacket *out_pkt = av_packet_alloc();
        if (!out_pkt)
            return AVERROR(ENOMEM);
        while (true) {
            int recv_ret = avcodec_receive_packet(ctx.encoder_ctx, out_pkt);
            if (recv_ret == AVERROR(EAGAIN) || recv_ret == AVERROR_EOF) {
                av_packet_unref(out_pkt);
                break;
            }
            if (recv_ret < 0) {
                av_packet_unref(out_pkt);
                ret = recv_ret;
                break;
            }

            out_pkt->stream_index = ctx.output_stream->index;
            av_packet_rescale_ts(out_pkt, ctx.encoder_ctx->time_base,
                                 ctx.output_stream->time_base);
            out_pkt->pos = -1;
            int write_ret = av_interleaved_write_frame(output_fmt, out_pkt);
            av_packet_unref(out_pkt);
            if (write_ret < 0) {
                ret = write_ret;
                break;
            }
        }
        av_packet_free(&out_pkt);
    }

    av_frame_free(&decoded);
    return ret;
}

void cleanup_audio_transcode(AudioTranscodeContext &ctx) {
    if (ctx.input_ctx) {
        avformat_close_input(&ctx.input_ctx);
        ctx.input_ctx = nullptr;
    }
    if (ctx.decoder_ctx) {
        avcodec_free_context(&ctx.decoder_ctx);
    }
    if (ctx.encoder_ctx) {
        avcodec_free_context(&ctx.encoder_ctx);
    }
    if (ctx.swr_ctx) {
        swr_free(&ctx.swr_ctx);
    }
    if (ctx.resampled_frame) {
        av_frame_free(&ctx.resampled_frame);
    }
    if (ctx.fifo) {
        av_audio_fifo_free(ctx.fifo);
        ctx.fifo = nullptr;
    }
    ctx.output_stream = nullptr;
    ctx.stream_index = -1;
    ctx.input_stream = nullptr;
    ctx.next_pts = 0;
}
