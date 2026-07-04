// UIKit entry point for the embedded SSR host.
//
// The iOS analogue of the macOS `AppDelegate` in swift/main.swift, minus
// everything AppKit/menu/status-item/window-geometry specific. It boots the
// embedded Scala app (via JSONRPCBridge -> ssr_init), wires the mount/patch/
// event handlers to a UIKit `Renderer`, and shows one full-screen view
// controller whose view is the SSR component tree.

import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        // Cap the Scala Native (Immix) GC heap BEFORE the runtime boots. Immix
        // pre-reserves a virtual heap sized from system RAM — on an iPhone that
        // works out to multiple GB, which exceeds the per-app memory limit and
        // the reservation fails at startup ("Failed to allocate heap space,
        // requested size=~5.6GB"), killing the app. iOS gives apps far less, so
        // cap it small; this UI app needs only a few tens of MB. Must be set
        // before ScalaNativeInit()/ssr_init() run (i.e. before the bridge is
        // created in RootViewController.viewDidLoad).
        setenv("GC_MAXIMUM_HEAP_SIZE", "256m", 1)

        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = RootViewController()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}

final class RootViewController: UIViewController {
    private var bridge: JSONRPCBridge!
    private var renderer: Renderer!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        // Boot the embedded Scala app and wire the protocol handlers. Only the
        // notifications the minimal renderer understands are handled; the rest
        // (window/menu/status-item/etc.) are accepted-and-ignored so the Scala
        // side's boot sequence (which sends setWindow/setMenu before mount)
        // doesn't error on an unknown method.
        bridge = JSONRPCBridge()
        renderer = Renderer(container: view, bridge: bridge)

        bridge.onMount { [weak self] params in
            self?.renderer.mount(params.root)
        }
        bridge.onPatch { [weak self] params in
            self?.renderer.patch(id: params.id, op: params.op, value: params.value)
        }
        // Accept-and-ignore the desktop-only notifications the Scala boot still
        // emits, so we don't spam "no handler" on stderr. (window/menu/status
        // item/activation policy have no meaning on iOS; replaceChildren isn't
        // used by the counter demo.)
        bridge.onSetWindow { _ in }
        bridge.onSetMenu { _ in }
        bridge.onSetStatusItem { _ in }
        bridge.onSetActivationPolicy { _ in }
        bridge.onReplaceChildren { _ in }

        bridge.start()
    }
}
