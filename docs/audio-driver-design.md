# VibePad 自研虚拟麦克风驱动（AudioServerPlugIn）技术设计

> 状态：设计稿（仅调研与设计，不含实现代码）
> 目标：替代第三方 TFFAudio 虚拟声卡，Helper 首次运行弹一次管理员授权自动装好驱动，之后用户零操作。
> 许可红线：MIT 项目，新组件全部从零编写；BlackHole（GPLv3）与 Apple 官方 NullAudio 示例只作架构参考，不抄代码。

---

## 0. 结论摘要（给老板的人话版）

我们要自己写一个 macOS 系统级的「假麦克风」，换掉现在让用户手动安装的 TFFAudio。结论一句话：**完全可行，技术上没有硬门槛，免费分发也不需要花钱买苹果证书**。

- **形态**：做一个 `.driver` 插件放进系统的 `/Library/Audio/Plug-Ins/HAL/` 目录，系统音频服务（coreaudiod）启动时会自动加载它，Mac 上就多出一个「VibePad Microphone」。这是 BlackHole、Loopback 等所有同类软件的同款技术路线，苹果官方支持。DriverKit 那条「新」路只给真实硬件用，苹果明确不给纯虚拟声卡发授权，不用考虑。
- **用什么语言写**：用 Objective-C（一个文件约一千多行）。Swift 理论上能写，但接口是 C 风格的函数指针表，用 Swift 要自己手工搭表、手工管引用计数，容易出错且没有先例可参考，不值得。
- **签名要钱吗**：不要。我们现有的免费 Apple Development 证书就够用——驱动是由 Helper 自己拷贝进系统目录的，不经过浏览器的「隔离」检查，苹果对这条路径不强制 Developer ID 和公证。
- **安装体验**：Helper 首次运行时弹一次 macOS 标准的管理员密码框（和装打印机驱动一样），授权后脚本自动把驱动放好、修好权限、重启音频服务（系统声音会断两三秒）。之后永远零操作。卸载在 Helper 设置里一键完成。
- **现有代码改动很小**：现在负责把平板声音写进 TFFAudio 的 `AudioSink.swift` 只需把目标设备 UID 换一行；`AggregateMicrophone.swift` 暂时保留（Typeless 会隐藏「虚拟设备」，聚合设备是它绕不过的兼容层），等新驱动实测被 Typeless 直接识别后再删。
- **工期**：约 8–10 个工作日，四个里程碑，见第 5 节。

---

## 1. 架构

### 1.1 现状链路（要替换的部分）

```
安卓平板 ──TCP──> VibePad Helper(AudioSink.swift, 24kHz→48kHz 立体声升采样)
                     │ AudioQueue 定向写
                     ▼
        TFFAudio 回环输出设备 (UID com.toofifi.audio.Loopback_v001)   ← 用户手动装的第三方驱动
                     │ 系统内回环
                     ▼
        AggregateMicrophone.swift 建的聚合输入设备「VibePad Microphone」
        (UID com.xiaoxi.vibepad.microphone)
                     ▼
              Typeless 等 App 当麦克风读
```

### 1.2 目标链路

```
安卓平板 ──TCP──> VibePad Helper(AudioSink.swift，升采样逻辑原样保留)
                     │ AudioQueue 定向写（仅换 UID 常量）
                     ▼
        VibePadAudio.driver（自研 HAL 插件，进程内环形缓冲区回环）
          ├── 输出流：接收 Helper 写入的 PCM
          └── 输入流：把同一份数据暴露给任何 App 当麦克风读
                     ▼
        （兼容期）AggregateMicrophone.swift 聚合设备照旧包一层给 Typeless
                     ▼
              Typeless 等 App
```

关键变化：TFFAudio 这个角色由我们自己的插件顶替；**插件单个设备同时带输入流和输出流，回环在插件进程内的环形缓冲区里完成，理论上可以甩掉聚合设备**。是否真甩取决于 Typeless 的过滤策略（见 3.6）。

### 1.3 插件对象模型（最小可行集）

AudioServerPlugIn 本质是 coreaudiod 进程内的一棵 CoreAudio 对象树，最小集为：

