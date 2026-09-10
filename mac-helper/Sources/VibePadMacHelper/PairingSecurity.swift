import AppKit
import CryptoKit
import Foundation
import Security

enum PairingCrypto {
    static let protocolLabel = Data("VibePad pairing v2".utf8)

    static func randomBytes(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else {
            fatalError("Secure random generation failed")
        }
        return Data(bytes)
    }

    static func derivePairingSecret(
        sharedSecret: SharedSecret,
        clientID: Data,
        clientPublicKey: Data,
        serverPublicKey: Data
    ) -> Data {
        let transcript = clientID + clientPublicKey + serverPublicKey
        let salt = Data(SHA256.hash(data: transcript))
        let key = sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: salt,
            sharedInfo: protocolLabel,
            outputByteCount: 32
        )
        return key.withUnsafeBytes { Data($0) }
    }

    static func verificationCode(secret: Data, transcript: Data) -> String {
        let mac = hmac(secret: secret, data: Data("VibePad SAS".utf8) + transcript)
        let number = mac.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return String(format: "%06u", number)
    }

    static func hmac(secret: Data, data: Data) -> Data {
        let code = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: secret))
        return Data(code)
    }

    static func constantTimeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for index in lhs.indices { difference |= lhs[index] ^ rhs[index] }
        return difference == 0
    }
}

final class PairingStore {
    private let service = "com.xiaoxi.vibepad.pairing.v2"

    func secret(for clientID: Data) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: clientID.hexString,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    @discardableResult
    func save(secret: Data, clientID: Data, deviceName: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: clientID.hexString,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: secret,
            kSecAttrLabel as String: "VibePad · \(deviceName)",
            kSecAttrDescription as String: deviceName,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let updateStatus = SecItemUpdate(base as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return true }
        var add = base
        attributes.forEach { add[$0.key] = $0.value }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    func pairedDeviceCount() -> Int {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return 0 }
        return (result as? [[String: Any]])?.count ?? (result == nil ? 0 : 1)
    }

    func removeAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

final class PairingGate {
    private let lock = NSLock()
    private var expiresAt = Date.distantPast

    func open(for seconds: TimeInterval = 60) {
        lock.lock()
        expiresAt = Date().addingTimeInterval(seconds)
        lock.unlock()
    }

    func close() {
        lock.lock()
        expiresAt = .distantPast
        lock.unlock()
    }

    var secondsRemaining: Int {
        lock.lock()
        let value = max(0, Int(ceil(expiresAt.timeIntervalSinceNow)))
        lock.unlock()
        return value
    }

