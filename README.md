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

## Features

- 📹 **Playback** – Scrub through MLV RAW footage with real-time GPU rendering at up to 60fps
- 🎨 **Color Grading** – Adjust white balance, exposure, highlights/shadows, saturation, and debayer quality in real-time
- 🎬 **Export** – Convert to H.264/H.265 (8-bit/10-bit), ProRes, or CinemaDNG with hardware acceleration
- 🔄 **Batch Processing** – Export multiple clips in one job with automatic codec reuse
- 📱 **Adaptive UI** – Optimized for phones, tablets, and foldables with responsive layouts
- 🎯 **Focus Pixel Correction** – Automatic detection and removal of focus pixels for all supported camera models
- 🔊 **Audio Playback** – Synchronized audio during preview and export
- 🌙 **Dark Mode** – Modern Material Design 3 interface

---

## Architecture Highlights

- **Compose UI + ViewModels** –  
  `MainActivity` (`app/src/main/java/fm/forum/mlvapp/MainActivity.kt`) adapts to window size, memory, and CPU availability while routing navigation through a `NavController` that hosts the clip, playback, settings, and export flows.

- **Native decoding pipeline** –  
  `NativeLib` (`app/src/main/java/fm/forum/mlvapp/NativeInterface/NativeLib.kt`) bridges JNI calls to the shared `mlvcore` library, compiled from the original C/C++ sources via NDK (`app/src/main/cpp/CMakeLists.txt`).

- **GPU renderer** –  
  `MlvRenderer` streams 32-bit floating-point RGB (RGB32F) frame buffers to an OpenGL ES 3.0 `GLSurfaceView`, applying aspect-ratio and anamorphic corrections directly on the GPU.

- **FFmpeg export pipeline** –  
  `ExportViewModel` and `ExportService` coordinate background exports using a custom FFmpeg 8.0 build with hardware-accelerated encoders (H.264/H.265 via MediaCodec), batch processing support, and progress tracking through Kotlin `StateFlow`.

- **RAW correction engine** –  
  `RawCorrectionNative` exposes the full MLV processing pipeline to Kotlin, allowing real-time adjustments to white balance, exposure, highlights/shadows, saturation, and debayer quality. Settings are serialized through JNI and applied via `llrpSet*` functions before rendering or export.

- **Focus pixel management** –  
  `FocusPixelManager` downloads and caches required pixel maps from the upstream repository, integrating with the RAW correction pipeline to ensure clips render correctly across all camera models.

---

## Project Structure & Code Conventions

### Folder Structure

The project follows a **feature-based architecture** organized under `app/src/main/java/fm/magiclantern/forum/`:

```
fm.magiclantern.forum/
├── features/               # Feature modules (UI + ViewModel + business logic)
│   ├── clips/             # Clip browsing & management
│   │   ├── ui/           # ClipListScreen, ClipRemovalScreen
│   │   └── viewmodel/    # ClipListViewModel
│   ├── player/            # Video playback & rendering
│   │   ├── ui/           # VideoPlayerScreen, FullScreenView, NavigationBar
│   │   ├── viewmodel/    # PlayerViewModel
│   │   └── *.kt          # MLVRenderer, PlaybackEngine, AudioPlaybackController
│   ├── grading/           # Color grading & RAW correction
│   │   ├── ui/           # ColorGradingScreen, RawCorrectionArea, DebayerSelectScreen
│   │   └── viewmodel/    # GradingViewModel
│   ├── export/            # Video export & encoding
│   │   ├── ui/           # ExportSettingsScreen, ExportSelectionScreen, ExportLocationScreen
│   │   ├── viewmodel/    # ExportViewModel, ExportViewModelFactory
│   │   ├── model/        # ExportOptions, ExportClipPayload, ExportSettings
│   │   └── *.kt          # ExportService, ExportPreferences, ExportFdProvider
│   ├── settings/          # App settings
│   │   ├── ui/           # SettingScreen
│   │   └── viewmodel/    # SettingsViewModel
│   └── onboarding/        # First-run onboarding flow
│       └── *.kt          # OnboardingScreen, OnboardingRepository
├── domain/                # Business logic & domain models
│   ├── model/            # ClipPreview, ClipMetadata, ClipDetails, ClipGradingData
│   └── session/          # ActiveClipHolder (shared state)
├── data/                  # Data layer
│   ├── repository/       # ClipRepository
│   └── *.kt              # ClipPreviewData, ClipMetaData, ProcessingData
├── nativeInterface/       # JNI bridge to C++ core
│   ├── NativeLib.kt      # Main JNI wrapper
│   └── RawCorrectionNative.kt
├── di/                    # Dependency injection (Hilt modules)
│   └── RepositoryModule.kt
├── ui/                    # Shared UI components & theme
├── utils/                 # Utility classes (ClipFormatters, MappStorage)
└── *.kt                   # App-level: MainActivity, MainScreen, NavController, AppBar
```

### Code Conventions