| 对象 | 数量 | 说明 |
|---|---|---|
| PlugIn | 1 | 根对象，负责 `CreateObject`/枚举设备，实现 COM 风格 `QueryInterface/AddRef/Release` |
| Device | 1 | 名称「VibePad Microphone」，UID `com.xiaoxi.vibepad.audio.device`；报 `kAudioDevicePropertyDeviceIsAlive`、名义采样率等 |
| Stream（输出） | 1 | 接收 Helper 写入；`DoIOProc` 把客户端缓冲拷进环形缓冲 |
| Stream（输入） | 1 | 供 App 读取；`DoIOProc` 从环形缓冲拷出，无数据时填静音 |
| Control（可选） | 0–2 | 音量/静音控制。最小版可以不做，加了体验更好 |

- 不做 Box 对象（BlackHole 也没有）。
- 环形缓冲区（lock-free，单写单读 + 时间戳对齐）放在插件内；读写两侧的 `DoIOProc` 跑在 coreaudiod 的实时线程上，**不得分配内存、不得拿互斥锁、不得打印日志**，只用原子操作和 `memcpy`。
- 时钟：用 `mach_absolute_time` + `AudioGetCurrentHostTime` 维护 `kAudioDevicePropertyZeroTimeStampPeriod`，让 CoreAudio 能把读写两侧时间戳对齐。这是 BlackHole 架构里最值得借鉴的一块（思路公开，代码自写）。
- 缓冲容量取 2 的幂（如 16384 帧 ≈ 340ms@48kHz），写满覆盖最旧数据、读空补静音——和现状 AudioSink 的「断包重缓冲」语义一致。

### 1.4 安装与运行拓扑

```
VibePad Helper.app
 └── Contents/Resources/VibePadAudio.driver   ← 构建时嵌入，随 App 一起签名

首次运行：
 Helper 检测 /Library/Audio/Plug-Ins/HAL/VibePadAudio.driver 不存在或版本过旧
   → 弹一次管理员授权 → 以 root 执行安装脚本
   → mkdir/cp/chown root:wheel/chmod 755/去 quarantine/killall coreaudiod
   → coreaudiod 由 launchd 自动重启并加载插件 → 设备出现
```

---

## 2. 各问题结论

### 2.1 驱动形态（问题 1）

**结论：AudioServerPlugIn（`.driver` bundle 装入 `/Library/Audio/Plug-Ins/HAL/`）是唯一正确形态，loopback 单设备双流结构可以替代 TFFAudio；Aggregate 设备理论上可甩，实际上先留。**

- DriverKit/AudioDriverKit 路线排除：苹果论坛明确 `com.apple.developer.driverkit.family.audio` 授权不发給纯虚拟设备，AudioDriverKit 面向 USB/PCI 真硬件（WWDC21 起就是这套口径，macOS 26 没有变化）。
- 加载机制：coreaudiod 以 root 运行，启动时扫描 HAL 目录 dlopen 每个 `.driver`。bundle 必须 `root:wheel` 所有、权限不可组写，否则拒绝加载；`Info.plist` 必须有 `CFPlugInFactories`/`CFPlugInTypes` 入口。
- 插件跑在 coreaudiod 的沙箱里：只能读自己 bundle 内的文件，要跨进程通信须在 `Info.plist` 声明 `AudioServerPlugIn_MachServices`。我们的设计不需要跨进程通信——Helper 走标准 AudioQueue 写输出流即可，零额外授权。
- 单设备双流 loopback 意味着任何 App 都可以直接把本设备当输入源打开，**不依赖聚合设备**。

### 2.2 编程语言（问题 2）

**结论：用 Objective-C 写驱动。Swift 技术上可行，工程上不推荐。**

理由：

1. AudioServerPlugIn 的入口是 C ABI 的工厂函数，返回的是 COM 风格函数指针表（`AudioServerPlugInDriverInterface`）：每个属性回调都是函数指针，引用计数 `AddRef/Release` 要自己实现。Swift 可以用 `@_cdecl` 出口 + `@convention(c)` 闭包手工拼这张表，但每一步都是手工 ABI 劳动，任何一个指针签名写错就是 coreaudiod 崩溃。
2. 所有可参考的实现（BlackHole、Apple NullAudio 示例、公开的 HAL 插件教程）都是 C/ObjC。Swift 写 HAL 插件没有成熟先例，出问题无处查证。
3. Swift 运行时要被 dlopen 进 coreaudiod（root、沙箱、实时线程）的地址空间。macOS 12+ 系统自带 Swift 运行时所以能加载，但 ARC 的隐式保留/释放落在实时回调路径上是额外风险源；ObjC 用手工 C 结构 + 显式引用计数反而透明可控。
4. SwiftPM 也产不出 `.driver` bundle（见 2.7），反正要走出 SwiftPM，语言上没有「和主工程统一」的红利可吃。

