#!/bin/bash
# uninstall-driver.sh — remove VibePadAudio.driver from the system HAL plug-in directory.
#
# Executed as root (via osascript "with administrator privileges" from the VibePad helper app).
# Idempotent: a missing driver is not an error. Callers are responsible for also destroying the
# VibePad aggregate device via AudioHardwareDestroyAggregateDevice.

set -euo pipefail

readonly DRIVER_NAME="VibePadAudio.driver"
readonly HAL_DIR="/Library/Audio/Plug-Ins/HAL"
readonly TARGET="$HAL_DIR/$DRIVER_NAME"

if [ "$(id -u)" -ne 0 ]; then
    echo "uninstall-driver.sh: must run as root" >&2
    exit 77 # EX_NOPERM
fi

if [ -d "$TARGET" ]; then
    rm -rf "$TARGET"
    echo "uninstall-driver.sh: removed $TARGET"
else
    echo "uninstall-driver.sh: $TARGET is not installed; nothing to do"
fi

# coreaudiod rescans the HAL directory on restart; launchd relaunches it immediately.
killall coreaudiod 2>/dev/null || true
