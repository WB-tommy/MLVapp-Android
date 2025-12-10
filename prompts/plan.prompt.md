# plan.prompt.md
**Project:** MLV-App Android  
**Core Library:** [mlv-core](https://github.com/WB-tommy/mlv-core)

---

## Why

Bring **Magic Lantern RAW (MLV)** video playback and processing to Android.  
The goal is to reuse the proven desktop processing logic (`mlv-core`) and expose it through a mobile-friendly UI and renderer.

This project exists to make MLV workflows portable — quick previews, metadata inspection, and basic grading on-device — while keeping compatibility with MLV-App’s color science and file formats.

---

## Architecture Overview

- **UI (Kotlin + Jetpack Compose)**  
  Handles screens, SAF file picking, and playback controls.

- **Rendering (OpenGL ES 3.0)**  
  `GLSurfaceView` uploads 16-bit half-float RGB textures from native memory and draws with simple shaders, keeping correct aspect ratio (letterbox/pillarbox).

- **Native Core (C/C++ via JNI)**  
  Shared library [`mlv-core`](https://github.com/WB-tommy/mlv-core) built with NDK/CMake.  
  Provides metadata parsing, frame decoding, debayering, white balance, and color tools.

- **State Layer (ViewModel)**  
  Holds current clip handle, metadata, playback state, frame index, and dimensions.

- **Data Flow**
  SAF → JNI openClip() → mlv-core decode → RGB16F buffer → GL texture → Compose preview

---

## Development Phases

1. **Core Integration**  
 - Set up JNI bridge to `mlv-core` (open/close/decode).  
 - Display first decoded frame on screen.

2. **Playback Loop**  
 - Implement frame stepping, play/pause, and scrub controls.  
 - Support multi-chunk `.M00/.M01...` MLV sets.

3. **Export (Raw)**  
 - Export processed frames via FFmpeg with hybrid codec validation.  
 - Hardware acceleration attempted first (h264_mediacodec, hevc_mediacodec), automatic fallback to software (libx264, libx265).  
 - Structured error codes for better diagnostics.  
 - Validate end-to-end decode → render → encode pipeline.

4. **Processing Tools** *(Lower Priority - Deferred)*  
 - Native processing core already supports exposure, white balance, contrast, curves, etc.  
 - UI integration deferred until export pipeline is stable.  
 - Color grading is useless without working export functionality.

5. **Processed Export**  
 - Extend export pipeline to apply processing before encoding.  
 - Ensure preview and output match visually.

6. **User Experience & Polish**  
 - Add metadata display, persistent settings, error UI, and performance optimizations.

---

## Codec Validation Strategy

**Hybrid Approach**:
- **MediaCodecSupportHelper** scans device capabilities at startup, provides UI hints only.
- **FFmpeg** handles actual encoding with automatic hardware→software fallback.
- Users see clear error messages only when all encoder options fail.
- Focus Pixel Map (FPM) validation happens once during clip selection, cached for export.

**Rationale**: Keeps UI informative while letting FFmpeg's robust fallback handle device variability.

---

This plan defines the stable architecture and intended direction.  
Tasks, experiments, and short-term goals are tracked separately in `tasks.prompt.md`.
