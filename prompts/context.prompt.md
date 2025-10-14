# context.prompt.md
**Project:** MLV-App Android  
**Core Library:** [mlv-core](https://github.com/WB-tommy/mlv-core)  
**Base Reference:** [MLV-App Desktop](https://github.com/ilia3101/MLV-App)

---

## 🧭 Project Summary

MLV-App Android brings **Magic Lantern RAW (MLV)** video playback and basic processing to Android devices.  
It reuses the proven desktop processing engine (`mlv-core`) via JNI and provides a mobile-friendly UI and OpenGL renderer.  
The goal is to make MLV workflows portable — quick previews, metadata inspection, and basic grading on-device — while keeping full compatibility with the MLV-App color science and file formats.

---

## 🏗 Architecture Overview

| Layer | Purpose |
|-------|----------|
| **UI (Kotlin + Jetpack Compose)** | Handles screens, playback controls, and file picking (SAF). |
| **Rendering (OpenGL ES 3.0)** | Uses `GLSurfaceView` to render 16-bit half-float RGB textures with proper aspect ratio. |
| **Native Core (C/C++ via JNI)** | Integrates `mlv-core` for metadata parsing, frame decoding, debayering, white balance, and color tools. |
| **State Layer (ViewModel)** | Maintains current clip handle, playback state, frame index, and metadata. |
| **Data Flow** | SAF → JNI openClip() → mlv-core decode → RGB16F buffer → GL texture → Compose preview. |

---

## ⚙️ Modules & Responsibilities (Android Version)

| Module | Responsibility |
|--------|----------------|
| **Import & I/O** | Open MLV files (including spanned .m00/.m01…), parse metadata, manage session. |
| **Processing Core** | Demosaicing, RAW corrections, color adjustments, filters — implemented in native C++ or Kotlin bridge. |
| **Preview / Rendering** | Real-time frame display, playback (frame-by-frame / drop-frame). |
| **Audio / Sync** | Audio playback and synchronization in drop-frame mode. |
| **Export / Encoding** | Encode processed clips or frame sequences via FFmpeg mobile. |
| **Session / Receipt Management** | Save / restore processing settings, import/export “receipts.” |
| **Scopes & Tools** | Histogram, waveform, RGB parade, vector scope visualizations. |
| **UI / Navigation** | Compose-based navigation, controls, and settings screens. |

---

## 🎛 Key Feature Specifications

### Import & Metadata Parsing
- SAF multi-select; supports spanned .M00/.M01 sets.  
- Extracts frame count, fps, bit depth, camera / lens / WB metadata.  
- Displays preview thumbnail and error UI on failure.

### Preview & Playback
- Modes: exact frame-step and drop-frame real-time.  
- Audio sync (drop-frame).  
- OpenGL rendering of decoded RGB16F buffers.  
- Playback controls: play/pause, scrub, loop, next/previous frame.

### Processing / Adjustments
- Exposure, white balance, contrast (initial).  
- Chainable adjustment pipeline (native C++).  
- Real-time preview approximations; full-precision for export.

### Export / Encoding
- Export to H.264/H.265 or image sequences.  
- FFmpeg mobile backend.  
- Progress / cancel handling; audio muxing; trimming.  
- Clean resource handling on cancel or error.

### Session / Receipts
- Copy/paste settings, import/export .receipt files.  
- Persistent sessions via Room / DataStore.  
- Backward-compatible receipt format.

### Scopes & Analysis Tools
- Optional overlays (histogram, waveform, RGB parade, vector scope).  
- GPU / Canvas rendering; throttle updates for performance.

---

## 📋 Current Development Phases & Status

| Phase | Description | Status |
|-------|--------------|--------|
| **1 – Core Integration** | JNI bridge to mlv-core, initial frame decode. | ✅ Completed |
| **2 – Playback Loop** | Frame stepping, play/pause, scrub controls. | ✅ Completed |
| **3 – Export (Raw)** | Raw RGB16F export pipeline, FFmpeg integration. | 🔄 In Progress |
| **4 – Processing Tools** | Exposure/WB/contrast controls, UI sliders, reset logic. | 🔄 In Progress |
| **5 – Processed Export** | Apply processing before encode; validate output vs preview. | ⏳ Planned |
| **6 – UX / Polish** | Metadata display, persistent settings, error UI, tests. | ⏳ Planned |

### Backlog Ideas
- Histogram & waveform scopes  
- Audio playback sync  
- Batch import/export  
- GPU / Vulkan acceleration  
- Receipt sharing with desktop version  

---

## 🧱 Constraints & Guidelines

- Heavy processing → native C++ (`mlv-core`) for performance.  
- Mobile resource limits → background threads, async UI.  
- Kotlin for UI and state logic; C++ for decode/processing.  
- Use OpenGL ES 3.0 for preview rendering.  
- Storage access via Android SAF.  
- Maintain visual fidelity with desktop MLV-App color science.  

---

## 🧠 Agent / LLM Collaboration Guidelines

- **Planner Agent:** derive new tasks consistent with architecture and specs.  
- **Coder Agent:** implement features within defined modules only; respect constraints.  
- **Reviewer Agent:** verify code matches specs and maintains performance limits.  
- **Documentor Agent:** keep plan/spec/tasks/context files aligned after major updates.

---

## 📚 Source Prompt Files

| File | Role |
|------|------|
| `plan.prompt.md` | Long-term architecture & vision. |
| `specify.prompt.md` | Detailed feature specifications and requirements. |
| `tasks.prompt.md` | Live development roadmap and progress tracker. |
| `context.prompt.md` | **Shared summary for multi-LLM collaboration** — the canonical project context. |
