# MLV Chunks Handling (Android)

This note documents how the code currently handles single‑file `.MLV` clips and multi‑chunk recordings (`.MLV` + `.M00`, `.M01`, …) in the Android build, what went wrong previously, and options to fully support spanned clips via the Android Storage Access Framework (SAF).

## TL;DR
- Previously, chunk handling reused the same `FILE*` up to 100 times for `.MLV`, causing the same file to be scanned repeatedly and inflating frame counts (e.g., 84 → 8400).
- Now, we open exactly one file from the provided file descriptor, and only add real extra chunks if we can resolve and `fopen` sibling files from a real filesystem path.
- Under SAF (URIs without a filesystem path), we treat the selection as a single file; we do not guess sibling chunks to avoid counting bugs.
- To properly support spanned clips under SAF, we will need multiple FDs or folder access to enumerate and open siblings.

## Background
- Multi‑chunk MLV recordings split across files: `<base>.MLV` (or sometimes `<base>.M00` as the first chunk) followed by `<base>.M00`, `<base>.M01`, ... up to `.M99`.
- On desktop builds, the loader finds sibling files on disk by constructing those names and opening them.
- On Android, files are typically accessed via SAF URIs, not raw filesystem paths. You receive a single `fd` for the selected document; you cannot `fopen` siblings by filename unless you also have a resolvable path or additional permissions.

## What was wrong (before the fix)
- Function: `load_all_chunks` in `app/src/main/cpp/src/mlv/video_mlv.c`.
- Behavior (old):
  - Opened the primary file/FD once, then looped `seq_number` up to 99.
  - For each "chunk", it wrote new names (e.g., `.M00`, `.M01`) into a buffer, but instead of actually opening those files, it assigned the same `FILE*` to every entry: `files[*entries] = files[0];`.
  - Result: `openMlvClip` scanned the same stream multiple times—one pass per fake chunk—multiplying the number of parsed `VIDF` frames by ~100. This is why a clip with 84 frames appeared as ~8400 frames.

## Current behavior (after the fix)
- `load_all_chunks`:
  - Always uses `fdopen(fd, "rb")` to create a single `FILE*` for the selected document and sets `*entries = 1`.
  - If `base_filename` appears to be a real filesystem path (contains `/`), it attempts to open additional chunks by constructing uppercase `Mxx` extensions (e.g., `.M00`, `.M01`, …) and calling `fopen` for each that exists. Only successfully opened files are added to the `files` array.
  - If `base_filename` is just a display name (no path), no guessing is performed. We keep only the primary file; no duplicated `FILE*` entries are created.
- `openMlvClip` uses the returned `files` array. With the above logic:
  - Single‑file clips are scanned exactly once; frame counts are correct.
  - Real multi‑chunk clips are supported only when siblings are accessible via `fopen` (i.e., when we have a real path). Under SAF without a path, only the selected file is opened.

## Limitations under Android SAF
- With a single SAF `fd` and no directory access, we cannot reliably locate or open sibling chunk files by name.
- The `MLVI.fileCount` header can tell us that the clip is spanned, but we still need a way to open the other files.
- The current compromise is correctness over guessing: do not fabricate chunks; parse only what we can actually open.

## Proposed improvements (not implemented yet)
- Multiple‑FD JNI API:
  - Add a native entry that accepts an array of file descriptors plus their base names. JNI would pass these to a loader that directly uses `fdopen` for each chunk.
  - Kotlin would gather sibling chunk URIs and obtain FDs via `ContentResolver.openFileDescriptor`.
- Folder access approach:
  - Use `ACTION_OPEN_DOCUMENT_TREE` to pick the parent directory, enumerate siblings matching `<base>.MLV`/`M00..M99` via `DocumentFile`, open each with `ContentResolver`, and pass their FDs to native.
- Reactive prompt based on header:
  - Open the main file; if `MLVI.fileCount > 1` and only one FD was provided, surface a UI hint to select additional chunks or the parent folder.
- Safety and naming details:
  - MLV spans use uppercase `Mxx`. Some recorders may start at `.M00` instead of `.MLV`. Enumeration logic should consider both variants when building the set.

## Test plan ideas
- Single file `.MLV` (not spanned): verify `frames` matches reference (e.g., 84 → 84, not 8400).
- Spanned clip with accessible filesystem path: ensure loader opens `.M00`, `.M01`, … and total `frames` equals the sum across all chunks.
- SAF‑only selection (no path): verify we don’t inflate frame counts and we report only frames from the selected file.
- Header awareness: read `MLVI.fileCount` and confirm it matches the number of opened chunks when we can open siblings.

## Summary
- The frame inflation bug was caused by reusing the same `FILE*` for fabricated chunks.
- The loader now only opens actual files; no duplication.
- Full multi‑chunk support on Android requires passing multiple FDs or enabling directory access to enumerate sibling files. Until then, we prioritize correctness and avoid guessing.

