import AppKit
import ApplicationServices
import CoreGraphics
import CryptoKit
import Foundation
import Network
import TouchBarBridge

private enum Wire {
    static let port: UInt16 = ProcessInfo.processInfo.environment["VIBEPAD_PORT"]
        .flatMap(UInt16.init) ?? 39_876
    static let headerSize = 10
    static let maxPayload = 65_535
    static let magic = Data([0x57, 0x50]) // WP
    static let version: UInt8 = 2
}

private enum PacketType: UInt8 {
    case serverChallenge = 0x02
    case authenticate = 0x03
    case authenticationOK = 0x04
    case pairRequest = 0x05
    case pairOffer = 0x06
    case pairAccept = 0x07
    case pairReject = 0x08
    case pairingDisabled = 0x09
    case move = 0x10
    case button = 0x11
    case scroll = 0x12
    case key = 0x13
    case releaseAll = 0x14
    case gesture = 0x15
    case ping = 0x20
    case pong = 0x21
    case usage = 0x30
    case appsRequest = 0x40
    case appsBegin = 0x41
    case appItem = 0x42
    case appsEnd = 0x43
    case launchApp = 0x44
    case touchBarSubscribe = 0x50
    case touchBarFrame = 0x51
    case touchBarFrameChunk = 0x52
    case touchBarEvent = 0x53
    case audioStart = 0x54
    case audioData = 0x55
    case audioStop = 0x56
    case configRequest = 0x60
    case config = 0x61
    case configUpdate = 0x62
}

private struct Packet {
    let type: PacketType
    let sequence: UInt32
    let payload: Data
}

private func uint16(_ data: Data, at offset: Int) -> UInt16 {
    UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
}

private func int16(_ data: Data, at offset: Int) -> Int16 {
    Int16(bitPattern: uint16(data, at: offset))
}

private func uint32(_ data: Data, at offset: Int) -> UInt32 {
    UInt32(data[offset]) << 24 | UInt32(data[offset + 1]) << 16 |
        UInt32(data[offset + 2]) << 8 | UInt32(data[offset + 3])
}

private func uint64(_ data: Data, at offset: Int) -> UInt64 {
    UInt64(uint32(data, at: offset)) << 32 | UInt64(uint32(data, at: offset + 4))
}

/// Mac 当前前台 App。平板用它高亮常用 App；NSWorkspace 只在主线程访问，
/// 网络线程读缓存值。
final class FrontmostAppTracker {
    static let shared = FrontmostAppTracker()
    private let lock = NSLock()
    private var cached: String?
    private var observer: NSObjectProtocol?

    var bundleID: String? {
        lock.lock()
        defer { lock.unlock() }
        return cached
    }

    @MainActor
    func start() {
        update(NSWorkspace.shared.frontmostApplication?.bundleIdentifier)
        guard observer == nil else { return }
        observer = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            self?.update(app?.bundleIdentifier)
        }
    }

    private func update(_ value: String?) {
        lock.lock()
        cached = value
        lock.unlock()
    }
}

private final class InputInjector {
    private static let moveFlushInterval = DispatchTimeInterval.milliseconds(8)
    private static let maxPendingMove = Int64(Int32.max)

    private let queue: DispatchQueue
    private let source = CGEventSource(stateID: .hidSystemState)
    private var buttons: UInt8 = 0
    private var pressedKeys: [CGKeyCode: CGEventFlags] = [:]
    private var heldModifiers: UInt8 = 0
    private var pendingMoveX: Int64 = 0
    private var pendingMoveY: Int64 = 0
    private var moveFlushGeneration: UInt64 = 0
    private var scheduledMoveFlush: UInt64?
    private var lastInputAt: Date?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func move(dx: Int16, dy: Int16) {
        dispatchPrecondition(condition: .onQueue(queue))
        lastInputAt = Date()
        pendingMoveX = Self.clampedMoveSum(pendingMoveX, Int64(dx))
        pendingMoveY = Self.clampedMoveSum(pendingMoveY, Int64(dy))
        guard scheduledMoveFlush == nil else { return }
        moveFlushGeneration &+= 1
        let generation = moveFlushGeneration
        scheduledMoveFlush = generation
        queue.asyncAfter(deadline: .now() + Self.moveFlushInterval) { [weak self] in
            guard let self, self.scheduledMoveFlush == generation else { return }
            self.scheduledMoveFlush = nil
            self.postPendingMove()
        }
    }

    @discardableResult
    func flushPendingMove() -> CGPoint? {
        dispatchPrecondition(condition: .onQueue(queue))
        // Invalidate a previously scheduled timer. Its closure checks the
        // generation before doing anything, so an old timer cannot clear a newer one.
        scheduledMoveFlush = nil
        moveFlushGeneration &+= 1
        return postPendingMove()
    }

    @discardableResult
    private func postPendingMove() -> CGPoint? {
        dispatchPrecondition(condition: .onQueue(queue))
        guard pendingMoveX != 0 || pendingMoveY != 0 else { return nil }
        guard let current = CGEvent(source: nil)?.location else { return nil }
        let dx = pendingMoveX
        let dy = pendingMoveY
        pendingMoveX = 0
        pendingMoveY = 0
        // Read the absolute cursor once per 8 ms batch. Reading it for every
        // tiny MOVE can return the same pre-post location and overwrite earlier
        // deltas before WindowServer consumes them.
        let target = CGPoint(x: current.x + CGFloat(dx), y: current.y + CGFloat(dy))
        let kind: (CGEventType, CGMouseButton)
        if buttons & 1 != 0 { kind = (.leftMouseDragged, .left) }
        else if buttons & 2 != 0 { kind = (.rightMouseDragged, .right) }
        else if buttons & 4 != 0 { kind = (.otherMouseDragged, .center) }
        else { kind = (.mouseMoved, .left) }
        CGEvent(mouseEventSource: source, mouseType: kind.0,
                mouseCursorPosition: target, mouseButton: kind.1)?.post(tap: .cghidEventTap)
        return target
    }

