// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorStreamHttpV2",
    platforms: [.iOS(.v13)],
    products: [
        .library(
            name: "CapacitorStreamHttpV2",
            targets: ["StreamHttpPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", branch: "main")
    ],
    targets: [
        .target(
            name: "StreamHttpPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/StreamHttpPlugin"),
        .testTarget(
            name: "StreamHttpPluginTests",
            dependencies: ["StreamHttpPlugin"],
            path: "ios/Tests/StreamHttpPluginTests")
    ]
)