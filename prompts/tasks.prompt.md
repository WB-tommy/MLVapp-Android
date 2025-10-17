# tasks.prompt.md
**Project:** MLV-App Android  
**Last Updated:** (update as needed)

This file lists current development tasks derived from `plan.prompt.md`.  
Each section can change freely — completed tasks should be marked `[x]`, new ones appended as `[ ]`.

---

## Phase 1 – Core Integration

---

## Phase 2 – Playback Loop
- [ ] Implement frame stepping vs. real-time playback modes.
- [ ] Measure decode/render performance on test device.

---

## Phase 3 – Export (Raw)
- [ ] Implement export action (basic RGB16F → video or image sequence)
- [ ] Integrate FFmpeg mobile build
- [ ] Verify exported video matches preview
- [ ] Add progress and cancel support

---

## Phase 4 – Processing Tools
- [ ] Expose native controls for exposure, white balance, and contrast.
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

---

## Completed
- [x] Phase 1 - Confirm native library loads on all supported ABIs.
- [x] Phase 1 – Set up JNI bridge for `mlv-core` (openClip, fillFrame16, closeClip).
- [x] Phase 1 – Decode first frame and display via `GLSurfaceView`.
- [x] Phase 1 – Implement RAM/core count detection and pass to native.
- [x] Phase 1 – Handle SAF multi-select and group chunked MLV files by GUID.
- [x] Phase 1 - Handle `.mcraw` files in addition to `.MLV`.
- [x] Phase 1 - Implement two-phase clip loading (fast preview and full load).
- [x] Phase 2 – Add play/pause, prev/next frame, first/last frame controls.
- [x] Phase 2 – Maintain playback state in `VideoViewModel`.
- [x] Phase 2 – Update GL texture per decoded frame with correct aspect ratio.
- [x] Phase 2 - Implement audio playback and A/V sync with audio as master clock.
- [x] Phase 4 – Surface focus-pixel correction controls in UI (auto-download ready).
- [x] Phase 4 – auto-download and cache focus pixel maps alongside clips.
- [x] Phase 6 - Persist user settings (e.g., debayering mode) using SharedPreferences.