    func setButton(mask: UInt8, pressed: Bool) {
        lastInputAt = Date()
        flushPendingMove()
        let relevant = mask & 0x07
        let next = pressed ? buttons | relevant : buttons & ~relevant
        let changed = buttons ^ next
        if changed & 1 != 0 { postButton(.left, down: next & 1 != 0) }
        if changed & 2 != 0 { postButton(.right, down: next & 2 != 0) }
        if changed & 4 != 0 { postButton(.center, down: next & 4 != 0) }
        buttons = next
    }

    func scroll(vertical: Int16, horizontal: Int16) {
        lastInputAt = Date()
        // A two-finger gesture often begins immediately after a pointer move. WindowServer
        // may not have consumed that final move yet, so a location-less synthetic wheel
        // event can be routed to the focused window instead of the window under the cursor.
        // Bind the wheel event to the final intended pointer position explicitly.
        let pendingTarget = flushPendingMove()
        guard let target = pendingTarget ?? CGEvent(source: nil)?.location,
              let event = CGEvent(
                scrollWheelEvent2Source: source,
                units: .pixel,
                wheelCount: 2,
                wheel1: Int32(vertical),
                wheel2: Int32(horizontal),
                wheel3: 0
              )
        else { return }
        event.location = target
        event.setIntegerValueField(.scrollWheelEventIsContinuous, value: 1)
        event.post(tap: .cghidEventTap)
    }

    func gesture(_ value: UInt8) {
        lastInputAt = Date()
        flushPendingMove()
        switch value {
        case 1: postShortcut(keyCode: 126, flags: .maskControl) // Mission Control
        case 2: postShortcut(keyCode: 125, flags: .maskControl) // App Exposé
        case 3: postShortcut(keyCode: 123, flags: .maskControl) // Previous Space
        case 4: postShortcut(keyCode: 124, flags: .maskControl) // Next Space
        case 5: postShortcut(keyCode: 103, flags: .maskSecondaryFn) // Show Desktop
        case 6: postShortcut(keyCode: 49, flags: .maskCommand) // Spotlight / apps
        case 7: postShortcut(keyCode: 24, flags: [.maskCommand, .maskShift]) // Zoom in
        case 8: postShortcut(keyCode: 27, flags: .maskCommand) // Zoom out
        case 9: postShortcut(keyCode: 2, flags: [.maskCommand, .maskControl]) // Look Up
        default: break
        }
    }

