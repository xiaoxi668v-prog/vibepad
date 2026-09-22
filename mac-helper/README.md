# VibePad Mac Helper

VibePad 的原生 macOS 接收端，以菜单栏图标形式常驻。它通过 Bonjour 广播
`_vibepad._tcp.`，监听 TCP `39876`（可用环境变量 `VIBEPAD_PORT` 覆盖），
启用 `TCP_NODELAY`，并通过 CoreGraphics 注入鼠标、滚轮、键盘与系统手势事件。
可选功能：Touch Bar 画面回传（依赖私有 DFR API，动态解析，不可用时自动跳过）
和平板麦克风直通（依赖 TFFAudio 虚拟声卡）。

## 构建与运行

```bash
cd mac-helper
swift build -c release
.build/release/vibepad-mac-helper
```

首次启动会提示缺少“辅助功能”权限：系统设置 → 隐私与安全性 → 辅助功能，
启用 VibePad Helper（或直接运行时的 Terminal），然后重启 helper。若 macOS 询问
是否允许传入网络连接，请选择“允许”。正式安装请使用仓库根目录的
`scripts/release-vibepad.sh`，它会打包 App bundle、签名并注册 LaunchAgent。

## 应用签名（重要）

安装到 `~/Applications/VibePad Helper.app` 的版本必须使用一个**固定的**
Apple Development 签名身份：

```bash
codesign --force --deep --options runtime --timestamp=none \
  --sign "Apple Development: you@example.com (TEAMID1234)" \
  "$HOME/Applications/VibePad Helper.app"
```

不要使用 ad-hoc 签名（`--sign -`）。ad-hoc 的 designated requirement 绑定每次
构建生成的新 CDHash，macOS 会把更新后的二进制当作新应用，静默收回原有
“辅助功能”授权，表现为平板显示已连接但 Mac 毫无反应。

## 麦克风直通依赖

`AudioSink.swift` 把平板音频定向写入 UID 为 `com.toofifi.audio.Loopback_v001`
的 TFFAudio 回环输出设备，并在启动时确保存在名为「VibePad Microphone」的公开
Aggregate 输入设备（UID `com.xiaoxi.vibepad.microphone`），供 Typeless 选择。
未安装 TFFAudio 时 Helper 会打印 `TFFAudio was not found`，其余功能不受影响。

## 配对与认证（协议 v2）

- 菜单栏点“允许配对新平板（60 秒）”打开配对窗口；平板发起 `PAIR_REQUEST` 后，
  菜单栏显示 6 位验证码，与平板一致时在 Mac 上点“允许”。
- 密钥由临时 P-256 ECDH 经 HKDF-SHA256 派生（info `VibePad pairing v2`），
  验证码为 `HMAC(secret, "VibePad SAS" || transcript)` 前 4 字节模 10⁶。
- 每台平板的密钥以 clientId 为账户名存入 Keychain（service
  `com.xiaoxi.vibepad.pairing.v2`），“清除所有配对”会删除全部条目。
- 重连时 Mac 先发 32 字节随机 `SERVER_CHALLENGE`；平板回复
  `clientId(16) || clientNonce(32) || HMAC(secret, "client-auth" || serverNonce || clientNonce || clientId)`；
  Mac 校验后回复 `HMAC(secret, "server-auth" || ...)`。任一方校验失败即断开。
- 未认证连接 90 秒内不完成认证会被关闭，最多同时保留 16 个待认证连接。

认证之后的帧没有加密和逐帧 MAC，只适合可信局域网。

断线时 helper 会释放全部鼠标按键，避免残留拖拽状态。键盘 payload 使用 USB
HID Keyboard/Keypad usage；helper 负责将其映射为 macOS virtual keycode。

## 菜单栏设置窗口（3.5.0 起）

菜单栏图标里新增「VibePad 设置…」，可以直接在 Mac 上配置平板界面，不必只在平板上改：

- 界面皮肤：经典 / 深空专业 / 双手操控（对应 `designs/skins/` 的 01 / 02 / 05）；
- 常用 App：3 × 3 下拉框，顺序即平板显示顺序，可留空；
- 鼠标与滚动灵敏度。

配置存放在 `~/Library/Application Support/VibePad/pad-config.json`，通过
`0x60 CONFIG_REQUEST` / `0x61 CONFIG` / `0x62 CONFIG_UPDATE` 三个帧与平板双向同步，
`revision` 大的一方胜出。字段定义见 `PadConfigStore.swift` 与平板端的 `PadConfig.kt`。

心跳回执 `0x21 PONG` 的健康 JSON 增加 `frontmostApp`（当前前台 App 的 bundle id），
平板用它高亮常用 App。

> 上面的 “Wire protocol v1” 一节是历史记录：当前实际协议为 v2（HMAC 配对与鉴权、
> Touch Bar 画面、音频、配置同步），以源码为准。

