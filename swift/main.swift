import AppKit
import Foundation
import JSONRPC
import LanguageServerProtocol
import UniformTypeIdentifiers

// MARK: - Wire types: see swift/generated/WireTypes.swift (generated from smithy/ui.smithy)

// MARK: - JSON-RPC bridge over LSP framing
//
// Thin wrapper around ChimeHQ/JSONRPC's JSONRPCSession. The session owns id
// allocation, the pending-response table, and request/response correlation.
// We provide a small registry of notification + request handlers and a loop
// that drains the session's event stream and dispatches by method name.

final class JSONRPCBridge {
    private let process = Process()
    private let stdinPipe = Pipe()
    private let stdoutPipe = Pipe()
    private let session: JSONRPCSession

    typealias NotificationHandler = (Data) -> Void
    typealias RequestHandler = (Data) async throws -> Data

    private var notificationHandlers: [String: NotificationHandler] = [:]
    private var requestHandlers: [String: RequestHandler] = [:]

    init(executable: String, arguments: [String]) {
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardInput = stdinPipe
        process.standardOutput = stdoutPipe
        process.standardError = FileHandle.standardError

        let channel = JSONRPCBridge.makeChannel(stdin: stdinPipe, stdout: stdoutPipe)
        self.session = JSONRPCSession(channel: channel.withMessageFraming())
    }

    func on<P: Decodable>(_ method: String, _ handler: @escaping (P) -> Void) {
        notificationHandlers[method] = { data in
            do {
                let params = try JSONDecoder().decode(P.self, from: data)
                DispatchQueue.main.async { handler(params) }
            } catch {
                FileHandle.standardError.write("Decode error for \(method): \(error)\n".data(using: .utf8)!)
            }
        }
    }

    // Methods with no params or unit params.
    func on(_ method: String, _ handler: @escaping () -> Void) {
        notificationHandlers[method] = { _ in
            DispatchQueue.main.async { handler() }
        }
    }

    // Registers a request handler. Called by codegen-emitted typed wrappers
    // (e.g. `onOpenPanel`) — see swift/generated/WireTypes.swift.
    func registerRequest(_ method: String, _ handler: @escaping RequestHandler) {
        requestHandlers[method] = handler
    }

    func start() throws {
        try process.run()
        Task { [weak self] in await self?.run() }
    }

    func terminate() {
        process.terminate()
    }

