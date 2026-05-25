import AppKit
import Foundation

// MARK: - Wire types: see swift/generated/WireTypes.swift (generated from smithy/ui.smithy)

// MARK: - JSON-RPC bridge over LSP framing

final class JSONRPCBridge {
    private let process = Process()
    private let stdinPipe = Pipe()
    private let stdoutPipe = Pipe()
    private var buffer = Data()
    private let stdinQueue = DispatchQueue(label: "jsonrpc.stdin")

    typealias NotificationHandler = (Data) -> Void
    private var handlers: [String: NotificationHandler] = [:]

    init(executable: String, arguments: [String]) {
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardInput = stdinPipe
        process.standardOutput = stdoutPipe
        process.standardError = FileHandle.standardError
    }

    func on<P: Decodable>(_ method: String, _ handler: @escaping (P) -> Void) {
        handlers[method] = { data in
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
        handlers[method] = { _ in
            DispatchQueue.main.async { handler() }
        }
    }

    func start() throws {
        stdoutPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let self = self else { return }
            let data = handle.availableData
            guard !data.isEmpty else { return }
            self.consume(data)
        }
        try process.run()
    }

    func terminate() {
        process.terminate()
    }

    func sendNotification<P: Encodable>(method: String, params: P) {
        let envelope = NotificationEnvelope(jsonrpc: "2.0", method: method, params: params)
        guard let body = try? JSONEncoder().encode(envelope) else { return }
        let header = "Content-Length: \(body.count)\r\n\r\n".data(using: .ascii)!
        stdinQueue.async { [weak self] in
            self?.stdinPipe.fileHandleForWriting.write(header)
            self?.stdinPipe.fileHandleForWriting.write(body)
        }
    }

    func sendNotification(method: String) {
        struct Empty: Encodable {}
        sendNotification(method: method, params: Empty())
    }

    // MARK: - Decoding

    private struct NotificationEnvelope<P: Encodable>: Encodable {
        let jsonrpc: String
        let method: String
        let params: P
    }

    private func consume(_ data: Data) {
        buffer.append(data)
        while let frame = takeFrame() {
            dispatch(frame)
        }
    }

    private func takeFrame() -> Data? {
        guard let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let headerData = buffer.subdata(in: 0..<headerEnd.lowerBound)
        guard let headerString = String(data: headerData, encoding: .ascii) else {
            buffer.removeSubrange(0..<headerEnd.upperBound)
            return nil
        }
        var contentLength: Int = -1
        for line in headerString.split(separator: "\r\n", omittingEmptySubsequences: true) {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            if parts.count == 2 && parts[0].lowercased() == "content-length" {
                contentLength = Int(parts[1]) ?? -1
            }
        }
        guard contentLength >= 0 else {
            buffer.removeSubrange(0..<headerEnd.upperBound)
            return nil
        }
        let bodyStart = headerEnd.upperBound
        let bodyEnd = bodyStart + contentLength
        guard buffer.count >= bodyEnd else { return nil }
        let body = buffer.subdata(in: bodyStart..<bodyEnd)
        buffer.removeSubrange(0..<bodyEnd)
        return body
    }

    private func dispatch(_ frame: Data) {
        guard let envelope = try? JSONDecoder().decode(GenericEnvelope.self, from: frame),
              let method = envelope.method else {
            FileHandle.standardError.write("Frame without method: \(String(data: frame, encoding: .utf8) ?? "?")\n".data(using: .utf8)!)
            return
        }
        guard let handler = handlers[method] else {
            FileHandle.standardError.write("No handler for method: \(method)\n".data(using: .utf8)!)
            return
        }
        handler(envelope.params ?? Data("{}".utf8))
    }
}

// Decoder helper: capture `params` as raw JSON so the typed handler can decode it.
private struct GenericEnvelope: Decodable {
    let method: String?
    let params: Data?

    enum CodingKeys: String, CodingKey { case method, params }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        method = try c.decodeIfPresent(String.self, forKey: .method)
        if c.contains(.params) {
            // Re-encode the JSON value at `params` back to bytes.
            let nested = try c.decode(JSONValue.self, forKey: .params)
            params = try JSONEncoder().encode(nested)
        } else {
            params = nil
        }
    }
}

