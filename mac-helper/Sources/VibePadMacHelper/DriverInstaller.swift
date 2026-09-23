import Foundation

/// Installs/removes the VibePadAudio HAL plug-in. The .driver bundle and the install scripts
/// ship inside the app (Contents/Resources/Driver). Privileged execution goes through
/// AppleScript's "with administrator privileges": the user types their password into the
/// system dialog and the helper never sees it.
enum DriverInstaller {
    static let installedPath = "/Library/Audio/Plug-Ins/HAL/VibePadAudio.driver"

    static var isInstalled: Bool {
        FileManager.default.fileExists(atPath: installedPath)
    }

    static var bundledDriverAvailable: Bool {
        resource("VibePadAudio", "driver") != nil
    }

    static func install(completion: @escaping (Bool, String) -> Void) {
        guard let script = resource("install-driver", "sh"),
              let driver = resource("VibePadAudio", "driver") else {
            completion(false, "安装包内缺少驱动文件，请重新下载 VibePad Helper。")
            return
        }
        runAsRoot(script: script, arguments: [driver.path], completion: completion)
    }

    static func uninstall(completion: @escaping (Bool, String) -> Void) {
        guard let script = resource("uninstall-driver", "sh") else {
            completion(false, "安装包内缺少卸载脚本，请重新下载 VibePad Helper。")
            return
        }
        AggregateMicrophone.destroy()
        runAsRoot(script: script, arguments: [], completion: completion)
    }

    private static func resource(_ name: String, _ ext: String) -> URL? {
        Bundle.main.url(forResource: name, withExtension: ext, subdirectory: "Driver")
    }

    private static func runAsRoot(
        script: URL,
        arguments: [String],
        completion: @escaping (Bool, String) -> Void
    ) {
        let command = ([script.path] + arguments).map(shellQuoted).joined(separator: " ")
        let source = "do shell script \"/bin/bash \(command)\" with administrator privileges"
        DispatchQueue.global(qos: .userInitiated).async {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", source]
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            do {
                try process.run()
            } catch {
                completion(false, "无法请求管理员授权：\(error.localizedDescription)")
                return
            }
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            let output = String(data: data, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if process.terminationStatus == 0 {
                completion(true, output)
            } else if output.contains("-128") || output.contains("User canceled") {
                completion(false, "已取消。")
            } else {
                completion(false, output.isEmpty ? "失败（退出码 \(process.terminationStatus)）" : output)
            }
        }
    }

    private static func shellQuoted(_ path: String) -> String {
        "'" + path.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
