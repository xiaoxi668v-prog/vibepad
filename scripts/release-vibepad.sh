#!/bin/zsh
# 构建 + 固定身份签名 + 打包到 dist/；加 --install 时再安装到本机与已连接平板。
#
# 可配置项（全部通过环境变量覆盖，脚本不再硬编码任何个人路径）：
#   SIGNING_IDENTITY     必填。稳定的 Apple Development 签名身份，例如
#                        "Apple Development: you@example.com (TEAMID1234)"。
#                        身份缺失时脚本直接停止，绝不回退到 ad-hoc 签名。
#   VIBEPAD_INSTALL_DIR  Helper 安装目录，默认 ~/Applications
#   JAVA_HOME            默认 /opt/homebrew/opt/openjdk@17
#   ANDROID_HOME         默认 ~/Library/Android/sdk
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
ROOT="${SCRIPT_DIR:h}"
ANDROID_PROJECT="$ROOT/android"
HELPER_PROJECT="$ROOT/mac-helper"
INSTALL_DIR="${VIBEPAD_INSTALL_DIR:-$HOME/Applications}"
INSTALLED_HELPER="$INSTALL_DIR/VibePad Helper.app"
SIGNING_IDENTITY="${SIGNING_IDENTITY:-}"
DIST_DIR="$ROOT/dist"
STAGED_HELPER="$DIST_DIR/VibePad Helper.app"
INFO_PLIST_TEMPLATE="$HELPER_PROJECT/Resources/Info.plist"
LAUNCH_AGENT_TEMPLATE="$SCRIPT_DIR/com.xiaoxi.vibepad.mac-helper.plist"
LAUNCH_AGENT_LABEL="com.xiaoxi.vibepad.mac-helper"
LAUNCH_AGENT_PATH="$HOME/Library/LaunchAgents/$LAUNCH_AGENT_LABEL.plist"
APK="$ANDROID_PROJECT/app/build/outputs/apk/debug/app-debug.apk"
HELPER_BINARY="$HELPER_PROJECT/.build/release/vibepad-mac-helper"
MODE="${1:-stage}"

if [[ "$MODE" != "stage" && "$MODE" != "--install" ]]; then
  echo "Usage: SIGNING_IDENTITY='Apple Development: ...' $0 [stage|--install]" >&2
  exit 2
fi

if [[ -z "$SIGNING_IDENTITY" ]]; then
  echo "SIGNING_IDENTITY is not set. Export a stable Apple Development identity first;" >&2
  echo "run 'security find-identity -v -p codesigning' to list the ones on this Mac." >&2
  exit 3
fi

if ! security find-identity -v -p codesigning | grep -Fq "\"$SIGNING_IDENTITY\""; then
  echo "Signing identity '$SIGNING_IDENTITY' is unavailable; refusing to use ad-hoc signing." >&2
  exit 3
fi

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

(cd "$ANDROID_PROJECT" && ./gradlew :app:assembleDebug)
(cd "$HELPER_PROJECT" && swift build -c release)
(cd "$HELPER_PROJECT/Driver" && SIGNING_IDENTITY="$SIGNING_IDENTITY" ./build-driver.sh)

mkdir -p "$DIST_DIR"
if [[ -e "$STAGED_HELPER" ]]; then
  mv "$STAGED_HELPER" "$DIST_DIR/VibePad Helper.previous.$(date +%Y%m%d-%H%M%S).app"
fi

# 从仓库模板构建 App bundle，不依赖已安装副本
mkdir -p "$STAGED_HELPER/Contents/MacOS" "$STAGED_HELPER/Contents/Resources/Driver"
cp -p "$INFO_PLIST_TEMPLATE" "$STAGED_HELPER/Contents/Info.plist"
cp -p "$HELPER_PROJECT/Resources/AppIcon.icns" "$STAGED_HELPER/Contents/Resources/AppIcon.icns"
cp -p "$HELPER_BINARY" "$STAGED_HELPER/Contents/MacOS/vibepad-mac-helper"

# 麦克风驱动及一键安装/卸载脚本随 App 分发，由设置窗口通过系统授权框安装
ditto "$HELPER_PROJECT/Driver/build/VibePadAudio.driver" \
  "$STAGED_HELPER/Contents/Resources/Driver/VibePadAudio.driver"
cp -p "$HELPER_PROJECT/Driver/install-driver.sh" \
      "$HELPER_PROJECT/Driver/uninstall-driver.sh" \
      "$STAGED_HELPER/Contents/Resources/Driver/"

codesign --force --deep --options runtime --timestamp=none \
  --sign "$SIGNING_IDENTITY" "$STAGED_HELPER"
codesign --verify --deep --strict --verbose=2 "$STAGED_HELPER"

# designated requirement 必须绑定固定 Bundle ID 与开发者证书，而不是本次构建的 cdhash；
# 否则每次更新二进制后 macOS 都会静默收回“辅助功能”授权。
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
  installed_apk="$(adb shell pm path com.xiaoxi.vibepad 2>/dev/null | head -n 1 | tr -d '\r' | sed 's/^package://' || true)"
  if [[ -n "$installed_apk" ]]; then
    adb pull "$installed_apk" "$backup_dir/app-before-install.apk"
  fi

  mkdir -p "$INSTALL_DIR"
  ditto "$STAGED_HELPER" "$INSTALLED_HELPER"
  codesign --verify --deep --strict --verbose=2 "$INSTALLED_HELPER"

  # 生成 LaunchAgent：launchd 不展开 ~，模板中的占位符在这里替换为真实路径
  mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
  launchctl bootout "gui/$(id -u)/com.xiaoxi.webpad.mac-helper" 2>/dev/null || true  # 旧名称
  launchctl bootout "gui/$(id -u)/$LAUNCH_AGENT_LABEL" 2>/dev/null || true
  sed -e "s|__HELPER_BINARY__|$INSTALLED_HELPER/Contents/MacOS/vibepad-mac-helper|g" \
      -e "s|__HOME__|$HOME|g" \
      "$LAUNCH_AGENT_TEMPLATE" > "$LAUNCH_AGENT_PATH"
  launchctl bootstrap "gui/$(id -u)" "$LAUNCH_AGENT_PATH"

  adb install -r "$APK"

  echo ""
  echo "安装完成。首次安装或签名身份变化后还需手动完成："
  echo "1. 系统设置 → 隐私与安全性 → 辅助功能 → 添加 VibePad Helper（旧条目先移除）"
  echo "2. 平板端进入设置页配对（Mac 菜单栏先点“允许配对新平板（60 秒）”）"
  echo "3. Typeless 输入源选择「VibePad Microphone」"
fi
