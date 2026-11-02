// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "DebunkaxKit",
    platforms: [.iOS(.v26), .macOS(.v26)],
    products: [
        .library(name: "DebunkaxCore", targets: ["DebunkaxCore"]),
        .library(name: "DebunkaxData", targets: ["DebunkaxData"]),
    ],
    targets: [
        .target(name: "DebunkaxCore"),
        .target(name: "DebunkaxData", dependencies: ["DebunkaxCore"]),
        .testTarget(name: "DebunkaxCoreTests", dependencies: ["DebunkaxCore"]),
        .testTarget(name: "DebunkaxDataTests", dependencies: ["DebunkaxData", "DebunkaxCore"]),
    ]
)
