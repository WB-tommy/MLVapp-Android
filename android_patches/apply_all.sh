#!/usr/bin/env bash
# Apply the current upstream -> Android shared-native delta as one transaction.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR/.." rev-parse --show-toplevel)"
PATCH_ARCHIVE="$SCRIPT_DIR/current_upstream_android_delta.patch.gz"
BASE_MANIFEST="$SCRIPT_DIR/current_upstream_base.sha256"
RESULT_MANIFEST="$SCRIPT_DIR/current_android_result.sha256"
CHECK_ONLY=0
PATCH_FILE=""

cleanup() {
    if [[ -n "$PATCH_FILE" ]]; then
        rm -f "$PATCH_FILE"
    fi
}
trap cleanup EXIT

usage() {
    cat <<'EOF'
Usage: bash android_patches/apply_all.sh [--check]

  --check  Validate the exact upstream baseline and patch applicability only.

The script is intentionally strict and does not attempt a 3-way merge. It
either applies the consolidated patch cleanly or leaves the tree unchanged.
EOF
}

if [[ "$#" -gt 1 ]]; then
    usage >&2
    exit 2
fi

case "${1:-}" in
    "") ;;
    --check) CHECK_ONLY=1 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac

for artifact in "$PATCH_ARCHIVE" "$BASE_MANIFEST" "$RESULT_MANIFEST"; do
    if [[ ! -s "$artifact" ]]; then
        echo "Missing patch artifact: $artifact" >&2
        exit 1
    fi
done

if ! command -v gzip >/dev/null 2>&1; then
    echo "gzip is required to read $PATCH_ARCHIVE" >&2
    exit 1
fi
PATCH_FILE="$(mktemp "${TMPDIR:-/tmp}/mlv-android-delta.XXXXXX.patch")"
gzip -dc "$PATCH_ARCHIVE" > "$PATCH_FILE"
if [[ ! -s "$PATCH_FILE" ]]; then
    echo "Consolidated patch archive is empty: $PATCH_ARCHIVE" >&2
    exit 1
fi

check_manifest() {
    local manifest="$1"
    local expected_files
    local actual_files
    local status=0
    expected_files="$(mktemp "${TMPDIR:-/tmp}/mlv-patch-expected.XXXXXX")"
    actual_files="$(mktemp "${TMPDIR:-/tmp}/mlv-patch-actual.XXXXXX")"

    if command -v shasum >/dev/null 2>&1; then
        (cd "$REPO_ROOT" && shasum -a 256 -c "$manifest") || status=1
    elif command -v sha256sum >/dev/null 2>&1; then
        (cd "$REPO_ROOT" && sha256sum -c "$manifest") || status=1
    else
        echo "Neither shasum nor sha256sum is available." >&2
        status=1
    fi

    awk '{print $2}' "$manifest" | LC_ALL=C sort > "$expected_files"
    (
        cd "$REPO_ROOT"
        find app/src/main/cpp/src -type f ! -name .DS_Store -print | LC_ALL=C sort
    ) > "$actual_files"
    cmp -s "$expected_files" "$actual_files" || status=1

    rm -f "$expected_files" "$actual_files"
    return "$status"
}

if check_manifest "$RESULT_MANIFEST" >/dev/null 2>&1; then
    echo "Android shared-native delta is already applied and verified."
    exit 0
fi

if ! check_manifest "$BASE_MANIFEST" >/dev/null 2>&1; then
    echo "Shared-native files do not match the supported upstream baseline" >&2
    echo "or the verified Android result. No files were changed." >&2
    echo >&2
    echo "Expected desktop upstream: 877dea2cb9413bd0542abb622af517cf12db63d3" >&2
    echo "Re-run the documented isolated overlay, or regenerate this delta" >&2
    echo "for the new upstream revision. Do not force a 3-way apply." >&2
    exit 1
fi

git -C "$REPO_ROOT" apply --check --whitespace=nowarn "$PATCH_FILE"

if [[ "$CHECK_ONLY" -eq 1 ]]; then
    echo "Upstream baseline verified; consolidated patch applies cleanly."
    exit 0
fi

git -C "$REPO_ROOT" apply --whitespace=nowarn "$PATCH_FILE"

if ! check_manifest "$RESULT_MANIFEST" >/dev/null 2>&1; then
    echo "Patch applied, but result verification failed." >&2
    echo "Stop and inspect the touched shared-native files." >&2
    exit 1
fi

echo "Consolidated Android shared-native delta applied and verified."
