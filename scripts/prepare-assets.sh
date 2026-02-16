#!/bin/bash
# Copies the ARM64 bitcoind binary and libc++_shared.so into the app assets
# directory for bundling into the APK.
#
# Run this before building the APK:
#   ./scripts/prepare-assets.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"

# Paths
BITCOIND="$PROJECT_DIR/build/android-arm64/bitcoind"
NDK_ROOT="${ANDROID_NDK_ROOT:-/Users/joehey/tools/android-sdk/ndk/27.2.12479018}"
LIBCXX="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"

echo "=== Preparing Bitcoin Pocket Node assets ==="

# Check sources exist
for f in "$BITCOIND" "$LIBCXX"; do
    if [ ! -f "$f" ]; then
        echo "ERROR: Missing $f"
        echo "Build bitcoind first (see docs/cross-compile.md)"
        exit 1
    fi
done

mkdir -p "$ASSETS_DIR"

echo "Copying bitcoind ($(du -h "$BITCOIND" | cut -f1))..."
cp "$BITCOIND" "$ASSETS_DIR/bitcoind"

echo "Copying libc++_shared.so..."
cp "$LIBCXX" "$ASSETS_DIR/libc++_shared.so"

echo ""
echo "Done! Assets ready in $ASSETS_DIR:"
ls -lh "$ASSETS_DIR"
