import Foundation

/// 线程安全的连接状态中心：网络串行队列写入，菜单栏主线程读取。
final class StatusCenter {
    static let shared = StatusCenter()

    struct ConnectionInfo {
        let deviceName: String?
        let endpoint: String
        let connectedAt: Date
    }

    struct Snapshot {
        let connections: [ConnectionInfo]

        var connected: Bool { !connections.isEmpty }
        var summary: String {
            guard let first = connections.first else { return "未连接" }
            let name = first.deviceName ?? first.endpoint
            if connections.count > 1 {
                return "\(name) 等 \(connections.count) 台"
            }
            return name
        }
    }

    private let lock = NSLock()
    private var connections: [UUID: ConnectionInfo] = [:]

    func sessionAuthenticated(id: UUID, deviceName: String?, endpoint: String) {
        lock.lock()
        connections[id] = ConnectionInfo(deviceName: deviceName, endpoint: endpoint, connectedAt: Date())
        lock.unlock()
    }

    func sessionEnded(id: UUID) {
        lock.lock()
        connections.removeValue(forKey: id)
        lock.unlock()
    }

    var snapshot: Snapshot {
        lock.lock()
        let list = connections.values.sorted { $0.connectedAt < $1.connectedAt }
        lock.unlock()
        return Snapshot(connections: list)
    }
}
