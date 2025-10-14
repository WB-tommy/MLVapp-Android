# specify.prompt.md  
**Project:** MLV-App Android Port  
**Base reference:** https://github.com/ilia3101/MLV-App :contentReference[oaicite:1]{index=1}  

---

## 1. Scope & constraints

- The Android version should support core MLV import, processing, preview, and export features (a subset or all as feasible).  
- Performance is critical: many processing operations are computationally heavy (demosaic, curves, filters).  
- May need to offload processing to native (C/C++ via JNI) or leverage GPU / RenderScript / Vulkan / OpenGL ES.  
- UI must be responsive; long-running jobs should run in background, with progress feedback.  
- Storage, memory, and power constraints on mobile must be considered.  
- File access must use Android’s file APIs / SAF (Storage Access Framework).  
- Support for external imports (e.g. from SD cards).  
- Modular architecture: separate UI / processing / I/O layers.

---

## 2. High-level modules (Android version)

| Module | Responsibility | Notes / mapping from desktop |
|---|------------------|-------------------------------|
| **Import & I/O** | Open MLV files (including spanned: .m00, .m01…), import, read metadata, manage session | The desktop app supports spanned files :contentReference[oaicite:2]{index=2} |
| **Processing Core** | Demosaicing, RAW corrections, color adjustments, filters, etc. | Use existing algorithms (if possible via JNI) or port to Kotlin/C++ |
| **Preview / Rendering** | Display frames, timeline, playback (frame‐by‐frame, drop‐frame) | Desktop supports both modes :contentReference[oaicite:3]{index=3} |
| **Audio / Sync** | Audio playback and sync when in drop‐frame playback | Desktop supports audio playback in drop‐frame mode :contentReference[oaicite:4]{index=4} |
| **Export / Encoding** | Export processed clip (various formats), single frames, image sequences, etc. | Desktop supports many formats (ProRes, H.264/265, DNG/PNG, TIFF, etc.) :contentReference[oaicite:5]{index=5} |
| **Session / Project Management** | Manage sessions: open, save, copy/paste “receipts” (processing settings), batch operations | Desktop supports receipt import/export, batch operations :contentReference[oaicite:6]{index=6} |
| **Scopes & Tools** | Histogram, waveform, RGB parade, vector scope | Desktop shows these analysis tools :contentReference[oaicite:7]{index=7} |
| **UI / Navigation** | Screens, controls, menus, settings, navigation between modules | Map desktop UI to Android patterns |

---

## 3. Feature specs (selected features)

Below are detailed specs for a few key features; you can expand this for all features.

---

### 3.1 Import & metadata parsing

**Name:** MLV Import with Metadata Extraction  
**Description:** Allow user to pick an MLV (or spanned set) file, parse its metadata (bit depth, frame info, timecode, camera settings) and add to session.  

**Desktop behavior (reference):**  
- Supports spanned files (e.g. .m00, .m01) :contentReference[oaicite:8]{index=8}  
- Reads embedded metadata: white balance, black/white levels, etc. :contentReference[oaicite:9]{index=9}  

**Android spec:**

- UI: “Import Clip(s)” button → opens SAF picker allowing multi-file selection  
- Logic:
  - Accept file URI(s)
  - Determine whether spanned files apply; group into a single logical clip if so.
  - Parse metadata (frame count, fps, bit depth, camera info, lens data, white balance, etc.)
  - Store metadata in internal data model
- On success: add to session view with thumbnail / preview frame  
- On failure: show error message (file unsupported, parsing error)  

**Edge / constraints:**

- Some spanned sets might not be in same folder—validate relative paths  
- Handle large files without blocking UI thread  
- Must request storage permissions if needed (on older Android versions)  
- Consider memory footprint for reading metadata

---

### 3.2 Preview & Playback

**Name:** Clip Preview / Playback  
**Description:** Show frames from clip; support frame stepping and drop-frame (real time) playback with audio sync.

**Desktop behavior:**  
- Two modes: show every frame, or "drop-frame" (play as many frames as possible) :contentReference[oaicite:10]{index=10}  
- Audio playback in drop-frame mode :contentReference[oaicite:11]{index=11}  
- Navigation: next frame / previous frame, timeline scrubbing :contentReference[oaicite:12]{index=12}  

**Android spec:**

- UI:
  - Video preview area / surface
  - Playback controls: play / pause, next-frame, previous-frame, scrub bar, loop toggle
  - Display timecode/duration label
- Logic:
  - Decode frames (via processing core) into a renderable image buffer (e.g. OpenGL texture or Bitmap)
  - For drop-frame mode: skip frames to maintain real-time speed but still show approximate correct positions
  - Sync audio (if loaded) during drop-frame playback
  - For frame-by-frame mode: exactly step one frame at a time