    var isOpen: Bool { secondsRemaining > 0 }
}

@MainActor
final class MenuBarController: NSObject {
    private static weak var shared: MenuBarController?
    private let gate: PairingGate
    private let store: PairingStore
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private let pairingItem = NSMenuItem(title: "允许配对新平板（60 秒）", action: #selector(openPairing), keyEquivalent: "")
    private let countItem = NSMenuItem(title: "已配对设备：0", action: nil, keyEquivalent: "")
    private let pendingDeviceItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let allowItem = NSMenuItem(title: "✓ 验证码一致，允许", action: #selector(allowPendingPairing), keyEquivalent: "")
    private let rejectItem = NSMenuItem(title: "拒绝本次配对", action: #selector(rejectPendingPairing), keyEquivalent: "")
    private let clearItem = NSMenuItem(title: "清除所有配对", action: #selector(clearPairings), keyEquivalent: "")
    private let quitItem = NSMenuItem(title: "退出 VibePad Helper", action: #selector(quitHelper), keyEquivalent: "q")
    private var pendingCode: String?
    private var pendingCompletion: ((Bool) -> Void)?
    private var pendingExpiresAt = Date.distantPast
    private var clearConfirmationUntil = Date.distantPast
    private var timer: Timer?

    init(gate: PairingGate, store: PairingStore) {
        self.gate = gate
        self.store = store
        super.init()
        Self.shared = self
        item.button?.title = "WP"
        item.button?.toolTip = "VibePad Helper"
        pairingItem.target = self
        allowItem.target = self
        rejectItem.target = self
        clearItem.target = self
        quitItem.target = self
        menu.addItem(countItem)
        menu.addItem(.separator())
        menu.addItem(pairingItem)
        menu.addItem(pendingDeviceItem)
        menu.addItem(allowItem)
        menu.addItem(rejectItem)
        menu.addItem(.separator())
        menu.addItem(clearItem)
        menu.addItem(quitItem)
        item.menu = menu
        refreshMenu()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshMenu() }
        }
    }

    @objc private func openPairing() {
        gate.open()
        refreshMenu()
        // Do not put a modal information alert in front of the actual verification alert.
        // The changing menu title is the acknowledgement; the next window must be the SAS.
    }

    @objc private func clearPairings() {
        if clearConfirmationUntil > Date() {
            resolvePending(approved: false)
            store.removeAll()
            gate.close()
            clearConfirmationUntil = .distantPast
        } else {
            clearConfirmationUntil = Date().addingTimeInterval(5)
        }
        refreshMenu()
    }

    @objc private func allowPendingPairing() {
        resolvePending(approved: true)
    }

    @objc private func rejectPendingPairing() {
        resolvePending(approved: false)
    }

    @objc private func quitHelper() {
        gate.close()
        resolvePending(approved: false)
        let launchctl = Process()
        launchctl.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        launchctl.arguments = ["bootout", "gui/\(getuid())/com.xiaoxi.vibepad.mac-helper"]
        try? launchctl.run()
        // When launched outside launchd, bootout has nothing to stop; terminate normally.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            NSApp.terminate(nil)
        }
    }

    private func showPendingPairing(deviceName: String, code: String, completion: @escaping (Bool) -> Void) {
        guard pendingCompletion == nil else {
            completion(false)
            return
        }
        gate.close()
        pendingCode = code
        pendingDeviceItem.title = "设备：\(deviceName)"
        pendingCompletion = completion
        pendingExpiresAt = Date().addingTimeInterval(60)
        refreshMenu()
    }

    private func resolvePending(approved: Bool) {
        let completion = pendingCompletion
        pendingCompletion = nil
        pendingCode = nil
        pendingExpiresAt = .distantPast
        refreshMenu()
        completion?(approved)
    }

    private func refreshMenu() {
        if pendingCompletion != nil, pendingExpiresAt <= Date() {
            resolvePending(approved: false)
            return
        }
        countItem.title = "已配对设备：\(store.pairedDeviceCount())"
        let remaining = gate.secondsRemaining
        if let pendingCode {
            item.button?.title = "WP  \(pendingCode.chunkedCode)"
            item.button?.toolTip = "VibePad 验证码：\(pendingCode)"
            pairingItem.isHidden = true
            pendingDeviceItem.isHidden = false
            allowItem.isHidden = false
            rejectItem.isHidden = false
        } else {
            item.button?.title = remaining > 0 ? "WP •" : "WP"
            item.button?.toolTip = "VibePad Helper"
            pairingItem.isHidden = false
            pendingDeviceItem.isHidden = true
            allowItem.isHidden = true
            rejectItem.isHidden = true
            pairingItem.title = remaining > 0 ? "等待平板请求（\(remaining) 秒）" : "允许配对新平板（60 秒）"
        }
        clearItem.title = clearConfirmationUntil > Date()
            ? "再次点击确认清除所有配对"
            : "清除所有配对"
    }

    nonisolated static func approvePairing(deviceName: String, code: String, completion: @escaping (Bool) -> Void) {
        // AppKit's application.run() owns the main run loop. Dispatch directly to that run
        // loop instead of enqueueing another Swift MainActor Task behind the non-returning run().
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
            guard let controller = Self.shared else {
                completion(false)
                return
            }
            controller.showPendingPairing(deviceName: deviceName, code: code, completion: completion)
            }
        }
    }

    var displayedStatusTitle: String { item.button?.title ?? "" }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

private extension String {
    var chunkedCode: String {
        guard count == 6 else { return self }
        let middle = index(startIndex, offsetBy: 3)
        return "\(self[..<middle])  \(self[middle...])"
    }
}
