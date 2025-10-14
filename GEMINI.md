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

## File Handling and Loading

The app uses a sophisticated two-phase loading process to provide a responsive user experience.

1.  **Preview Generation (`openClipForPreview`):** When a user first selects a file, the app calls a fast JNI function that reads only the essential headers to generate a thumbnail and extract a unique `guid`. This avoids loading the entire file into memory just to display it in the file list.
2.  **Full Clip Loading (`openClip`):** When the user selects a clip for playback, the app calls a second JNI function that loads the full file(s), parses all metadata, and prepares the audio and video indexes for playback.
3.  **File Type Handling:** The JNI layer in `handle_clip.cpp` now correctly distinguishes between standard `.MLV` files and `.mcraw` files, calling the appropriate C-level parser for each. This ensures that both formats are handled correctly.

## Real-Time Playback and Rendering

The playback engine is designed for synchronized audio and video, using the audio clock as the source of truth.

*   **Audio Master Clock (`AudioPlaybackController.kt`):** This new class is the core of the A/V sync system. It runs a dedicated coroutine to stream audio data from the native library into an Android `AudioTrack`. Crucially, it exposes the `playbackPositionUs()` function, which provides a high-resolution master timestamp based on the audio being played.
*   **Video Slave Loop (`VideoPlayerScreen.kt`):** A `LaunchedEffect` in the video player composable runs the main playback loop. In each iteration, it queries the current time from `AudioPlaybackController` and determines which video frame should be visible. It then updates the `currentFrame` state in the `VideoViewModel`. This forces the video to stay locked to the audio, automatically dropping frames if processing falls behind.
*   **OpenGL Rendering (`MLVRenderer.kt`):** The renderer uses a `GLSurfaceView` and OpenGL ES 3.0. It leverages integer textures (`GL_RGB16UI`) to handle 16-bit video data from the backend without precision loss. Aspect ratio correction is handled in the vertex shader to prevent distortion. The render loop is set to `RENDERMODE_WHEN_DIRTY` and is triggered by changes to the `currentFrame` state in the ViewModel.

## Multi-Chunk and SAF Handling

The application is built to work with Android's Storage Access Framework (SAF) and correctly handles multi-chunk MLV files (`.MLV`, `.M00`, etc.).

*   **File Descriptors:** Instead of file paths, the UI layer passes an array of integer file descriptors (`fd`) down to the native layer.
*   **Native Backend:** The C function `load_all_chunks` was rewritten to accept this array of file descriptors, using `fdopen()` to create `FILE*` streams for each chunk. This avoids fragile filename guessing and works seamlessly with SAF.
*   **Clip Grouping:** The app inspects the header of each selected file to find its `guid`. All files with the same `guid` are grouped into a single `Clip` object, ensuring that all parts of a multi-chunk recording are handled as a single entity.