驱动本体预计一个 `.m` 文件 1200–1800 行（对象树 + 属性回调 + 环形缓冲 + IO 回调），用 `clang -bundle` 直接编译，链 `CoreAudio/CoreFoundation` 框架。

### 2.3 签名要求（问题 3）

**结论：现有免费 Apple Development 证书足够，不需要 Developer ID，不需要公证，免费分发可行。**

依据与细节：

1. coreaudiod 加载 HAL 插件走的是 dlopen，不经过 Gatekeeper 的「首次打开」评估。Gatekeeper/公证拦截的是带 `com.apple.quarantine` 隔离属性的下载物；我们的驱动由 Helper 从自己 bundle 里拷贝出去，**安装脚本显式 `xattr -dr com.apple.quarantine` 后文件上根本没有隔离属性**，公证无从谈起。
2. Apple Silicon 上所有机器码必须有签名，但 **ad-hoc 或 Apple Development 签名都满足这个平台要求**，不要求 Developer ID。
3. coreaudiod 作为系统进程加载第三方插件是产品设计（BlackHole 等全靠这个），不做团队 ID 限定（disable-library-validation）。
4. 稳妥起见仍用与 App 相同的 `SIGNING_IDENTITY` 给 `.driver` 单独签名，再由 release 脚本对整包 `codesign --deep` 覆盖；`codesign --verify --deep --strict` 照旧。
5. 对开源用户的含义：自己 `git clone` 构建、用自己的免费证书签名即可全功能使用，没有任何付费门槛。如果未来要在 GitHub Releases 发预编译包，再评估 Developer ID（99 美元/年），与本设计解耦。
6. 真正会被系统拒的是**文件属主和权限**：必须 `root:wheel` + 不可组写。这由安装脚本保证，与签名无关。

### 2.4 安装流程（问题 4）

**结论：推荐「osascript 一次性提权脚本」为主路径——一次标准管理员密码框完成全部安装；SMAppService 特权守护列为未来升级项。**

三个候选方案对比：

| 方案 | 用户交互 | 系统要求 | 代码量 | 备注 |
|---|---|---|---|---|
| **A. osascript `do shell script … with administrator privileges`**（推荐） | 一次密码框 | macOS 12+ 全支持 | 最小（一个脚本 + 几行 Swift） | 弹的是系统标准授权框；驱动升级时再弹一次 |
| B. SMAppService 特权守护（LaunchDaemon） | 无密码框，但要去「系统设置 → 登录项与扩展」手动放行后台项 | macOS 13+（当前部署目标 12，需提） | 大（XPC 协议 + 守护生命周期） | 交互未必更少；适合驱动需要频繁自更新的未来 |
| C. AuthorizationServices / SMJobBless | 一次密码框 | 已废弃（macOS 13 起 SMJobBless deprecated） | 大 | 直接排除 |

推荐 A 的具体设计：

- Helper 启动时检测：`/Library/Audio/Plug-Ins/HAL/VibePadAudio.driver` 不存在，或 bundle 内 `CFBundleVersion` 低于 App 内嵌版本 → 触发安装流程。
- 安装脚本（随 App 打包在 Resources，明文 shell 可读可审计，开源友好）以 root 执行：
  1. `mkdir -p /Library/Audio/Plug-Ins/HAL`
  2. 用 `ditto`（保留权限）把 App 内嵌的 `VibePadAudio.driver` 拷入
  3. `xattr -dr com.apple.quarantine` 去隔离
  4. `chown -R root:wheel` + `chmod -R 755`
  5. `killall coreaudiod`（launchd 自动拉起，音频中断约 2–3 秒，UI 上事先提示）
- 失败处理：任一命令非零即回滚（删掉拷入的目录）并在 Helper 菜单栏给出明确错误与重试入口。
- **卸载**：设置窗口提供「卸载虚拟麦克风」按钮，同样一次提权执行 `rm -rf` + `killall coreaudiod`；同时销毁聚合设备（`AudioHardwareDestroyAggregateDevice`）。App 被直接拖废纸篓时驱动会残留但无害（不占资源、不弹窗），README 里给一行手动卸载命令兜底。
- 升级路径：Helper 版本内嵌驱动版本号不一致时走同一安装脚本覆盖，覆盖前 `killall coreaudiod` 使旧插件卸载。

