/*
 * Batch Export Context
 *
 * Holds shared encoder state that can be reused across multiple clips
 * in a batch export operation.
 */

#ifndef MLVAPP_BATCH_EXPORT_CONTEXT_H
#define MLVAPP_BATCH_EXPORT_CONTEXT_H

#include "ffmpeg_presets.h"
#include <string>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
}

// Cached encoder information to skip fallback probing on subsequent clips
struct CachedEncoder {
  std::string encoder_name; // Name of the working encoder (e.g., "libx264")
  bool is_hardware = false; // Whether it's a hardware encoder
  bool valid = false;       // Whether the cache is valid
};

// Batch export context - holds state shared across clips
struct BatchExportContext {
  // Cached preset (computed once from export options)
  VideoPreset preset;
  bool preset_initialized = false;

  // Cached working encoder (determined from first clip)
  CachedEncoder cached_encoder;

  // Current codec context (reused when dimensions match)
  AVCodecContext *codec_ctx = nullptr;
  int current_width = 0;
  int current_height = 0;
  AVRational current_fps = {0, 1};

  // Track if context is active
  bool active = false;
};

// Initialize batch export context with export options
// Call once at the start of batch export
void init_batch_context(BatchExportContext &ctx,
                        const export_options_t &options);

// Check if current codec context can be reused for given dimensions
bool can_reuse_codec(const BatchExportContext &ctx, int width, int height,
                     AVRational fps);

// Get or create codec context for batch export
// Returns existing context if dimensions match, creates new one otherwise
AVCodecContext *get_batch_codec_context(BatchExportContext &ctx, int width,
                                        int height, AVRational fps,
                                        int thread_count,
                                        AVFormatContext *fmt_ctx,
                                        AVStream *stream);

// Cleanup batch export context
// Call once at the end of batch export
void cleanup_batch_context(BatchExportContext &ctx);

#endif // MLVAPP_BATCH_EXPORT_CONTEXT_H
