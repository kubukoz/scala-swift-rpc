// Simulates a click with a slight mouse drag — like a human finger that
// doesn't release at exactly the same pixel.
// Usage: swift scripts/cg-drag-click.swift <process-name> <role> <title>
import AppKit
import ApplicationServices

let args = CommandLine.arguments
let processName = args[1]
let wantRole = args[2]
let wantTitle = args[3]
guard let app = NSWorkspace.shared.runningApplications.first(where: {
    $0.localizedName == processName || $0.executableURL?.lastPathComponent == processName
}) else { exit(3) }
app.activate(options: .activateIgnoringOtherApps)
usleep(200_000)

let appAX = AXUIElementCreateApplication(app.processIdentifier)
var windows: AnyObject?
AXUIElementCopyAttributeValue(appAX, kAXWindowsAttribute as CFString, &windows)
guard let warr = windows as? [AXUIElement], let win = warr.first else { exit(4) }

func find(_ el: AXUIElement) -> AXUIElement? {
    var roleV: AnyObject?, titleV: AnyObject?, valueV: AnyObject?
    AXUIElementCopyAttributeValue(el, kAXRoleAttribute as CFString, &roleV)
    AXUIElementCopyAttributeValue(el, kAXTitleAttribute as CFString, &titleV)
    AXUIElementCopyAttributeValue(el, kAXValueAttribute as CFString, &valueV)
    if (roleV as? String) == wantRole,
       (titleV as? String) == wantTitle || (valueV as? String) == wantTitle { return el }
    var kids: AnyObject?
    AXUIElementCopyAttributeValue(el, kAXChildrenAttribute as CFString, &kids)
    if let karr = kids as? [AXUIElement] { for k in karr { if let m = find(k) { return m } } }
    return nil
}

guard let element = find(win) else { exit(6) }
var posV: AnyObject?, sizeV: AnyObject?
AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &posV)
AXUIElementCopyAttributeValue(element, kAXSizeAttribute as CFString, &sizeV)
var origin = CGPoint.zero, size = CGSize.zero
AXValueGetValue(posV as! AXValue, .cgPoint, &origin)
AXValueGetValue(sizeV as! AXValue, .cgSize, &size)
let start = CGPoint(x: origin.x + size.width / 2, y: origin.y + size.height / 2)
let end = CGPoint(x: start.x + 3, y: start.y + 2)

let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: start, mouseButton: .left)!
down.post(tap: .cghidEventTap)
usleep(30_000)
let drag = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: end, mouseButton: .left)!
drag.post(tap: .cghidEventTap)
usleep(20_000)
let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: end, mouseButton: .left)!
up.post(tap: .cghidEventTap)
print("drag-click start=\(start) end=\(end)")
