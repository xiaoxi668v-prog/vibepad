# Orbit UI 交付包 · 方案 02 / 05

本包保留已经选中的两个方向：**02 深空专业 Graphite Pro**、**05 双手操控 Titanium Duo**。原 01、03、04 不在本次交付范围内。Orbit 是当前演示名称，可替换为实际产品名称。

## 先看哪里

1. 双击 `index.html`，在两个方案之间切换，体验交互。
2. 双击 `02-graphite-pro.html` 或 `05-titanium-duo.html`，看独立的平板全屏界面。
3. 打开 `screens/` 查看 1280 × 800 PNG 主界面、Chrome 联动状态和设置弹层。
4. 开发先读 `design/HANDOFF.md`，再对照 `design/tokens.json` 和 `design/measurements.json`。

**HTML 已内嵌样式、脚本和图标，断网也可以打开。** 不需要安装依赖，不需要 Codex。建议在现代 Chrome、Edge 或 Safari 中查看。

## 交付内容

| 路径 | 用途 |
|---|---|
| `index.html` | 两个方案的交互比较 |
| `02-graphite-pro.html` | 02 深空专业，纯界面，无方案选择器 |
| `05-titanium-duo.html` | 05 双手操控，纯界面，无方案选择器 |
| `screens/` | 实际界面截图，可直接发群或放入需求文档 |
| `src/orbit-ui.css` | 颜色、圆角、排版、布局、状态样式 |
| `src/orbit-ui.js` | 前端交互与本地演示状态 |
| `src/markup.html` | HTML 入口骨架 |
| `src/icons.js` | 离线图标渲染器 |
| `src/selected-fragment.html` | 可重新构建的完整源文件 |
| `assets/icons/` | Lucide 单个 SVG 图标 |
| `design/HANDOFF.md` | 界面、布局、交互和接入说明 |
| `design/tokens.json` | 可读取的样式变量 |
| `design/measurements.json` | 从浏览器实测的关键区域尺寸 |
| `design/QA.md` | 实际验证结果和范围 |

## 实现边界

这是 **UI 设计与交互参考源码**，供开发接入现有安卓 App。它没有连接真实电脑，没有调用麦克风、系统亮度或真实键盘。连接状态、时间、延迟、电量和语音转写使用演示值。开发应复用现有连接协议与业务逻辑，并将这里的状态替换为真实数据。

两个方案是待选择的独立布局。交付预览顶部的 02 / 05 切换器属于设计比较工具，不属于 App 产品界面。无须因为本包有两个方案，就在正式 App 中同时实现两套界面。

## 修改与重新生成

优先编辑 `src/selected-fragment.html`，然后运行：

```sh
python3 tools/build.py
```

此命令会重新生成三份独立 HTML，以及拆分后的 CSS、JS 与入口文件。单独编辑 `src/orbit-ui.css` 或 `src/orbit-ui.js` 不会自动回写完整源文件。截图和测量文件需在界面变更后重新导出。

所有图片和代码均随本包提供。系统字体不随包分发。第三方通用图标许可见 `assets/LICENSE-lucide.txt`。