    private func postShortcut(keyCode: CGKeyCode, flags: CGEventFlags) {
        guard let down = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true),
              let up = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false)
        else { return }
        down.flags = flags
        up.flags = flags
        down.post(tap: .cghidEventTap)
        up.post(tap: .cghidEventTap)
    }

    func key(hidUsage: UInt16, modifierByte: UInt8, pressed: Bool) {
        lastInputAt = Date()
        flushPendingMove()
        // A USB boot-keyboard report represents modifier-only input with usage 0.
        // Modifier usages 0xe0...0xe7 are accepted as well for protocol clients
        // that send the physical modifier key explicitly.
        if hidUsage == 0 {
            updateModifiers(mask: modifierByte, pressed: pressed)
            return
        }
        if (0xe0...0xe7).contains(hidUsage) {
            updateModifiers(mask: UInt8(1 << (hidUsage - 0xe0)), pressed: pressed)
            return
        }
        guard let keyCode = Self.keyCode(for: hidUsage) else { return }
        let flags = Self.eventFlags(from: modifierByte)
        guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: pressed)
        else { return }
        event.flags = flags
        event.post(tap: .cghidEventTap)
        if pressed { pressedKeys[keyCode] = flags } else { pressedKeys.removeValue(forKey: keyCode) }
    }

    func releaseAll() {
        flushPendingMove()
        setButton(mask: 0x07, pressed: false)
        let flags = Self.eventFlags(from: heldModifiers)
        for keyCode in pressedKeys.keys {
            let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
        pressedKeys.removeAll()
        updateModifiers(mask: heldModifiers, pressed: false)
    }

    func healthPayload() -> Data {
        dispatchPrecondition(condition: .onQueue(queue))
        let age = lastInputAt.map { max(0, Int(Date().timeIntervalSince($0) * 1_000)) }
        var object: [String: Any] = [
            "accessibilityTrusted": AXIsProcessTrusted(),
            "helperVersion": HelperInfo.version,
            "protocolVersion": Int(Wire.version),
            "lastInputAgeMs": age ?? NSNull(),
            "mouseButtons": Int(buttons),
            "modifiers": Int(heldModifiers),
        ]
        if let frontmost = FrontmostAppTracker.shared.bundleID { object["frontmostApp"] = frontmost }
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    /// 供菜单栏状态面板使用；允许从其他线程调用。
    func inputStatusSnapshot() -> (lastInputAt: Date?, buttons: UInt8, modifiers: UInt8) {
        queue.sync { (lastInputAt, buttons, heldModifiers) }
    }

    private func updateModifiers(mask: UInt8, pressed: Bool) {
        // Post one flagsChanged event per physical modifier. The event flags must
        // describe the state *after* the transition: Option is present on down
        // and absent on up. This is required by apps that monitor modifier taps.
        for bit: UInt8 in [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80] {
            guard mask & bit != 0 else { continue }
            let wasHeld = heldModifiers & bit != 0
            guard wasHeld != pressed, let keyCode = Self.modifierKeyCode(for: bit) else { continue }
            if pressed { heldModifiers |= bit } else { heldModifiers &= ~bit }
            guard let event = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: pressed)
            else { continue }
            event.type = .flagsChanged
            event.flags = Self.eventFlags(from: heldModifiers)
            event.post(tap: .cghidEventTap)
        }
    }

    private func postButton(_ button: CGMouseButton, down: Bool) {
        guard let point = CGEvent(source: nil)?.location else { return }
        let type: CGEventType
        switch (button, down) {
        case (.left, true): type = .leftMouseDown
        case (.left, false): type = .leftMouseUp
        case (.right, true): type = .rightMouseDown
        case (.right, false): type = .rightMouseUp
        case (_, true): type = .otherMouseDown
        case (_, false): type = .otherMouseUp
        }
        CGEvent(mouseEventSource: source, mouseType: type,
                mouseCursorPosition: point, mouseButton: button)?.post(tap: .cghidEventTap)
    }

    private static func eventFlags(from modifiers: UInt8) -> CGEventFlags {
        var flags = CGEventFlags()
        if modifiers & 0x22 != 0 { flags.insert(.maskShift) }
        if modifiers & 0x11 != 0 { flags.insert(.maskControl) }
        if modifiers & 0x44 != 0 { flags.insert(.maskAlternate) }
        if modifiers & 0x88 != 0 { flags.insert(.maskCommand) }
        return flags
    }

    private static func modifierKeyCode(for bit: UInt8) -> CGKeyCode? {
        switch bit {
        case 0x01: return 59 // left Control
        case 0x02: return 56 // left Shift
        case 0x04: return 58 // left Option
        case 0x08: return 55 // left Command/GUI
        case 0x10: return 62 // right Control
        case 0x20: return 60 // right Shift
        case 0x40: return 61 // right Option
        case 0x80: return 54 // right Command/GUI
        default: return nil
        }
    }

    private static func clampedMoveSum(_ current: Int64, _ delta: Int64) -> Int64 {
        min(max(current + delta, -maxPendingMove), maxPendingMove)
    }

    // USB HID Keyboard/Keypad usage page (0x07) to macOS virtual key codes.
    private static func keyCode(for usage: UInt16) -> CGKeyCode? {
        let table: [UInt16: CGKeyCode] = [
            0x04:0, 0x05:11, 0x06:8, 0x07:2, 0x08:14, 0x09:3, 0x0a:5,
            0x0b:4, 0x0c:34, 0x0d:38, 0x0e:40, 0x0f:37, 0x10:46, 0x11:45,
            0x12:31, 0x13:35, 0x14:12, 0x15:15, 0x16:1, 0x17:17, 0x18:32,
            0x19:9, 0x1a:13, 0x1b:7, 0x1c:16, 0x1d:6,
            0x1e:18, 0x1f:19, 0x20:20, 0x21:21, 0x22:23, 0x23:22, 0x24:26,
            0x25:28, 0x26:25, 0x27:29,
            0x28:36, 0x29:53, 0x2a:51, 0x2b:48, 0x2c:49, 0x2d:27, 0x2e:24,
            0x2f:33, 0x30:30, 0x31:42, 0x32:10, 0x33:41, 0x34:39, 0x35:50,
            0x36:43, 0x37:47, 0x38:44, 0x39:57,
            0x3a:122, 0x3b:120, 0x3c:99, 0x3d:118, 0x3e:96, 0x3f:97,
            0x40:98, 0x41:100, 0x42:101, 0x43:109, 0x44:103, 0x45:111,
            0x49:115, 0x4a:116, 0x4b:121, 0x4c:117, 0x4d:119, 0x4e:114,
            0x4f:124, 0x50:123, 0x51:125, 0x52:126,
            0x53:71, 0x54:75, 0x55:67, 0x56:78, 0x57:69, 0x58:76,
            0x59:83, 0x5a:84, 0x5b:85, 0x5c:86, 0x5d:87, 0x5e:88,
            0x5f:89, 0x60:91, 0x61:92, 0x62:82, 0x63:65,
            0xe0:59, 0xe1:56, 0xe2:58, 0xe3:55,
            0xe4:62, 0xe5:60, 0xe6:61, 0xe7:54
        ]
        return table[usage]
    }
}

private struct InstalledApp {
    let name: String
    let bundleID: String
    let url: URL
    let iconPNG: Data?

    var wirePayload: Data? {
        var object: [String: Any] = ["name": name, "bundleId": bundleID]
        if let iconPNG { object["icon"] = iconPNG.base64EncodedString() }
        return try? JSONSerialization.data(withJSONObject: object)
    }
}

private final class AppCatalog {
    private let lock = NSLock()
    private var cached: [InstalledApp]?