### 2.5 音频格式适配（问题 5）

**结论：重采样继续放 Helper，驱动保持「哑」回环；驱动对外报 44.1kHz/48kHz 标准采样率。**

- 现状：`AudioSink.swift` 已把平板来的 24kHz mono PCM16LE 用整数 2 倍复制法升到 48kHz stereo PCM16（`upsampleTo48kStereo`，零阶保持，对语音足够），这个函数和整套 AudioQueue 缓冲/重缓冲逻辑**原样保留**，一行不改。
- 驱动侧：设备报支持 44.1kHz 与 48kHz（可选加宽到 88.2/96k），位深报 CoreAudio 原生的 32-bit float（coreaudiod 会在 Helper 写入的 PCM16 与设备格式之间自动转换，和今天 TFFAudio 路径一样）。
- 不放进驱动的理由：IO 回调在实时线程，重采样要分配状态/滤波器，违反实时安全原则；HAL 插件的正确职责就是搬运字节，采样率转换天然属于客户端（Helper/App）。
- 44.1kHz 由 CoreAudio 的 AUHAL 在 App 侧自动 SRC，驱动和 Helper 都不操心。

### 2.6 与现有代码的整合（问题 6）

- `AudioSink.swift`：改动面 = 常量一行。`targetDeviceUID` 从 `com.toofifi.audio.Loopback_v001` 改为 `com.xiaoxi.vibepad.audio.device`；`targetOutputDeviceExists()` 等逻辑不用动。日志文案里的「TFFAudio」顺手改名。
- `AggregateMicrophone.swift`：**保留，改 UID**。`tffUID` 指向新驱动 UID。原因：文件头注释写明 Typeless 故意隐藏虚拟传输类型（`kAudioDeviceTransportTypeVirtual`）的设备、只认聚合输入设备——换成自研驱动后这个过滤大概率依旧生效（我们的设备同样是 virtual transport）。保留聚合层是零风险的兼容兜底。
- 后续可做实验：驱动把 transport type 报成非 virtual 看 Typeless 是否直接认；若认，再发一个版本删掉 `AggregateMicrophone.swift`。此为优化项，不进首版范围。
- 新增 `DriverInstaller.swift`（检测版本、调 osascript 提权、卸载入口），并在 `StatusCenter`/`SettingsWindow` 暴露「驱动状态/重新安装/卸载」。

### 2.7 目录结构与构建（问题 7）

**结论：SwiftPM 构建不了 `.driver` bundle，用独立 clang 编译脚本；产物嵌进 App 的 Resources，release 脚本统一签名打包。**

```
mac-helper/
├── Package.swift                      （不变）
├── Sources/VibePadMacHelper/
│   ├── AudioSink.swift                （改一行 UID + 文案）
│   ├── AggregateMicrophone.swift      （改一行 UID）
│   ├── DriverInstaller.swift          （新增：安装/卸载/版本检测）
│   └── …
└── Driver/
    ├── VibePadAudio/
    │   ├── VibePadAudioDriver.m       （插件全部实现）
    │   └── Info.plist                 （CFPlugInFactories/CFPlugInTypes、版本号）
    ├── install-driver.sh              （提权执行的安装脚本，打包进 Resources）
    ├── uninstall-driver.sh
    └── build-driver.sh                （clang -bundle 编译 → VibePadAudio.driver）
```

- `build-driver.sh` 核心：`clang -bundle -fobjc-arc -O2 -mmacosx-version-min=12.0 -framework CoreFoundation -framework CoreAudio -o VibePadAudio.driver/Contents/MacOS/VibePadAudio VibePadAudioDriver.m`，再落 Info.plist。不引 Xcode 工程，保持仓库「无 Xcode 也能构建」的现状。
- `release-vibepad.sh` 插入三步：`build-driver.sh` → 把 `VibePadAudio.driver` 和安装/卸载脚本拷进 `STAGED_HELPER/Contents/Resources/` → 现有 `codesign --deep` 自然覆盖新内容；designated requirement 校验逻辑不变。
- SwiftPM 为什么不行：SwiftPM 没有「带自定义 Info.plist 与 C ABI 入口的 CFPlugIn bundle」目标类型；即便用 C target 编译出 dylib 也拼不出 bundle 目录结构。独立脚本反而更透明、可审计。

