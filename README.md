# VibePad

<p align="center"><img src="docs/assets/vibepad-icon.png" width="128" alt="VibePad 图标"></p>

安卓平板变 Mac 无线触控台：触控板与系统手势、Vibe Coding 快捷键、Touch Bar 画面回传、
平板麦克风直通 Typeless。平板与 Mac 在同一 5GHz 局域网内通过 Bonjour 发现、
TCP 直连，首次使用需在 Mac 菜单栏允许配对并核对 6 位验证码。

## 结构

```text
android/     安卓平板 App（Kotlin，包名 com.xiaoxi.vibepad）
mac-helper/  Mac 菜单栏 Helper（Swift，Bundle ID com.xiaoxi.vibepad.helper）
designs/     平板端三套 UI 皮肤（01 经典黑 / 02 深空专业 / 05 双手操控）与设计源码
scripts/     发布脚本与 LaunchAgent 模板
docs/        交接文档（协议、历史坑、验收流程）
dist/        发布产物（本地产出，不进 git）
backups/     安装前自动备份（本地产出，不进 git）
```

## 皮肤与配置

平板端内置三套皮肤（经典 / 深空专业 / 双手操控），在平板设置弹层里切换。
界面皮肤、常用 App 和触控灵敏度也可以在 Mac 菜单栏的「VibePad 设置…」里配置，
两端通过 `0x60/0x61/0x62` 三个帧双向同步，Mac 侧存在
`~/Library/Application Support/VibePad/pad-config.json`。

## 环境要求

- macOS 12+，Xcode 命令行工具（`swift build`）。
- 一个稳定的 Apple Development 签名身份（免费 Apple ID 即可）。Helper 需要
  “辅助功能”权限，而 ad-hoc 签名每次构建都会变成“新应用”并静默丢失授权，
  所以脚本强制要求固定身份，见 [mac-helper/README.md](mac-helper/README.md)。
- Android 9（API 28）以上的横屏平板，JDK 17 与 Android SDK。
- 麦克风直通功能额外依赖 TFFAudio 虚拟回环声卡（设备 UID
  `com.toofifi.audio.Loopback_v001`）。未安装时触控、键盘、Touch Bar 照常可用，
  仅“按住说话”不可用，Helper 启动日志会提示未找到该设备。

## 构建与发布

```bash
export SIGNING_IDENTITY="Apple Development: you@example.com (TEAMID1234)"
scripts/release-vibepad.sh            # 构建 + 签名 + 打包到 dist/
scripts/release-vibepad.sh --install  # 并安装到本机 ~/Applications 与已连接平板
```

脚本在签名身份缺失时直接停止，绝不回退 ad-hoc 签名。`JAVA_HOME`、`ANDROID_HOME`、
`VIBEPAD_INSTALL_DIR` 均可用环境变量覆盖。

## 安全边界

- 配对使用临时 P-256 ECDH + HKDF 派生每台平板独立密钥，两端显示同一 6 位验证码，
  只能在 Mac 上允许；密钥分别存于 macOS Keychain 与 Android Keystore。
- 每次重连做双向 HMAC 挑战应答，旧请求不能重放；未认证连接不能注入任何输入。
- 认证之后的会话**没有逐帧加密和完整性校验**，只适合可信局域网。请不要在公共
  Wi-Fi 上使用；如需对外发布，应为整个会话加 TLS 或逐包 AEAD。

详细协议、历史坑和验收流程见 [docs/HANDOFF.md](docs/HANDOFF.md)。

## 许可证

[MIT](LICENSE)。`designs/orbit-source/assets/icons/` 下的图标来自 Lucide，
许可见同目录 `LICENSE-lucide.txt`。