    func load(rescan: Bool = false, completion: @escaping ([InstalledApp]) -> Void) {
        lock.lock()
        let existing = rescan ? nil : cached
        lock.unlock()
        if let existing {
            completion(existing)
            return
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self else { return }
            let apps = self.scan()
            self.lock.lock()
            self.cached = apps
            self.lock.unlock()
            completion(apps)
        }
    }

    /// 供菜单栏设置窗口列出可选 App；只暴露名称与 bundle id。
    /// rescan 为 true 时重新扫盘，但不清空缓存：扫描期间 launch() 仍按旧列表放行。
    func summaries(rescan: Bool, completion: @escaping ([PadAppSummary]) -> Void) {
        load(rescan: rescan) { apps in
            completion(apps.map { PadAppSummary(name: $0.name, bundleID: $0.bundleID) })
        }
    }

    func launch(bundleID: String) {
        lock.lock()
        let app = cached?.first { $0.bundleID == bundleID }
        lock.unlock()
        guard let app else {
            print("Rejected launch request for unlisted bundle: \(bundleID)")
            return
        }
        DispatchQueue.main.async {
            NSWorkspace.shared.openApplication(
                at: app.url,
                configuration: NSWorkspace.OpenConfiguration()
            ) { _, error in
                if let error { print("Could not open \(app.name): \(error)") }
            }
        }
    }

    private func scan() -> [InstalledApp] {
        let roots = [
            URL(fileURLWithPath: "/Applications", isDirectory: true),
            URL(fileURLWithPath: "/System/Applications", isDirectory: true),
            FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true),
        ]
        var byBundle: [String: InstalledApp] = [:]
        for root in roots {
            guard let enumerator = FileManager.default.enumerator(
                at: root,
                includingPropertiesForKeys: [.isApplicationKey],
                options: [.skipsHiddenFiles, .skipsPackageDescendants]
            ) else { continue }
            for case let url as URL in enumerator where url.pathExtension.lowercased() == "app" {
                guard let bundle = Bundle(url: url),
                      let bundleID = bundle.bundleIdentifier,
                      !bundleID.isEmpty else { continue }
                if (bundle.object(forInfoDictionaryKey: "LSUIElement") as? Bool) == true ||
                    (bundle.object(forInfoDictionaryKey: "LSBackgroundOnly") as? Bool) == true {
                    continue
                }
                let name = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
                    ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
                    ?? url.deletingPathExtension().lastPathComponent
                guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { continue }
                let icon = Self.pngIcon(for: url)
                byBundle[bundleID] = InstalledApp(name: name, bundleID: bundleID, url: url, iconPNG: icon)
            }
        }
        let finderURL = URL(fileURLWithPath: "/System/Library/CoreServices/Finder.app")
        if let bundle = Bundle(url: finderURL), let bundleID = bundle.bundleIdentifier {
            byBundle[bundleID] = InstalledApp(
                name: "Finder",
                bundleID: bundleID,
                url: finderURL,
                iconPNG: Self.pngIcon(for: finderURL)
            )
        }
        return byBundle.values.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private static func pngIcon(for url: URL) -> Data? {
        let image = NSWorkspace.shared.icon(forFile: url.path)
        let size = NSSize(width: 48, height: 48)
        guard let bitmap = NSBitmapImageRep(
            bitmapDataPlanes: nil,
            pixelsWide: Int(size.width),
            pixelsHigh: Int(size.height),
            bitsPerSample: 8,
            samplesPerPixel: 4,
            hasAlpha: true,
            isPlanar: false,
            colorSpaceName: .deviceRGB,
            bytesPerRow: 0,
            bitsPerPixel: 0
        ) else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
        image.draw(in: NSRect(origin: .zero, size: size),
                   from: .zero, operation: .copy, fraction: 1)
        NSGraphicsContext.restoreGraphicsState()
        return bitmap.representation(using: .png, properties: [:])
    }
}

private final class UsageProvider {
    private let lock = NSLock()
    private var cached: Data?
    private var lastFetch = Date.distantPast
    private var fetchInFlight = false

    func refresh(completion: @escaping (Data?) -> Void) {
        lock.lock()
        let shouldFetch = !fetchInFlight && Date().timeIntervalSince(lastFetch) >= 30
        let current = cached
        if shouldFetch {
            fetchInFlight = true
            lastFetch = Date()
        }
        lock.unlock()
        if !shouldFetch {
            completion(current)
            return
        }
        guard let url = URL(string: "http://127.0.0.1:8088/usage") else {
            finish(data: nil, completion: completion)
            return
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3
        URLSession.shared.dataTask(with: request) { [weak self] data, _, _ in
            guard let self else { return }
            self.finish(data: data.flatMap(Self.sanitized), completion: completion)
        }.resume()
    }

    private func finish(data: Data?, completion: @escaping (Data?) -> Void) {
        lock.lock()
        if let data { cached = data }
        fetchInFlight = false
        let result = cached
        lock.unlock()
        completion(result)
    }

    private static func sanitized(_ data: Data) -> Data? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func windows(_ value: Any?, names: [String]) -> [String: Any] {
            guard let object = value as? [String: Any] else { return [:] }
            var result: [String: Any] = [:]
            for name in names {
                if let window = object[name] as? [String: Any] {
                    result[name] = [
                        "used_pct": window["used_pct"] ?? NSNull(),
                        "reset_at": window["reset_at"] ?? NSNull(),
                    ]
                }
            }
            return result
        }
        let safe: [String: Any] = [
            "claude": windows(root["claude"], names: ["five_hour", "seven_day", "fable_5"]),
            "codex": windows(root["codex"], names: ["five_hour", "weekly"]),
        ]
        return try? JSONSerialization.data(withJSONObject: safe)
    }
}

