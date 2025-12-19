//
// Created by Sungmin Choi on 2025. 11. 12..
//
// FFmpeg-related encoding/decoding functions extracted from export_handler.
// This file contains video/audio encoding logic using FFmpeg libraries.
//
// Refactored to delegate logic to ffmpeg_presets and ffmpeg_audio.
//

#include "ffmpeg_handler.h"
#include "../export/export_handler.h"
#include "../utils.h"

#include <algorithm>
#include <android/log.h>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

extern "C" {
#include "../../src/mlv/macros.h"
#include "../../src/mlv/mlv_object.h"
}

static const char *LOG_TAG = "FFmpegHandler";

static int fd_write_packet(void *opaque, const uint8_t *buf, int buf_size) {
  auto *io = reinterpret_cast<FdIoContext *>(opaque);
  if (!io)
    return AVERROR(EINVAL);
  const ssize_t written = write(io->fd, buf, static_cast<size_t>(buf_size));
  if (written < 0) {
    LOGE(LOG_TAG, "fd_write_packet failed: fd=%d, size=%d, errno=%d (%s)",
         io->fd, buf_size, errno, strerror(errno));
  }
  return static_cast<int>(written);
}

static int64_t fd_seek_packet(void *opaque, int64_t offset, int whence) {
  auto *io = reinterpret_cast<FdIoContext *>(opaque);
  if (!io)
    return AVERROR(EINVAL);
  if (whence == AVSEEK_SIZE) {
    struct stat st{};
    if (fstat(io->fd, &st) == 0) {
      return st.st_size;
    }
    return AVERROR(errno);
  }
  return lseek(io->fd, offset, whence);
}

std::unique_ptr<FdIoContext> make_fd_io(int fd) {
  const int bufferSize = 32 * 1024;
  auto io = std::make_unique<FdIoContext>();
  io->fd = fd;
  io->buffer = static_cast<uint8_t *>(av_malloc(bufferSize));
  if (!io->buffer) {
    return nullptr;
  }
  io->ctx = avio_alloc_context(io->buffer, bufferSize, 1, io.get(), nullptr,
                               fd_write_packet, fd_seek_packet);
  if (!io->ctx) {
    av_free(io->buffer);
    return nullptr;
  }
  io->ctx->seekable = AVIO_SEEKABLE_NORMAL;
  if (io->ctx->seekable && fd_seek_packet(io.get(), 0, SEEK_CUR) < 0) {
    io->ctx->seekable = 0;
  }
  return io;
}

void free_fd_io(std::unique_ptr<FdIoContext> &io) {
  if (!io)
    return;
  if (io->ctx) {
    avio_flush(io->ctx);
    avio_context_free(&io->ctx);
  }
  if (io->buffer) {
    av_free(io->buffer);
  }
  if (io->fd >= 0) {
    close(io->fd);
  }
  io.reset();
}

