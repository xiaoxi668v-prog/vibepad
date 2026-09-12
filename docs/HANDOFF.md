# VibePad 项目交接文档

> 本文是开发过程中的交接记录，保留了历史版本、SHA-256 与验收步骤，供理解设计取舍。
> 其中的家目录、设备型号、IP 与 SSID 已替换为占位符；当前构建方式以根目录
> README 与 `scripts/release-vibepad.sh` 为准。

> **2026-09-10 改名公告**：项目由 WebPad 改名为 VibePad，源码从
> `~/Downloads/codex/` 迁至 `~/vibepad/`（android/、mac-helper/、scripts/、docs/）。
> Bundle ID `com.xiaoxi.vibepad.helper`、Android 包名 `com.xiaoxi.vibepad`、
> LaunchAgent `com.xiaoxi.vibepad.mac-helper` 全部换新。
> 本文档历史章节中的截图/备份路径仍指向 `~/Downloads/codex/` 下的旧文件，仅作历史记录。
> 改名后首次部署需：重新授予辅助功能权限、平板重新配对、Typeless 重选
> 「VibePad Microphone」。

更新时间：2026-07-23（Asia/Shanghai）

## 1. 当前结论

VibePad 当前已经可以通过 5GHz 局域网控制 Mac，核心触控已经由用户亲自验证可用。

当前稳定链路：

```text
安卓平板物理触摸
  → VibePad Android App
  → TCP + TCP_NODELAY（5GHz Wi-Fi）
  → VibePad Helper.app
  → CoreGraphics / Accessibility
  → macOS 鼠标、滚轮和键盘事件
```

当前不再使用蓝牙 HID。蓝牙方案曾造成蓝牙音箱 A2DP 音质下降、HCI 超时、
VibePad 周期性断连，因此已经放弃。不要在没有明确需求时重新启用蓝牙传输。

## 2. 最重要的交接规则

### 2.1 禁止使用 ad-hoc 签名

这是整个项目最关键的坑。

曾经使用 `codesign --sign -` 给 Helper 做 ad-hoc 签名。ad-hoc 应用的 designated
requirement 会绑定每次构建产生的新 CDHash，因此每次替换二进制后，macOS 都可能
把 Helper 当成新应用，原来的“辅助功能”授权会静默失效。表现是：

- 平板仍显示“已连接”；
- TCP 和心跳都正常；
- Mac 也持续收到数据；
- 但鼠标、键盘和 Typeless 全部没有反应。

当前已经改用这台 Mac 现有的稳定签名身份：

```text
Apple Development: you@example.com (TEAMID1234)
TeamIdentifier: <你的 Team ID>
Bundle ID: com.xiaoxi.vibepad.helper
```

以后安装或更新 App 必须使用：

```bash
codesign --force --deep --options runtime --timestamp=none \
  --sign "Apple Development: you@example.com (TEAMID1234)" \
  "$HOME/Applications/VibePad Helper.app"
```

绝对不要再执行：

```bash
codesign --sign - ...
```

签名后必须验证：

```bash
codesign --verify --deep --strict --verbose=2 \
  "$HOME/Applications/VibePad Helper.app"

codesign -dvvv -r- \
  "$HOME/Applications/VibePad Helper.app"
```

正确的 designated requirement 应包含固定 Bundle ID、`anchor apple generic` 和
Apple Development 证书，不应只显示 `cdhash H"..."`。

### 2.2 权限只由用户操作

用户已经明确要求：助手不得代替用户操作 macOS 或安卓权限设置。只说明设置路径，
由用户亲自完成。

macOS 辅助功能路径：

```text
系统设置 → 隐私与安全性 → 辅助功能 → VibePad Helper
```

如果签名身份发生变化，可能需要移除旧条目，再重新添加：

```text
~/Applications/VibePad Helper.app
```

正常的小版本更新在使用同一稳定签名身份时，不应再反复丢失授权。

### 2.3 验收必须使用真实手指

ADB 的 `input swipe` 可以验证安卓 UI 到 Mac 的代码通路，但不能代替真实手指验收。
之前出现过 ADB 模拟滑动有效、真实高频触摸几乎不移动的情况。以后必须让用户在
平板右侧触控区亲自滑动并确认。

### 2.4 修改前保护当前稳定基线

当前版本是用户确认可用的基线。继续优化前应先备份当前 App、APK 和对应源码，
一次只改一个问题，并保留明确回退点。不要同时修改传输、手势、签名和权限流程。

## 3. 项目路径与运行产物

### Android

- 工程：`~/vibepad/android`
- 包名：`com.xiaoxi.vibepad`
- 主 Activity：`app/src/main/java/com/xiaoxi/vibepad/MainActivity.kt`
- 触控逻辑：`app/src/main/java/com/xiaoxi/vibepad/ui/TrackpadView.kt`
- 主界面：`app/src/main/java/com/xiaoxi/vibepad/ui/VibePadView.kt`
- Wi-Fi 传输：`app/src/main/java/com/xiaoxi/vibepad/input/WifiInputSink.kt`
- Kiosk/沉浸模式：`app/src/main/java/com/xiaoxi/vibepad/system/KioskController.kt`
- 当前 APK：`~/vibepad/android/app/build/outputs/apk/debug/app-debug.apk`
- APK SHA-256：`6a8848080bc79ecbfa82658a6d582f88337715a30f459a6941d0dd03014dd71a`
- 平板最后已知 ADB serial：`<平板 serial>`
- 平板型号：`AGS2-AL00`

当前清单固定横屏：

```xml
android:screenOrientation="landscape"
```

### Mac Helper

