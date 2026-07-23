# Android shared-native delta

This directory preserves Android-specific changes when the shared native tree
is refreshed from `desktop_src/src`.

## Supported baseline

- Desktop repository commit:
  `877dea2cb9413bd0542abb622af517cf12db63d3`
- Android destination: `app/src/main/cpp/src/`
- Consolidated delta: `current_upstream_android_delta.patch.gz`

The delta includes the complete difference between that upstream baseline and
the verified Android shared-native result: SAF/file-descriptor I/O, dark-frame
ownership, DNG descriptor export, Android build/header fixes, corrected Bayer
GPU playback, MCRAW decoding, and the Android integration of the current Dual
ISO strategy.

The former six-patch stack is retained under
`archive/legacy_split_2026-07-13/` for history only. Do not apply it to the
current upstream tree: patches 01 and 06 overlap changed upstream code, and a
sequential run can leave a partially patched tree.

## Safe refresh workflow

First checkpoint all Android work. The overlay command deliberately replaces
shared upstream files, so never run it over uncommitted implementation work.

Verify the desktop clone:

```bash
git -C ../desktop_src status --short
git -C ../desktop_src rev-parse HEAD
```

The status must be empty and the revision must match the supported baseline.
Then, from `MLVapp_android/`, overlay the upstream shared tree while retaining
Android-only sources and excluding desktop-only assets:

```bash
rsync -a \
  --exclude '/icon/' \
  --exclude '/mlv/OpenJPH/' \
  ../desktop_src/src/ app/src/main/cpp/src/
```

Preflight and apply the single delta:

```bash
bash android_patches/apply_all.sh --check
bash android_patches/apply_all.sh
```

`apply_all.sh` is intentionally strict:

- Full-tree SHA-256 manifests and file-list checks require either the exact
  supported upstream baseline or the exact verified Android result.
- `git apply --check` runs before any write.
- The patch is one transaction; there is no partially applied sequence.
- It never falls back to `--3way`. A mismatch means the delta must be reviewed
  and regenerated for the new upstream revision.
- A second manifest verifies the patched result.

## Isolated rehearsal

To validate without touching the working shared-native tree, create a scratch
repository. Seeding it from Android first retains Android-only files; the next
step overwrites every upstream-owned file with the desktop baseline.

```bash
scratch="$(mktemp -d)"
mkdir -p "$scratch/app/src/main/cpp/src"
rsync -a --exclude '.DS_Store' \
  app/src/main/cpp/src/ "$scratch/app/src/main/cpp/src/"
rsync -a \
  --exclude '/icon/' \
  --exclude '/mlv/OpenJPH/' \
  ../desktop_src/src/ "$scratch/app/src/main/cpp/src/"
rsync -a android_patches/ "$scratch/android_patches/"
git -C "$scratch" init -q
bash "$scratch/android_patches/apply_all.sh" --check
bash "$scratch/android_patches/apply_all.sh"
diff -qr --exclude='.DS_Store' \
  "$scratch/app/src/main/cpp/src" app/src/main/cpp/src
```

An empty `diff` proves the current upstream baseline plus the consolidated
delta reproduces the Android shared-native tree byte for byte.

## When upstream advances

Do not force this patch onto a new revision. In an isolated scratch repository:

1. Seed Android-only files, then overlay the new `desktop_src/src` as above.
2. Commit that untouched baseline.
3. Replace only the scratch `app/src/main/cpp/src/` with the reviewed Android
   target tree.
4. Generate one binary-safe patch with
   `git diff --binary --full-index -- app/src/main/cpp/src`, then store it with
   deterministic `gzip -n -9` compression as
   `current_upstream_android_delta.patch.gz`.
5. Regenerate both SHA-256 manifests for every file in
   `app/src/main/cpp/src/` (excluding `.DS_Store`).
6. Run the isolated rehearsal and the Android native build before replacing
   the artifacts in this directory.

Keep the previous consolidated patch in `archive/` with its upstream revision
when rotating to a newer baseline.
