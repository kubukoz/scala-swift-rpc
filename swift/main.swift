import AppKit
import Foundation

// MARK: - Protocol types

struct Node: Decodable {
    let tag: String
    let text: String?
    let value: String?
    let id: String?
    let children: [Node]?
}

struct MenuItemSpec: Decodable {
    let title: String
    let id: String?
    let key: String?
    let separator: Bool?
    let children: [MenuItemSpec]?
}

struct StatusBarSpec: Decodable {
    let title: String
    let items: [MenuItemSpec]
}

struct WindowSpec: Decodable {
    let width: Double
    let height: Double
    let x: Double?
    let y: Double?
    let screen: String?
}

struct Command: Decodable {
    let cmd: String
    let root: Node?
    let menus: [MenuItemSpec]?
    let statusBar: StatusBarSpec?
    let id: String?
    let op: String?
    let value: String?
    let window: WindowSpec?
}

struct Event: Encodable {
    let event: String
    let id: String
    let value: String?

    init(event: String, id: String, value: String? = nil) {
        self.event = event
        self.id = id
        self.value = value
    }
}

// MARK: - Subprocess bridge

final class ScalaBridge {
    private let process = Process()
    private let stdinPipe = Pipe()
    private let stdoutPipe = Pipe()
    private var buffer = Data()

    var onMount: ((Node) -> Void)?
    var onPatch: ((String, String, String?) -> Void)?
    var onMenu: (([MenuItemSpec]) -> Void)?
    var onStatusBar: ((StatusBarSpec) -> Void)?
    var onWindow: ((WindowSpec) -> Void)?
    var onQuit: (() -> Void)?

    init(executable: String, arguments: [String]) {
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardInput = stdinPipe
        process.standardOutput = stdoutPipe
        process.standardError = FileHandle.standardError
    }

    func start() throws {
        stdoutPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            guard let self = self else { return }
            let data = handle.availableData
            guard !data.isEmpty else { return }
            self.handleIncoming(data)
        }
        try process.run()
    }

    private func handleIncoming(_ data: Data) {
        buffer.append(data)
        while let newlineIdx = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer.subdata(in: 0..<newlineIdx)
            buffer.removeSubrange(0...newlineIdx)
            guard !lineData.isEmpty else { continue }
            do {
                let cmd = try JSONDecoder().decode(Command.self, from: lineData)
                switch cmd.cmd {
                case "mount":
                    if let root = cmd.root {
                        DispatchQueue.main.async { [weak self] in
                            self?.onMount?(root)
                        }
                    }
                case "patch":
                    if let id = cmd.id, let op = cmd.op {
                        let value = cmd.value
                        DispatchQueue.main.async { [weak self] in
                            self?.onPatch?(id, op, value)
                        }
                    }
                case "menu":
                    if let menus = cmd.menus {
                        DispatchQueue.main.async { [weak self] in
                            self?.onMenu?(menus)
                        }
                    }
                case "statusbar":
                    if let sb = cmd.statusBar {
                        DispatchQueue.main.async { [weak self] in
                            self?.onStatusBar?(sb)
                        }
                    }
                case "window":
                    if let w = cmd.window {
                        DispatchQueue.main.async { [weak self] in
                            self?.onWindow?(w)
                        }
                    }
                case "quit":
                    DispatchQueue.main.async { [weak self] in
                        self?.onQuit?()
                    }
                default:
                    FileHandle.standardError.write("Unknown cmd: \(cmd.cmd)\n".data(using: .utf8)!)
                }
            } catch {
                FileHandle.standardError.write("Decode error: \(error)\n".data(using: .utf8)!)
            }
        }
    }

    func send(event: Event) {
        guard let data = try? JSONEncoder().encode(event) else { return }
        var payload = data
        payload.append(0x0A)
        stdinPipe.fileHandleForWriting.write(payload)
    }

    func terminate() {
        process.terminate()
    }
}

// MARK: - Renderer

final class Renderer {
    private weak var container: NSStackView?
    private let bridge: ScalaBridge
    private var index: [String: NSView] = [:]

    init(container: NSStackView, bridge: ScalaBridge) {
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
                    self?.bridge.send(event: Event(event: "click", id: id))
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
                    self?.bridge.send(event: Event(event: "input", id: id, value: newValue))
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
    var bridge: ScalaBridge!
    var renderer: Renderer!
    var statusItem: NSStatusItem?

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
        window.title = "Scala-driven UI POC"

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

        bridge = ScalaBridge(executable: executable, arguments: arguments)
        renderer = Renderer(container: container, bridge: bridge)
        bridge.onMount = { [weak self] root in
            self?.renderer.mount(root)
        }
        bridge.onPatch = { [weak self] id, op, value in
            self?.renderer.patch(id: id, op: op, value: value)
        }
        bridge.onMenu = { [weak self] menus in
            self?.installMenu(menus)
        }
        bridge.onStatusBar = { [weak self] spec in
            self?.installStatusBar(spec)
        }
        bridge.onWindow = { [weak self] spec in
            self?.applyWindow(spec)
        }
        bridge.onQuit = {
            NSApp.terminate(nil)
        }

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(windowDidResize(_:)),
            name: NSWindow.didResizeNotification,
            object: window
        )

        do {
            try bridge.start()
        } catch {
            FileHandle.standardError.write("Failed to start Scala subprocess: \(error)\n".data(using: .utf8)!)
            NSApp.terminate(nil)
            return
        }

        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationWillTerminate(_ notification: Notification) {
        bridge?.terminate()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }

    private func applyWindow(_ spec: WindowSpec) {
        let size = NSSize(width: spec.width, height: spec.height)
        let screen = resolveScreen(spec.screen) ?? window.screen ?? NSScreen.main
        let visible = screen?.visibleFrame ?? window.frame
        let originX = spec.x ?? (visible.midX - size.width / 2)
        let originY = spec.y ?? (visible.midY - size.height / 2)
        let frame = NSRect(x: originX, y: originY, width: size.width, height: size.height)
        window.setFrame(frame, display: true)
        sendWindowResize(window.frame.size)
    }

    @objc private func windowDidResize(_ notification: Notification) {
        sendWindowResize(window.frame.size)
    }

    private func sendWindowResize(_ size: NSSize) {
        let value = "\(size.width)x\(size.height)"
        bridge.send(event: Event(event: "resize", id: "__window__", value: value))
    }

    private func resolveScreen(_ selector: String?) -> NSScreen? {
        guard let selector = selector else { return nil }
        switch selector {
        case "main": return NSScreen.main
        case "primary": return NSScreen.screens.first
        default:
            if let idx = Int(selector), NSScreen.screens.indices.contains(idx) {
                return NSScreen.screens[idx]
            }
            return nil
        }
    }

    private func installStatusBar(_ spec: StatusBarSpec) {
        if statusItem == nil {
            statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        }
        statusItem?.button?.title = spec.title
        let menu = NSMenu()
        for item in spec.items {
            menu.addItem(buildMenuItem(item))
        }
        statusItem?.menu = menu
    }

    private func installMenu(_ specs: [MenuItemSpec]) {
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

    private func buildMenuItem(_ spec: MenuItemSpec) -> NSMenuItem {
        if spec.separator == true {
            return NSMenuItem.separator()
        }
        let (keyEquivalent, modifiers) = parseShortcut(spec.key)
        let item = ClosureMenuItem(title: spec.title, keyEquivalent: keyEquivalent)
        item.keyEquivalentModifierMask = modifiers
        if let id = spec.id {
            item.onSelect = { [weak self] in
                self?.bridge.send(event: Event(event: "click", id: id))
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