- Swift 工程：`~/vibepad/mac-helper`
- 主源码：`~/vibepad/mac-helper/Sources/VibePadMacHelper/main.swift`
- 安装位置：`~/Applications/VibePad Helper.app`
- App 内执行文件：`Contents/MacOS/vibepad-mac-helper`
- Bundle ID：`com.xiaoxi.vibepad.helper`
- App 类型：`LSUIElement=true`，不显示 Dock 图标
- 当前执行文件 SHA-256：`3ab00fb3fdab3fddae65dca631451ec4628931193caa6a1afc059a98a7427385`

### LaunchAgent

- plist：`~/Library/LaunchAgents/com.xiaoxi.vibepad.mac-helper.plist`
- label：`com.xiaoxi.vibepad.mac-helper`
- 执行路径必须指向 App 内二进制，不能再指向 Swift `.build` 目录：

```text
~/Applications/VibePad Helper.app/Contents/MacOS/vibepad-mac-helper
```

- 标准输出：`~/Library/Logs/VibePadMacHelper.log`
- 标准错误：`~/Library/Logs/VibePadMacHelper.error.log`

## 4. 构建、安装和重启

### 4.1 Android 构建

```bash
cd ~/vibepad/android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=~/Library/Android/sdk
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
./gradlew assembleDebug
```

安装前先确认设备：

```bash
adb devices -l
```

安装：

```bash
adb install -r \
  ~/vibepad/android/app/build/outputs/apk/debug/app-debug.apk
```

### 4.2 Mac Helper 构建

```bash
cd ~/vibepad/mac-helper
swift build -c release
```

将 release 二进制复制到 App 内后，必须使用第 2.1 节的稳定 Apple Development
身份签名，不得 ad-hoc 签名。

### 4.3 重启 LaunchAgent

只需要让新二进制生效时优先使用：

```bash
launchctl kickstart -k gui/$(id -u)/com.xiaoxi.vibepad.mac-helper
```

检查状态：

```bash
launchctl print gui/$(id -u)/com.xiaoxi.vibepad.mac-helper
lsof -nP -iTCP:39876
```

如需完整重载：

```bash
launchctl bootout gui/$(id -u)/com.xiaoxi.vibepad.mac-helper
launchctl bootstrap gui/$(id -u) \
  ~/Library/LaunchAgents/com.xiaoxi.vibepad.mac-helper.plist
```

## 5. Wi-Fi 架构与协议

> 本节记录的是最初的协议 v1（静态 token）。0.3.0 起已升级为 v2 安全配对，
> 当前帧格式与全部帧类型见 `mac-helper/README.md`；第 16 节记录了升级过程。

- 平板与 Mac 位于同一个 5GHz Wi-Fi 局域网。
- 最近使用的 SSID：`<你的 5GHz SSID>`。
- 平板最近地址：`<平板 IP>`。
- Mac 最近地址：`<Mac IP>`。
- 地址可能由 DHCP 改变，不得硬编码依赖。
- Bonjour service：`_vibepad._tcp.`
- TCP 端口：`39876`
- 使用 `TCP_NODELAY`、keepalive 和单条持久 TCP 连接。
- 心跳间隔：500ms。
- 协议版本：1。
- 帧头：10 bytes，大端。

```text
'W' 'P' | version:u8 | type:u8 | payloadLength:u16 | sequence:u32 | payload
```

帧类型：

| Type | 含义 |
| --- | --- |
| `0x01` | HELLO/鉴权 |
| `0x10` | MOVE，`dx:i16, dy:i16` |
| `0x11` | BUTTON |
| `0x12` | SCROLL |
| `0x13` | KEY，USB HID usage + modifier + pressed |
| `0x14` | RELEASE_ALL |
| `0x20` | PING |
| `0x21` | PONG |

Android 与 Mac 源码中内置同一份 16-byte 预共享 token。交接文档不记录密钥明文；
如果需要轮换，必须同时修改两端并重新部署。

## 6. 当前已经实现的交互

- 横屏固定布局。
- 右侧为大面积触控板。
- 左侧包含常用 App、AI 对话占位区、Vibe Coding 快捷键和 Typeless 按钮。
- 单指移动。
- 单击。
- 长按拖动。
- 双指滚动。
- 双指轻点右键。
- 常用键：Enter、Esc、Y、N、方向键、Space、Ctrl-C、Command-Enter、Ctrl-L、Ctrl-D。
- Typeless 按钮按住时等价于左 Option/Alt，松开时释放。
- 沉浸式全屏，隐藏系统栏；退出入口在 App 内设置菜单。
- Android 状态区仅保留时间、电量和连接状态。

## 7. 已修复的关键问题

### 7.1 Typeless 在 Wi-Fi 模式无效

Android 发送 modifier-only 帧：

```text
按下：usage=0, modifier=0x04, pressed=1
松开：usage=0, modifier=0x04, pressed=0
```

旧版 Mac Helper 会先查 `usage` 的普通键映射；`usage=0` 不存在，因此直接丢弃。

当前修复：

- `usage=0` 走纯修饰键分支；
- 左 Option 使用 macOS keyCode 58；
- 发送 `.flagsChanged`；
- 按下 flags 包含 `.maskAlternate`；
- 松开先清状态，再发送空 flags；
- 使用 `heldModifiers` 跟踪左右 Ctrl/Shift/Option/Command；
- `releaseAll()` 会释放残留修饰键。

### 7.2 真实手指高频移动几乎无效

ADB swipe 能动，但真实手指会产生约 130 次/秒的小位移。旧版 Helper 每帧都读取
当前系统光标位置并投递绝对目标；同一网络批次中 WindowServer 尚未更新坐标，后续
小事件会反复基于同一旧位置计算，前面的增量被覆盖。

当前修复位于 `InputInjector`：

- `Int64` 累计连续 MOVE；
- 每 8ms（约 120Hz）刷新一次；
- 每批只读取一次系统光标位置；
- BUTTON、SCROLL、KEY、RELEASE_ALL 和断线清理前强制 flush；
- 全部操作仍位于 server 串行队列；
- generation 防止旧 timer 干扰新批次。

