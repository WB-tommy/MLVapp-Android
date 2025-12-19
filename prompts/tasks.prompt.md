# tasks.prompt.md
**Project:** MLV-App Android  
**Last Updated:** (update as needed)

This file lists current development tasks derived from `plan.prompt.md`.  
Each section can change freely — completed tasks should be marked `[x]`, new ones appended as `[ ]`.

---

## Phase 1 – Core Integration
- [x] Phase 1 - Confirm native library loads on all supported ABIs.
- [x] Phase 1 – Set up JNI bridge for `mlv-core` (openClip, fillFrame16, closeClip).
- [x] Phase 1 – Decode first frame and display via `GLSurfaceView`.
- [x] Phase 1 – Implement RAM/core count detection and pass to native.
- [x] Phase 1 – Handle SAF multi-select and group chunked MLV files by GUID.
- [x] Phase 1 - Handle `.mcraw` files in addition to `.MLV`.
- [x] Phase 1 - Implement two-phase clip loading (fast preview and full load).

---

## Phase 2 – Playback Loop
- [x] Add play/pause, prev/next frame, first/last frame controls.
- [x] Maintain playback state in `VideoViewModel`.
- [x] Update GL texture per decoded frame with correct aspect ratio.
- [x] Implement audio playback and A/V sync with audio as master clock.
- [x] Implement frame stepping vs. real-time playback modes.
- [x] Measure decode/render performance on test device.

---

## Phase 3 – Export (Raw)
- [x] Export CinemaDNG (CDNG) image sequence support.
- [x] Add audio-only export option.
- [x] Integrate FFmpeg mobile build
- [x] Add progress and cancel support
- [x] Implement hybrid codec validation (MediaCodec for UI hints, FFmpeg for execution)
- [x] Add hardware→software encoder fallback (h264_mediacodec → libx264, etc.)
- [x] Implement structured error codes (CODEC_UNAVAILABLE, IO_ERROR, etc.)
- [x] Add comprehensive logging at decision points
- [x] Remove duplicate FPM checking (now done once at selection)
- [x] Verify exported video matches preview across all codecs
- [x] Add retry logic for transient I/O errors

---

## Phase 4 – Processing Tools
- [x] Surface focus-pixel correction controls in UI (auto-download ready).
- [x] auto-download and cache focus pixel maps alongside clips.
- [x] Expose native controls for raw corrections.
- [ ] Expose native controls for exposure contrast.
- [ ] Apply adjustments in preview and verify visual effect.
- [ ] Create Compose sliders for processing parameters.
- [ ] Add reset and default value handling.
- [ ] Optimize JNI calls to minimize latency.

---

## Phase 5 – Processed Export
- [ ] Apply processing pipeline before frame encoding
- [ ] Compare visual output vs. preview
- [ ] Validate performance and memory usage

---

## Phase 6 – User Experience / Polish
- [x] Persist user settings (e.g., debayering mode) using SharedPreferences.
- [ ] Display metadata (camera, lens, ISO, FPS, etc.).
- [ ] Add error UI for failed opens or decode issues.
- [ ] Improve playback controls UI/UX (icons, gestures).
- [ ] Write small integration tests for clip open/close and frame rendering.

---

## Backlog / Ideas
- [ ] Histogram and waveform scopes.
- [ ] Batch import/export multiple clips.
- [ ] GPU or Vulkan path for preview acceleration.
- [ ] Share/export receipts between desktop and Android versions.
- [ ] Codec capability probing (pre-test with dummy frames).
- [ ] Adaptive bitrate based on device performance.
- [ ] Telemetry for encoder success/failure stats.