int export_image_sequence(mlvObject_t *video, const export_options_t &options,
                          const export_fd_provider_t &provider,
                          AVCodecID codec_id, AVPixelFormat dst_format,
                          const char *extension,
                          void (*progress_callback)(int progress)) {
  if (!provider.acquire_frame_fd) {
    return -1;
  }

  const int src_w = getMlvWidth(video);
  const int src_h = getMlvHeight(video);
  int dst_w = 0;
  int dst_h = 0;
  compute_dimensions(options, src_w, src_h, dst_w, dst_h);

  const int scale_flags = select_scale_flags(options.resize_algorithm);
  SwsContext *sws_ctx =
      sws_getContext(src_w, src_h, AV_PIX_FMT_RGB48LE, dst_w, dst_h, dst_format,
                     scale_flags, nullptr, nullptr, nullptr);
  if (!sws_ctx) {
    LOGE(LOG_TAG, "sws context is null.");
    return -1;
  }

  // Find encoder once for all frames
  const AVCodec *codec = avcodec_find_encoder(codec_id);
  if (!codec) {
    LOGE(LOG_TAG, "Failed to find encoder for codec_id=%d", codec_id);
    sws_freeContext(sws_ctx);
    return -1;
  }

  AVCodecContext *codec_ctx = avcodec_alloc_context3(codec);
  if (!codec_ctx) {
    sws_freeContext(sws_ctx);
    return -1;
  }

  codec_ctx->codec_id = codec_id;
  codec_ctx->pix_fmt = dst_format;
  codec_ctx->width = dst_w;
  codec_ctx->height = dst_h;
  codec_ctx->time_base = AVRational{1, 25};
  codec_ctx->framerate = AVRational{25, 1};

  if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
    LOGE(LOG_TAG, "Failed to open encoder for codec_id=%d", codec_id);
    avcodec_free_context(&codec_ctx);
    sws_freeContext(sws_ctx);
    return -1;
  }

  AVFrame *frame = av_frame_alloc();
  if (!frame) {
    avcodec_free_context(&codec_ctx);
    sws_freeContext(sws_ctx);
    return -1;
  }
  frame->format = dst_format;
  frame->width = dst_w;
  frame->height = dst_h;
  if (av_frame_get_buffer(frame, 0) < 0) {
    LOGE(LOG_TAG, "failed to get a image buffer.");
    av_frame_free(&frame);
    avcodec_free_context(&codec_ctx);
    sws_freeContext(sws_ctx);
    return -1;
  }

  std::vector<uint16_t> src_buffer(static_cast<size_t>(src_w) * src_h * 3);
  const int total_frames = getMlvFrames(video);
  int ret = 0;

  // Allocate packet once for the whole sequence
  AVPacket *pkt = av_packet_alloc();
  if (!pkt) {
    LOGE(LOG_TAG, "Failed to allocate packet");
    av_frame_free(&frame);
    avcodec_free_context(&codec_ctx);
    sws_freeContext(sws_ctx);
    return -1;
  }

  for (int i = 0; i < total_frames; ++i) {
    if (is_export_cancelled()) {
      ret = EXPORT_CANCELLED;
      break;
    }

    const uint32_t frame_number = getMlvFrameNumber(video, i);
    char relative_name[256];
    snprintf(relative_name, sizeof(relative_name), "%s_%06u%s",
             options.source_base_name.c_str(), frame_number, extension);

    const int fd = provider.acquire_frame_fd(
        provider.ctx, static_cast<uint32_t>(i), relative_name);

    if (fd < 0) {
      LOGE(LOG_TAG, "Failed to acquire frame fd for %s", relative_name);
      ret = -1;
      break;
    }

    // Process frame
    getMlvProcessedFrame16(video, i, src_buffer.data(), getMlvCpuCores(video));
    uint8_t *src_data[4] = {reinterpret_cast<uint8_t *>(src_buffer.data()),
                            nullptr, nullptr, nullptr};
    int src_linesize[4] = {src_w * 3 * static_cast<int>(sizeof(uint16_t)), 0, 0,
                           0};

    av_frame_make_writable(frame);
    sws_scale(sws_ctx, src_data, src_linesize, 0, src_h, frame->data,
              frame->linesize);
    frame->pts = i;

    // Encode frame
    int enc_ret = avcodec_send_frame(codec_ctx, frame);
    if (enc_ret < 0) {
      LOGE(LOG_TAG, "Failed to send frame %d to encoder", i);
      close(fd);
      ret = -1;
      break;
    }

    while (true) {
      enc_ret = avcodec_receive_packet(codec_ctx, pkt);
      if (enc_ret == AVERROR(EAGAIN) || enc_ret == AVERROR_EOF) {
        break;
      }
      if (enc_ret < 0) {
        LOGE(LOG_TAG, "Failed to receive packet for frame %d", i);
        ret = -1;
        break;
      }

      // Write packet data directly to file descriptor (bypass muxer!)
      ssize_t written = write(fd, pkt->data, pkt->size);
      if (written != pkt->size) {
        LOGE(LOG_TAG,
             "Failed to write image data for frame %d: wrote %zd of %d bytes, "
             "errno=%d (%s)",
             i, written, pkt->size, errno, strerror(errno));
        ret = -1;
      }
      av_packet_unref(pkt);
      break; // For image codecs, there's only one packet per frame
    }

    close(fd);

    if (ret != 0) {
      break;
    }

    if (progress_callback) {
      progress_callback(static_cast<int>((i + 1) * 100.0f / total_frames));
    }
  }

  av_packet_free(&pkt);
  av_frame_free(&frame);
  avcodec_free_context(&codec_ctx);
  sws_freeContext(sws_ctx);
  return ret;
}

