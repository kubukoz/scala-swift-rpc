// Usage: swift scripts/dump-ax.swift <process-name>
// Dumps each AX element's role, title (or value), and screen frame.
import AppKit
import ApplicationServices

let args = CommandLine.arguments
guard args.count == 2 else { print("usage: dump-ax <process-name>"); exit(2) }
let processName = args[1]

guard let app = NSWorkspace.shared.runningApplications.first(where: {
    $0.localizedName == processName || $0.executableURL?.lastPathComponent == processName
}) else {
    print("not found"); exit(3)
}
let ax = AXUIElementCreateApplication(app.processIdentifier)
var windows: AnyObject?
guard AXUIElementCopyAttributeValue(ax, kAXWindowsAttribute as CFString, &windows) == .success,
      let warr = windows as? [AXUIElement], let win = warr.first else { print("no win"); exit(4) }

func dump(_ el: AXUIElement, depth: Int) {
    var roleV: AnyObject?, titleV: AnyObject?, valueV: AnyObject?, posV: AnyObject?, sizeV: AnyObject?
    AXUIElementCopyAttributeValue(el, kAXRoleAttribute as CFString, &roleV)
    AXUIElementCopyAttributeValue(el, kAXTitleAttribute as CFString, &titleV)
    AXUIElementCopyAttributeValue(el, kAXValueAttribute as CFString, &valueV)
    AXUIElementCopyAttributeValue(el, kAXPositionAttribute as CFString, &posV)
    AXUIElementCopyAttributeValue(el, kAXSizeAttribute as CFString, &sizeV)
    let role = (roleV as? String) ?? "?"
    let title = (titleV as? String) ?? (valueV as? String) ?? ""
    var p = CGPoint.zero, s = CGSize.zero
    if let pv = posV { AXValueGetValue(pv as! AXValue, .cgPoint, &p) }
    if let sv = sizeV { AXValueGetValue(sv as! AXValue, .cgSize, &s) }
    let indent = String(repeating: "  ", count: depth)
    print("\(indent)\(role) '\(title)' x=\(Int(p.x)) y=\(Int(p.y)) w=\(Int(s.width)) h=\(Int(s.height))")
    var kids: AnyObject?
    if AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &kids) == .success,
       let karr = kids as? [AXUIElement] {
        for k in karr { dump(k, depth: depth + 1) }
    }
}
dump(win, depth: 0)
