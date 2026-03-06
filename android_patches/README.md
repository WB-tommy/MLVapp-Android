# Android Patches

These patches restore all Android-specific modifications after syncing
`mlv-core` from the upstream desktop repository.

---

## When to use

Every time you sync files from `desktop_src/` into
`app/src/main/cpp/src/`, run:

```bash
cd MLVapp_android/
bash android_patches/apply_all.sh
```

---

## Patch overview

| File | What it patches | Why needed |
|------|----------------|------------|
| `01_fd_based_file_io.patch` | `video_mlv.c/h`, `mcraw.c/h` | Restores `int fd` / `int *fds` parameters in `openMlvClip`, `openMcrawClip`, `initMlvObjectWithClip`, `initMlvObjectWithMcrawClip`, `mr_decoder_open`. Android scoped storage cannot use file paths — Java must open files and pass file descriptors to native code. |
| `02_dark_frame_fds.patch` | `llrawproc_object.h`, `darkframe.h`, `darkframe.c` | Restores `dark_frame_fds[1]` field and updates the `openMlvClip` call in `darkframe.c` to use the stored fd. |
| `03_save_dng_fd.patch` | `dng.c`, `dng.h` | Adds `saveDngFrameFd(int fd, ...)` — saves a DNG frame to a pre-opened file descriptor. Required for Android scoped storage during DNG export. |
| `04_cmake_fixes.patch` | `librtprocess/src/CMakeLists.txt` | Restores `librtprocesswrapper.cpp` to the rtprocess source list and `librtprocesswrapper.h` to `PUBLIC_HEADER`. Upstream drops these Android-specific wrapper files on every sync. |
| `05_header_fixes.patch` | `image_profile.h`, `processing.c` | Adds missing `#include <stdint.h>` to `image_profile.h` (fixes `uint8_t` unknown type on Android NDK), and adds `#include <math.h>` + macros to `processing.c`. |

---

## Files that do NOT need patches

| File | Reason |
|------|--------|
| `video_mlv_misc.c` | Exists in upstream (`desktop_src/src/mlv/`) and is synced normally. Android-specific `#ifdef ANDROID` blocks are already inside it. |
| `jni/clip/handle_clip.cpp` | Android-only file, never overwritten by upstream sync. |
| `CMakeLists.txt` (top-level) | Android-only build file, never overwritten by upstream sync. |

---

## If a patch conflicts

When upstream changes the same area as a patch, `apply_all.sh` automatically
retries with `--3way` merge. If that also fails:

```bash
# Apply with conflict markers
git apply --3way android_patches/01_fd_based_file_io.patch

# Edit the conflicted file, look for <<<<<<< markers
# Then mark as resolved
git add <resolved_file>
```

Continue with the next patch manually:
```bash
git apply --3way android_patches/02_dark_frame_fds.patch
# ...etc
```

---

## Notes

- `01_fd_based_file_io.patch` is the largest (~25KB) because it includes
  both Android fd changes AND the upstream Dual ISO overhaul mixed together.
  If it conflicts heavily, consider applying it with `--3way` from the start.

- `05_thumbnail_android.patch` creates a **new file** (`video_mlv_misc.c`).
  If upstream adds a file with the same name, you will need to merge manually.

- Patches were generated on **2026-03-06** against commit `20558de`.