### 7.3 sequence 超过 255 时 Helper 崩溃

旧代码直接使用 `UInt8(sequence >> n)`，超出范围可能触发 Swift trap。已统一使用：

```swift
UInt8(truncatingIfNeeded: ...)
```

曾用本地客户端连续发送 350 个 ping 验证，Helper PID 保持不变。

### 7.4 蓝牙干扰音箱

日志曾确认以下顺序：

```text
A2DP Audio Queue full / packet flushed
→ VibePad HID disconnect reason 708 / HCI timeout
→ 自动重连
```

因此最终选择 Wi-Fi，Android `MainActivity` 当前只实例化 `WifiInputSink`。虽然工程中
仍保留 `BluetoothHidInputSink.kt`，但不要误以为当前运行时会使用它。

## 8. 当前健康状态与验证方法

交接文档生成时：

- LaunchAgent 状态：running。
- Helper PID：`67024`（PID 会变化，不可依赖）。
- TCP `39876`：LISTEN。
- 平板到 Mac：ESTABLISHED。
- 用户已经亲自确认当前版本可用。

### 8.1 网络与进程

```bash
launchctl print gui/$(id -u)/com.xiaoxi.vibepad.mac-helper | \
  rg 'state =|pid =|program =|job state'

lsof -nP -iTCP:39876
netstat -anv -p tcp | rg '\.39876'
```

### 8.2 Android 日志

```bash
adb logcat -d -v time | \
  rg 'VibePadWifi.*(status=|transport RTT)' | tail -n 30
```

正常状态应看到：

```text
status=CONNECTED detail=5GHz Wi-Fi · VibePad Mac
transport RTT avg=... max=... samples=20
```

历史观测的平均 RTT 多数约 15–30ms，偶尔有 100–280ms 峰值。

### 8.3 端到端验收

按以下顺序让用户亲自测试：

1. 单指连续移动，确认 Mac 光标可见且跟手。
2. 单击和双击。
3. 长按拖动。
4. 双指滚动。
5. 双指轻点右键。
6. Esc、Enter、方向键。
7. 按住 Typeless，说一句话，松开。
8. 连续使用 10–15 分钟，观察是否断连、卡住或延迟突然升高。

不要仅根据“状态显示已连接”判定可用；当前状态主要证明 TCP/心跳，不等同于系统
输入注入一定成功。

## 9. 仍需优化的问题

### P0：端到端健康状态

Android 当前的“已连接”只表示 TCP 和 PONG 正常。建议扩展 PONG/状态帧，让 Mac
返回至少以下信息：

- Helper 是否有辅助功能授权（`AXIsProcessTrusted()`）。
- 最近一次成功处理输入的时间。
- 当前协议版本和 Helper build version。
- 当前是否存在按住的鼠标键或修饰键。

Android UI 应区分：

```text
网络已连接
输入可用
权限缺失
Helper 版本不兼容
输入状态异常
```

### P0：可观测性

建议增加低频聚合指标，不要逐触摸刷日志：

- 每 10 秒 MOVE/BUTTON/SCROLL/KEY 计数。
- MOVE 的 `sum(dx/dy)` 与 `sum(abs(dx/dy))`。
- 队列最大长度和丢弃数。
- 当前 RTT 的 p50/p95/max。
- reconnect 次数和原因。
- Mac 注入帧数及最后注入时间。

### P1：队列安全

`WifiInputSink.addBounded()` 在队列满时当前会直接移除队首，理论上可能误删按键、
鼠标按钮或 RELEASE_ALL。建议改成：

- MOVE/SCROLL 可合并或丢弃旧 motion；
- BUTTON/KEY/RELEASE_ALL 永不静默丢弃；
- 断线后不重放旧输入；
- 重连时双方强制 release-all。

### P1：Android 侧按显示帧合并

`TrackpadView` 会遍历 MotionEvent history。虽然 Mac 已经按 8ms 合并，Android 端仍可
使用 Choreographer/帧回调累计位移，每帧最多发送一次，从源头减少小包和 CPU 唤醒。

### P1：稳定发布流程

建议建立一个安装脚本，固定执行：

1. Swift release build。
2. 备份当前稳定 App。
3. 复制二进制到 App bundle。
4. 使用固定 Apple Development 身份签名。
5. 验证 designated requirement。
6. 重启 LaunchAgent。
7. 网络检查。
8. 用户真实手指验收。

脚本必须在发现签名身份不存在时直接停止，不能自动回退到 ad-hoc。

### P2：产品体验

- 删除 AI 对话占位区，按第 10 节已确认的高保真原型重做左侧控制区。
- App 快捷启动目前需要继续验证每个目标 App。
- 完成 Vibe Coding 自定义快捷按钮的添加、保存、编辑和删除流程。
- 接入 Claude/Codex 顶部额度栏；不得在 Android 端复制或读取登录凭据。
- 增加可调灵敏度、滚动方向和加速度曲线。
- 增加触控手势可视化或轻量反馈。
- 评估三指手势，但不要承诺完全复制苹果原装触控板；CoreGraphics 合成事件与原生
  Multi-Touch 驱动能力不同。

## 10. 已确认的下一版 UI 与功能规划（尚未开发）

本节记录 2026-07-23 与用户逐项确认的下一版设计。这里描述的是**已确认原型**，
不是当前 APK 已实现功能。当前稳定 APK 仍以第 6 节为准。

### 10.1 设计产物

- Stitch 项目：`VibePad 高保真横屏控制台`
- Stitch Project ID：（已省略）
- 当前主 Screen ID：（已省略）
- 最终可交互 HTML：
  `~/Downloads/codex/output/vibepad-stitch/vibepad-prototype-final.html`
