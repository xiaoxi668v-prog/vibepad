#!/bin/bash
# install-driver.sh — install VibePadAudio.driver into the system HAL plug-in directory.
#
# Executed as root (via osascript "with administrator privileges" from the VibePad helper app).
# Usage: install-driver.sh /path/to/VibePadAudio.driver
#
# Idempotent: an existing installation is replaced. On any failure before the final swap the
# previous installation is left untouched; a failed swap restores it.

set -euo pipefail

readonly DRIVER_NAME="VibePadAudio.driver"
readonly HAL_DIR="/Library/Audio/Plug-Ins/HAL"
readonly TARGET="$HAL_DIR/$DRIVER_NAME"

SOURCE="${1:-}"
if [ -z "$SOURCE" ] || [ ! -d "$SOURCE" ]; then
    echo "install-driver.sh: usage: install-driver.sh /path/to/VibePadAudio.driver" >&2
    exit 64 # EX_USAGE
fi
if [ ! -x "$SOURCE/Contents/MacOS/VibePadAudio" ] || [ ! -f "$SOURCE/Contents/Info.plist" ]; then
    echo "install-driver.sh: $SOURCE is not a valid VibePadAudio.driver bundle" >&2
    exit 65 # EX_DATAERR
fi
if [ "$(id -u)" -ne 0 ]; then
    echo "install-driver.sh: must run as root" >&2
    exit 77 # EX_NOPERM
fi

mkdir -p "$HAL_DIR"

readonly STAGING="$HAL_DIR/.$DRIVER_NAME.staging.$$"
readonly BACKUP="$HAL_DIR/.$DRIVER_NAME.backup.$$"

cleanup() {
    rm -rf "$STAGING" 2>/dev/null || true
}
trap cleanup EXIT

# Stage the new driver next to the target and normalize it before touching the live install.
rm -rf "$STAGING"
ditto "$SOURCE" "$STAGING"
# Strip quarantine attributes if present; xattr exits non-zero when there are none, which is
# the normal case for a helper-copied bundle, so it is tolerated here.
xattr -dr com.apple.quarantine "$STAGING" 2>/dev/null || true
# coreaudiod refuses to load plug-ins that are not root:wheel and group-writable.
chown -R root:wheel "$STAGING"
chmod -R 755 "$STAGING"

# Swap into place with rollback.
if [ -d "$TARGET" ]; then
    rm -rf "$BACKUP"
    mv "$TARGET" "$BACKUP"
fi
if ! mv "$STAGING" "$TARGET"; then
    echo "install-driver.sh: failed to move driver into place; restoring previous install" >&2
    if [ -d "$BACKUP" ]; then
        mv "$BACKUP" "$TARGET"
    fi
    exit 1
fi
rm -rf "$BACKUP"

# coreaudiod rescans the HAL directory on restart; launchd relaunches it immediately.
# This drops system audio for a couple of seconds — callers must warn the user first.
killall coreaudiod 2>/dev/null || true

echo "install-driver.sh: installed $DRIVER_NAME to $HAL_DIR"
