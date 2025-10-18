# GEMINI.md

## Project Overview

This project is an Android application for processing and playing MLV (Magic Lantern Video) and mcraw files. The application features a user interface built with Jetpack Compose, a state-driven architecture using a ViewModel, and a C/C++ backend for high-performance video and audio processing.

**Key Technologies:**

*   **Frontend:** Kotlin, Jetpack Compose
*   **Architecture:** MVVM-like pattern with `ViewModel`, `StateFlow`, and a `Repository` for settings.
*   **Backend:** C/C++ (`libmlvcore.so`)
*   **Build System:** Gradle, CMake
*   **Platform:** Android

## Core Architecture

The application follows a modern Android architecture designed for separation of concerns and efficient state management.

*   **UI Layer (`MainScreen.kt`, `VideoPlayerScreen.kt`):** The UI is built with Jetpack Compose. `MainScreen` acts as the primary container, handling navigation and the file selection list. `VideoPlayerScreen` is dedicated to video playback, observing state from the `VideoViewModel`.
*   **ViewModel (`VideoViewModel.kt`):** This class serves as the central hub for UI state. It manages the currently loaded clip, playback status (`isPlaying`, `isLoading`), the current frame number, and exposes all clip metadata to the UI.
*   **Settings (`SettingsRepository.kt`, `SettingsViewModel.kt`):** User preferences, such as debayering mode and playback strategy (drop-frame vs. single-step), are managed by a `SettingsRepository`. This repository persists settings to `SharedPreferences` and exposes them as `StateFlow`s for the rest of the app to observe.
*   **Data Model (`Clip.kt`, `ClipMetaData.kt`):** The `Clip` data class holds all information related to a media file, including its source URIs, metadata, native handle, and playback state. `ClipMetaData` is a dedicated class for deserializing data from the JNI layer.
*   **System Awareness:** The application detects the device's RAM and core count, passing this information to the native layer to optimize processing performance and memory allocation.

## File Handling and Loading

The app uses a sophisticated two-phase loading process to provide a responsive user experience.

1.  **Preview Generation (`openClipForPreview`):** When a user first selects a file, the app calls a fast JNI function that reads only the essential headers to generate a thumbnail and extract a unique `guid`. This avoids loading the entire file into memory just to display it in the file list.
2.  **Full Clip Loading (`openClip`):** When the user selects a clip for playback, the app calls a second JNI function that loads the full file(s), parses all metadata, and prepares the audio and video indexes for playback.
3.  **File Type Handling:** The JNI layer in `handle_clip.cpp` now correctly distinguishes between standard `.MLV` files and `.mcraw` files, calling the appropriate C-level parser for each. This ensures that both formats are handled correctly.
4.  **Focus Pixel Map Handling:** The app automatically downloads and caches focus pixel maps from an external server. These maps are used by the native layer to correct for sensor artifacts, and the UI provides controls for managing this feature.

## Real-Time Playback and Rendering

The playback engine is designed for synchronized audio and video. To ensure robust playback for all files, including those without audio, the video timeline serves as the master clock.

*   **Video Master Clock (`VideoPlayerScreen.kt`):** The main playback loop, implemented as a `LaunchedEffect` in the video player, is the heart of the playback system. It advances the `currentFrame` state in the `VideoViewModel` based on the clip's FPS, creating a master timeline.
*   **Audio Slave (`AudioPlaybackController.kt`):** When an audio track is present, the `AudioPlaybackController` synchronizes audio playback to the video master clock. It streams audio data and adjusts its timing to match the video frame being displayed, ensuring A/V sync.
*   **OpenGL Rendering (`MLVRenderer.kt`):** The renderer uses a `GLSurfaceView` and OpenGL ES 3.0. For broad device compatibility, it uses 32-bit float textures (`GL_RGB32F`). The native backend provides the frame data in this 32-bit float format, which is then uploaded to the GPU. Aspect ratio correction is handled in the vertex shader to prevent distortion. The render loop is set to `RENDERMODE_WHEN_DIRTY` and is triggered by changes to the `currentFrame` state in the ViewModel.

## Multi-Chunk and SAF Handling

The application is built to work with Android's Storage Access Framework (SAF) and correctly handles multi-chunk MLV files (`.MLV`, `.M00`, etc.).

*   **File Descriptors:** Instead of file paths, the UI layer passes an array of integer file descriptors (`fd`) down to the native layer.
*   **Native Backend:** The C function `load_all_chunks` was rewritten to accept this array of file descriptors, using `fdopen()` to create `FILE*` streams for each chunk. This avoids fragile filename guessing and works seamlessly with SAF.
*   **Clip Grouping:** The app inspects the header of each selected file to find its `guid`. All files with the same `guid` are grouped into a single `Clip` object, ensuring that all parts of a multi-chunk recording are handled as a single entity.
