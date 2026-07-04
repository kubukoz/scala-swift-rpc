// swift-tools-version: 5.9
import PackageDescription
import Foundation

// Embedded (FFI) build: when SSR_FFI_LIB points at the Scala Native static
// library (libssr-demos.a exporting `ssr_init` + `ScalaNativeInit`), link it in
// so the host embeds the Scala app instead of spawning it. Unset → the classic
// subprocess host, with no dependency on the Scala library. The `ssr_init` /
// `ScalaNativeInit` symbols referenced by main.swift are only reached under
// SSR_FFI=1, but the linker still needs them resolved — so we only reference
// them (and set -DSSR_FFI) when the lib is present, building the FFI host as a
// distinct configuration.
let ffiLib = ProcessInfo.processInfo.environment["SSR_FFI_LIB"]

var linkerSettings: [LinkerSetting] = []
if let lib = ffiLib {
    // Link the static archive by absolute path. `-force_load` pulls in the
    // exported symbols (ssr_init, ScalaNativeInit) even though nothing in the
    // archive's object files is referenced until runtime.
    linkerSettings = [
        .unsafeFlags(["-Xlinker", "-force_load", "-Xlinker", lib]),
    ]
}

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
            sources: ["main.swift", "generated/WireTypes.swift"],
            swiftSettings: ffiLib != nil ? [.define("SSR_FFI")] : [],
            linkerSettings: linkerSettings
        )
    ]
)