private final class ClientSession {
    private let connection: NWConnection
    private let injector: InputInjector
    private let queue: DispatchQueue
    private let appCatalog: AppCatalog
    private let usageProvider: UsageProvider
    private let pairingGate: PairingGate
    private let pairingStore: PairingStore
    private let audioSink: AudioSink
    private let configStore: PadConfigStore
    private let sessionID: UUID
    private var buffer = Data()
    private var authenticated = false
    private var pairingInProgress = false
    private var stopped = false
    private let serverNonce = PairingCrypto.randomBytes(count: 32)
    private var lastUsageSentAt = Date.distantPast
    private let touchBar = WPTouchBarBridge()
    private var touchBarSubscribed = false
    private var nextTouchBarFrameID: UInt32 = 1
    private let onStop: () -> Void

    init(
        connection: NWConnection,
        injector: InputInjector,
        queue: DispatchQueue,
        appCatalog: AppCatalog,
        usageProvider: UsageProvider,
        pairingGate: PairingGate,
        pairingStore: PairingStore,
        audioSink: AudioSink,
        configStore: PadConfigStore,
        sessionID: UUID,
        onStop: @escaping () -> Void
    ) {
        self.connection = connection
        self.injector = injector
        self.queue = queue
        self.appCatalog = appCatalog
        self.usageProvider = usageProvider
        self.pairingGate = pairingGate
        self.pairingStore = pairingStore
        self.audioSink = audioSink
        self.configStore = configStore
        self.sessionID = sessionID
        self.onStop = onStop
    }

