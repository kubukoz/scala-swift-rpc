// ssr-uitest — a small AX-based UI test driver for SSR (AppKit host) apps.
//
// One tool, consolidating the earlier dump-ax / ax-click / cg-click scripts.
// It drives the *real* Swift host through the macOS Accessibility API, so it
// exercises the true AppKit rendering + hit-test path (unlike the in-process
// `ssr-testkit` harness, which is faster and fully headless but never renders).
//
// Usage:
//   swift scripts/ssr-uitest.swift dump  <process>
//       Print the AX element tree (role, title/value, screen frame) for the
//       process's first window. Focus-free — safe to run in the background.
//
//   swift scripts/ssr-uitest.swift read  <process> <role> <title-or-value>
//       Print the AXValue of the matched element (e.g. a label's text). Use
//       this to assert on state. Focus-free.
//
//   swift scripts/ssr-uitest.swift click <process> <role> <title> [--real] [--drag]
//       Activate the control. Default: AXPress — focus-free, does NOT steal
//       focus, but bypasses AppKit event delivery (can mask hit-test / gesture
//       bugs). With --real: post a physical CGEvent mouse click at the
//       element's center — highest fidelity, but REQUIRES the app be frontmost,
//       so it briefly steals focus. Add --drag (implies --real) to nudge the
//       cursor a few px between mouse-down and up — a human-finger click that
//       shakes out gesture-recognizer edge cases a clean click won't.
//
// Match: `title` is compared against AXTitle first, then AXValue (NSTextField
// labels expose their string via value, not title). Exit 0 on success.
//
// Prereq: the driving terminal needs Accessibility permission
// (System Settings → Privacy & Security → Accessibility).

import AppKit
import ApplicationServices

func err(_ s: String) { FileHandle.standardError.write((s + "\n").data(using: .utf8)!) }

func findApp(_ name: String) -> NSRunningApplication? {
    NSWorkspace.shared.runningApplications.first {
        $0.localizedName == name || $0.executableURL?.lastPathComponent == name
    }
}

func firstWindow(_ app: NSRunningApplication) -> AXUIElement? {
    let ax = AXUIElementCreateApplication(app.processIdentifier)
    var windows: AnyObject?
    guard AXUIElementCopyAttributeValue(ax, kAXWindowsAttribute as CFString, &windows) == .success,
          let arr = windows as? [AXUIElement], let first = arr.first
    else { return nil }
    return first
}

func attr(_ el: AXUIElement, _ key: String) -> AnyObject? {
    var v: AnyObject?
    AXUIElementCopyAttributeValue(el, key as CFString, &v)
    return v
}

// A node matches if its role equals `role` and either its title OR its value
// equals `title` — mirrors how SSR labels surface text as AXValue.
func matches(_ el: AXUIElement, role: String, title: String) -> Bool {
    guard (attr(el, kAXRoleAttribute as String) as? String) == role else { return false }
    if (attr(el, kAXTitleAttribute as String) as? String) == title { return true }
    return (attr(el, kAXValueAttribute as String) as? String) == title
}

func find(in el: AXUIElement, role: String, title: String) -> AXUIElement? {
    if matches(el, role: role, title: title) { return el }
    if let kids = attr(el, kAXChildrenAttribute as String) as? [AXUIElement] {
        for k in kids { if let m = find(in: k, role: role, title: title) { return m } }
    }
    return nil
}

func frame(of el: AXUIElement) -> CGRect? {
    guard let posV = attr(el, kAXPositionAttribute as String),
          let sizeV = attr(el, kAXSizeAttribute as String) else { return nil }
    var p = CGPoint.zero, s = CGSize.zero
    AXValueGetValue(posV as! AXValue, .cgPoint, &p)
    AXValueGetValue(sizeV as! AXValue, .cgSize, &s)
    return CGRect(origin: p, size: s)
}

