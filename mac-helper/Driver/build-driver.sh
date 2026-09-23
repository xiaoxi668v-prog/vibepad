#!/bin/bash
# build-driver.sh — compile the VibePadAudio AudioServerPlugIn into a .driver bundle.
#
# Produces mac-helper/Driver/build/VibePadAudio.driver with the standard bundle layout:
#   Contents/MacOS/VibePadAudio   (universal arm64+x86_64, ad-hoc or identity signed)
#   Contents/Info.plist
#
# Signing identity is taken from the SIGNING_IDENTITY environment variable; when unset the
# bundle is ad-hoc signed ("-"), which is sufficient for locally installed HAL plug-ins.

set -euo pipefail

DRIVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$DRIVER_DIR/VibePadAudio"
BUILD_DIR="$DRIVER_DIR/build"
BUNDLE_DIR="$BUILD_DIR/VibePadAudio.driver"
EXECUTABLE_NAME="VibePadAudio"
SIGNING_IDENTITY="${SIGNING_IDENTITY:--}"

echo ">> Building $EXECUTABLE_NAME from $SRC_DIR"

rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR/Contents/MacOS"

# -Wno-unused-parameter: the COM/IO callback signatures carry many parameters we legitimately
# ignore; everything else stays under -Wall -Wextra.
xcrun clang -bundle -fobjc-arc -O2 \
    -mmacosx-version-min=12.0 \
    -arch arm64 -arch x86_64 \
    -Wall -Wextra -Wno-unused-parameter \
    -framework CoreFoundation \
    -framework CoreAudio \
    -o "$BUNDLE_DIR/Contents/MacOS/$EXECUTABLE_NAME" \
    "$SRC_DIR/VibePadAudioDriver.m"

cp "$SRC_DIR/Info.plist" "$BUNDLE_DIR/Contents/Info.plist"
plutil -lint "$BUNDLE_DIR/Contents/Info.plist"

echo ">> Signing with identity: $SIGNING_IDENTITY"
codesign --force --sign "$SIGNING_IDENTITY" "$BUNDLE_DIR"
codesign --verify --deep --strict --verbose=2 "$BUNDLE_DIR"

echo ">> Built and verified: $BUNDLE_DIR"