### 2.8 风险与测试（问题 8）

**主要风险**

1. **插件崩溃 = coreaudiod 崩溃 = 全机无声**（launchd 会反复拉起反复崩）。这是本方案唯一的高危点。对策：实现坚持「最小哑回环」，IO 路径零分配零锁；开发期全程 Address Sanitizer 跑单元级环形缓冲测试；首版宁可功能少。
2. **插件静默不加载**：属主不是 root:wheel、权限可组写、隔离属性未清除、Info.plist key 缺失、符号缺失，都会让 coreaudiod 直接跳过，且 UI 无提示。
3. **安装时音频中断**：`killall coreaudiod` 会切断正在进行的通话/播放，安装前 UI 必须明示。
4. **TCC/麦克风权限**：App 首次从我们的设备录音时系统照常弹麦克风授权（按 App 维度），与 TFFAudio 时代一致，无新增权限负担。

**排查手段**

- 加载日志：`log show --last 10m --predicate 'process == "coreaudiod"'`（开发期用 `log stream` 实时盯）。
- 崩溃报告：`/Library/Logs/DiagnosticReports/coreaudiod-*.ips`。
- 设备是否出现：「音频 MIDI 设置」、或 `system_profiler SPAudioDataType`。
- 插件被跳过时 coreaudiod 日志里会有明确的拒绝原因（签名/权限/plist），照单修即可。

**回归验证清单**

1. 干净用户首次运行 Helper → 一次密码框 → 「音频 MIDI 设置」出现 VibePad Microphone。
2. 平板端说话 → Typeless 经聚合设备转写成功（现状用例不变）。
3. QuickTime/`afrecord` 直接从新设备录音，波形与平板输入一致，无爆音无漂移（连续 10 分钟）。
4. Helper 重启、平板断连重连、Mac 睡眠唤醒后链路自动恢复。
5. 卸载按钮 → 设备消失、聚合设备销毁、HAL 目录无残留；重装可重复。
6. 模拟坏插件（故意塞错误版本）→ Helper 检测到缺失/版本不符并引导重装，coreaudiod 崩溃时能给出「卸载驱动即可恢复」的文档指引。

### 2.9 许可合规

- BlackHole 是 GPLv3，**只看架构思路（对象树、环形缓冲、时间戳对齐），一行代码不抄**。
- Apple NullAudio 示例受 Apple Sample Code License 约束，同样只读不搬。
- 新组件全部原创，与仓库其余部分同 MIT。

---

## 3. 里程碑与工作量（问题 9）

| 里程碑 | 内容 | 验收 | 估时 |
|---|---|---|---|
| M1 骨架可见 | ObjC 插件对象树 + 属性回调；`build-driver.sh`；手动 sudo 安装 | 「音频 MIDI 设置」出现设备，coreaudiod 稳定 | 2 天 |
| M2 回环打通 | 环形缓冲 + 双流 IO + 时间戳对齐；`AudioSink` 换 UID 端到端 | 平板说话 → QuickTime 从新设备录到声音 | 3 天 |
| M3 安装体验 | `DriverInstaller.swift` + 提权安装/卸载脚本 + 设置页入口 + release 打包 | 干净系统一键装好、一键卸净 | 2 天 |
| M4 硬化与回归 | ASan 测试、10 分钟录音回归、睡眠/断连矩阵、文档 | 第 2.8 节清单全过 | 2 天 |

**合计约 9 个工作日**（缓冲 1 天）。Typeless 直认实验与删 `AggregateMicrophone.swift` 不在本批次，作为后续优化单独立项。

---

## 参考资料

- Apple: [Building an Audio Server Plug-in and Driver Extension](https://developer.apple.com/documentation/coreaudio/building-an-audio-server-plug-in-and-driver-extension)
- Apple QA1811: [Audio Server PlugIn](https://developer.apple.com/library/archive/qa/qa1811/_index.html)
- BlackHole（GPLv3，架构参考）: <https://github.com/ExistentialAudio/BlackHole>
- HAL 插件实战教程: <https://theevilbit.github.io/beyond/beyond_0013/>
- SMAppService 笔记: <https://theevilbit.github.io/posts/smappservice/>
- AudioDriverKit 不适用虚拟设备的讨论: <https://developer.apple.com/forums/tags/audiodriverkit>
