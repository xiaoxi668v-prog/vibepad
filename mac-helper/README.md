# VibePad Mac Helper

VibePad 的原生 macOS 5 GHz Wi-Fi 接收器。它通过 Bonjour 广播
`_vibepad._tcp.`，监听 TCP `39876`，启用 `TCP_NODELAY`，并通过
CoreGraphics 注入鼠标、滚轮与键盘事件。

## 构建与启动

```bash
cd /Users/shishuai/vibepad/mac-helper
swift build -c release
.build/release/vibepad-mac-helper
```

首次启动会请求“辅助功能”权限。请在“系统设置 → 隐私与安全性 →
辅助功能”中启用 Terminal（或实际启动 helper 的 App），然后退出并重新启动
helper。若 macOS 询问是否允许传入网络连接，请选择“允许”。运行期间使用
`Control-C` 退出。

## 应用签名（重要）

安装到 `~/Applications/VibePad Helper.app` 的版本必须使用这台 Mac 已有的
稳定签名身份：

```bash
codesign --force --deep --options runtime --timestamp=none \
  --sign "Apple Development: shishuaiok@sina.cn (347H32TQSD)" \
  "$HOME/Applications/VibePad Helper.app"
```

不要使用 ad-hoc 签名（`--sign -`）。ad-hoc 的 designated requirement 会绑定
每次构建生成的新 CDHash，导致 macOS 在更新二进制后静默拒绝原有“辅助功能”
授权。稳定的 Apple Development 签名可让后续版本继续保持同一应用身份。

## Wire protocol v1

每帧为 10 字节 header 加 payload，所有多字节数字均为大端：

`'W' 'P' | version:u8 | type:u8 | payloadLength:u16 | sequence:u32 | payload`

连接后的第一帧必须是 `HELLO`，payload 为固定的 16-byte token。认证失败的
连接会立即断开，且不会注入输入。token（hex）：
`7d13b8e4c29a4f6ea5170bc839d2e641`。

| Type | 名称 | Payload |
| ---: | --- | --- |
| `0x01` | HELLO | 16-byte token |
| `0x10` | MOVE | `dx:i16, dy:i16` |
| `0x11` | BUTTON | `mask:u8, pressed:u8`；mask: left=1/right=2/middle=4 |
| `0x12` | SCROLL | `vertical:i16, horizontal:i16`（pixel） |
| `0x13` | KEY | `USB HID usage:u16, modifiers:u8, pressed:u8` |
| `0x14` | RELEASE_ALL | empty |
| `0x20` | PING | empty |
| `0x21` | PONG | empty，sequence 与 PING 相同 |

断线时 helper 会释放全部鼠标按键，避免残留拖拽状态。键盘 payload 使用 USB
HID Keyboard/Keypad usage；helper 负责将其映射为 macOS virtual keycode。

## 菜单栏设置窗口（3.5.0 起）

菜单栏图标里新增「VibePad 设置…」，可以直接在 Mac 上配置平板界面，不必只在平板上改：

- 界面皮肤：经典 / 深空专业 / 双手操控（对应 `designs/skins/` 的 01 / 02 / 05）；
- 顶部区域：Mac Touch Bar 画面或本地额度栏；
- 常用 App：3 × 3 下拉框，顺序即平板显示顺序，可留空；
- 鼠标与滚动灵敏度。

配置存放在 `~/Library/Application Support/VibePad/pad-config.json`，通过
`0x60 CONFIG_REQUEST` / `0x61 CONFIG` / `0x62 CONFIG_UPDATE` 三个帧与平板双向同步，
`revision` 大的一方胜出。字段与冲突规则见 `docs/HANDOFF.md` 第 20 节。

心跳回执 `0x21 PONG` 的健康 JSON 增加 `frontmostApp`（当前前台 App 的 bundle id），
平板用它高亮常用 App。

> 上面的 “Wire protocol v1” 一节是历史记录：当前实际协议为 v2（HMAC 配对与鉴权、
> Touch Bar 画面、音频、配置同步），以 `docs/HANDOFF.md` 与源码为准。