// A tiny dynamic-JSON representation so we can round-trip arbitrary `params`.
private enum JSONValue: Codable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let b = try? c.decode(Bool.self) { self = .bool(b); return }
        if let d = try? c.decode(Double.self) { self = .number(d); return }
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
        if let o = try? c.decode([String: JSONValue].self) { self = .object(o); return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unknown JSON")
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let d): try c.encode(d)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }
}

// MARK: - Renderer

final class Renderer {
    private weak var container: NSStackView?
    private let bridge: JSONRPCBridge
    private var index: [String: NSView] = [:]

    init(container: NSStackView, bridge: JSONRPCBridge) {
        self.container = container
        self.bridge = bridge
    }

    func mount(_ root: Node) {
        guard let container = container else { return }
        for v in container.arrangedSubviews {
            container.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        index.removeAll()
        if let view = buildView(root) {
            container.addArrangedSubview(view)
        }
    }

    func patch(id: String, op: String, value: String?) {
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
            if let field = view as? NSTextField, field.isEditable {
                if field.stringValue != (value ?? "") {
                    field.stringValue = value ?? ""
                }
            }
        default:
            FileHandle.standardError.write("patch: unknown op \(op)\n".data(using: .utf8)!)
        }
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
            view = label
        case "button":
            let button = ClosureButton(title: node.text ?? "")
            button.bezelStyle = .rounded
            if let id = node.id {
                button.onClick = { [weak self] in
                    self?.bridge.sendNotification(method: Methods.click, params: ClickInput(id: id))
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
                    self?.bridge.sendNotification(
                        method: Methods.input,
                        params: InputInput(id: id, value: newValue)
                    )
                }
                field.delegate = delegate
                objc_setAssociatedObject(field, &textFieldDelegateKey, delegate, .OBJC_ASSOCIATION_RETAIN)
            }
            view = field
        default:
            view = nil
        }
        if let v = view, let id = node.id {
            index[id] = v
        }
        return view
    }
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

    convenience init(title: String) {
        self.init(frame: .zero)
        self.title = title
        self.target = self
        self.action = #selector(handle)
    }

    @objc private func handle() {
        onClick?()
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
        if let bin = env["SCALA_APP_BIN"] {
            executable = bin
            arguments = []
        } else {
            let scalaPath = env["SCALA_APP_PATH"]
                ?? FileManager.default.currentDirectoryPath + "/../scala"
            executable = "/usr/bin/env"
            arguments = ["scala-cli", "run", scalaPath]
        }

        window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 400, height: 200),
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "SSR"

        let container = NSStackView()
        container.orientation = .vertical
        container.alignment = .leading
        container.edgeInsets = NSEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
        container.translatesAutoresizingMaskIntoConstraints = false

        let content = NSView()
        content.addSubview(container)
        NSLayoutConstraint.activate([
            container.topAnchor.constraint(equalTo: content.topAnchor),
            container.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            container.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        window.contentView = content

        bridge = JSONRPCBridge(executable: executable, arguments: arguments)
        renderer = Renderer(container: container, bridge: bridge)

        bridge.on(Methods.mount) { [weak self] (params: MountInput) in
            self?.renderer.mount(params.root)
        }
        bridge.on(Methods.patch) { [weak self] (params: PatchInput) in
            self?.renderer.patch(id: params.id, op: params.op, value: params.value)
        }
        bridge.on(Methods.window) { [weak self] (params: SetWindowInput) in
            self?.applyWindow(params)
        }
        bridge.on(Methods.menu) { [weak self] (params: SetMenuInput) in
            self?.installMenu(params.menus)
        }
        bridge.on(Methods.quit) {
            NSApp.terminate(nil)
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
        bridge.sendNotification(
            method: Methods.frame,
            params: WindowFrame(
                x: Double(f.origin.x),
                y: Double(f.origin.y),
                width: Double(f.size.width),
                height: Double(f.size.height)
            )
        )
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
                self?.bridge.sendNotification(method: Methods.click, params: ClickInput(id: id))
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
