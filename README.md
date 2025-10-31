# MLV-App Android

**MLV-App Android** is an Android port of the open-source [MLV-App](https://github.com/ilia3101/MLV-App), a toolkit for processing **Magic Lantern RAW (MLV)** video files.  
This project brings professional-grade RAW video decoding, playback, and export tools to mobile devices — integrating native C++ code with a modern **Kotlin + Jetpack Compose** interface and **OpenGL ES** rendering pipeline.

---

## Overview

Think of it as **“Lightroom for Magic Lantern video”**, designed for Android.

- View and scrub through Magic Lantern `.MLV` RAW footage directly on your phone or tablet.
- Export to multiple video formats with background progress handling.
- Built entirely with modern Android tools: **Compose UI**, **Kotlin coroutines**, **StateFlow**, and **NDK**.

This repository demonstrates my experience with:
- Native/managed interop (JNI)
- GPU-based video rendering
- Long-running background services
- Android system integration and performance tuning

---

## Architecture Highlights

- **Compose UI + ViewModels** –  
  `MainActivity` (`app/src/main/java/fm/forum/mlvapp/MainActivity.kt`) adapts to window size, memory, and CPU availability while routing navigation through a `NavController` that hosts the clip, playback, settings, and export flows.

- **Native decoding pipeline** –  
  `NativeLib` (`app/src/main/java/fm/forum/mlvapp/NativeInterface/NativeLib.kt`) bridges JNI calls to the shared `mlvcore` library, compiled from the original C/C++ sources via NDK (`app/src/main/cpp/CMakeLists.txt`).

- **GPU renderer** –  
  `MlvRenderer` streams 32-bit floating-point RGB (RGB32F) frame buffers to an OpenGL ES 3.0 `GLSurfaceView`, applying aspect-ratio and anamorphic corrections directly on the GPU.

- **Export service** –  
  `ExportViewModel` and `ExportService` coordinate foreground exports, expose progress through Kotlin `StateFlow`, and allow cancellation from the Android notification shade.

- **Focus pixel management** –  
  `FocusPixelManager` downloads and caches required pixel maps from the upstream repository so clips render correctly across all devices.

---

## Build & Run

1. Install [Android Studio](https://developer.android.com/studio) **Hedgehog or newer** with the following components:
   - Android SDK 36  
   - Android NDK `28.1.13356709`  
   - CMake `3.22.1`

2. Clone this repository and open it in Android Studio.  
   The Gradle configuration uses **Kotlin 2.0**, **Compose BOM 2024.09**, and **AGP 8.13** (`app/build.gradle.kts`).

3. Let Gradle sync, then build the native library through the **externalNativeBuild** task (Android Studio runs this automatically).

4. Run:
   ```bash
   ./gradlew assembleDebug
   ```
   or simply press Run in Android Studio to install the app on a device running Android 10 (API 29) or later.

---

## Example

[![Watch the demo](https://i.vimeocdn.com/video/2071071018-a9a18f2566d4ef25d51b96d7afacd29fd551b26bac0b96ba7e145db0dd90889b-d_270X534)](https://vimeo.com/1128103176)

---

## License & Credits

Licensed under the GNU General Public License v3.0.
Core decoding and processing code from the upstream MLV-App project.
Focus pixel maps downloaded from the official MLV-App repository to preserve image fidelity.