- 早期缩略预览：
  `~/Downloads/codex/output/vibepad-stitch/vibepad-prototype.png`

注意：早期 PNG 生成在 Typeless/发送按钮优先级调整之前，仅供视觉风格参考；
`vibepad-prototype-final.html` 和 Stitch 当前 Screen 才是键位、贴底布局与按钮宽度的
权威版本。

### 10.2 总体结构

- 目标设备：横屏 Android 平板，设计基准 `1920 × 1200`、`16:10`。
- 顶部为贯穿全宽的状态/额度栏。
- 主体继续保持左侧控制区约 `36%`、右侧触控区约 `64%`，不改变大框架。
- 删除“AI 对话”栏目，不再实现该占位功能。
- 右侧必须保持纯触控面板，不放发送、快捷键或悬浮按钮。
- 触控目标不小于 `48dp`。

### 10.3 顶部状态与额度栏

顶部左侧：

- 时间；
- 简洁的 Wi-Fi 已连接/未连接图标；
- 电量；
- 小扳手设置按钮。

顶部中部必须同时显示五组额度，不折叠：

| 产品 | 额度栏 |
| --- | --- |
| Claude | `5 小时`、`7 天`、`Fable 5` |
| Codex | `5 小时`、`7 天` |

每组额度包含短进度条和百分比。顶部右侧只显示简洁单色状态，例如
`输入正常`，不要使用复杂的状态色或大面积颜色。

### 10.4 额度数据源

现有数据源来自：

```text
（本机私有的额度采集服务，不在本仓库内）
http://127.0.0.1:8088/usage
http://127.0.0.1:8088/usage/events
```

`/usage` 已返回归一化 JSON：

- Claude 当前已有 `five_hour`、`seven_day`；
- Codex 当前已有 `five_hour`、`weekly`；
- Claude 的 `Fable 5` 尚未在 `usage-collector.js` 中归一化输出，实施前必须先确认
  Anthropic 原始响应中的真实字段，不得伪造或估算；
- 设计稿中的百分比只是排版示意，不是实时值。

推荐数据链路：

```text
Clawd :8088/usage
  → Mac Helper 只访问 127.0.0.1
  → 现有 VibePad 鉴权 TCP 增加状态/额度帧
  → Android 顶部栏
```

不要让 Android 复制 Claude/Codex token，也不要在 Android 重新实现凭据读取。Mac
Helper 只消费 8088 已归一化的结果并转发所需百分比、重置时间和状态。

### 10.5 左侧三段式布局

左侧必须按以下垂直结构实现：

```text
常用 App（固定顶部）
        ↓
弹性空白（所有多余高度都放这里）
        ↓
Vibe Coding 标题 + 键盘
唤醒 Typeless + 发送（固定底部）
```

键盘不能靠上，也不能在键盘或底部按钮下方留下大块空白。Vibe Coding 整组应靠近
用户手部自然操作位置。

### 10.6 常用 App

- 保留 `常用 App` 和右上角 `自定义`。
- 图标必须使用 Mac 上真实 App 图标，不使用统一的占位线性图标。
- Mac Helper 建议扫描 `/Applications`、`/System/Applications`、`~/Applications`，
  通过 `NSWorkspace` 获取名称、Bundle ID、路径和图标。
- Android 保存用户选择的 Bundle ID 与排序，并缓存缩略图。
- 点击后通过现有鉴权 TCP 发送受控的 `LAUNCH_APP` 指令，由 Helper 使用
  `NSWorkspace` 启动。
- 只允许启动用户已配置的 App/Bundle ID；禁止把该功能做成任意 shell 命令执行器。

### 10.7 Vibe Coding 精确键位

键位已由用户手绘并逐项确认。必须按物理键盘式网格实现，不得重新分组：

```text
┌─────┬─────┬─────┬─────┬─────┐
│ Esc │停止 │ 是 Y│  ↑  │ 否 N│
├───────────┼─────┼─────┼─────┤
│   Space   │  ←  │  ↓  │  →  │
├─────┬─────┼─────┴───────────┤
│剪切 │复制 │粘贴 │    删除     │
│ ⌘X  │ ⌘C  │ ⌘V  │ Backspace  │
└─────┴─────┴─────┴───────────┘
```

约束：

- `↑` 必须位于 `↓` 正上方；`←`、`↓`、`→` 位于同一排。
- `Space` 横跨前两列。
- `删除/Backspace` 横跨最后两列，显示退格图标与“删除”。
- `停止` 对应 `Control + C`，只使用轻微警示语义，不做大红块。
- `剪切/复制/粘贴` 分别对应 `Command + X/C/V`，中文名称和快捷键都显示。

Vibe Coding 标题右侧增加独立的 `＋` 按钮，语义为“添加自定义快捷键”。它与
常用 App 的 Add/自定义是两个不同入口。下一步需要设计并实现添加、编辑、排序、
删除与持久化；建议只允许构造键盘 usage + modifiers 组合，不允许执行任意命令。

### 10.8 底部主操作

底部两按钮同高，但优先级和宽度不同：

| 按钮 | 宽度 | 层级 | 行为 |
| --- | --- | --- | --- |
| 唤醒 Typeless | 约 `2/3` | 蓝色主按钮 | 单击触发 |
| 发送 | 约 `1/3` | 中性次按钮 | 短按 Enter；长按约 650ms 触发 Shift+Enter |

当前 APK 的 Typeless 是“按住发送左 Alt、松开释放”。下一版用户已经明确要求改成
单击唤醒。实现时应发送一次完整的 Typeless 热键按下/松开序列，并让用户在 Typeless
实际设置下验收，避免遗留修饰键状态。

发送键的长按逻辑建议：

1. `ACTION_DOWN` 启动约 `650ms` 计时，不立即发送 Enter；
2. 到时仍按住则发送一次 `Shift + Enter`；
3. `ACTION_UP` 时若未触发长按，则发送一次 Enter；
4. `ACTION_CANCEL` 必须清理计时器并释放所有可能残留的 Shift。