## 线路格式

每帧 10 字节头加 payload，多字节数字均为大端：

`'W' 'P' | version:u8 (=2) | type:u8 | payloadLength:u16 | sequence:u32 | payload`

| Type | 方向 | 名称 | Payload |
| ---: | --- | --- | --- |
| `0x02` | Mac→平板 | SERVER_CHALLENGE | 32 字节随机 nonce |
| `0x03` | 平板→Mac | AUTHENTICATE | `clientId(16) + clientNonce(32) + hmac(32)` |
| `0x04` | Mac→平板 | AUTHENTICATION_OK | `hmac(32)` |
| `0x05` | 平板→Mac | PAIR_REQUEST | `clientId(16) + nameLen:u8 + name + clientPublicKey(65, X9.63)` |
| `0x06` | Mac→平板 | PAIR_OFFER | `serverPublicKey(65)` |
| `0x07` | Mac→平板 | PAIR_ACCEPT | `HMAC(secret, "pair-accept" || transcript)` |
| `0x08` | Mac→平板 | PAIR_REJECT | ASCII 错误码：`PAIRING_REQUIRED` / `INVALID_REQUEST` / `PAIRING_REJECTED` / `KEY_AGREEMENT_FAILED` |
| `0x09` | Mac→平板 | PAIRING_DISABLED | UTF-8 提示文本 |
| `0x10` | 平板→Mac | MOVE | `dx:i16, dy:i16` |
| `0x11` | 平板→Mac | BUTTON | `mask:u8, pressed:u8`；mask: left=1 / right=2 / middle=4 |
| `0x12` | 平板→Mac | SCROLL | `vertical:i16, horizontal:i16`（像素，连续滚动） |
| `0x13` | 平板→Mac | KEY | `USB HID usage:u16, modifiers:u8, pressed:u8`；usage=0 表示纯修饰键 |
| `0x14` | 平板→Mac | RELEASE_ALL | 空 |
| `0x15` | 平板→Mac | GESTURE | `id:u8`：1 Mission Control、2 App Exposé、3/4 上/下一桌面、5 显示桌面、6 Spotlight、7/8 放大/缩小、9 查询 |
| `0x20` | 平板→Mac | PING | 任意（平板发送 8 字节时间戳） |
| `0x21` | Mac→平板 | PONG | JSON 健康状态：`accessibilityTrusted`、`helperVersion`、`protocolVersion`、`lastInputAgeMs`、`mouseButtons`、`modifiers`；sequence 与 PING 相同 |
| `0x40` | 平板→Mac | APPS_REQUEST | 空 |
| `0x41`/`0x42`/`0x43` | Mac→平板 | APPS_BEGIN / APP_ITEM / APPS_END | APP_ITEM 为 JSON：`name`、`bundleId`、可选 `icon`（48px PNG base64） |
| `0x44` | 平板→Mac | LAUNCH_APP | UTF-8 bundle ID，仅允许 Helper 已枚举的 App |
| `0x50` | 平板→Mac | TOUCH_BAR_SUBSCRIBE | `enabled:u8` |
| `0x51` | Mac→平板 | TOUCH_BAR_FRAME | `frameId:u32, width:u16, height:u16, codec:u8(=1 PNG)` + PNG |
| `0x52` | Mac→平板 | TOUCH_BAR_FRAME_CHUNK | 同上头部 + `chunkIndex:u16, chunkCount:u16` + 分片（每片 ≤ 60 KiB，整帧 ≤ 1 MiB） |
| `0x53` | 平板→Mac | TOUCH_BAR_EVENT | `phase:u8(0 down/1 drag/2 up), reserved:u8, x:u16, y:u16`（0…65535，左上原点） |
| `0x54` | 平板→Mac | AUDIO_START | `streamId:u32, sampleRate:u32(=24000), channels:u8(=1), format:u8(=1 PCM16LE), framesPerPacket:u16(=480)` |
| `0x55` | 平板→Mac | AUDIO_DATA | `streamId:u32, seq:u32, captureTimeNs:u64, sampleCount:u16` + PCM16LE |
| `0x56` | 平板→Mac | AUDIO_STOP | `streamId:u32, reason:u8` |

连接断开时 helper 会释放全部鼠标按键与修饰键，避免残留拖拽或按住状态。
键盘 payload 使用 USB HID Keyboard/Keypad usage；helper 映射为 macOS virtual keycode。

## 回归探针

`Tests/MenuBarSchedulingProbe.swift` 不属于任何 SwiftPM target，用于手动验证
菜单栏在真实 AppKit 主循环下能收到网络线程投递的验证码：把它与
`Sources/VibePadMacHelper/PairingSecurity.swift`、`StatusCenter.swift` 一起用
`swiftc` 编译运行，期望输出 `MENU_TITLE=验证码 482  731` 且退出码 0。
