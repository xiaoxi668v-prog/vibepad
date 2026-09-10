import AppKit

/// Mac 菜单栏「VibePad 设置…」窗口：在电脑上直接改平板的界面皮肤、顶部区域、
/// 常用 App 与触控灵敏度。改动写进 PadConfigStore，由服务端推给已连接的平板。
@MainActor
final class SettingsWindowController: NSObject, NSWindowDelegate {
    private let store: PadConfigStore
    private let loadApps: (Bool, @escaping ([PadAppSummary]) -> Void) -> Void
    private var window: NSWindow?
    private var apps: [PadAppSummary] = []
    private var appPopUps: [NSPopUpButton] = []
    private let skinPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let headerPopUp = NSPopUpButton(frame: .zero, pullsDown: false)
    private let mouseSlider = NSSlider(value: 1, minValue: 0.5, maxValue: 2, target: nil, action: nil)
    private let scrollSlider = NSSlider(value: 1, minValue: 0.5, maxValue: 4, target: nil, action: nil)
    private let mouseValue = NSTextField(labelWithString: "1.0x")
    private let scrollValue = NSTextField(labelWithString: "1.0x")
    private let statusLabel = NSTextField(labelWithString: "")
    private var suppressActions = false

    init(store: PadConfigStore, loadApps: @escaping (Bool, @escaping ([PadAppSummary]) -> Void) -> Void) {
        self.store = store
        self.loadApps = loadApps
        super.init()
        store.addObserver { [weak self] config, _ in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.render(config) }
            }
        }
    }

    func show() {
        if window == nil { window = makeWindow() }
        render(store.snapshot())
        refreshApps(rescan: false)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    // MARK: - 界面

    private func makeWindow() -> NSWindow {
        let content = NSStackView()
        content.orientation = .vertical
        content.alignment = .leading
        content.spacing = 12
        content.edgeInsets = NSEdgeInsets(top: 20, left: 22, bottom: 20, right: 22)

        content.addArrangedSubview(sectionTitle("平板界面"))
        skinPopUp.removeAllItems()
        for skin in PadConfig.skins {
            skinPopUp.addItem(withTitle: PadConfig.skinNames[skin] ?? skin)
        }
        skinPopUp.target = self
        skinPopUp.action = #selector(skinChanged)
        content.addArrangedSubview(labeledRow("皮肤", skinPopUp))

        headerPopUp.removeAllItems()
        headerPopUp.addItem(withTitle: "Mac Touch Bar 画面")
        headerPopUp.addItem(withTitle: "本地额度栏")
        headerPopUp.target = self
        headerPopUp.action = #selector(headerModeChanged)
        content.addArrangedSubview(labeledRow("顶部区域", headerPopUp))

        content.addArrangedSubview(separator())
        content.addArrangedSubview(sectionTitle("常用 App"))
        content.addArrangedSubview(hint("最多 9 个，按下方顺序显示在平板上；留空表示不占位。"))

        let grid = NSStackView()
        grid.orientation = .vertical
        grid.alignment = .leading
        grid.spacing = 6
        appPopUps = []
        for row in 0..<3 {
            let line = NSStackView()
            line.orientation = .horizontal
            line.spacing = 6
            for column in 0..<3 {
                let popUp = NSPopUpButton(frame: .zero, pullsDown: false)
                popUp.tag = row * 3 + column
                popUp.target = self
                popUp.action = #selector(appChanged(_:))
                popUp.translatesAutoresizingMaskIntoConstraints = false
                popUp.widthAnchor.constraint(equalToConstant: 126).isActive = true
                appPopUps.append(popUp)
                line.addArrangedSubview(popUp)
            }
            grid.addArrangedSubview(line)
        }
        content.addArrangedSubview(grid)

        let rescan = NSButton(title: "重新扫描 Mac 上的 App", target: self, action: #selector(rescanApps))
        rescan.bezelStyle = .rounded
        content.addArrangedSubview(rescan)

        content.addArrangedSubview(separator())
        content.addArrangedSubview(sectionTitle("触控"))
        mouseSlider.target = self
        mouseSlider.action = #selector(mouseSensitivityChanged)
        mouseSlider.isContinuous = true
        scrollSlider.target = self
        scrollSlider.action = #selector(scrollSensitivityChanged)
        scrollSlider.isContinuous = true
        content.addArrangedSubview(sliderRow("鼠标灵敏度", mouseSlider, mouseValue))
        content.addArrangedSubview(sliderRow("滚动灵敏度", scrollSlider, scrollValue))

        content.addArrangedSubview(separator())
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.stringValue = "改动会立刻同步到已连接的平板；平板上的改动也会回写到这里。"
        statusLabel.lineBreakMode = .byWordWrapping
        statusLabel.preferredMaxLayoutWidth = 380
        content.addArrangedSubview(statusLabel)

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 440, height: 520),
            styleMask: [.titled, .closable, .miniaturizable],
            backing: .buffered,
            defer: false
        )
        window.title = "VibePad 设置"
        window.contentView = content
        window.delegate = self
        window.isReleasedWhenClosed = false
        window.center()
        return window
    }

    private func sectionTitle(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 13, weight: .medium)
        return label
    }

    private func hint(_ text: String) -> NSTextField {
        let label = NSTextField(labelWithString: text)
        label.font = .systemFont(ofSize: 11)
        label.textColor = .secondaryLabelColor
        label.lineBreakMode = .byWordWrapping
        label.preferredMaxLayoutWidth = 380
        return label
    }

    private func separator() -> NSBox {
        let box = NSBox()
        box.boxType = .separator
        box.translatesAutoresizingMaskIntoConstraints = false
        box.widthAnchor.constraint(equalToConstant: 396).isActive = true
        return box
    }

    private func labeledRow(_ title: String, _ control: NSView) -> NSStackView {
        let label = NSTextField(labelWithString: title)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.widthAnchor.constraint(equalToConstant: 76).isActive = true
        let row = NSStackView(views: [label, control])
        row.orientation = .horizontal
        row.spacing = 8
        return row
    }

    private func sliderRow(_ title: String, _ slider: NSSlider, _ value: NSTextField) -> NSStackView {
        slider.translatesAutoresizingMaskIntoConstraints = false
        slider.widthAnchor.constraint(equalToConstant: 240).isActive = true
        value.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        value.textColor = .secondaryLabelColor
        let row = labeledRow(title, slider)
        row.addArrangedSubview(value)
        return row
    }

    // MARK: - 数据

    private func refreshApps(rescan: Bool) {
        loadApps(rescan) { [weak self] apps in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.apps = apps
                    self.render(self.store.snapshot())
                }
            }
        }
    }

    /// 把配置画到控件上。渲染期间屏蔽控件回调，避免自己触发一次“修改”。
    private func render(_ config: PadConfig) {
        guard window != nil else { return }
        suppressActions = true
        defer { suppressActions = false }

        if let index = PadConfig.skins.firstIndex(of: config.skin) {
            skinPopUp.selectItem(at: index)
        }
        headerPopUp.selectItem(at: config.headerMode == "quota" ? 1 : 0)

        for (index, popUp) in appPopUps.enumerated() {
            popUp.removeAllItems()
            popUp.addItem(withTitle: "（空）")
            let selected = config.apps.indices.contains(index) ? config.apps[index] : nil
            var selectedRow = 0
            for (offset, app) in apps.enumerated() {
                popUp.addItem(withTitle: app.name)
                if app.bundleID == selected { selectedRow = offset + 1 }
            }
            // App 列表还没读回来时，至少保留已选 bundle id，避免渲染一次就把配置清空。
            if selectedRow == 0, let selected {
                popUp.addItem(withTitle: selected)
                selectedRow = popUp.numberOfItems - 1
            }
            popUp.selectItem(at: selectedRow)
        }

        mouseSlider.doubleValue = config.mouseSensitivity
        scrollSlider.doubleValue = config.scrollSensitivity
        mouseValue.stringValue = String(format: "%.1fx", config.mouseSensitivity)
        scrollValue.stringValue = String(format: "%.1fx", config.scrollSensitivity)
    }

    /// 从九个下拉框读回有序、去重的 bundle id 列表。
    private func selectedApps() -> [String] {
        var result: [String] = []
        for popUp in appPopUps {
            let index = popUp.indexOfSelectedItem
            guard index > 0 else { continue }
            let bundleID: String
            if index - 1 < apps.count {
                bundleID = apps[index - 1].bundleID
            } else {
                // 目录里没有的条目：标题本身就是之前存下来的 bundle id。
                bundleID = popUp.titleOfSelectedItem ?? ""
            }
            if !bundleID.isEmpty, !result.contains(bundleID) { result.append(bundleID) }
        }
        return Array(result.prefix(PadConfig.maxApps))
    }

    // MARK: - 控件回调

    @objc private func skinChanged() {
        guard !suppressActions else { return }
        let index = skinPopUp.indexOfSelectedItem
        guard PadConfig.skins.indices.contains(index) else { return }
        store.update { $0.skin = PadConfig.skins[index] }
    }

    @objc private func headerModeChanged() {
        guard !suppressActions else { return }
        store.update { $0.headerMode = self.headerPopUp.indexOfSelectedItem == 1 ? "quota" : "touchbar" }
    }

    @objc private func appChanged(_ sender: NSPopUpButton) {
        guard !suppressActions else { return }
        let apps = selectedApps()
        store.update { $0.apps = apps }
    }

    @objc private func rescanApps() {
        refreshApps(rescan: true)
    }

    @objc private func mouseSensitivityChanged() {
        guard !suppressActions else { return }
        let value = (mouseSlider.doubleValue * 10).rounded() / 10
        mouseValue.stringValue = String(format: "%.1fx", value)
        guard NSApp.currentEvent?.type != .leftMouseDragged else { return }
        store.update { $0.mouseSensitivity = value }
    }

    @objc private func scrollSensitivityChanged() {
        guard !suppressActions else { return }
        let value = (scrollSlider.doubleValue * 10).rounded() / 10
        scrollValue.stringValue = String(format: "%.1fx", value)
        guard NSApp.currentEvent?.type != .leftMouseDragged else { return }
        store.update { $0.scrollSensitivity = value }
    }
}