- Performance considerations:
  - Buffering ahead a few frames
  - Use hardware-accelerated rendering (OpenGL ES / Vulkan) if possible
  - Offload heavy processing to background threads or native code

---

### 3.3 Processing / Adjustments

**Name:** Adjustment Pipeline (Exposure, Contrast, Color, Filters, etc.)  
**Description:** Apply chained processing steps to raw frames (e.g. white balance, contrast, curves, sharpness) and display the result in real-time (preview) and prepare for export.

**Desktop behavior:**  
- The desktop app supports a wide set of processing features: exposure, contrast, white balance, clarity, vibrance, saturation, highlight/shadow adjustments, curves, sharpening, hue vs hue/saturation, denoising filters, toning, RAW corrections, LUTs, etc. :contentReference[oaicite:13]{index=13}  
- Also supports advanced demosaicing algorithms (AHD, LMMSE, DCB, etc.) :contentReference[oaicite:14]{index=14}  

**Android spec:**

- UI:
  - Settings / sliders / input controls for each adjustment (e.g. exposure, contrast, curves)
  - Toggle filters on/off
  - Reset button, copy/paste settings (“receipt”)
- Logic:
  - Represent adjustments as a processing pipeline or chain
  - For preview: apply pipeline to current frame in a performance-optimized way
  - For export: apply full-precision pipeline to all frames
- Implementation:
  - Likely via native code / C++ using existing algorithms (if licensing allows)
  - Use efficient memory management, e.g. operate on small tiles / blocks
- Constraints:
  - Some adjustments are expensive; consider limiting defaults or using lower resolution previews
  - For real-time preview, approximate or partial calculations may be acceptable

---

### 3.4 Export / Encoding

**Name:** Clip Export / Format Conversion  
**Description:** Export processed clip (or frames) into various output formats (video or image sequences) with audio (when applicable).

**Desktop behavior:**  
- Many supported formats: ProRes (various profiles), H.264/265, RAW AVI, DNG, TIFF, PNG, DNxHD / HR, etc. :contentReference[oaicite:15]{index=15}  
- Supports batch export, clip trimming, aspect ratio adjustments, HDR blending, etc. :contentReference[oaicite:16]{index=16}  

**Android spec:**

- UI:
  - Export screen with format selector, codec options, resolution, frame range (start/end), audio inclusion toggle
  - Progress indicator and cancel option
- Logic:
  - Given a clip and processing pipeline, walk each frame, apply processing, encode to target format
  - If video + audio: mux processed video and audio streams
  - Support trimming (only export a subsection)
  - Support batch exports (multiple clips) if memory allows
- Backend:
  - Use FFmpeg (via mobile FFmpeg wrapper) or built-in encoding libraries
  - Manage threading / buffering
- Edge cases:
  - Fail gracefully on resource exhaustion (storage, memory)
  - Clean up partial output on cancel
  - Ensure correct profile & bit depth support (if underlying codec supports it on mobile)

---

### 3.5 Session / Receipt Management

**Name:** Sessions & Receipt (Processing Settings)  
**Description:** Allow user to manage a session of multiple clips, copy & paste processing settings (“receipts”), reset settings, import/export receipt files.

**Desktop behavior:**  
- Supports receipt import/export, batch paste, reset receipts, etc. :contentReference[oaicite:17]{index=17}  
- Sessions: open, import into current session, delete from session, save session metadata etc. :contentReference[oaicite:18]{index=18}  

**Android spec:**

- UI:
  - Session view: list of clips with thumbnails, metadata, controls (delete, reorder, import receipt, export receipt)
  - For a clip: option to “Paste Receipt”, “Copy Receipt”, “Reset to default”
- Logic:
  - Receipt = serialized JSON / binary of processing settings
  - When pasting: merge or replace settings
  - Import/export: allow sharing .receipt files (e.g. via share sheet or file pickers)
  - Save session metadata persistently (local database or file)
- Edge:
  - Versioning / backward compatibility of receipts
  - Handling missing fields or new features gracefully

---

### 3.6 Scopes & Analysis Tools

**Name:** Histogram, Waveform, RGB Parade, Vector Scope  
**Description:** Visualize image analysis overlays while previewing frames.

**Desktop behavior:**  
- Provides these scopes and markers for under/over exposure etc. :contentReference[oaicite:19]{index=19}  

**Android spec:**

- UI:
  - Toggleable overlay or side panel showing scopes (histogram, waveform, RGB parade, vector scope)
  - Allow resizing / hiding
- Logic:
  - For the current preview frame, compute the data (histogram counts, waveform, etc.)
  - Render scope visuals efficiently (e.g. via Canvas or GPU shaders)
  - Update scopes in real-time or with minimal lag
- Constraints:
  - Scope calculations are extra CPU overhead, may throttle or reduce resolution for real-time responsiveness
  - Offer option to disable scopes for performance
