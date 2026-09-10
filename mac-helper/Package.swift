// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "VibePadMacHelper",
    platforms: [.macOS(.v12)],
    products: [
        .executable(name: "vibepad-mac-helper", targets: ["VibePadMacHelper"])
    ],
    targets: [
        .target(
            name: "TouchBarBridge",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreImage"),
                .linkedFramework("IOSurface")
            ]
        ),
        .executableTarget(
            name: "VibePadMacHelper",
            dependencies: ["TouchBarBridge"],
            linkerSettings: [
                .linkedFramework("AVFoundation"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreAudio")
            ]
        )
    ]
)
