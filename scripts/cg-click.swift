// Usage: swift scripts/cg-click.swift <app-process-name> <role> <title>
// Uses AX to find the element and read its screen position+size, then posts
// real CGEvent mouse-down/mouse-up events at that center.

import AppKit
import ApplicationServices

func main() -> Int32 {
    let args = CommandLine.arguments
    guard args.count == 4 else {
        FileHandle.standardError.write("usage: cg-click <process-name> <role> <title>\n".data(using: .utf8)!)
        return 2
    }
    let processName = args[1]
    let wantRole = args[2]
    let wantTitle = args[3]

    let running = NSWorkspace.shared.runningApplications
    guard let app = running.first(where: { $0.localizedName == processName || $0.executableURL?.lastPathComponent == processName }) else {
        FileHandle.standardError.write("process not found: \(processName)\n".data(using: .utf8)!)
        return 3
    }
    app.activate(options: .activateIgnoringOtherApps)
    usleep(200_000)

    let appAX = AXUIElementCreateApplication(app.processIdentifier)
    var windows: AnyObject?
    guard AXUIElementCopyAttributeValue(appAX, kAXWindowsAttribute as CFString, &windows) == .success,
          let windowArray = windows as? [AXUIElement],
          let firstWindow = windowArray.first
    else {
        FileHandle.standardError.write("no windows\n".data(using: .utf8)!)
        return 4
    }

    guard let element = find(in: firstWindow, role: wantRole, title: wantTitle) else {
        FileHandle.standardError.write("element not found: role=\(wantRole) title=\(wantTitle)\n".data(using: .utf8)!)
        return 6
    }

    var positionValue: AnyObject?
    var sizeValue: AnyObject?
    AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &positionValue)
    AXUIElementCopyAttributeValue(element, kAXSizeAttribute as CFString, &sizeValue)
    guard let posVal = positionValue, let sizeVal = sizeValue else {
        FileHandle.standardError.write("no position/size\n".data(using: .utf8)!)
        return 7
    }
    var origin = CGPoint.zero
    var size = CGSize.zero
    AXValueGetValue(posVal as! AXValue, .cgPoint, &origin)
    AXValueGetValue(sizeVal as! AXValue, .cgSize, &size)
    let center = CGPoint(x: origin.x + size.width / 2, y: origin.y + size.height / 2)
    FileHandle.standardError.write("clicking at \(center) (origin=\(origin) size=\(size))\n".data(using: .utf8)!)

    let down = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: center, mouseButton: .left)
    let up = CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: center, mouseButton: .left)
    down?.post(tap: .cghidEventTap)
    usleep(50_000)
    up?.post(tap: .cghidEventTap)
    print("posted CG click at \(center)")
    return 0
}

func find(in element: AXUIElement, role: String, title: String) -> AXUIElement? {
    if matches(element, role: role, title: title) { return element }
    var children: AnyObject?
    if AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &children) == .success,
       let kids = children as? [AXUIElement]
    {
        for kid in kids {
            if let m = find(in: kid, role: role, title: title) { return m }
        }
    }
    return nil
}

func matches(_ element: AXUIElement, role: String, title: String) -> Bool {
    var roleValue: AnyObject?
    AXUIElementCopyAttributeValue(element, kAXRoleAttribute as CFString, &roleValue)
    if (roleValue as? String) != role { return false }
    // Try AXTitle first, then AXValue (NSTextField labels expose their
    // string via value, not title).
    var titleValue: AnyObject?
    AXUIElementCopyAttributeValue(element, kAXTitleAttribute as CFString, &titleValue)
    if (titleValue as? String) == title { return true }
    var valueValue: AnyObject?
    AXUIElementCopyAttributeValue(element, kAXValueAttribute as CFString, &valueValue)
    return (valueValue as? String) == title
}

exit(main())
