#!/bin/bash
# =============================================================================
# apply_all.sh — Apply all Android-specific patches after an upstream sync
#
# Usage (run from MLVapp_android/ directory):
#   bash android_patches/apply_all.sh
#
# If a patch fails due to upstream conflict, use --3way for that patch:
#   git apply --3way android_patches/01_fd_based_file_io.patch
# Then resolve conflict markers manually, then continue with the next patch.
# =============================================================================

set -e
PATCH_DIR="$(dirname "$0")"
FAILED=()
SKIPPED=()

apply_patch() {
    local num="$1"
    local name="$2"
    local file="$PATCH_DIR/${num}_${name}.patch"

    echo ""
    echo "──────────────────────────────────────────"
    echo "  Applying ${num}_${name}.patch ..."
    echo "──────────────────────────────────────────"

    if [ ! -f "$file" ]; then
        echo "  [SKIP] File not found: $file"
        SKIPPED+=("$file")
        return
    fi

    if [ ! -s "$file" ]; then
        echo "  [SKIP] Empty patch (no changes)"
        SKIPPED+=("$file")
        return
    fi

    if git apply --check "$file" 2>/dev/null; then
        git apply "$file"
        echo "  [OK]"
    else
        echo "  [CONFLICT] Clean apply failed — retrying with 3-way merge..."
        if git apply --3way "$file"; then
            echo "  [OK via 3-way merge — check for conflict markers!]"
        else
            echo "  [FAILED] Manual resolution required."
            echo "  Run: git apply --3way $file"
            FAILED+=("$file")
        fi
    fi
}

echo ""
echo "============================================="
echo "  MLVapp Android Patch Applicator"
echo "  $(date)"
echo "============================================="

# Apply in dependency order
apply_patch "01" "fd_based_file_io"    # video_mlv.c/h, mcraw.c/h
apply_patch "02" "dark_frame_fds"       # llrawproc_object.h, darkframe.h/c
apply_patch "03" "save_dng_fd"          # dng.c/h
apply_patch "04" "cmake_fixes"          # librtprocess/src/CMakeLists.txt
apply_patch "05" "header_fixes"         # image_profile.h, processing.c

echo ""
echo "============================================="
if [ ${#FAILED[@]} -eq 0 ] && [ ${#SKIPPED[@]} -eq 0 ]; then
    echo "  All patches applied successfully!"
elif [ ${#FAILED[@]} -eq 0 ]; then
    echo "  Done. ${#SKIPPED[@]} patch(es) skipped (empty or missing)."
else
    echo "  Done with ${#FAILED[@]} failure(s). Manual resolution needed:"
    for f in "${FAILED[@]}"; do
        echo "    - $f"
    done
    echo ""
    echo "  For each failed patch:"
    echo "    git apply --3way <patch_file>"
    echo "  Then resolve conflicts and: git add <file>"
fi
echo "============================================="
