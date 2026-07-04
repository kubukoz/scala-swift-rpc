// Minimal UIKit renderer for the SSR wire protocol.
//
// The macOS host maps SSR `Node`s to AppKit views (swift/main.swift's
// `Renderer`); this is the iOS analogue for the core tags only —
// vstack / hstack / label / button / textfield. It handles the same
// mount / patch lifecycle:
//   - `mount(root)` builds the whole tree once and indexes views by id
//   - `patch(id, op, value)` looks a view up and applies setText / setValue
//
// No scrollview / splitview / image / progress yet — the iOS demo (CounterFfi)
// only uses the core tags, keeping this deliberately small.

import UIKit

final class Renderer {
    private weak var container: UIView?
    private let bridge: JSONRPCBridge
    private var index: [String: UIView] = [:]
    // UIButton/UITextField targets are held weakly by UIKit; retain the
    // per-node action handlers so taps/edits keep firing.
    private var handlers: [ActionHandler] = []

    init(container: UIView, bridge: JSONRPCBridge) {
        self.container = container
        self.bridge = bridge
    }

    func mount(_ root: Node) {
        guard let container = container else { return }
        container.subviews.forEach { $0.removeFromSuperview() }
        index.removeAll()
        handlers.removeAll()
        guard let view = buildView(root) else { return }
        view.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(view)
        // Pin the tree's root to the safe area, top-aligned. A vstack's
        // intrinsic height is smaller than the screen, so we don't pin the
        // bottom (that would stretch it).
        let guide = container.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: guide.topAnchor, constant: 16),
            view.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 16),
            view.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -16),
        ])
    }

    func patch(id: String, op: String, value: String?) {
        guard let view = index[id] else {
            FileHandle.standardError.write("patch: unknown id \(id)\n".data(using: .utf8)!)
            return
        }
        switch op {
        case "setText":
            if let label = view as? UILabel {
                label.text = value ?? ""
            } else if let button = view as? UIButton {
                button.setTitle(value ?? "", for: .normal)
            }
        case "setValue":
            if let field = view as? UITextField {
                if field.text != (value ?? "") { field.text = value ?? "" }
            } else if let label = view as? UILabel {
                label.text = value ?? ""
            }
        default:
            FileHandle.standardError.write("patch: unsupported op \(op)\n".data(using: .utf8)!)
        }
    }

    private func buildView(_ node: Node) -> UIView? {
        let view: UIView?
        switch node.tag {
        case "vstack", "hstack":
            let stack = UIStackView()
            stack.axis = node.tag == "vstack" ? .vertical : .horizontal
            stack.spacing = 8
            stack.alignment = node.tag == "vstack" ? .leading : .center
            for child in node.children ?? [] {
                if let v = buildView(child) { stack.addArrangedSubview(v) }
            }
            view = stack
        case "label":
            let label = UILabel()
            label.text = node.text ?? ""
            label.font = .systemFont(ofSize: 17)
            label.numberOfLines = 0
            view = label
        case "button":
            let button = UIButton(type: .system)
            button.setTitle(node.text ?? "", for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 17)
            if let id = node.id {
                let handler = ActionHandler { [weak self] in
                    self?.bridge.sendClick(ClickInput(id: id))
                }
                button.addTarget(handler, action: #selector(ActionHandler.fire), for: .touchUpInside)
                handlers.append(handler)
            }
            view = button
        case "textfield":
            let field = UITextField()
            field.text = node.value ?? ""
            field.borderStyle = .roundedRect
            field.autocorrectionType = .no
            field.autocapitalizationType = .none
            field.translatesAutoresizingMaskIntoConstraints = false
            field.widthAnchor.constraint(greaterThanOrEqualToConstant: 200).isActive = true
            if let id = node.id {
                let handler = ActionHandler { [weak self, weak field] in
                    self?.bridge.sendInput(InputInput(id: id, value: field?.text ?? ""))
                }
                field.addTarget(handler, action: #selector(ActionHandler.fire), for: .editingChanged)
                handlers.append(handler)
            }
            view = field
        default:
            FileHandle.standardError.write("buildView: unsupported tag \(node.tag)\n".data(using: .utf8)!)
            view = nil
        }
        if let view = view, let id = node.id {
            index[id] = view
        }
        return view
    }
}

// Bridges UIKit's target/action (which needs an @objc method) to a Swift
// closure. Retained by the Renderer since controls hold targets weakly.
final class ActionHandler: NSObject {
    private let action: () -> Void
    init(_ action: @escaping () -> Void) { self.action = action }
    @objc func fire() { action() }
}