### 10.9 实施边界与推荐顺序

本轮只完成设计和原型，没有修改 Android/Helper 运行源码，也没有重新构建 APK。

建议一次只落一个阶段并保留回退点：

1. 先只改 Android 静态布局，额度和 App 列表用本地 mock，验证横屏尺寸与触控热区。
2. 接入新键位、Backspace、剪切/复制/粘贴、Typeless 单击和发送长按。
3. 实现 Vibe Coding 自定义快捷键的数据模型和设置弹层。
4. 扩展 Helper 的受控 App 枚举、图标传输和启动协议。
5. 扩展 Helper 状态帧，接入 8088 额度数据。
6. 最后按第 2、4、8 节流程构建、稳定签名、部署并由用户真实手指验收。

任何阶段都不得顺手改回蓝牙、不得使用 ad-hoc 签名、不得代替用户操作系统权限。

## 11. 新对话建议开场提示词

可以把下面这段连同本文件路径发给新对话：

```text
请先完整阅读：
~/vibepad/docs/HANDOFF.md

这是当前已经可用的 VibePad 安卓平板 + Mac Helper 项目。先保持当前稳定基线，
不要重启蓝牙方案，不要使用 ad-hoc 签名，不要代替我操作任何系统权限。
修改前先备份并给出单一变更计划；修改后必须使用固定 Apple Development 身份签名，
并让我用真实手指完成端到端验收。下一版 UI 已在第 10 节确认，实施时必须严格遵循
键位、贴底布局、Typeless 主按钮优先级和五组额度栏，不要重新自由设计。
```

## 12. 2026-07-23 新版开发候选（尚未安装）

已在不替换当前稳定 APK/Helper 的前提下完成 `0.2.0` 开发候选：

- Android 新版 16:10 布局、全宽额度栏和纯触控右侧面板；
- 已确认的三行五列 Vibe Coding 键位；
- Typeless 单击完整 Option 按下/松开；
- 发送短按 Enter、长按 650ms 发送 Shift+Enter；
- 自定义快捷键添加、编辑、排序、删除与本地持久化；
- Mac 真实 App 图标/Bundle ID 清单、自定义选择和受控启动；
- PONG 健康状态：辅助功能授权、Helper/协议版本、最近输入和按住状态；
- Helper 从 `127.0.0.1:8088/usage` 读取并脱敏转发额度；
- Android 发送队列优先丢弃/合并 motion，BUTTON/KEY/RELEASE_ALL 不静默丢弃；
- 固定签名、备份、验证和安装脚本：`~/vibepad/scripts/release-vibepad.sh`。

当前候选产物：

```text
~/vibepad/dist/VibePad-debug.apk
SHA-256 783739189e5a96cb0eb78d86bc8923728a21c006c18ff1083eee4daf44ae0f70

~/vibepad/dist/VibePad Helper.app
执行文件 SHA-256 2914a6c2d5b9030e4a6d4c9af0dbd913babd956cbd524bf46e922cc54a3fecf8
```

候选 Helper 已使用固定 Apple Development 身份签名并通过 designated requirement
验证。独立端口协议实测通过，能返回 `accessibilityTrusted=true`、Helper `2.0.0`、
真实额度和带 PNG 图标的 App 清单。

生成候选时 `adb devices` 没有在线设备，因此没有执行安装，当前运行中的稳定
Helper 和平板 APK 均未替换。安装时连接并授权平板后执行：

```bash
~/vibepad/scripts/release-vibepad.sh --install
```

脚本在签名身份缺失或 ADB 设备离线时会直接停止，不会退回 ad-hoc，也不会只安装
一半。安装后仍须由用户按第 8.3 节使用真实手指验收。

Claude 当前 `/usage` 状态为 `not_logged_in`，且归一化输出没有真实 `fable_5`
字段；Android 因此将 Claude 和 Fable 5 显示为 `--`，没有伪造或估算。

## 13. 2026-07-23 UI 优化版 0.2.1（已安装）

根据平板实机反馈完成并安装：

- 五组额度压缩到触控板正上方，进度条贴近文字，不占左侧 App 区；
- 左上角改为时间、接入点图标、电量和小尺寸设置图标；
- 右上角状态改为“连接正常”；
- 左侧面板增高并收紧内边距；
- 常用 App 去除重复加号，选择上限从 5 个提高到 9 个，使用三列网格；
- Vibe Coding 改用固定高度原生键帽，三行全部对齐；
- Typeless 去除 emoji，改用 Android VectorDrawable 麦克风；
- Typeless/发送改为自绘操作卡片，四角圆角完整，发送文案简化为“发送/长按换行”。

平板当前安装版本：`versionCode=3`、`versionName=0.2.1`。Mac Helper 没有修改，
仍使用稳定签名且 designated requirement 验证通过。新版已重新连接到 5GHz Wi-Fi，
Android 日志显示 `status=CONNECTED`。

最终 APK：

```text
~/vibepad/dist/VibePad-debug.apk
SHA-256 09dae9f362e1561dcbe877f0d164887e8d2fcc39c1b687d436d426b75764f9a7
```

本轮安装前 APK 备份：

```text
~/vibepad/backups/2026-07-23-ui-polish-preinstall/
```

## 14. 2026-07-23 顶栏与键帽对齐修正版 0.2.2（已安装）

- 左上状态组改为同一行紧凑排列：时间、Mac 风格 Wi-Fi、电池图标、百分比、设置；
- 五组额度整体右移，首个额度卡不再越过触控板左边线；
- 所有键帽统一使用“主标题 + 固定副标题槽位”，停止、删除、剪切、复制、粘贴
  的文字基线已对齐；
- 平板当前版本：`versionCode=4`、`versionName=0.2.2`。

