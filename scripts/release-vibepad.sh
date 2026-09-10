#!/bin/zsh
set -euo pipefail

ROOT="/Users/shishuai/vibepad"
ANDROID_PROJECT="$ROOT/android"
HELPER_PROJECT="$ROOT/mac-helper"
INSTALLED_HELPER="/Users/shishuai/Applications/VibePad Helper.app"
SIGNING_IDENTITY="Apple Development: shishuaiok@sina.cn (347H32TQSD)"
DIST_DIR="$ROOT/dist"
STAGED_HELPER="$DIST_DIR/VibePad Helper.app"
INFO_PLIST_TEMPLATE="$HELPER_PROJECT/Resources/Info.plist"
APK="$ANDROID_PROJECT/app/build/outputs/apk/debug/app-debug.apk"
HELPER_BINARY="$HELPER_PROJECT/.build/release/vibepad-mac-helper"
MODE="${1:-stage}"

if [[ "$MODE" != "stage" && "$MODE" != "--install" ]]; then
  echo "Usage: $0 [stage|--install]" >&2
  exit 2
fi

if ! security find-identity -v -p codesigning | grep -Fq "\"$SIGNING_IDENTITY\""; then
  echo "Required signing identity is unavailable; refusing to use ad-hoc signing." >&2
  exit 3
fi

export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="/Users/shishuai/Library/Android/sdk"
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

(cd "$ANDROID_PROJECT" && ./gradlew :app:assembleDebug)
(cd "$HELPER_PROJECT" && swift build -c release)

mkdir -p "$DIST_DIR"
if [[ -e "$STAGED_HELPER" ]]; then
  mv "$STAGED_HELPER" "$DIST_DIR/VibePad Helper.previous.$(date +%Y%m%d-%H%M%S).app"
fi

# 从仓库模板构建 App bundle，不再依赖已安装副本
mkdir -p "$STAGED_HELPER/Contents/MacOS"
cp -p "$INFO_PLIST_TEMPLATE" "$STAGED_HELPER/Contents/Info.plist"
cp -p "$HELPER_BINARY" "$STAGED_HELPER/Contents/MacOS/vibepad-mac-helper"

codesign --force --deep --options runtime --timestamp=none \
  --sign "$SIGNING_IDENTITY" "$STAGED_HELPER"
codesign --verify --deep --strict --verbose=2 "$STAGED_HELPER"

requirement="$(codesign -dvvv -r- "$STAGED_HELPER" 2>&1)"
if [[ "$requirement" != *'identifier "com.xiaoxi.vibepad.helper"'* ||
      "$requirement" != *"anchor apple generic"* ||
      "$requirement" != *"$SIGNING_IDENTITY"* ]]; then
  echo "Staged helper designated requirement is not stable; refusing to continue." >&2
  exit 5
fi

cp -p "$APK" "$DIST_DIR/VibePad-debug.apk"
shasum -a 256 "$DIST_DIR/VibePad-debug.apk" \
  "$STAGED_HELPER/Contents/MacOS/vibepad-mac-helper"

if [[ "$MODE" == "--install" ]]; then
  if ! adb devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit !found }'; then
    echo "No authorized Android device is connected; nothing was installed." >&2
    exit 6
  fi

  stamp="$(date +%Y%m%d-%H%M%S)"
  backup_dir="$ROOT/backups/$stamp"
  mkdir -p "$backup_dir"
  if [[ -d "$INSTALLED_HELPER" ]]; then
    ditto "$INSTALLED_HELPER" "$backup_dir/VibePad Helper.app"
  fi
  installed_apk="$(adb shell pm path com.xiaoxi.vibepad 2>/dev/null | head -n 1 | tr -d '\r' | sed 's/^package://')"
  if [[ -n "$installed_apk" ]]; then
    adb pull "$installed_apk" "$backup_dir/app-before-install.apk"
  fi

  mkdir -p "/Users/shishuai/Applications"
  ditto "$STAGED_HELPER" "$INSTALLED_HELPER"
  codesign --verify --deep --strict --verbose=2 "$INSTALLED_HELPER"

  # LaunchAgent 换装新 label
  old_label="com.xiaoxi.webpad.mac-helper"
  new_label="com.xiaoxi.vibepad.mac-helper"
  launchctl bootout "gui/$(id -u)/$old_label" 2>/dev/null || true
  launchctl bootout "gui/$(id -u)/$new_label" 2>/dev/null || true
  cp -p "$ROOT/scripts/com.xiaoxi.vibepad.mac-helper.plist" \
    "/Users/shishuai/Library/LaunchAgents/com.xiaoxi.vibepad.mac-helper.plist"
  launchctl bootstrap "gui/$(id -u)" \
    "/Users/shishuai/Library/LaunchAgents/com.xiaoxi.vibepad.mac-helper.plist"

  adb install -r "$APK"

  echo ""
  echo "首次以新 Bundle ID 安装后，需要手动完成两件事："
  echo "1. 系统设置 → 隐私与安全性 → 辅助功能 → 移除旧 WebPad Helper，添加 VibePad Helper"
  echo "2. 平板端进入设置页重新配对（旧配对密钥已随 Bundle ID 更换作废）"
  echo "3. Typeless 输入源重新选择「VibePad Microphone」"
fi
