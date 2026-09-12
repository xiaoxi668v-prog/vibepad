# VibePad Android

横屏平板端 App（Kotlin 原生 View，无 WebView，包名 `com.xiaoxi.vibepad`，
minSdk 28）。通过 Bonjour 发现 VibePad Mac Helper，建立认证 TCP 连接后提供：

- 右侧触控板：单指移动 / 轻点 / 长按拖动，双指自然滚动、惯性、轻点右键、捏合缩放，
  三/四指系统手势（`ui/TrackpadView.kt`）；
- 左侧常用 App（从 Mac 拉取真实图标）、Vibe Coding 键位与自定义快捷键、
  按住说话（`ui/VibePadView.kt`、`MainActivity.kt`）；
- 顶栏实时 Touch Bar 画面回传与触摸转发；
- 传输、配对与认证（`input/WifiInputSink.kt`、`input/PairingSecurity.kt`），
  配对密钥保存在 Android Keystore。

`input/BluetoothHidInputSink.kt` 与 `PreferredInputSink.kt` 是早期蓝牙 HID 方案的
历史代码，`MainActivity` 不会实例化它们，manifest 也没有声明蓝牙权限。

## 构建

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # 或你的 JDK 17
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

当前 `targetSdk = 28`，release 构建沿用 debug 签名（`app/build.gradle.kts`），
适合自用侧载；上架商店前需要自行提高 targetSdk 并配置正式签名。

## 首次使用

1. Mac 菜单栏点 VibePad 图标 → “允许配对新平板（60 秒）”；
2. 平板设置页 → “配对这台平板”，核对两端 6 位验证码后只在 Mac 上点“允许”；
3. “按住说话”首次会申请 `RECORD_AUDIO` 权限，授权后重新按住。

App 以沉浸式全屏运行并禁用返回键；退出入口在设置页。`system/KioskController.kt`
只有在设备管理员已把本包加入 lock-task 允许列表时才会进入锁定模式，不会主动
申请 Device Owner。
