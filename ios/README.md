# SSR on iOS

A minimal iOS host that embeds a Scala SSR app. The Scala Native app is
cross-compiled to a static library and linked into a UIKit host over the same
shared-memory ring-buffer **FFI transport** the macOS host uses — no subprocess
(iOS forbids spawning children), one signed binary. This is the App Store / iOS
path the FFI work was built toward.

The demo (`scala/demos-native/CounterFfi.scala`) is a counter plus a text field
that mirrors into a label, using only the core tags (label/button/textfield/
stacks) that the minimal `UIKitRenderer` supports.

## Layout

```
project.yml           XcodeGen spec (source of truth; the .xcodeproj is generated)
SSRiOS/Info.plist     app plist (managed by XcodeGen from project.yml)
SSRiOS/Sources/
  Bridge.swift        JSONRPCBridge + Ring + FfiTransport (FFI-only; the
                      platform-agnostic half of ../swift/main.swift)
  UIKitRenderer.swift minimal renderer: vstack/hstack/label/button/textfield
  AppDelegate.swift   UIWindow + RootViewController; boots the embedded app
  PthreadShim.c       weak no-op pthread_condattr_setclock (see gotchas)
```

The Swift wire types are reused from `../swift/generated/WireTypes.swift`
(generated from `smithy/ui.smithy`, shared with the macOS host).

## Build & run

Prerequisites: Xcode, an iOS Simulator runtime (`xcodebuild -downloadPlatform
iOS` if missing), `sbt`, and XcodeGen (`nix run nixpkgs#xcodegen`).

Generate the Xcode project (once, and after editing `project.yml`):

```bash
cd ios && nix run nixpkgs#xcodegen
```

### Simulator

```bash
# 1. Build the Scala Native static lib for the simulator (arm64).
sbt -Dssr.ios=true demosNative3/Compile/nativeLink

# 2. Build the app.
xcodebuild -project ios/SSRiOS.xcodeproj -scheme SSRiOS \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -derivedDataPath ios/build/dd build

# 3. Install + launch.
xcrun simctl boot 'iPhone 17 Pro'; open -a Simulator
APP=ios/build/dd/Build/Products/Debug-iphonesimulator/SSRiOS.app
xcrun simctl install 'iPhone 17 Pro' "$APP"
xcrun simctl launch 'iPhone 17 Pro' com.kubukoz.ssr.SSRiOS
```

### Physical device

```bash
# 1. Build the lib for the device triple (arm64-apple-ios). Wipe native-3 first
#    if you previously built for the simulator (the .a archive is not fully
#    rebuilt on a target switch — see gotchas).
rm -rf scala/demos/target/native-3
sbt -Dssr.ios.device=true demosNative3/Compile/nativeLink

# 2. Build (signing: DEVELOPMENT_TEAM is set in project.yml; override with
#    SSR_DEV_TEAM at xcodegen time or DEVELOPMENT_TEAM= at build time).
xcodebuild -project ios/SSRiOS.xcodeproj -scheme SSRiOS \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath ios/build/dd-device -allowProvisioningUpdates build

# 3. Install + launch (find the device UDID via `xcrun devicectl list devices`).
APP=ios/build/dd-device/Build/Products/Debug-iphoneos/SSRiOS.app
xcrun devicectl device install app --device <UDID> "$APP"
xcrun devicectl device process launch --device <UDID> com.kubukoz.ssr.SSRiOS
```

First-time device setup: the provisioning profile may need to be minted by
running once from the Xcode GUI (open `SSRiOS.xcodeproj`, pick your team + the
device, Run), and the developer app must be trusted on the device (Settings →
General → VPN & Device Management → Developer App → Trust). After that, the CLI
flow above is headless.

## Gotchas (all handled)

- **Stale `ar` archive.** `nativeLink` appends to `libssr-demos.a` and doesn't
  drop obsolete objects, so switching target (macOS ↔ simulator ↔ device) can
  leave objects for the wrong platform in the archive (`ld: building for X, but
  linking in object file built for Y`). Fix: `rm -rf scala/demos/target/native-3`
  before a cross-target relink.
- **`pthread_condattr_setclock`.** Scala Native's `PosixThread` calls it; iOS
  doesn't export it (macOS does, undocumented — which is why the desktop build
  links). `PthreadShim.c` provides a weak no-op. The proper fix belongs upstream
  in Scala Native.
- **`user.home` is null on iOS.** Handled in the library (`scala/lib/main.scala`
  falls back user.home → $HOME → tmpdir).
- **GC heap.** Immix pre-reserves a virtual heap sized from system RAM (~GBs) —
  more than an iPhone allows, so the host caps it via
  `GC_MAXIMUM_HEAP_SIZE=256m` before the runtime boots.
