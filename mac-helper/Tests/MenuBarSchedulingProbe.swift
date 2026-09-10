import AppKit
import Foundation

@main
struct MenuBarSchedulingProbe {
    @MainActor
    static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        app.finishLaunching()
        let gate = PairingGate()
        let store = PairingStore()
        let controller = MenuBarController(gate: gate, store: store)

        DispatchQueue.global().asyncAfter(deadline: .now() + 0.05) {
            MenuBarController.approvePairing(
                deviceName: "Protocol Probe",
                code: "482731"
            ) { _ in }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.30) {
            MainActor.assumeIsolated {
                let title = controller.displayedStatusTitle
                print("MENU_TITLE=\(title)")
                fflush(stdout)
                exit(title.contains("482") && title.contains("731") ? 0 : 1)
            }
        }
        withExtendedLifetime(controller) { app.run() }
    }
}