- **Language**: Kotlin 2.0 with Jetpack Compose for UI  
- **Architecture**: MVVM (Model-View-ViewModel) with unidirectional data flow  
- **State Management**: `StateFlow` and `MutableStateFlow` for reactive state  
- **Dependency Injection**: Hilt (see `di/` modules)  
- **Naming**:
  - **Screens**: `*Screen.kt` (e.g., `ClipListScreen.kt`)  
  - **ViewModels**: `*ViewModel.kt` (e.g., `ExportViewModel.kt`)  
  - **Repositories**: `*Repository.kt` (e.g., `ClipRepository.kt`)  
  - **Native interfaces**: `*Native.kt` (e.g., `RawCorrectionNative.kt`)  
- **Package naming**: Follow feature-first organization; shared domain models live in `domain/model/`  
- **JNI**: All native calls go through `nativeInterface/` to keep the boundary explicit  

---

## System Requirements

- **OS**: Android 10 (API 29) or later
- **RAM**: 4GB+ recommended for 4K clips; 6GB+ for batch export
- **CPU**: ARMv8 (64-bit) with NEON support
- **GPU**: OpenGL ES 3.0 compatible
- **Storage**: ~200MB for app + space for exported videos
- **Permissions**: Storage access for reading MLV files and writing exports

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
## FFmpeg Integration

The app bundles a custom FFmpeg build (version **8.0**) compiled with the following configuration:

```bash
  configure \
    --prefix="$PREFIX" \
    --target-os=android \
    --arch="$ARCH" \
    --cpu="$CPU" \
    --enable-cross-compile \
    --sysroot="$SYSROOT" \
    --cc="$CC" --cxx="$CXX" \
    --ar="$AR" --as="$AS" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
    --cross-prefix="$TOOLCHAIN/bin/${BINUTIL_TRIPLE}-" \
    --pkg-config="$PKG_CONFIG_BIN" \
    --pkg-config-flags="--static" \
    \
    --enable-shared --disable-static \
    --enable-pic \
    --disable-doc \
    --disable-debug \
    $PROGRAMS_FLAGS \
    --enable-pthreads \
    --enable-asm \
    --enable-neon \
    --enable-jni \
    --enable-mediacodec \
    \
    $GPL_FLAGS \
    --enable-libx264 \
    --enable-libx265 \
    --enable-libopenjpeg \
    --enable-libvidstab \
    --enable-libvpx \
    --enable-libvorbis \
    --enable-libopus \
    --enable-encoder=$ENCODERS_LIST
```

This build enables hardware‑accelerated encoders (`mediacodec`), NEON optimizations, and a wide range of libraries (x264, x265, libvpx, libopus, …) used by the new export features.

---

## Usage

### Getting Started

1. **Import clips** – Tap the **folder icon** in the top bar to browse your device storage and select `.MLV` files
2. **Preview** – Tap a clip thumbnail to open the player with real-time rendering
3. **Adjust colors** – Swipe up to reveal the **grading panel** and tweak RAW settings (white balance, exposure, saturation, etc.)
4. **Export** – Tap the **export icon**, select clips, choose codec and quality settings, then tap **Start Export**
5. **Monitor progress** – Exports run in the background; check the notification shade for progress

### Tips

- **Fullscreen mode**: Tap the fullscreen icon in the player for distraction-free viewing
- **Frame-by-frame scrubbing**: Drag the timeline slider for precise navigation
- **Reset corrections**: Use the reset buttons in the grading panel to restore original values
- **Batch export**: Select multiple clips in the export screen to process them in one job

---

## Example

[![Watch the demo](https://i.vimeocdn.com/video/2071071018-a9a18f2566d4ef25d51b96d7afacd29fd551b26bac0b96ba7e145db0dd90889b-d_270X534)](https://vimeo.com/1128103176)

---

## Known Limitations

- **CinemaDNG export** – Currently supports only single-frame export; sequence export is in development
- **Audio sync** – May drift slightly on clips longer than 10 minutes due to timestamp precision
- **Hardware encoder** – May fail on resolutions exceeding device capabilities (>4K on some devices); automatically falls back to software encoding
- **Focus pixel maps** – Requires internet connection on first launch to download camera-specific maps
- **Memory usage** – Large clips (>6K resolution) may cause out-of-memory errors on devices with <6GB RAM

---

## Contributing

Contributions are welcome! To contribute:

1. **Fork** this repository and create a feature branch (`feature/your-feature-name`)
2. **Follow** the code conventions documented in "Project Structure & Code Conventions"
3. **Test** your changes:
   ```bash
   ./gradlew test
   ./gradlew connectedAndroidTest  # if you have a device connected
   ```
4. **Commit** with clear, descriptive messages (follow [Conventional Commits](https://www.conventionalcommits.org/))
5. **Open a PR** with a detailed description of your changes and any related issues

### Areas for Contribution

- 🎨 UI/UX improvements for the grading panel
- 🚀 Performance optimizations for 6K+ clips
- 📝 Documentation and code comments
- 🧪 Additional unit and integration tests
- 🌍 Internationalization (i18n) support

---

## License & Credits

**License**: [GNU General Public License v3.0](LICENSE)

**Credits**:
- **[MLV-App](https://github.com/ilia3101/MLV-App)** – Original desktop application by [ilia3101](https://github.com/ilia3101)
- **[FFmpeg](https://ffmpeg.org/)** – Video encoding library (GPL-compatible build)
- **Android Open Source Project** – Jetpack Compose, Material Design 3, and NDK
