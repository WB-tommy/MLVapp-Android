#ifndef MLVAPP_FFMPEG_AUDIO_H
#define MLVAPP_FFMPEG_AUDIO_H

#include <memory>
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/audio_fifo.h"
#include "libswresample/swresample.h"
}

// Audio stream copy context
struct AudioCopyContext {
  AVFormatContext *input_ctx = nullptr;
  int stream_index = -1;
  AVStream *input_stream = nullptr;
  AVStream *output_stream = nullptr;
};

// Audio transcode context (decode -> resample -> encode)
struct AudioTranscodeContext {
  AVFormatContext *input_ctx = nullptr;
  int stream_index = -1;
  AVStream *input_stream = nullptr;
  AVStream *output_stream = nullptr;
  AVCodecContext *decoder_ctx = nullptr;
  AVCodecContext *encoder_ctx = nullptr;
  SwrContext *swr_ctx = nullptr;
  AVFrame *resampled_frame = nullptr;
  AVAudioFifo *fifo =
      nullptr; // For buffering samples to match encoder frame size
  int64_t next_pts = 0;
};

// Audio copy functions
int init_audio_copy(const std::string &audio_path, AVFormatContext *output_fmt,
                    AudioCopyContext &ctx);
int copy_audio_packets(AudioCopyContext &ctx, AVFormatContext *output_fmt);
void cleanup_audio_copy(AudioCopyContext &ctx);

// Audio transcode functions (Opus/Vorbis/AAC)
int init_audio_transcode(const std::string &audio_path,
                         AVFormatContext *output_fmt,
                         AudioTranscodeContext &ctx, bool prefer_opus,
                         bool prefer_aac);
int transcode_audio_packets(AudioTranscodeContext &ctx,
                            AVFormatContext *output_fmt);
void cleanup_audio_transcode(AudioTranscodeContext &ctx);

#endif // MLVAPP_FFMPEG_AUDIO_H