func cmdDump(_ processName: String) -> Int32 {
    guard let app = findApp(processName) else { err("process not found: \(processName)"); return 3 }
    guard let win = firstWindow(app) else { err("no windows"); return 4 }
    func walk(_ el: AXUIElement, _ depth: Int) {
        let role = (attr(el, kAXRoleAttribute as String) as? String) ?? "?"
        let title = (attr(el, kAXTitleAttribute as String) as? String)
            ?? (attr(el, kAXValueAttribute as String) as? String) ?? ""
        let f = frame(of: el) ?? .zero
        let indent = String(repeating: "  ", count: depth)
        print("\(indent)\(role) '\(title)' x=\(Int(f.minX)) y=\(Int(f.minY)) w=\(Int(f.width)) h=\(Int(f.height))")
        if let kids = attr(el, kAXChildrenAttribute as String) as? [AXUIElement] {
            for k in kids { walk(k, depth + 1) }
        }
    }
    walk(win, 0)
    return 0
}

func cmdRead(_ processName: String, _ role: String, _ title: String) -> Int32 {
    guard let app = findApp(processName) else { err("process not found: \(processName)"); return 3 }
    guard let win = firstWindow(app) else { err("no windows"); return 4 }
    guard let el = find(in: win, role: role, title: title) else {
        err("element not found: role=\(role) title=\(title)"); return 6
    }
    let value = (attr(el, kAXValueAttribute as String) as? String)
        ?? (attr(el, kAXTitleAttribute as String) as? String) ?? ""
    print(value)
    return 0
}

func cmdClick(_ processName: String, _ role: String, _ title: String, real: Bool, drag: Bool) -> Int32 {
    guard let app = findApp(processName) else { err("process not found: \(processName)"); return 3 }
    if real { app.activate(); usleep(200_000) }
    guard let win = firstWindow(app) else { err("no windows"); return 4 }
    guard let el = find(in: win, role: role, title: title) else {
        err("element not found: role=\(role) title=\(title)"); return 6
    }
    if !real {
        // Focus-free path: AXPress. Fast and background-safe, but bypasses the
        // AppKit mouse-event pipeline.
        let rc = AXUIElementPerformAction(el, kAXPressAction as CFString)
        if rc == .success { print("pressed \(role) '\(title)'"); return 0 }
        err("AXPress failed: \(rc.rawValue)"); return 5
    }
    // High-fidelity path: real CGEvent click at the element center. Goes through
    // the same delivery path as a physical click; catches hit-test/gesture bugs
    // that AXPress would sail past. Requires the app to be frontmost.
    guard let f = frame(of: el) else { err("no position/size"); return 7 }
    let start = CGPoint(x: f.midX, y: f.midY)
    let end = drag ? CGPoint(x: start.x + 3, y: start.y + 2) : start
    CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: start, mouseButton: .left)?
        .post(tap: .cghidEventTap)
    usleep(drag ? 30_000 : 50_000)
    if drag {
        // A slight mouse-drag between down and up — mimics a real finger, which
        // rarely lands perfectly still. Surfaces gesture-recognizer / hit-slop
        // bugs a pixel-perfect click sails past.
        CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: end, mouseButton: .left)?
            .post(tap: .cghidEventTap)
        usleep(20_000)
    }
    CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: end, mouseButton: .left)?
        .post(tap: .cghidEventTap)
    print("\(drag ? "drag-clicked" : "clicked") \(role) '\(title)' at \(end)")
    return 0
}

func usage() -> Int32 {
    err("""
    usage:
      ssr-uitest dump  <process>
      ssr-uitest read  <process> <role> <title>
      ssr-uitest click <process> <role> <title> [--real] [--drag]
    """)
    return 2
}

func main() -> Int32 {
    let a = CommandLine.arguments
    guard a.count >= 2 else { return usage() }
    switch a[1] {
    case "dump" where a.count == 3: return cmdDump(a[2])
    case "read" where a.count == 5: return cmdRead(a[2], a[3], a[4])
    case "click" where a.count >= 5:
        let flags = Set(a.dropFirst(5))
        let known: Set<String> = ["--real", "--drag"]
        guard flags.isSubset(of: known) else { return usage() }
        let drag = flags.contains("--drag")
        // --drag implies a real CGEvent (AXPress has no notion of dragging).
        return cmdClick(a[2], a[3], a[4], real: flags.contains("--real") || drag, drag: drag)
    default: return usage()
    }
}

exit(main())
