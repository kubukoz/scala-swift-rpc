// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "ssr-host",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(url: "https://github.com/ChimeHQ/JSONRPC", from: "0.9.0"),
        .package(url: "https://github.com/ChimeHQ/LanguageServerProtocol", from: "0.14.0"),
    ],
    targets: [
        .executableTarget(
            name: "ssr-host",
            dependencies: [
                "JSONRPC",
                .product(name: "LanguageServerProtocol", package: "LanguageServerProtocol"),
            ],
            path: ".",
            exclude: ["Package.swift", "Package.resolved", ".build"],
            sources: ["main.swift", "generated/WireTypes.swift"]
        )
    ]
)