    func start(on queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.send(type: .serverChallenge, sequence: 0, payload: self.serverNonce)
                self.receive()
                self.queue.asyncAfter(deadline: .now() + 90) { [weak self] in
                    guard let self, !self.authenticated, !self.pairingInProgress else { return }
                    print("Closing client that did not authenticate in time: \(self.connection.endpoint)")
                    self.stop()
                }
            case .failed(let error):
                print("Connection failed: \(error)")
                self.stop()
            case .cancelled: self.stop()
            default: break
            }
        }
        connection.start(queue: queue)
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data, !data.isEmpty { self.buffer.append(data); self.drain() }
            if complete || error != nil { self.stop() } else { self.receive() }
        }
    }

    private func drain() {
        while buffer.count >= Wire.headerSize {
            guard buffer[0] == 0x57, buffer[1] == 0x50 else {
                buffer = Data(buffer.dropFirst()); continue
            }
            guard buffer[2] == Wire.version else {
                print("Closing client with obsolete protocol version \(buffer[2]): \(connection.endpoint)")
                stop()
                return
            }
            let payloadLength = Int(uint16(buffer, at: 4))
            guard payloadLength <= Wire.maxPayload else { stop(); return }
            let frameLength = Wire.headerSize + payloadLength
            guard buffer.count >= frameLength else { return }
            let type = PacketType(rawValue: buffer[3])
            let sequence = uint32(buffer, at: 6)
            let payload = Data(buffer[Wire.headerSize..<frameLength])
            buffer = Data(buffer.dropFirst(frameLength))
            if let type { handle(Packet(type: type, sequence: sequence, payload: payload)) }
        }
    }

    private func handle(_ packet: Packet) {
        if !authenticated {
            handleUnauthenticated(packet)
            return
        }
        switch packet.type {
        case .move where packet.payload.count == 4:
            injector.move(dx: int16(packet.payload, at: 0), dy: int16(packet.payload, at: 2))
        case .button where packet.payload.count == 2:
            injector.setButton(mask: packet.payload[0], pressed: packet.payload[1] != 0)
        case .scroll where packet.payload.count == 4:
            injector.scroll(vertical: int16(packet.payload, at: 0), horizontal: int16(packet.payload, at: 2))
        case .key where packet.payload.count == 4:
            injector.key(hidUsage: uint16(packet.payload, at: 0),
                         modifierByte: packet.payload[2], pressed: packet.payload[3] != 0)
        case .releaseAll:
            injector.releaseAll()
        case .gesture where packet.payload.count == 1:
            injector.gesture(packet.payload[0])
        case .ping:
            send(type: .pong, sequence: packet.sequence, payload: injector.healthPayload())
            if Date().timeIntervalSince(lastUsageSentAt) >= 30 {
                lastUsageSentAt = Date()
                usageProvider.refresh { [weak self] data in
                    guard let self, let data else { return }
                    self.queue.async { self.send(type: .usage, sequence: packet.sequence, payload: data) }
                }
            }
        case .appsRequest:
            send(type: .appsBegin, sequence: packet.sequence)
            appCatalog.load { [weak self] apps in
                guard let self else { return }
                self.queue.async {
                    for app in apps {
                        guard let payload = app.wirePayload, payload.count <= Wire.maxPayload else { continue }
                        self.send(type: .appItem, sequence: packet.sequence, payload: payload)
                    }
                    self.send(type: .appsEnd, sequence: packet.sequence)
                }
            }
        case .configRequest:
            // 平板握手：带上自己的配置，revision 大的一方胜出，回一份当前配置。
            sendPadConfig(mergePadConfig(packet.payload).merged, sequence: packet.sequence)
        case .configUpdate:
            // 平板改了配置：存下来，并由服务端转发给其它已连接会话。
            // Mac 这边更新（平板的 revision 更旧）时回推一份，别让平板停在旧配置上。
            let result = mergePadConfig(packet.payload)
            if let incoming = result.incoming, !result.merged.sameContent(as: incoming) {
                sendPadConfig(result.merged, sequence: packet.sequence)
            }
        case .launchApp:
            guard let bundleID = String(data: packet.payload, encoding: .utf8),
                  bundleID.count <= 512 else { break }
            appCatalog.launch(bundleID: bundleID)
        case .touchBarSubscribe where packet.payload.count == 1:
            setTouchBarSubscribed(packet.payload[0] != 0)
        case .touchBarEvent where packet.payload.count == 6:
            let phase = packet.payload[0]
            guard phase <= 2 else { break }
            touchBar.postTouchPhase(
                phase,
                normalizedX: uint16(packet.payload, at: 2),
                normalizedY: uint16(packet.payload, at: 4)
            )
        case .audioStart where packet.payload.count == 12:
            let accepted = audioSink.start(
                owner: sessionID,
                streamID: uint32(packet.payload, at: 0),
                sampleRate: uint32(packet.payload, at: 4),
                channels: packet.payload[8],
                format: packet.payload[9],
                framesPerPacket: uint16(packet.payload, at: 10)
            )
            if !accepted { print("Rejected VibePad audio START from \(connection.endpoint)") }
        case .audioData where packet.payload.count >= 18:
            let sampleCount = uint16(packet.payload, at: 16)
            guard packet.payload.count == 18 + Int(sampleCount) * 2 else { break }
            audioSink.enqueue(
                owner: sessionID,
                streamID: uint32(packet.payload, at: 0),
                audioSequence: uint32(packet.payload, at: 4),
                captureTimeNs: uint64(packet.payload, at: 8),
                sampleCount: sampleCount,
                pcm16LE: Data(packet.payload.dropFirst(18))
            )
        case .audioStop where packet.payload.count == 5:
            audioSink.stop(
                owner: sessionID,
                streamID: uint32(packet.payload, at: 0),
                reason: packet.payload[4]
            )
        default: break
        }
    }

    private func mergePadConfig(_ payload: Data) -> (merged: PadConfig, incoming: PadConfig?) {
        guard let incoming = PadConfig(payload: payload) else {
            print("Ignoring malformed pad config from \(connection.endpoint)")
            return (configStore.snapshot(), nil)
        }
        return (configStore.accept(incoming, from: sessionID), incoming)
    }

    private func sendPadConfig(_ config: PadConfig, sequence: UInt32 = 0) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard let payload = config.payload, payload.count <= Wire.maxPayload else { return }
        send(type: .config, sequence: sequence, payload: payload)
    }

    /// 由服务端在配置变化时调用（Mac 设置窗口改的，或别的平板推来的）。
    func pushPadConfig(_ config: PadConfig) {
        queue.async { [weak self] in
            guard let self, self.authenticated, !self.stopped else { return }
            self.sendPadConfig(config)
        }
    }

    private func setTouchBarSubscribed(_ subscribed: Bool) {
        dispatchPrecondition(condition: .onQueue(queue))
        guard subscribed != touchBarSubscribed else { return }
        if !subscribed {
            touchBarSubscribed = false
            touchBar.stop()
            return
        }
        let started = touchBar.start { [weak self] png, width, height in
            guard let self else { return }
            self.queue.async { [weak self] in
                guard let self, self.authenticated, self.touchBarSubscribed, !self.stopped else { return }
                self.sendTouchBarFrame(png, width: width, height: height)
            }
        }
        touchBarSubscribed = started
        if !started {
            print("Touch Bar unavailable: required private DFR APIs are missing or the display stream failed")
        }
    }

    private func sendTouchBarFrame(_ png: Data, width: UInt16, height: UInt16) {
        dispatchPrecondition(condition: .onQueue(queue))
        let frameID = nextTouchBarFrameID
        nextTouchBarFrameID &+= 1
        if nextTouchBarFrameID == 0 { nextTouchBarFrameID = 1 }

        var common = Data()
        common.append(contentsOf: [
            UInt8(truncatingIfNeeded: frameID >> 24),
            UInt8(truncatingIfNeeded: frameID >> 16),
            UInt8(truncatingIfNeeded: frameID >> 8),
            UInt8(truncatingIfNeeded: frameID),
            UInt8(truncatingIfNeeded: width >> 8), UInt8(truncatingIfNeeded: width),
            UInt8(truncatingIfNeeded: height >> 8), UInt8(truncatingIfNeeded: height),
            1, // PNG
        ])
        if common.count + png.count <= Wire.maxPayload {
            send(type: .touchBarFrame, sequence: frameID, payload: common + png)
            return
        }

        let maxChunkData = 60 * 1024
        let chunkCount = (png.count + maxChunkData - 1) / maxChunkData
        guard chunkCount <= Int(UInt16.max) else { return }
        for index in 0..<chunkCount {
            let lower = index * maxChunkData
            let upper = min(lower + maxChunkData, png.count)
            var payload = common
            payload.append(contentsOf: [
                UInt8(truncatingIfNeeded: UInt16(index) >> 8),
                UInt8(truncatingIfNeeded: UInt16(index)),
                UInt8(truncatingIfNeeded: UInt16(chunkCount) >> 8),
                UInt8(truncatingIfNeeded: UInt16(chunkCount)),
            ])
            payload.append(png[lower..<upper])
            send(type: .touchBarFrameChunk, sequence: frameID, payload: payload)
        }
    }

    private func handleUnauthenticated(_ packet: Packet) {
        switch packet.type {
        case .authenticate where packet.payload.count == 80:
            let clientID = Data(packet.payload[0..<16])
            let clientNonce = Data(packet.payload[16..<48])
            let receivedMAC = Data(packet.payload[48..<80])
            guard let secret = pairingStore.secret(for: clientID) else {
                send(type: .pairReject, sequence: packet.sequence,
                     payload: Data("PAIRING_REQUIRED".utf8))
                return
            }
            let authData = Data("client-auth".utf8) + serverNonce + clientNonce + clientID
            let expected = PairingCrypto.hmac(secret: secret, data: authData)
            guard PairingCrypto.constantTimeEqual(receivedMAC, expected) else {
                print("Rejected invalid authentication proof from \(connection.endpoint)")
                stop()
                return
            }
            let responseData = Data("server-auth".utf8) + serverNonce + clientNonce + clientID
            send(type: .authenticationOK, sequence: packet.sequence,
                 payload: PairingCrypto.hmac(secret: secret, data: responseData))
            authenticated = true
            StatusCenter.shared.sessionAuthenticated(
                id: sessionID,
                deviceName: pairingStore.deviceName(for: clientID),
                endpoint: endpointHost(connection.endpoint)
            )
            print("VibePad paired client authenticated: \(connection.endpoint)")

        case .pairRequest:
            handlePairRequest(packet)

        default:
            print("Rejected unauthenticated packet \(packet.type) from \(connection.endpoint)")
        }
    }

    private func handlePairRequest(_ packet: Packet) {
        guard pairingGate.isOpen else {
            send(type: .pairingDisabled, sequence: packet.sequence,
                 payload: Data("请先在 Mac 菜单栏打开 60 秒配对窗口".utf8))
            return
        }
        guard !pairingInProgress,
              packet.payload.count >= 82 else {
            send(type: .pairReject, sequence: packet.sequence, payload: Data("INVALID_REQUEST".utf8))
            return
        }
        let clientID = Data(packet.payload[0..<16])
        let nameLength = Int(packet.payload[16])
        let publicKeyOffset = 17 + nameLength
        guard nameLength <= 63,
              packet.payload.count == publicKeyOffset + 65,
              let deviceName = String(data: Data(packet.payload[17..<publicKeyOffset]), encoding: .utf8),
              let clientPublicKey = try? P256.KeyAgreement.PublicKey(
                x963Representation: Data(packet.payload[publicKeyOffset..<(publicKeyOffset + 65)]))
        else {
            send(type: .pairReject, sequence: packet.sequence, payload: Data("INVALID_REQUEST".utf8))
            return
        }

        let serverPrivateKey = P256.KeyAgreement.PrivateKey()
        let serverPublicKey = serverPrivateKey.publicKey.x963Representation
        let clientPublicData = clientPublicKey.x963Representation
        guard let shared = try? serverPrivateKey.sharedSecretFromKeyAgreement(with: clientPublicKey) else {
            send(type: .pairReject, sequence: packet.sequence, payload: Data("KEY_AGREEMENT_FAILED".utf8))
            return
        }
        let secret = PairingCrypto.derivePairingSecret(
            sharedSecret: shared,
            clientID: clientID,
            clientPublicKey: clientPublicData,
            serverPublicKey: serverPublicKey
        )
        let transcript = clientID + clientPublicData + serverPublicKey
        let code = PairingCrypto.verificationCode(secret: secret, transcript: transcript)
        pairingInProgress = true
        send(type: .pairOffer, sequence: packet.sequence, payload: serverPublicKey)

        MenuBarController.approvePairing(deviceName: deviceName, code: code) { [weak self] approved in
            guard let self else { return }
            self.queue.async {
                guard !self.stopped else { return }
                self.pairingInProgress = false
                guard approved, self.pairingStore.save(secret: secret, clientID: clientID, deviceName: deviceName) else {
                    self.send(type: .pairReject, sequence: packet.sequence, payload: Data("PAIRING_REJECTED".utf8))
                    return
                }
                let proof = PairingCrypto.hmac(
                    secret: secret,
                    data: Data("pair-accept".utf8) + transcript
                )
                self.send(type: .pairAccept, sequence: packet.sequence, payload: proof)
                self.authenticated = true
                StatusCenter.shared.sessionAuthenticated(
                    id: self.sessionID,
                    deviceName: deviceName,
                    endpoint: self.endpointHost(self.connection.endpoint)
                )
                self.pairingGate.close()
                print("VibePad paired: \(deviceName) \(clientID.hexString)")
            }
        }
    }

    private func send(type: PacketType, sequence: UInt32, payload: Data = Data()) {
        guard payload.count <= Wire.maxPayload else { return }
        let length = UInt16(payload.count)
        let bytes: [UInt8] = [0x57, 0x50, Wire.version, type.rawValue,
                              UInt8(truncatingIfNeeded: length >> 8),
                              UInt8(truncatingIfNeeded: length),
                              UInt8(truncatingIfNeeded: sequence >> 24),
                              UInt8(truncatingIfNeeded: sequence >> 16),
                              UInt8(truncatingIfNeeded: sequence >> 8),
                              UInt8(truncatingIfNeeded: sequence)]
        connection.send(content: Data(bytes) + payload, completion: .contentProcessed { _ in })
    }

    private func endpointHost(_ endpoint: NWEndpoint) -> String {
        if case .hostPort(let host, _) = endpoint { return "\(host)" }
        return "\(endpoint)"
    }

    private func stop() {
        guard !stopped else { return }
        stopped = true
        StatusCenter.shared.sessionEnded(id: sessionID)
        touchBarSubscribed = false
        touchBar.stop()
        audioSink.stop(owner: sessionID)
        if authenticated { injector.releaseAll(); print("VibePad disconnected") }
        connection.cancel()
        onStop()
    }
}