最终 APK SHA-256：`6073bd41b20b8ada62328dae971c6c10adb3b0cc4f04a1f1ee38f51206ef13ba`。

实机复核截图：

```text
~/Downloads/codex/vibepad-v022.png
```

## 15. 2026-07-23 状态栏、额度与设置版 0.2.4（已安装）

- 删除“连接正常”文字；网络和 Helper 均正常时，Mac 风格 Wi-Fi 图标显示绿色；
- 电池百分比直接绘制在电池轮廓内部；
- 五个额度卡完整平铺在 Touchpad 上方，组合左右边界与 Touchpad 边框对齐；
- 设置页状态简化为“已连接”，删除蓝牙说明；
- 设置页新增鼠标灵敏度和触摸板灵敏度，范围 `0.5x–2.0x`，即时生效并持久保存；
- “自定义”和 Vibe Coding 加号使用与左侧标题对称的内边距；
- 平板当前版本：`versionCode=6`、`versionName=0.2.4`。
- 最终 APK SHA-256：`d6edea7b6fc753db6bd1de184c98585a05126a91d0be296a8c0df4af519952e7`。

实机截图：

```text
~/Downloads/codex/vibepad-v024.png
~/Downloads/codex/vibepad-settings-v024-reopen.png
```

## 16. 2026-07-24 安全配对版 0.3.0（Mac 已安装，平板待连接）

- 删除 APK 与 Helper 共用的静态全局 Token，协议升级为 v2；
- Mac 菜单栏新增 `WP`：默认关闭新设备配对，只能手动开启 60 秒窗口；
- 首次配对使用临时 P-256 ECDH + HKDF-SHA256 派生每台平板独立密钥；
- Mac 与平板同时显示 6 位验证码，最终只在 Mac 弹窗中允许或拒绝；
- Mac 配对密钥写入 Keychain，Android 配对密钥写入 Android Keystore；
- 每次重连使用服务端/客户端随机数和双向 HMAC 认证，旧请求不能重放；
- 未认证客户端不能发送鼠标、键盘、App 启动或额度请求，90 秒未认证自动断开；
- Mac 菜单提供已配对设备数量和“清除所有配对”；Android 设置页提供配对/重新配对入口；
- Android：`versionCode=7`、`versionName=0.3.0`。

安全版 Helper 已安装并以固定 Apple Development 身份签名，固定 Team ID，
Bundle ID `com.xiaoxi.vibepad.helper`；辅助功能 designated requirement 保持稳定。
本轮没有 ADB 在线设备，因此 APK 尚未推送到平板。旧版 APK 已无法通过安全认证。

候选产物：

```text
~/vibepad/dist/VibePad-debug.apk
SHA-256 a9059416cab2b47404f564b36ab3f688a9db6710203f1a51ba2db1a34ab69805

~/vibepad/dist/VibePad Helper.app
执行文件 SHA-256 440f331931c6eb631068472886d01a72d080cfc529d774fb716749201930d521
```

安装前 Helper 备份：

```text
~/vibepad/backups/20260724-001208/VibePad Helper.app
```

## 17. 2026-07-24 触控手感版 0.3.1

- 双指改为 macOS“自然滚动”方向，内容跟随手指移动；
- 基于触摸采样速度增加抬手惯性和指数减速，新的触摸会立即停止惯性；
- 滚动基础增益提高，设置范围从 `0.5x–2.0x` 扩展为 `0.5x–4.0x`；
- 双指捏合映射缩放；三/四指上下滑映射 Mission Control / App Exposé；
- 三/四指左右滑切换桌面和全屏 App；四指张开显示桌面、捏合打开 App 搜索；
- 三/四指轻点执行查询；识别成功提供轻触觉反馈；
- 设置页增加每项灵敏度说明和完整触控操作说明；
- Helper 支持多个待认证连接并行，旧协议连接立即淘汰，避免另一台旧平板阻塞新版；
- Android：`versionCode=8`、`versionName=0.3.1`；Helper 健康版本 `3.1.0`。

最终候选：

```text
~/vibepad/dist/VibePad-debug.apk
SHA-256 fc4a50b10d529ecc3ec864a1a0ba034e8940da3c4988eb3d008456473a829c26

~/vibepad/dist/VibePad Helper.app
执行文件 SHA-256 1efbcf02f86dcae05972d5cbfa947c46204b91c87506d0f2d20931fdf5ee6579
```

AGS2-AL00 已安装首个 0.3.1 构建（APK SHA-256
`65a926bf8f22c4d82c330c070fe682b0d821d5d3a9f0c715c5408a229eb6bf25`）。
最终设置页压缩修正版已触发覆盖安装，但停在华为“风险提示”，必须由用户亲自点击
“继续安装”后才能完成替换。Mac Helper 手势版已安装且固定签名验证通过。

### 配对窗口前台修复

首次实测发现平板已收到 6 位码、但后台菜单栏 Helper 的普通 `NSAlert` 没有可靠
显示到当前 Space。现已删除“配对已开启”的前置模态弹窗，验证码窗口改为状态栏
层级、跨所有 Space/全屏置顶并请求用户注意；验证码使用 32pt 等宽大字。开启 60 秒
配对窗口后菜单栏标识由 `WP` 变为 `WP •`。最终安装 Helper 执行文件 SHA-256：
`c8d6e3119240dd884bd754f9ae7bd2671b2f3d6e1781b0a947c4092403f3935c`。

### 配对交互改为纯菜单栏

用户实测置顶验证码窗口仍不可见，因此已彻底删除配对相关窗口。新流程为：

