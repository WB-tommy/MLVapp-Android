# GEMINI.md

## Project Overview

This project is an Android application for processing MLV (Magic Lantern Video) files. The application consists of a user interface built with Jetpack Compose and a C/C++ backend for processing the MLV data.

The core logic is implemented in C/C++ and is organized into several static libraries that are ultimately linked into a single shared library, `libmlvcore.so`. This library is then loaded into the Android application and called from Kotlin/Java using JNI.

**Key Technologies:**

*   **Frontend:** Kotlin, Jetpack Compose
*   **Backend:** C/C++
*   **Build System:** Gradle, CMake
*   **Platform:** Android

## Building and Running

The project can be built and run using Android Studio or the Gradle wrapper.

**Using Android Studio:**

1.  Open the project in Android Studio.
2.  Sync the project with Gradle files.
3.  Select a run configuration and a target device/emulator.
4.  Click the "Run" button.

**Using Gradle:**

To build the project from the command line, use the following command:

```bash
./gradlew assembleDebug
```

To install the application on a connected device, use:

```bash
./gradlew installDebug
```

**Running Tests:**

To run the unit tests, use the following command:

```bash
./gradlew test
```

To run the instrumented tests, use:

```bash
./gradlew connectedAndroidTest
```

## Development Conventions

*   **Code Style:** The project uses the standard Kotlin and C++ code styles. Use Android Studio's auto-formatting features to maintain a consistent style.
*   **Testing:** Unit tests are located in the `app/src/test` directory. Instrumented tests are in the `app/src/androidTest` directory. All new features should be accompanied by corresponding tests.
*   **JNI:** The communication between the Kotlin/Java and C/C++ code is handled through JNI. The JNI interface is defined in `app/src/main/cpp/jni/mlv_processor_jni.cpp`.

## Rendering Architecture

The application uses OpenGL ES 3.0 via a `GLSurfaceView` to render video frames for high performance and to correctly display high-bit-depth color.

### Data Pipeline

The video data pipeline is designed for memory safety and performance:

1.  The Kotlin/UI layer requests a frame from the `ViewModel`.
2.  A direct `ByteBuffer` is allocated on the Kotlin side to hold the frame data. This avoids memory leaks by keeping buffer management within the garbage-collected environment.
3.  The buffer is passed to the C++ backend via a JNI function (`fillFrame16`).
4.  The C++ library performs a simple debayering operation and fills the buffer with 3-channel (RGB), 16-bit per channel, color data.

### OpenGL Implementation

The `MlvRenderer.kt` class implements the `GLSurfaceView.Renderer` and contains the core rendering logic:

*   **Integer Textures:** To handle the 16-bit integer data from the C++ library without losing precision, the renderer uses an integer texture pipeline. The texture is created with the `GL_RGB16UI` internal format, and the fragment shader uses a `usampler2D` to sample it. This is the key to correctly processing the high-bit-depth data.
*   **Texture Filtering:** Integer textures do not support linear filtering. The renderer correctly sets the `TEXTURE_MIN_FILTER` and `TEXTURE_MAG_FILTER` to `GL_NEAREST`.
*   **Aspect Ratio:** To ensure the video is never stretched or cropped, aspect ratio correction is handled inside the vertex shader. A `uScale` uniform is calculated in Kotlin by comparing the video's aspect ratio to the view's aspect ratio. This uniform then scales the drawing quad, effectively letterboxing or pillarboxing the image to fit perfectly in any view.
*   **Render Loop:** The `GLSurfaceView` is set to `RENDERMODE_WHEN_DIRTY` for efficiency. The render loop is driven from the Jetpack Compose UI. An `update` block on the `AndroidView` composable calls `requestRender()` whenever the `currentFrame` state in the `ViewModel` changes. This is used for frame-stepping. For continuous playback, the `VideoPlayerScreen` is responsible for triggering recomposition.

## Multi-Chunk File Handling

A significant refactoring was undertaken to correctly handle multi-chunk MLV files (e.g., `.MLV`, `.M00`, `.M01`) within the constraints of Android's Storage Access Framework (SAF).

### The Challenge

The original implementation relied on traditional filesystem access, where it would guess the filenames of subsequent chunks based on the first file's name (e.g., incrementing from `M00` to `M01`). This approach fails on modern Android, where SAF provides access via file descriptors (`fd`) without exposing direct file paths, making filename manipulation impossible. This led to bugs where only the first chunk of a multi-part file was loaded, resulting in incorrect frame counts and incomplete data.

### The Solution

A new architecture was implemented, passing an array of file descriptors from the Kotlin/UI layer down to the native C/C++ backend.

**1. User Interface (Kotlin):**
*   The user can now select multiple files at once.
*   When files are selected, the application inspects the header of each file to retrieve the `fileGuid` from the `MLVI` block. This `guid` is a unique identifier for a single recording.
*   All selected files are grouped by their `guid`. This allows the app to correctly associate all chunks with their parent recording.
*   The logic also supports incremental selection: if a user later selects more chunks belonging to an already-loaded clip, the new files are merged into the existing clip object. Duplicate file selections are automatically handled.

**2. JNI Interface:**
*   The `openClip` JNI function signature was changed from accepting a single `jint fd` to a `jintArray fds`.

**3. Native Backend (C/C++):**
*   The core C function `load_all_chunks` was completely rewritten. It no longer performs any filename guessing.
*   It now accepts an array of integer file descriptors and their count.
*   It iterates through the array, using `fdopen()` on each descriptor to create a valid `FILE*` stream for each chunk. This ensures all parts of the recording are opened and indexed correctly.

**4. Data Model (`Clip` class):**
*   The `Clip` data class was updated to hold a list of `Uri`s, representing all the chunks associated with that clip.
*   The `equals()` method for this class is intentionally overridden to only compare the `guid`. This establishes a stable identity for a recording, which is crucial for grouping chunks and managing the UI list, regardless of which or how many chunks have been selected by the user.