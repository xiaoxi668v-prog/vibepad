# VibePad

安卓平板变 Mac 无线触控台：触控板、Vibe Coding 快捷键、Touch Bar 画面回传、平板麦克风直通 Typeless。

原名 WebPad，2026-09-10 起改名 VibePad（Bundle ID / 包名 / LaunchAgent 全部换新，改名后需在 Mac 重新授予辅助功能权限并重新配对平板）。

## 结构

```text
android/     安卓平板 App（Kotlin，包名 com.xiaoxi.vibepad）
mac-helper/  Mac 菜单栏 Helper（Swift，Bundle ID com.xiaoxi.vibepad.helper）
designs/     平板端三套 UI 皮肤（01 经典黑 / 02 深空专业 / 05 双手操控）与设计源码
scripts/     发布脚本与 LaunchAgent 模板
docs/        交接文档（协议、坑、验收流程）
dist/        发布产物（本地产出，不进 git）
backups/     安装前自动备份（本地产出，不进 git）
```

## 构建与发布

```bash
scripts/release-vibepad.sh            # 构建 + 签名 + 打包到 dist/
scripts/release-vibepad.sh --install  # 并安装到 Mac 与已连接平板
```

签名身份固定为 `Apple Development: shishuaiok@sina.cn (347H32TQSD)`，脚本在身份缺失时直接停止，绝不回退 ad-hoc 签名。

详细协议、历史坑和验收流程见 [docs/HANDOFF.md](docs/HANDOFF.md)。