1. 点击 `WP`，选择“允许配对新平板（60 秒）”；顶栏显示 `WP •`；
2. 平板请求配对后，顶栏直接显示 `WP  123  456`；
3. 再点验证码，菜单内选择“验证码一致，允许”或“拒绝本次配对”；
4. 60 秒未确认自动拒绝；并发的第二个请求也会自动拒绝；
5. “清除所有配对”改为 5 秒内二次点击确认，不再创建弹窗。

纯菜单栏版 Helper 已安装，执行文件 SHA-256：
`325805828c0cb5360fceab97fd728345838c1f8aff4c3e582b35d37d3833b8e2`。

### Helper 退出与运行版本核验

- 菜单底部新增“退出 VibePad Helper”（⌘Q）；
- 退出时使用 `launchctl bootout gui/<uid>/com.xiaoxi.vibepad.mac-helper` 卸载当前
  KeepAlive LaunchAgent，避免普通退出后被系统立即拉起；
- 2026-07-24 12:01 强制重启：旧 PID `44422`，新 PID `50371`；
- 唯一运行进程路径为
  `~/Applications/VibePad Helper.app/Contents/MacOS/vibepad-mac-helper`；
- 运行安装包与 staged 包二进制 SHA-256 均为
  `42b647b8b6dccaab990edbfc54fa26713d4d7ff1745b85cf4b581193a300efec`；
- Spotlight 搜出的其他 VibePad Helper 均位于 `vibepad-backups` 或 `vibepad-dist`，是
  历史备份/候选包，不是运行进程。

### 配对验证码不更新的根因修复

平板能显示验证码但 Mac 菜单仍停留在“等待平板请求”的根因不是协议断链，而是
AppKit 主循环被放在一个永不返回的 `MainActor Task` 中；网络线程随后提交的
`Task { @MainActor ... }` 永远无法获得执行机会。现改为：

- AppKit `application.run()` 直接在进程主线程进入；
- 网络线程用 `DispatchQueue.main.async` 投递菜单栏状态，再用
  `MainActor.assumeIsolated` 保持 UI 隔离；
- 菜单定时更新同样不再创建 MainActor Task。

新增回归探针 `mac-helper/Tests/MenuBarSchedulingProbe.swift`。真实启动 AppKit 主循环，
从后台线程提交 `482731` 后读取状态栏标题，结果：

```text
MENU_TITLE=WP  482  731
probe_exit=0
```

修正版已强制重启：旧 PID `50371`，新 PID `56158`；唯一运行路径为 Applications
中的 Helper，运行文件与 staged 文件 SHA-256 均为
`e14f5919f5968a89ba5df867686cd70c6957945e0f080d64489c4dd8112b3999`。

## 18. 2026-07-24 Touch Bar 顶栏版 0.3.2（源码完成，尚未部署）

- 顶栏保留时间、Wi-Fi、电量和设置；原额度组件保留在代码中但默认隐藏；
- 中间区域改为实时 Touch Bar 画面，平板触摸可回传为按下、拖动、抬起事件；
- Mac 使用 `DFRTouchBarSimulatorCreate`、`DFRTouchBarCreateDisplayStream` 的现代路径，
  私有 API 与 `CGDisplayStreamStart/Stop` 全部通过 `dlopen/dlsym` 动态解析；
- 只在平板订阅时启动捕获，PNG 最多 12 FPS；断线后停止并释放模拟器；
- VibePad 协议新增 `0x50–0x53`，支持完整帧、60 KiB 分片、1 MiB 总量限制、
  认证重连后自动续订和拖动事件合并；
- Android 在后台执行 latest-wins 解码，主线程只替换显示帧；
- Android：`versionCode=9`、`versionName=0.3.2`；Helper 健康版本 `3.2.0`。

当前 macOS 26.1（Apple M4）真实捕获探针通过：

```text
started=yes
frame=2008x60 pngBytes=22121 timeout=no
```

本地联编通过：Swift debug/release、Android `compileDebugKotlin` 和 `assembleDebug`。
当前候选尚未复制到 `vibepad-dist`、未签名安装、未替换正在运行的 Helper，也未推送平板。

```text
~/vibepad/mac-helper/.build/release/vibepad-mac-helper
SHA-256 9eeb977d52de216df50468df3b548439c496ce29de40a282877a8182615dbe15

~/vibepad/android/app/build/outputs/apk/debug/app-debug.apk
SHA-256 693f53cb532de2f78edf639e2fd72e8b2a73d8e97f7e1d078999d110226c6e15
```

开发前完整备份：

```text
~/vibepad/backups/pre-touchbar-20260724-214150
```

### 0.3.2 实机安装与链路验收

2026-07-24 22:05 已通过 `release-vibepad.sh --install` 正式部署：

- 平板 `AGS2-AL00`：`versionCode=9`、`versionName=0.3.2`；
- Helper PID `28110`，LaunchAgent 状态 `running`；
- Helper 仍使用 Bundle ID `com.xiaoxi.vibepad.helper`、固定 Team ID 和固定
  Apple Development 身份；
- 安装后 Helper 执行文件 SHA-256：
  `45e96df81d40ae26b3bdd697e1cbdb97a99d6310e99542e6a4dc44b30d2bab84`；
- 平板截图确认顶栏已显示 Mac 实时 Touch Bar，包含当前 App 控件以及键盘、亮度、
  音量、静音、Siri 等系统控制；
- Android 日志无崩溃，稳定网络 RTT 约 `15–19 ms`；触摸后 Touch Bar 画面发生切换，
  证明 `0x53` 回传链路生效。

安装前自动备份：

```text
~/vibepad/backups/20260724-220401
旧 APK SHA-256 65a926bf8f22c4d82c330c070fe682b0d821d5d3a9f0c715c5408a229eb6bf25
旧 Helper SHA-256 e14f5919f5968a89ba5df867686cd70c6957945e0f080d64489c4dd8112b3999
```

验收截图：

```text
~/Downloads/codex/.scratch/vibepad-touchbar-installed.png
~/Downloads/codex/.scratch/vibepad-touchbar-after-tap.png
```