private final class VibePadServer {
    private let queue = DispatchQueue(label: "com.xiaoxi.vibepad.mac-helper", qos: .userInteractive)
    private lazy var injector = InputInjector(queue: queue)
    private let appCatalog = AppCatalog()
    private let usageProvider = UsageProvider()
    private let pairingGate: PairingGate
    private let pairingStore: PairingStore
    private let configStore: PadConfigStore
    private let audioSink = AudioSink()
    private var listener: NWListener?
    private var sessions: [UUID: ClientSession] = [:]

    init(pairingGate: PairingGate, pairingStore: PairingStore, configStore: PadConfigStore) {
        self.pairingGate = pairingGate
        self.pairingStore = pairingStore
        self.configStore = configStore
        configStore.addObserver { [weak self] config, origin in
            self?.broadcastConfig(config, excluding: origin)
        }
    }

    /// 配置变化后推给所有已认证会话；发起方自己不用再收一遍。
    private func broadcastConfig(_ config: PadConfig, excluding origin: UUID?) {
        queue.async { [weak self] in
            guard let self else { return }
            for (id, session) in self.sessions where id != origin {
                session.pushPadConfig(config)
            }
        }
    }

    /// 供菜单栏设置窗口使用。
    func loadAppSummaries(rescan: Bool, completion: @escaping ([PadAppSummary]) -> Void) {
        appCatalog.summaries(rescan: rescan, completion: completion)
    }