    func sendNotification<P: Encodable & Sendable>(method: String, params: P) {
        let session = self.session
        Task {
            do {
                try await session.sendNotification(params, method: method)
            } catch {
                FileHandle.standardError.write("sendNotification \(method) failed: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    func sendNotification(method: String) {
        let session = self.session
        Task {
            do {
                try await session.sendNotification(method: method)
            } catch {
                FileHandle.standardError.write("sendNotification \(method) failed: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    // Drain incoming events from the JSONRPCSession and route by method name.
    // The library hands us the whole envelope's bytes in `data`; we re-encode
    // just the typed `params: JSONValue` so the typed handler sees the inner
    // params object, not the outer JSON-RPC envelope.
    private func run() async {
        let encoder = JSONEncoder()
        for await event in await session.eventSequence {
            switch event {
            case .notification(let note, _):
                guard let handler = notificationHandlers[note.method] else {
                    FileHandle.standardError.write("No notification handler for \(note.method)\n".data(using: .utf8)!)
                    continue
                }
                let paramsData = (try? encoder.encode(note.params)) ?? Data("{}".utf8)
                handler(paramsData)
            case .request(let req, let respond, _):
                let method = req.method
                guard let handler = requestHandlers[method] else {
                    let err = AnyJSONRPCResponseError(code: -32601, message: "Method not found: \(method)")
                    await respond(.failure(err))
                    continue
                }
                let paramsData = (try? encoder.encode(req.params)) ?? Data("{}".utf8)
                do {
                    let resultData = try await handler(paramsData)
                    let shim = RawEncodable(data: resultData)
                    await respond(.success(shim))
                } catch {
                    let err = AnyJSONRPCResponseError(
                        code: -32603,
                        message: "Handler for \(method) threw: \(error)"
                    )
                    await respond(.failure(err))
                }
            case .error(let error):
                FileHandle.standardError.write("JSONRPC error: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    // Builds a DataChannel that talks to the child process's stdio pipes.
    private static func makeChannel(stdin: Pipe, stdout: Pipe) -> DataChannel {
        let writeHandle = stdin.fileHandleForWriting
        let writeQueue = DispatchQueue(label: "jsonrpc.stdin")

        let write: DataChannel.WriteHandler = { data in
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                writeQueue.async {
                    writeHandle.write(data)
                    cont.resume()
                }
            }
        }

        let (stream, continuation) = AsyncStream<Data>.makeStream()
        // NOTE: on a Pipe's reader end, an empty `availableData` does NOT mean
        // EOF — it just means the kernel had nothing buffered when the
        // readability source fired. EOF is signalled when the writer closes
        // its end of the pipe. So we only yield non-empty chunks here and
        // never finish the stream from this callback; the stream lives as
        // long as the bridge does.
        stdout.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if !data.isEmpty {
                continuation.yield(data)
            }
        }

        return DataChannel(writeHandler: write, dataSequence: stream)
    }
}

// Carries pre-encoded JSON through `JSONRPCSession`'s `Encodable & Sendable`
// result slot without a second round-trip through JSONEncoder.
private struct RawEncodable: Encodable, @unchecked Sendable {
    let data: Data

    func encode(to encoder: Encoder) throws {
        // The data is a self-contained JSON document; re-decode it into a
        // generic JSONValue and re-encode through the provided encoder so
        // the outer envelope's encoding settings (e.g., key ordering) apply
        // uniformly.
        let value = try JSONDecoder().decode(JSONRPC.JSONValue.self, from: data)
        try value.encode(to: encoder)
    }
}

// MARK: - Renderer

final class Renderer {
    private weak var container: NSView?
    private let bridge: JSONRPCBridge
    private var index: [String: NSView] = [:]

    init(container: NSView, bridge: JSONRPCBridge) {
        self.container = container
        self.bridge = bridge
    }

    func mount(_ root: Node) {
        guard let container = container else { return }
        for v in container.subviews { v.removeFromSuperview() }
        index.removeAll()
        if let view = buildView(root) {
            view.translatesAutoresizingMaskIntoConstraints = false
            container.addSubview(view)
            // Pin the root of the component tree to all four edges of the
            // container so it grows with the window. NSStackView with
            // arranged subviews + .leading alignment would not stretch
            // perpendicular-axis content, leaving the split view at its
            // intrinsic width when the window grew.
            NSLayoutConstraint.activate([
                view.topAnchor.constraint(equalTo: container.topAnchor),
                view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                view.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            ])
        }
    }

    func patch(id: String, op: String, value: String?, style: Style?) {
        guard let view = index[id] else {
            FileHandle.standardError.write("patch: unknown id \(id)\n".data(using: .utf8)!)
            return
        }
        switch op {
        case "setText":
            if let label = view as? NSTextField, !label.isEditable {
                label.stringValue = value ?? ""
            } else if let button = view as? NSButton {
                button.title = value ?? ""
            }
        case "setValue":
            if let imageView = view as? AspectFillImageView {
                imageView.assetName = value
                imageView.layer?.contents = loadImage(named: value ?? "")
            } else if let field = view as? NSTextField, field.isEditable {
                if field.stringValue != (value ?? "") {
                    field.stringValue = value ?? ""
                }
            }
        case "setChecked":
            if let button = view as? ClosureButton, button.isCheckbox {
                button.state = (value == "true") ? .on : .off
            }
        case "setStyle":
            applyStyle(style, to: view)
        default:
            FileHandle.standardError.write("patch: unknown op \(op)\n".data(using: .utf8)!)
        }
    }

    func replaceChildren(parent: String, mounted: [Node], order: [String]) {
        guard let parentView = index[parent] else {
            FileHandle.standardError.write("replaceChildren: unknown parent \(parent)\n".data(using: .utf8)!)
            return
        }
        // Build all newly-mounted subtrees; this also registers them in the
        // id->view map.
        var newByID: [String: NSView] = [:]
        for sub in mounted {
            if let v = buildView(sub), let id = sub.id {
                newByID[id] = v
            }
        }

        let host = childHost(of: parentView)
        let currentOrder = host.arrangedSubviews.compactMap { v in
            self.index.first(where: { $0.value === v })?.key
        }
        let orderSet = Set(order)
        // Remove views whose ids are no longer in `order`.
        for (i, oldID) in currentOrder.enumerated().reversed() {
            if !orderSet.contains(oldID) {
                let v = host.arrangedSubviews[i]
                host.removeArrangedSubview(v)
                v.removeFromSuperview()
                index.removeValue(forKey: oldID)
            }
        }
        // Re-add views in the requested order. Surviving views were already
        // present (so removeArrangedSubview + re-add reorders them);
        // newly-mounted views are inserted fresh.
        for (idx, id) in order.enumerated() {
            guard let v = index[id] else {
                FileHandle.standardError.write("replaceChildren: missing id \(id)\n".data(using: .utf8)!)
                continue
            }
            if host.arrangedSubviews.contains(v) {
                host.removeArrangedSubview(v)
            }
            host.insertArrangedSubview(v, at: idx)
        }
    }

    // A splitview's "children" live as the contentViews of its split items,
    // not directly in arrangedSubviews — but Landmarks uses keyed children
    // inside a stack (sidebar list / detail stack), so for now we only
    // support stack-shaped parents here.
    private func childHost(of view: NSView) -> NSStackView {
        if let stack = view as? NSStackView { return stack }
        // Scrollview's documentView is the stack we want.
        if let scroll = view as? NSScrollView, let stack = scroll.documentView as? NSStackView {
            return stack
        }
        // Visual-effect-wrapped stack (background.material wrap).
        if let effect = view as? NSVisualEffectView,
           let stack = effect.subviews.first(where: { $0 is NSStackView }) as? NSStackView
        {
            return stack
        }
        FileHandle.standardError.write("childHost: unsupported parent view kind \(type(of: view))\n".data(using: .utf8)!)
        // Fall back to an empty unattached stack so we don't crash; the
        // notification will silently no-op visually.
        return NSStackView()
    }

    private func buildView(_ node: Node) -> NSView? {
        let view: NSView?
        switch node.tag {
        case "vstack":
            let stack = NSStackView()
            stack.orientation = .vertical
            stack.alignment = .leading
            stack.spacing = 8
            for child in node.children ?? [] {
                if let v = buildView(child) { stack.addArrangedSubview(v) }
            }
            view = stack
        case "hstack":
            let stack = NSStackView()
            stack.orientation = .horizontal
            stack.alignment = .centerY
            stack.spacing = 8
            for child in node.children ?? [] {
                if let v = buildView(child) { stack.addArrangedSubview(v) }
            }
            view = stack
        case "label":
            let label = NSTextField(labelWithString: node.text ?? "")
            label.font = NSFont.systemFont(ofSize: 14)
            // Wrap by default so multi-line descriptions don't overflow.
            label.lineBreakMode = .byWordWrapping
            label.maximumNumberOfLines = 0
            label.cell?.wraps = true
            label.cell?.isScrollable = false
            // Hug horizontally lower than vertically, so wide stacks let us
            // stretch and wrap rather than expanding vertically.
            label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            view = label
        case "button":
            let button = ClosureButton(title: node.text ?? "")
            button.bezelStyle = .rounded
            if let id = node.id {
                button.onClick = { [weak self] in
                    self?.bridge.sendClick(ClickInput(id: id))
                }
            }
            view = button
        case "textfield":
            let field = NSTextField(string: node.value ?? "")
            field.isEditable = true
            field.isSelectable = true
            field.bezelStyle = .roundedBezel
            field.isBezeled = true
            field.translatesAutoresizingMaskIntoConstraints = false
            field.widthAnchor.constraint(greaterThanOrEqualToConstant: 160).isActive = true
            if let id = node.id {
                let delegate = TextFieldDelegate { [weak self] newValue in
                    self?.bridge.sendInput(InputInput(id: id, value: newValue))
                }
                field.delegate = delegate
                objc_setAssociatedObject(field, &textFieldDelegateKey, delegate, .OBJC_ASSOCIATION_RETAIN)
            }
            view = field
        case "image":
            // Plain view + CALayer backing — gives us SwiftUI-style
            // `.aspectFill` (fill the bounds, crop to maintain ratio).
            // NSImageView's built-in scaling modes either letterbox or
            // distort, so we sidestep it entirely.
            let imageView = AspectFillImageView()
            imageView.wantsLayer = true
            imageView.layer = CALayer()
            imageView.layer?.contentsGravity = .resizeAspectFill
            imageView.layer?.masksToBounds = true
            imageView.assetName = node.value
            if let img = loadImage(named: node.value ?? "") {
                imageView.layer?.contents = img
            }
            view = imageView
        case "scrollview":
            let scroll = NSScrollView()
            scroll.hasVerticalScroller = true
            scroll.drawsBackground = false
            scroll.borderType = .noBorder
            let documentStack = NSStackView()
            documentStack.orientation = .vertical
            documentStack.alignment = .leading
            documentStack.spacing = 4
            documentStack.distribution = .fill
            documentStack.translatesAutoresizingMaskIntoConstraints = false
            for child in node.children ?? [] {
                if let v = buildView(child) { documentStack.addArrangedSubview(v) }
            }
            // Flip the scroll view to use top-left origin so the documentView
            // anchors to the top of the visible area instead of the bottom.
            // Without this, when the documentView's intrinsic height is
            // shorter than the clipView, AppKit aligns it to the bottom.
            let flipped = FlippedClipView()
            flipped.drawsBackground = false
            scroll.contentView = flipped
            scroll.documentView = documentStack
            NSLayoutConstraint.activate([
                documentStack.widthAnchor.constraint(equalTo: flipped.widthAnchor),
                documentStack.topAnchor.constraint(equalTo: flipped.topAnchor),
                documentStack.leadingAnchor.constraint(equalTo: flipped.leadingAnchor),
                documentStack.trailingAnchor.constraint(equalTo: flipped.trailingAnchor),
            ])
            view = scroll
        case "splitview":
            let controller = NSSplitViewController()
            let kids = node.children ?? []
            if kids.count >= 1, let sidebar = buildView(kids[0]) {
                let item = NSSplitViewItem(sidebarWithViewController: hostController(for: sidebar))
                item.minimumThickness = 200
                item.maximumThickness = 320
                controller.addSplitViewItem(item)
            }
            if kids.count >= 2, let detail = buildView(kids[1]) {
                let item = NSSplitViewItem(viewController: hostController(for: detail))
                controller.addSplitViewItem(item)
            }
            view = controller.view
            // Retain the controller — view doesn't own it.
            objc_setAssociatedObject(controller.view, &splitControllerKey, controller, .OBJC_ASSOCIATION_RETAIN)
        case "toggle":
            let button = ClosureButton(checkboxTitle: node.text ?? "")
            if node.value == "true" { button.state = .on } else { button.state = .off }
            if let id = node.id {
                button.onToggle = { [weak self] isOn in
                    self?.bridge.sendToggle(ToggleInput(id: id, value: isOn))
                }
            }
            view = button
        case "spacer":
            let spacer = NSView()
            spacer.translatesAutoresizingMaskIntoConstraints = false
            spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
            spacer.setContentHuggingPriority(.defaultLow, for: .vertical)
            spacer.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
            spacer.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
            view = spacer
        case "zstack":
            // Layered children: every child fills the container, later
            // children sit on top of earlier ones. The base child drives the
            // container's intrinsic size; overlay children are clipped to it.
            let container = NSView()
            container.translatesAutoresizingMaskIntoConstraints = false
            let kids = node.children ?? []
            for (i, child) in kids.enumerated() {
                guard let v = buildView(child) else { continue }
                v.translatesAutoresizingMaskIntoConstraints = false
                container.addSubview(v)
                if i == 0 {
                    // Base layer pins to all edges; supplies intrinsic size.
                    NSLayoutConstraint.activate([
                        v.topAnchor.constraint(equalTo: container.topAnchor),
                        v.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                        v.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                        v.bottomAnchor.constraint(equalTo: container.bottomAnchor),
                    ])
                } else {
                    // Overlay layers are positioned inside the container; by
                    // default they bottom-leading-align so headline overlays
                    // sit on the bottom-left of a hero image (matches
                    // SwiftUI's typical use). Respect explicit padding via
                    // the child's own style.
                    NSLayoutConstraint.activate([
                        v.leadingAnchor.constraint(greaterThanOrEqualTo: container.leadingAnchor),
                        v.trailingAnchor.constraint(lessThanOrEqualTo: container.trailingAnchor),
                        v.topAnchor.constraint(greaterThanOrEqualTo: container.topAnchor),
                        v.bottomAnchor.constraint(equalTo: container.bottomAnchor),
                        v.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                    ])
                }
            }
            view = container
        case "hscrollview":
            let scroll = NSScrollView()
            scroll.hasHorizontalScroller = true
            scroll.hasVerticalScroller = false
            scroll.drawsBackground = false
            scroll.borderType = .noBorder
            scroll.horizontalScrollElasticity = .allowed
            let documentStack = NSStackView()
            documentStack.orientation = .horizontal
            documentStack.alignment = .top
            documentStack.spacing = 12
            documentStack.distribution = .fill
            documentStack.translatesAutoresizingMaskIntoConstraints = false
            for child in node.children ?? [] {
                if let v = buildView(child) { documentStack.addArrangedSubview(v) }
            }
            scroll.documentView = documentStack
            if let clip = scroll.contentView as? NSClipView {
                clip.drawsBackground = false
            }
            NSLayoutConstraint.activate([
                documentStack.heightAnchor.constraint(equalTo: scroll.contentView.heightAnchor),
                documentStack.topAnchor.constraint(equalTo: scroll.contentView.topAnchor),
                documentStack.leadingAnchor.constraint(equalTo: scroll.contentView.leadingAnchor),
                documentStack.bottomAnchor.constraint(equalTo: scroll.contentView.bottomAnchor),
            ])
            view = scroll
        case "divider":
            let box = NSBox()
            box.boxType = .separator
            view = box
        default:
            view = nil
        }
        // Register the view *before* applying style — applyStyle may wrap
        // the view in an NSVisualEffectView and the index should still
        // point at the original (so setStyle/setText etc. work on the
        // inner view).
        if let v = view, let id = node.id {
            index[id] = v
        }
        if let v = view {
            applyStyle(node.style, to: v)
        }
        // The Scala side flips `clickable=true` on any node whose
        // component registered an onClick handler. Only those nodes get
        // a click recognizer — so the recognizer sits exactly on the
        // node that wants the click, not on ancestors. Native controls
        // (button, textfield, toggle) deliver their own click events via
        // their action selectors, so skip the recognizer for them to
        // avoid double-firing.
        if let v = view, let id = node.id, node.clickable == true, !hasNativeClick(node.tag) {
            attachClickGesture(to: v, id: id)
        }
        return view
    }

    private func hasNativeClick(_ tag: String) -> Bool {
        switch tag {
        case "button", "toggle": return true
        default: return false
        }
    }

    private func attachClickGesture(to view: NSView, id: String) {
        let recognizer = ClickGestureRecognizer(target: nil, action: nil)
        recognizer.onClick = { [weak self] in
            self?.bridge.sendClick(ClickInput(id: id))
        }
        recognizer.target = recognizer
        recognizer.action = #selector(ClickGestureRecognizer.handle)
        view.addGestureRecognizer(recognizer)
    }

    private func loadImage(named: String) -> NSImage? {
        guard !named.isEmpty else { return nil }
        if let cached = Renderer.imageCache[named] { return cached }
        let env = ProcessInfo.processInfo.environment
        // Resolution order: explicit env var (dev / sbt) → assets/ inside the
        // .app bundle's Resources (packaged) → CWD-relative (bare-binary fallback).
        let assets = env["SSR_ASSETS_DIR"]
            ?? Bundle.main.resourcePath.map { $0 + "/assets" }
            ?? FileManager.default.currentDirectoryPath + "/assets"
        for ext in ["jpg", "jpeg", "png", "heic"] {
            let path = "\(assets)/\(named).\(ext)"
            if let image = NSImage(contentsOfFile: path) {
                Renderer.imageCache[named] = image
                return image
            }
        }
        return nil
    }

    // Process-wide cache so that re-mounting rows (e.g. clearing a search
    // filter) doesn't re-decode JPEGs on the main thread. NSImage instances
    // are immutable for our purposes; sharing them across views is safe.
    private static var imageCache: [String: NSImage] = [:]

    private func hostController(for view: NSView) -> NSViewController {
        let controller = NSViewController()
        controller.view = view
        return controller
    }

    func applyStyle(_ style: Style?, to view: NSView) {
        guard let style = style else { return }
        if let spacing = style.spacing, let stack = view as? NSStackView {
            stack.spacing = spacing
        }
        if let padding = style.padding, let stack = view as? NSStackView {
            stack.edgeInsets = NSEdgeInsets(
                top: padding.top,
                left: padding.leading,
                bottom: padding.bottom,
                right: padding.trailing
            )
        }
        if let alignment = style.alignment, let stack = view as? NSStackView {
            // SwiftUI's "alignment" on a stack is the perpendicular axis
            // (HStack's alignment is vertical, VStack's is horizontal). NSStackView
            // matches that semantics with its `alignment` property.
            switch stack.orientation {
            case .vertical:
                switch alignment {
                case .leading: stack.alignment = .leading
                case .center: stack.alignment = .centerX
                case .trailing: stack.alignment = .trailing
                }
            case .horizontal:
                switch alignment {
                case .leading: stack.alignment = .top
                case .center: stack.alignment = .centerY
                case .trailing: stack.alignment = .bottom
                }
            @unknown default: break
            }
        }
        if let font = style.font {
            let weight = nsWeight(font.weight)
            if let label = view as? NSTextField {
                label.font = NSFont.systemFont(ofSize: font.size, weight: weight)
            } else if let button = view as? NSButton {
                button.font = NSFont.systemFont(ofSize: font.size, weight: weight)
            }
        }
        if let fg = style.foreground, let color = nsColor(hex: fg) {
            if let label = view as? NSTextField {
                label.textColor = color
            }
        }
        if let bg = style.background {
            switch bg {
            case .color(let hex):
                if let color = nsColor(hex: hex) {
                    view.wantsLayer = true
                    view.layer?.backgroundColor = color.cgColor
                }
            case .material(let material):
                if material == .glass {
                    wrapInGlassEffect(view: view)
                } else {
                    wrapInVisualEffect(view: view, material: nsMaterial(material))
                }
            }
        }
        if let radius = style.cornerRadius {
            view.wantsLayer = true
            view.layer?.cornerRadius = radius
            view.layer?.masksToBounds = true
            // If we previously wrapped this view in a visual-effect view,
            // round its layer too so the blur respects the same corner.
            for sub in view.subviews where sub is NSVisualEffectView {
                sub.wantsLayer = true
                sub.layer?.cornerRadius = radius
                sub.layer?.masksToBounds = true
            }
        }
        if let frame = style.frame {
            view.translatesAutoresizingMaskIntoConstraints = false
            if let w = frame.width {
                view.widthAnchor.constraint(equalToConstant: w).isActive = true
            }
            if let h = frame.height {
                view.heightAnchor.constraint(equalToConstant: h).isActive = true
            }
            if let mw = frame.minWidth {
                view.widthAnchor.constraint(greaterThanOrEqualToConstant: mw).isActive = true
            }
            if let mxw = frame.maxWidth {
                view.widthAnchor.constraint(lessThanOrEqualToConstant: mxw).isActive = true
            }
            if let mh = frame.minHeight {
                view.heightAnchor.constraint(greaterThanOrEqualToConstant: mh).isActive = true
            }
            if let mxh = frame.maxHeight {
                view.heightAnchor.constraint(lessThanOrEqualToConstant: mxh).isActive = true
            }
        }
    }

    // Insert an NSVisualEffectView behind `view` in its superview. If the
    // view has no superview yet (style applied during build), wrap it in a
    // container by replacing references in the parent stack later — for
    // now, if not yet attached, just leave a marker on the view.
    private func wrapInVisualEffect(view: NSView, material: NSVisualEffectView.Material) {
        let effect = HitThroughVisualEffectView()
        effect.material = material
        effect.blendingMode = .behindWindow
        effect.state = .followsWindowActiveState
        effect.translatesAutoresizingMaskIntoConstraints = false
        // We can't insert behind because the view isn't in a hierarchy yet
        // during build. Instead: attach the effect as a subview of the view
        // itself (at the back), pinned to its bounds. The effect uses a
        // custom hitTest that returns nil so clicks pass through to the
        // sibling subviews on top of it.
        view.wantsLayer = true
        view.addSubview(effect, positioned: .below, relativeTo: nil)
        NSLayoutConstraint.activate([
            effect.topAnchor.constraint(equalTo: view.topAnchor),
            effect.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            effect.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            effect.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    // Attempt to wrap a view in NSGlassEffectView on macOS 26+ (Liquid Glass).
    // On older systems, falls back to a regular visual-effect view with the
    // closest matching material so behavior is consistent.
    private func wrapInGlassEffect(view: NSView) {
        if let glassClass = NSClassFromString("NSGlassEffectView") as? NSView.Type {
            // macOS 26+: use NSGlassEffectView for true Liquid Glass.
            let glass = glassClass.init()
            glass.translatesAutoresizingMaskIntoConstraints = false
            view.wantsLayer = true
            view.addSubview(glass, positioned: .below, relativeTo: nil)
            NSLayoutConstraint.activate([
                glass.topAnchor.constraint(equalTo: view.topAnchor),
                glass.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                glass.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                glass.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            ])
        } else {
            // Older macOS: closest approximation is the HUD material with
            // a subtle blur — gives a similar frosted, layered feel.
            wrapInVisualEffect(view: view, material: .hudWindow)
        }
    }

    private func nsWeight(_ w: FontWeight) -> NSFont.Weight {
        switch w {
        case .regular: return .regular
        case .medium: return .medium
        case .semibold: return .semibold
        case .bold: return .bold
        }
    }

    private func nsMaterial(_ m: Material) -> NSVisualEffectView.Material {
        switch m {
        case .sidebar: return .sidebar
        case .glass: return .hudWindow
        case .hud: return .hudWindow
        case .regular: return .contentBackground
        }
    }

    private func nsColor(hex: String) -> NSColor? {
        var s = hex
        if s.hasPrefix("#") { s.removeFirst() }
        guard s.count == 6 || s.count == 8, let value = UInt32(s, radix: 16) else { return nil }
        let r: CGFloat
        let g: CGFloat
        let b: CGFloat
        let a: CGFloat
        if s.count == 6 {
            r = CGFloat((value >> 16) & 0xff) / 255
            g = CGFloat((value >> 8) & 0xff) / 255
            b = CGFloat(value & 0xff) / 255
            a = 1
        } else {
            r = CGFloat((value >> 24) & 0xff) / 255
            g = CGFloat((value >> 16) & 0xff) / 255
            b = CGFloat((value >> 8) & 0xff) / 255
            a = CGFloat(value & 0xff) / 255
        }
        return NSColor(srgbRed: r, green: g, blue: b, alpha: a)
    }
}

private var splitControllerKey: UInt8 = 0

// NSClipView in AppKit uses a Y-up coordinate system, which makes the
// documentView anchor to the bottom of the visible area when shorter than
// the clip. Flipping it inverts to Y-down so documentView sits at the
// top — the standard pattern for "vertical list inside a scroll view".
final class FlippedClipView: NSClipView {
    override var isFlipped: Bool { true }
}

final class ClickGestureRecognizer: NSClickGestureRecognizer {
    var onClick: (() -> Void)?
    @objc func handle() { onClick?() }
}

// Plain NSView whose backing layer draws an image with .resizeAspectFill —
// fills the view bounds, cropping if needed. Layer.contents must be set;
// we hold on to `assetName` only for debugging / patch lookup.
final class AspectFillImageView: NSView {
    var assetName: String?

    override var wantsUpdateLayer: Bool { true }

    override func updateLayer() {
        // Layer.contents (an NSImage) handles its own scaling per
        // contentsGravity — no extra drawing work needed.
    }
}

// A visual-effect view that opts out of hit testing so it doesn't swallow
// clicks intended for sibling subviews stacked on top of it.
final class HitThroughVisualEffectView: NSVisualEffectView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}

private var textFieldDelegateKey: UInt8 = 0

final class TextFieldDelegate: NSObject, NSTextFieldDelegate {
    let onChange: (String) -> Void
    init(onChange: @escaping (String) -> Void) {
        self.onChange = onChange
    }
    func controlTextDidChange(_ obj: Notification) {
        if let field = obj.object as? NSTextField {
            onChange(field.stringValue)
        }
    }
}

final class ClosureButton: NSButton {
    var onClick: (() -> Void)?
    var onToggle: ((Bool) -> Void)?
    private(set) var isCheckbox = false

    convenience init(title: String) {
        self.init(frame: .zero)
        self.title = title
        self.target = self
        self.action = #selector(handle)
    }

    convenience init(checkboxTitle: String) {
        self.init(frame: .zero)
        self.setButtonType(.switch)
        self.isCheckbox = true
        self.title = checkboxTitle
        self.target = self
        self.action = #selector(handle)
    }

    @objc private func handle() {
        if isCheckbox {
            onToggle?(state == .on)
        } else {
            onClick?()
        }
    }
}

// MARK: - App

final class AppDelegate: NSObject, NSApplicationDelegate {
    var window: NSWindow!
    var bridge: JSONRPCBridge!
    var renderer: Renderer!

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = NSMenu()

        let env = ProcessInfo.processInfo.environment
        let executable: String
        let arguments: [String]
        // Resolution order: explicit env var (dev / sbt) → ssr-child inside the
        // .app bundle's Resources (packaged) → scala-cli on the dev checkout.
        let bundledChild = Bundle.main.resourcePath.map { $0 + "/ssr-child" }
        if let bin = env["SCALA_APP_BIN"] {
            executable = bin
            arguments = []
        } else if let child = bundledChild, FileManager.default.isExecutableFile(atPath: child) {
            executable = child
            arguments = []
        } else {
            let scalaPath = env["SCALA_APP_PATH"]
                ?? FileManager.default.currentDirectoryPath + "/../scala"
            let scalaMain = env["SCALA_APP_MAIN"] ?? "ssr.landmarks.LandmarksMain"
            executable = "/usr/bin/env"
            arguments = ["scala-cli", "run", scalaPath, "--main-class", scalaMain]
        }

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 200),
            styleMask: [.titled, .closable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.title = "SSR"
        window.titlebarAppearsTransparent = true

        let container = NSView()
        window.contentView = container

        bridge = JSONRPCBridge(executable: executable, arguments: arguments)
        renderer = Renderer(container: container, bridge: bridge)

        bridge.onMount { [weak self] params in
            self?.renderer.mount(params.root)
        }
        bridge.onPatch { [weak self] params in
            self?.renderer.patch(id: params.id, op: params.op, value: params.value, style: params.style)
        }
        bridge.onReplaceChildren { [weak self] params in
            self?.renderer.replaceChildren(parent: params.parent, mounted: params.mounted, order: params.order)
        }
        bridge.onSetWindow { [weak self] params in
            self?.applyWindow(params)
        }
        bridge.onSetMenu { [weak self] params in
            self?.installMenu(params.menus)
        }
        bridge.onQuit {
            NSApp.terminate(nil)
        }
        bridge.onOpenPanel { input in
            await MainActor.run {
                let panel = NSOpenPanel()
                panel.title = input.title ?? "Open"
                panel.allowsMultipleSelection = false
                panel.canChooseDirectories = false
                if let exts = input.allowedExtensions, !exts.isEmpty {
                    panel.allowedContentTypes = exts.compactMap { ext in
                        UTType(filenameExtension: ext)
                    }
                }
                let response = panel.runModal()
                let path = response == .OK ? panel.url?.path : nil
                return OpenPanelOutput(path: path)
            }
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(windowDidResize(_:)),
            name: NSWindow.didResizeNotification,
            object: window
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(windowDidMove(_:)),
            name: NSWindow.didMoveNotification,
            object: window
        )

        do {
            try bridge.start()
        } catch {
            FileHandle.standardError.write("Failed to start Scala subprocess: \(error)\n".data(using: .utf8)!)
            NSApp.terminate(nil)
            return
        }

        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationWillTerminate(_ notification: Notification) {
        bridge?.terminate()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    private func applyWindow(_ spec: SetWindowInput) {
        let wasVisible = window.isVisible
        let size = NSSize(width: spec.width, height: spec.height)
        let screen = resolveScreen(spec.screen) ?? window.screen ?? NSScreen.main
        let visible = screen?.visibleFrame ?? window.frame
        let originX = spec.x ?? (visible.midX - size.width / 2)
        let originY = spec.y ?? (visible.midY - size.height / 2)
        let frame = NSRect(x: originX, y: originY, width: size.width, height: size.height)
        window.setFrame(frame, display: false)

        if !wasVisible {
            window.makeKeyAndOrderFront(nil)
        } else {
            window.displayIfNeeded()
        }
        sendWindowFrame()
    }

    @objc private func windowDidResize(_ notification: Notification) {
        sendWindowFrame()
    }

    @objc private func windowDidMove(_ notification: Notification) {
        sendWindowFrame()
    }

    private func sendWindowFrame() {
        let f = window.frame
        bridge.sendFrame(WindowFrame(
            x: Double(f.origin.x),
            y: Double(f.origin.y),
            width: Double(f.size.width),
            height: Double(f.size.height)
        ))
    }

    private func installMenu(_ specs: [MenuItem]) {
        let mainMenu = NSMenu()
        for spec in specs {
            let topItem = NSMenuItem()
            topItem.title = spec.title
            let submenu = NSMenu(title: spec.title)
            for child in spec.children ?? [] {
                submenu.addItem(buildMenuItem(child))
            }
            topItem.submenu = submenu
            mainMenu.addItem(topItem)
        }
        NSApp.mainMenu = mainMenu
    }

    private func buildMenuItem(_ spec: MenuItem) -> NSMenuItem {
        if spec.separator == true {
            return NSMenuItem.separator()
        }
        let (keyEquivalent, modifiers) = parseShortcut(spec.key)
        let item = ClosureMenuItem(title: spec.title, keyEquivalent: keyEquivalent)
        item.keyEquivalentModifierMask = modifiers
        if let id = spec.id {
            item.onSelect = { [weak self] in
                self?.bridge.sendClick(ClickInput(id: id))
            }
        }
        if let kids = spec.children, !kids.isEmpty {
            let sub = NSMenu(title: spec.title)
            for c in kids { sub.addItem(buildMenuItem(c)) }
            item.submenu = sub
        }
        return item
    }

    private func parseShortcut(_ key: String?) -> (String, NSEvent.ModifierFlags) {
        guard let key = key, !key.isEmpty else { return ("", []) }
        let parts = key.lowercased().split(separator: "+").map(String.init)
        var mods: NSEvent.ModifierFlags = []
        var equiv = ""
        for p in parts {
            switch p {
            case "cmd", "command": mods.insert(.command)
            case "shift": mods.insert(.shift)
            case "opt", "alt", "option": mods.insert(.option)
            case "ctrl", "control": mods.insert(.control)
            default: equiv = p
            }
        }
        return (equiv, mods)
    }

    private func resolveScreen(_ selector: String?) -> NSScreen? {
        guard let selector = selector else { return nil }
        switch selector {
        case "main":
            let mouse = NSEvent.mouseLocation
            return NSScreen.screens.first(where: { $0.frame.contains(mouse) })
                ?? NSScreen.screens.first
        case "primary":
            return NSScreen.screens.first(where: { $0.frame.origin == .zero })
                ?? NSScreen.screens.first
        case "focused":
            return NSScreen.main
        default:
            if let idx = Int(selector), NSScreen.screens.indices.contains(idx) {
                return NSScreen.screens[idx]
            }
            return nil
        }
    }
}

final class ClosureMenuItem: NSMenuItem {
    var onSelect: (() -> Void)?

    override init(title: String, action: Selector?, keyEquivalent: String) {
        super.init(title: title, action: action, keyEquivalent: keyEquivalent)
    }

    convenience init(title: String, keyEquivalent: String) {
        self.init(title: title, action: #selector(handle), keyEquivalent: keyEquivalent)
        self.target = self
    }

    required init(coder: NSCoder) {
        super.init(coder: coder)
    }

    @objc private func handle() {
        onSelect?()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