### 0.3.3 纯黑背景版

用户实机对比发现 VibePad 原背景 `#131313` 与 Touch Bar 的 `#000000` 存在色差。
现将页面根背景和顶栏背景统一为纯黑，卡片、按键与触控板仍保留原深灰层次。

- Android：`versionCode=10`、`versionName=0.3.3`；
- APK SHA-256：`27bbefb207f63838cb963ec3a7ae42027681c01f9397175e89cc36c936cfe555`；
- 2026-07-24 22:41 已覆盖安装到 `AGS2-AL00`；
- 实机认证恢复正常，Touch Bar 画面正常，无崩溃日志；
- 0.3.2 安装前 APK 备份：
  `~/vibepad/backups/20260724-224131-black-background`；
- 验收截图：
  `~/Downloads/codex/.scratch/vibepad-black-background-installed.png`。

### 0.3.4 纯黑面板与悬停滚动修复

- 左侧 App/快捷键大面板改为纯黑，仅保留外框；各按钮自身的圆角矩形保持深灰；
- Touchpad 内部改为纯黑，仅保留连接状态描边和提示文字；
- Android：`versionCode=11`、`versionName=0.3.4`；
- APK SHA-256：`c72140096a5ad3416aa71774e6996b05d11aa6e0e241e777c0072c6554139774`；
- Helper 升级至 `3.2.1`：滚动事件显式携带最终光标坐标，并标记为连续滚动，
  修复单指刚移动后立即双指滚动时 WindowServer 偶尔投递到旧焦点窗口的问题；
- 安装 Helper SHA-256：
  `b20338fd5cf06a558a0ac00d8ac0c42a3ce4c4ca82359cc23d175597e0053c03`；
- Helper 固定签名验证通过，PID `79925`，平板重新认证并连接成功；
- 视觉验收截图：
  `~/Downloads/codex/.scratch/vibepad-black-panels-installed.png`。

回退点：

```text
~/vibepad/backups/20260724-224454-black-panels
~/vibepad/backups/20260724-225648-hover-scroll
```

## 19. 2026-07-24 平板麦克风 → Typeless（0.4.0）

已完成并实机部署。Android 只有在用户点击“唤醒 Typeless”后才创建 `AudioRecord`；
点击停止、App 退到后台、连接断开或退出时都会立即释放录音器。首次启动才申请
`RECORD_AUDIO` 权限。

音频链路：

```text
平板 AudioRecord（24 kHz / mono / PCM16LE / 20 ms）
  -> 已认证的 VibePad TCP 会话（0x54 START / 0x55 DATA / 0x56 STOP）
  -> Helper AudioQueue（80 ms 预缓冲，转换为 48 kHz stereo）
  -> 精确 UID 定向写入 TFFAudio
  -> 公开 Aggregate Device「VibePad Microphone」
  -> Typeless
```

关键实现：

- Android：`input/MicrophoneStreamer.kt`、`MainActivity.kt`、`VibePadView.kt`、
  `WifiInputSink.kt`；版本 `0.4.0`、`versionCode=12`；
- macOS：`AudioSink.swift` 使用 `AudioQueueNewOutput` 和
  `kAudioQueueProperty_CurrentDevice`，不会改默认输入、默认输出或系统输出；
- `AggregateMicrophone.swift` 在 Helper 启动时确保稳定 UID
  `com.xiaoxi.vibepad.microphone` 存在。Typeless 会过滤 Virtual transport，
  但会枚举该公开 Aggregate 输入；
- Helper 版本 `3.3.1`。

回归探针结果：TFFAudio 捕获 `-44.80 dBFS`；前三包保持静音、第四包后播放；
测试前后默认设备均为 input `97`、output/system output `112`，全部 PASS。

端到端实机验证：Typeless 明确选择 `VibePad Microphone` 后，历史记录成功生成平板端
语音“测试一下能不能？”、“现在使用的是平板的麦克风测试”等文本。Android
`dumpsys audio` 同时确认每次按钮操作均成对出现 `rec start` / `rec stop`。

已安装产物：

```text
Android APK SHA-256
b9681ad7664c8989d37518f2c9b4016ad0406ed9a812427599b4cd2ce7856e70

VibePad Helper SHA-256
0c09fc0c8e8b41fe8c3bbfe15f347b48c049c53c5e2d9dafde1471b4e98d2d90

自动备份
~/vibepad/backups/20260724-235750
```

当前 MVP 的已知安全边界：设备配对与身份验证已有 HMAC/密钥交换保护，但认证后的
音频 payload 尚未加密，仅适合可信局域网。正式对外版本应为整个会话增加 TLS 或
逐包 AEAD，并加入重放保护。

### 0.4.1 按住说话

- 麦克风按钮改为 push-to-talk：`ACTION_DOWN` 立即开始录音并启动 Typeless，
  `ACTION_UP` 停止录音并触发转录；`ACTION_CANCEL` 同样安全停止；
- 空闲文案为“按住说话”，录音中文案为“松开转录”；
- 快速松手与 Typeless 快捷键的 90 ms key-up 做了串行化，避免启动/停止按键重叠；
- 首次权限弹窗会中断本次按压，授权后提示用户重新按住，不会在手指已经松开后误录；
- 断网、退后台和退出继续强制释放麦克风；
- Android `versionCode=13`、`versionName=0.4.1`；APK SHA-256：
  `cec634fda2cabd8471eefb634b8ea60876fbb461950bc552ecfbb124cef2790c`；
- 2026-07-25 00:02 已安装到 `AGS2-AL00`，自动备份：
  `~/vibepad/backups/20260725-000217`；
- ADB 1.6 秒长按回归显示录音 `00:02:57.924 rec start`，松手后
  `00:02:59.495 rec stop`，无崩溃日志。