    /// 供菜单栏状态面板使用；线程安全（InputInjector 内部 queue.sync）。
    func inputStatusSnapshot() -> (lastInputAt: Date?, buttons: UInt8, modifiers: UInt8) {
        injector.inputStatusSnapshot()
    }

    func start() throws {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        tcp.keepaliveInterval = 2
        tcp.keepaliveCount = 3
        let parameters = NWParameters(tls: nil, tcp: tcp)
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: Wire.port)!)
        // A distinct v2 service name also invalidates stale mDNS caches left by the legacy helper.
        listener.service = NWListener.Service(name: "VibePad Mac Secure", type: "_vibepad._tcp")
        listener.stateUpdateHandler = { state in
            if case .ready = state { print("Listening on TCP \(Wire.port) (Bonjour _vibepad._tcp.)") }
            if case .failed(let error) = state { fputs("Listener failed: \(error)\n", stderr); exit(2) }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            guard self.sessions.count < 16 else {
                print("Rejecting client because the pending-session limit was reached")
                connection.cancel()
                return
            }
            let sessionID = UUID()
            let session = ClientSession(
                connection: connection,
                injector: self.injector,
                queue: self.queue,
                appCatalog: self.appCatalog,
                usageProvider: self.usageProvider,
                pairingGate: self.pairingGate,
                pairingStore: self.pairingStore,
                audioSink: self.audioSink,
                configStore: self.configStore,
                sessionID: sessionID
            ) { [weak self] in
                self?.sessions.removeValue(forKey: sessionID)
            }
            self.sessions[sessionID] = session
            session.start(on: self.queue)
        }
        self.listener = listener
        listener.start(queue: queue)
    }
}

@MainActor
private func secureMain() {
    let trusted = AXIsProcessTrusted()
    print("VibePad Mac Helper v\(HelperInfo.version) · VibePad aggregate microphone streaming")
    if !trusted {
        print("需要辅助功能权限：系统设置 > 隐私与安全性 > 辅助功能，启用 VibePad Helper 后重启本程序。")
    }
    let pairingGate = PairingGate()
    let pairingStore = PairingStore()
    let configStore = PadConfigStore()
    let application = NSApplication.shared
    application.setActivationPolicy(.accessory)
    application.finishLaunching()
    _ = AggregateMicrophone.ensureAvailable()
    FrontmostAppTracker.shared.start()
    let server = VibePadServer(
        pairingGate: pairingGate,
        pairingStore: pairingStore,
        configStore: configStore
    )
    let settingsWindow = SettingsWindowController(store: configStore) { rescan, completion in
        server.loadAppSummaries(rescan: rescan, completion: completion)
    }
    let menuBarController = MenuBarController(
        gate: pairingGate,
        store: pairingStore,
        inputStatus: { server.inputStatusSnapshot() },
        openSettings: { MainActor.assumeIsolated { settingsWindow.show() } }
    )
    withExtendedLifetime((menuBarController, server, settingsWindow)) {
        do {
            try server.start()
            application.run()
        } catch {
            fputs("Could not start helper: \(error)\n", stderr)
            exit(1)
        }
    }
}

// Enter AppKit's main loop directly on the process main thread. Wrapping this non-returning
// call in a Swift MainActor Task prevents later MainActor tasks from being scheduled.
MainActor.assumeIsolated { secureMain() }
