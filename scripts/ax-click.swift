// Usage: swift scripts/ax-click.swift <app-process-name> <role> <title>
//   role:  AXCheckBox | AXButton | AXStaticText | ...
//   title: exact title to match
//
// Walks the AX tree of the first window of the given process, finds the
// first element matching (role, title), and posts AXPress to it.
// Exits 0 on success, non-zero with a message on failure.

import AppKit
import ApplicationServices

func main() -> Int32 {
    let args = CommandLine.arguments
    guard args.count == 4 else {
        FileHandle.standardError.write("usage: ax-click <process-name> <role> <title>\n".data(using: .utf8)!)
        return 2
    }
    let processName = args[1]
    let wantRole = args[2]
    let wantTitle = args[3]

    let running = NSRunningApplication.runningApplications(withBundleIdentifier: "") +
        NSWorkspace.shared.runningApplications
    guard let app = running.first(where: { $0.localizedName == processName || $0.executableURL?.lastPathComponent == processName }) else {
        FileHandle.standardError.write("process not found: \(processName)\n".data(using: .utf8)!)
        return 3
    }

    let appAX = AXUIElementCreateApplication(app.processIdentifier)
    var windows: AnyObject?
    guard AXUIElementCopyAttributeValue(appAX, kAXWindowsAttribute as CFString, &windows) == .success,
          let windowArray = windows as? [AXUIElement],
          let firstWindow = windowArray.first
    else {
        FileHandle.standardError.write("no windows\n".data(using: .utf8)!)
        return 4
    }

    if let element = find(in: firstWindow, role: wantRole, title: wantTitle) {
        let result = AXUIElementPerformAction(element, kAXPressAction as CFString)
        if result == .success {
            print("clicked \(wantRole) '\(wantTitle)'")
            return 0
        } else {
            FileHandle.standardError.write("AXPress failed: \(result.rawValue)\n".data(using: .utf8)!)
            return 5
        }
    }

    FileHandle.standardError.write("element not found: role=\(wantRole) title=\(wantTitle)\n".data(using: .utf8)!)
    return 6
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
    var titleValue: AnyObject?
    AXUIElementCopyAttributeValue(element, kAXRoleAttribute as CFString, &roleValue)
    AXUIElementCopyAttributeValue(element, kAXTitleAttribute as CFString, &titleValue)
    return (roleValue as? String) == role && (titleValue as? String) == title
}

exit(main())