int export_video_container(mlvObject_t *video, const export_options_t &options,
                           const export_fd_provider_t &provider,
                           void (*progress_callback)(int progress)) {
  if (!provider.acquire_container_fd) {
    LOGE(LOG_TAG, "Export error: No file descriptor provider available");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }

  VideoPreset preset = select_video_preset(options);
  if (preset.codec_id == AV_CODEC_ID_NONE) {
    LOGE(LOG_TAG, "Export error: Invalid codec selected");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }

  const int src_w = getMlvWidth(video);
  const int src_h = getMlvHeight(video);
  int dst_w = 0;
  int dst_h = 0;
  compute_dimensions(options, src_w, src_h, dst_w, dst_h);
  if (preset.requires_even_dimensions) {
    if (dst_w & 1)
      ++dst_w;
    if (dst_h & 1)
      ++dst_h;
  }

  AVRational fps = select_fps(options, getMlvFramerate(video));

  const std::string output_name = options.source_base_name + preset.extension;
  int container_fd =
      provider.acquire_container_fd(provider.ctx, output_name.c_str());
  if (container_fd < 0) {
    LOGE(LOG_TAG, "Export error: Failed to acquire output file descriptor");
    return EXPORT_ERROR_IO;
  }

  auto io = make_fd_io(container_fd);
  if (!io) {
    close(container_fd);
    LOGE(LOG_TAG, "Export error: Failed to create I/O context");
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  AVFormatContext *fmt_ctx = nullptr;
  if (avformat_alloc_output_context2(
          &fmt_ctx, nullptr, preset.container_format.c_str(), nullptr) < 0 ||
      !fmt_ctx) {
    free_fd_io(io);
    LOGE(LOG_TAG, "Export error: Failed to allocate format context");
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }
  fmt_ctx->pb = io->ctx;
  fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

  AVStream *video_stream = avformat_new_stream(fmt_ctx, nullptr);
  if (!video_stream) {
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  AVCodecContext *codec_ctx = try_open_encoder_with_fallback(
      preset, dst_w, dst_h, fps, getMlvCpuCores(video), fmt_ctx, video_stream);

  if (!codec_ctx) {
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    LOGE(LOG_TAG, "Export error: All encoder candidates failed");
    return EXPORT_ERROR_CODEC_UNAVAILABLE;
  }

  AVPixelFormat actual_pix_fmt = codec_ctx->pix_fmt;
  if (preset.codec_id == AV_CODEC_ID_HEVC) {
    video_stream->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
  }

  AudioCopyContext audio_ctx{};
  AudioTranscodeContext audio_transcode_ctx{};
  const bool use_opus = (options.codec == EXPORT_CODEC_VP9);
  const bool use_aac = (options.codec == EXPORT_CODEC_H264 ||
                        options.codec == EXPORT_CODEC_H265);
  const bool transcode_audio = use_opus || use_aac;

  if (options.include_audio && !options.audio_path.empty()) {
    if (transcode_audio) {
      if (init_audio_transcode(options.audio_path, fmt_ctx, audio_transcode_ctx,
                               use_opus, use_aac) != 0) {
        LOGW(LOG_TAG, "Audio transcode init failed, continuing without audio");
        cleanup_audio_transcode(audio_transcode_ctx);
      }
    } else {
      if (init_audio_copy(options.audio_path, fmt_ctx, audio_ctx) != 0) {
        LOGW(LOG_TAG, "Audio init failed, continuing without audio");
        cleanup_audio_copy(audio_ctx);
      }
    }
  }

  int ret = avformat_write_header(fmt_ctx, nullptr);
  if (ret < 0) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(ret, errbuf, sizeof(errbuf));
    LOGE(LOG_TAG, "Failed to write header: %s", errbuf);
    cleanup_audio_copy(audio_ctx);
    cleanup_audio_transcode(audio_transcode_ctx);
    avcodec_free_context(&codec_ctx);
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return ret;
  }

  if (audio_transcode_ctx.input_ctx) {
    ret = transcode_audio_packets(audio_transcode_ctx, fmt_ctx);
  } else if (audio_ctx.input_ctx) {
    ret = copy_audio_packets(audio_ctx, fmt_ctx);
  }

  // --- Main Video Export Loop ---
  const int total_frames = getMlvFrames(video);
  const int scale_flags = select_scale_flags(options.resize_algorithm);
  SwsContext *sws_ctx =
      sws_getContext(src_w, src_h, AV_PIX_FMT_RGB48LE, dst_w, dst_h,
                     actual_pix_fmt, scale_flags, nullptr, nullptr, nullptr);

  if (!sws_ctx) {
    ret = EXPORT_ERROR_GENERIC;
  }

  AVFrame *frame = nullptr;
  if (sws_ctx) {
    frame = av_frame_alloc();
    if (frame) {
      frame->format = actual_pix_fmt;
      frame->width = dst_w;
      frame->height = dst_h;
      if (av_frame_get_buffer(frame, 0) < 0) {
        av_frame_free(&frame);
      }
    }
  }

  // Allocate packet once
  AVPacket *pkt = av_packet_alloc();
  if (!pkt) {
    ret = EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  if (!frame || !pkt) {
    if (sws_ctx)
      sws_freeContext(sws_ctx);
    if (frame)
      av_frame_free(&frame);
    if (pkt)
      av_packet_free(&pkt);
    cleanup_audio_transcode(audio_transcode_ctx);
    cleanup_audio_copy(audio_ctx);
    avcodec_free_context(&codec_ctx);
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  std::vector<uint16_t> src_buffer(static_cast<size_t>(src_w) * src_h * 3);
  int frame_idx = 0;
  int64_t pts = 0;

  for (; frame_idx < total_frames; ++frame_idx) {
    if (is_export_cancelled()) {
      ret = EXPORT_CANCELLED;
      break;
    }

    getMlvProcessedFrame16(video, frame_idx, src_buffer.data(),
                           getMlvCpuCores(video));
    const uint8_t *src_data[4] = {
        reinterpret_cast<const uint8_t *>(src_buffer.data()), nullptr, nullptr,
        nullptr};
    int src_linesize[4] = {src_w * 3 * static_cast<int>(sizeof(uint16_t)), 0, 0,
                           0};

    if (av_frame_make_writable(frame) < 0) {
      ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
      break;
    }

    sws_scale(sws_ctx, src_data, src_linesize, 0, src_h, frame->data,
              frame->linesize);
    frame->pts = pts++;

    int enc_ret = avcodec_send_frame(codec_ctx, frame);
    if (enc_ret < 0) {
      ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
      break;
    }

    while (true) {
      enc_ret = avcodec_receive_packet(codec_ctx, pkt);
      if (enc_ret == AVERROR(EAGAIN) || enc_ret == AVERROR_EOF) {
        break;
      }
      if (enc_ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(enc_ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG,
             "avcodec_receive_packet failed for frame %d with error %d: %s",
             frame_idx, enc_ret, errbuf);
        ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
        av_packet_unref(pkt);
        break;
      }

      pkt->stream_index = video_stream->index;
      av_packet_rescale_ts(pkt, codec_ctx->time_base, video_stream->time_base);
      pkt->pos = -1;
      if (av_interleaved_write_frame(fmt_ctx, pkt) < 0) {
        ret = EXPORT_ERROR_IO;
        av_packet_unref(pkt);
        break;
      }
      av_packet_unref(pkt);
    }
    if (ret != EXPORT_SUCCESS)
      break;

    if (progress_callback)
      progress_callback(
          static_cast<int>((frame_idx + 1) * 100.0f / total_frames));
  }

  // Flush
  if (ret == EXPORT_SUCCESS) {
    avcodec_send_frame(codec_ctx, nullptr);
    while (true) {
      int enc_ret = avcodec_receive_packet(codec_ctx, pkt);
      if (enc_ret == AVERROR(EAGAIN) || enc_ret == AVERROR_EOF)
        break;
      if (enc_ret < 0)
        break;
      pkt->stream_index = video_stream->index;
      av_packet_rescale_ts(pkt, codec_ctx->time_base, video_stream->time_base);
      pkt->pos = -1;
      av_interleaved_write_frame(fmt_ctx, pkt);
      av_packet_unref(pkt);
    }
    av_write_trailer(fmt_ctx);
  }

  av_packet_free(&pkt); // Free the shared packet
  av_frame_free(&frame);
  sws_freeContext(sws_ctx);
  cleanup_audio_transcode(audio_transcode_ctx);
  cleanup_audio_copy(audio_ctx);
  avcodec_free_context(&codec_ctx);
  avformat_free_context(fmt_ctx);
  free_fd_io(io);

  return ret;
}

// Batch video container export - uses shared encoder context
int export_video_container_batch(BatchExportContext &batch_ctx,
                                 mlvObject_t *video,
                                 const export_options_t &options,
                                 const export_fd_provider_t &provider,
                                 void (*progress_callback)(int progress)) {

  if (!provider.acquire_container_fd) {
    LOGE(LOG_TAG, "Export error: No file descriptor provider available");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }

  // Use preset from batch context (computed once for all clips)
  const VideoPreset &preset = batch_ctx.preset;
  if (preset.codec_id == AV_CODEC_ID_NONE) {
    LOGE(LOG_TAG, "Export error: Invalid codec in batch context");
    return EXPORT_ERROR_INVALID_PARAMETERS;
  }

  const int src_w = getMlvWidth(video);
  const int src_h = getMlvHeight(video);
  int dst_w = 0;
  int dst_h = 0;
  compute_dimensions(options, src_w, src_h, dst_w, dst_h);
  if (preset.requires_even_dimensions) {
    if (dst_w & 1)
      ++dst_w;
    if (dst_h & 1)
      ++dst_h;
  }

  AVRational fps = select_fps(options, getMlvFramerate(video));

  const std::string output_name = options.source_base_name + preset.extension;
  int container_fd =
      provider.acquire_container_fd(provider.ctx, output_name.c_str());
  if (container_fd < 0) {
    LOGE(LOG_TAG, "Export error: Failed to acquire output file descriptor");
    return EXPORT_ERROR_IO;
  }

  auto io = make_fd_io(container_fd);
  if (!io) {
    close(container_fd);
    LOGE(LOG_TAG, "Export error: Failed to create I/O context");
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  AVFormatContext *fmt_ctx = nullptr;
  if (avformat_alloc_output_context2(
          &fmt_ctx, nullptr, preset.container_format.c_str(), nullptr) < 0 ||
      !fmt_ctx) {
    free_fd_io(io);
    LOGE(LOG_TAG, "Export error: Failed to allocate format context");
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }
  fmt_ctx->pb = io->ctx;
  fmt_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

  AVStream *video_stream = avformat_new_stream(fmt_ctx, nullptr);
  if (!video_stream) {
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  // Use batch context to get codec (with caching)
  AVCodecContext *codec_ctx =
      get_batch_codec_context(batch_ctx, dst_w, dst_h, fps,
                              getMlvCpuCores(video), fmt_ctx, video_stream);

  if (!codec_ctx) {
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    LOGE(LOG_TAG, "Export error: All encoder candidates failed");
    return EXPORT_ERROR_CODEC_UNAVAILABLE;
  }

  AVPixelFormat actual_pix_fmt = codec_ctx->pix_fmt;
  if (preset.codec_id == AV_CODEC_ID_HEVC) {
    video_stream->codecpar->codec_tag = MKTAG('h', 'v', 'c', '1');
  }

  // Audio handling (same as non-batch version)
  AudioCopyContext audio_ctx{};
  AudioTranscodeContext audio_transcode_ctx{};
  const bool use_opus = (options.codec == EXPORT_CODEC_VP9);
  const bool use_aac = (options.codec == EXPORT_CODEC_H264 ||
                        options.codec == EXPORT_CODEC_H265);
  const bool transcode_audio = use_opus || use_aac;

  if (options.include_audio && !options.audio_path.empty()) {
    if (transcode_audio) {
      if (init_audio_transcode(options.audio_path, fmt_ctx, audio_transcode_ctx,
                               use_opus, use_aac) != 0) {
        LOGW(LOG_TAG, "Audio transcode init failed, continuing without audio");
        cleanup_audio_transcode(audio_transcode_ctx);
      }
    } else {
      if (init_audio_copy(options.audio_path, fmt_ctx, audio_ctx) != 0) {
        LOGW(LOG_TAG, "Audio init failed, continuing without audio");
        cleanup_audio_copy(audio_ctx);
      }
    }
  }

  int ret = avformat_write_header(fmt_ctx, nullptr);
  if (ret < 0) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(ret, errbuf, sizeof(errbuf));
    LOGE(LOG_TAG, "Failed to write header: %s", errbuf);
    cleanup_audio_copy(audio_ctx);
    cleanup_audio_transcode(audio_transcode_ctx);
    avcodec_free_context(&codec_ctx);
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return ret;
  }

  if (audio_transcode_ctx.input_ctx) {
    ret = transcode_audio_packets(audio_transcode_ctx, fmt_ctx);
  } else if (audio_ctx.input_ctx) {
    ret = copy_audio_packets(audio_ctx, fmt_ctx);
  }

  // --- Main Video Export Loop ---
  const int total_frames = getMlvFrames(video);
  const int scale_flags = select_scale_flags(options.resize_algorithm);
  SwsContext *sws_ctx =
      sws_getContext(src_w, src_h, AV_PIX_FMT_RGB48LE, dst_w, dst_h,
                     actual_pix_fmt, scale_flags, nullptr, nullptr, nullptr);

  if (!sws_ctx) {
    ret = EXPORT_ERROR_GENERIC;
  }

  AVFrame *frame = nullptr;
  if (sws_ctx) {
    frame = av_frame_alloc();
    if (frame) {
      frame->format = actual_pix_fmt;
      frame->width = dst_w;
      frame->height = dst_h;
      if (av_frame_get_buffer(frame, 0) < 0) {
        av_frame_free(&frame);
      }
    }
  }

  AVPacket *pkt = av_packet_alloc();
  if (!pkt) {
    ret = EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  if (!frame || !pkt) {
    if (sws_ctx)
      sws_freeContext(sws_ctx);
    if (frame)
      av_frame_free(&frame);
    if (pkt)
      av_packet_free(&pkt);
    cleanup_audio_transcode(audio_transcode_ctx);
    cleanup_audio_copy(audio_ctx);
    avcodec_free_context(&codec_ctx);
    avformat_free_context(fmt_ctx);
    free_fd_io(io);
    return EXPORT_ERROR_INSUFFICIENT_MEMORY;
  }

  std::vector<uint16_t> src_buffer(static_cast<size_t>(src_w) * src_h * 3);
  int frame_idx = 0;
  int64_t pts = 0;

  for (; frame_idx < total_frames; ++frame_idx) {
    if (is_export_cancelled()) {
      ret = EXPORT_CANCELLED;
      break;
    }

    getMlvProcessedFrame16(video, frame_idx, src_buffer.data(),
                           getMlvCpuCores(video));
    const uint8_t *src_data[4] = {
        reinterpret_cast<const uint8_t *>(src_buffer.data()), nullptr, nullptr,
        nullptr};
    int src_linesize[4] = {src_w * 3 * static_cast<int>(sizeof(uint16_t)), 0, 0,
                           0};

    if (av_frame_make_writable(frame) < 0) {
      ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
      break;
    }

    sws_scale(sws_ctx, src_data, src_linesize, 0, src_h, frame->data,
              frame->linesize);
    frame->pts = pts++;

    int enc_ret = avcodec_send_frame(codec_ctx, frame);
    if (enc_ret < 0) {
      ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
      break;
    }

    while (true) {
      enc_ret = avcodec_receive_packet(codec_ctx, pkt);
      if (enc_ret == AVERROR(EAGAIN) || enc_ret == AVERROR_EOF) {
        break;
      }
      if (enc_ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(enc_ret, errbuf, sizeof(errbuf));
        LOGE(LOG_TAG,
             "avcodec_receive_packet failed for frame %d with error %d: %s",
             frame_idx, enc_ret, errbuf);
        ret = EXPORT_ERROR_FRAME_PROCESSING_FAILED;
        av_packet_unref(pkt);
        break;
      }

      pkt->stream_index = video_stream->index;
      av_packet_rescale_ts(pkt, codec_ctx->time_base, video_stream->time_base);
      pkt->pos = -1;
      if (av_interleaved_write_frame(fmt_ctx, pkt) < 0) {
        ret = EXPORT_ERROR_IO;
        av_packet_unref(pkt);
        break;
      }
      av_packet_unref(pkt);
    }
    if (ret != EXPORT_SUCCESS)
      break;

    if (progress_callback)
      progress_callback(
          static_cast<int>((frame_idx + 1) * 100.0f / total_frames));
  }

  // Flush encoder
  if (ret == EXPORT_SUCCESS) {
    avcodec_send_frame(codec_ctx, nullptr);
    while (true) {
      int enc_ret = avcodec_receive_packet(codec_ctx, pkt);
      if (enc_ret == AVERROR(EAGAIN) || enc_ret == AVERROR_EOF)
        break;
      if (enc_ret < 0)
        break;
      pkt->stream_index = video_stream->index;
      av_packet_rescale_ts(pkt, codec_ctx->time_base, video_stream->time_base);
      pkt->pos = -1;
      av_interleaved_write_frame(fmt_ctx, pkt);
      av_packet_unref(pkt);
    }
    av_write_trailer(fmt_ctx);
  }

  av_packet_free(&pkt);
  av_frame_free(&frame);
  sws_freeContext(sws_ctx);
  cleanup_audio_transcode(audio_transcode_ctx);
  cleanup_audio_copy(audio_ctx);
  // Note: codec_ctx is managed by batch context, but for per-file output
  // we need to free it here since each file needs its own context
  avcodec_free_context(&codec_ctx);
  avformat_free_context(fmt_ctx);
  free_fd_io(io);

  return ret;
}