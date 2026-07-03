# Closing the gap: menu-bar launcher support

> **Status: implemented (2026-07-03).** All three capabilities below now exist
> in the protocol, host, and DSL. A few names differ from the original sketch:
> the tap event is `NotificationTapped` (`event/notificationTapped`), the notify
> command generates as `_notify` on the Scala emit client (smithy4s reserved-name
> avoidance), `RequestNotificationAuth` needed an explicit empty `input := {}`
> (the Swift codegen plugin requires request ops to declare both), and progress
> uses `attrs.fraction: Attr[Option[Double]]` (`None` = indeterminate). The
> notification capability is exposed as `SSR.notifications: Notifications`.

Design for the smithy + DSL + host additions needed to rebuild
signal-fun's `SigbrowseSync` launcher (a menu-bar reminder agent) on SSR.

Three capabilities are missing today. Each is additive — no existing
operation changes shape, so current apps keep working.

1. **Status item** — a menu-bar (`NSStatusItem`) presence with a glyph/title
   and its own dropdown, plus an `.accessory` activation policy.
2. **Notifications** — post banners, define categories/actions, schedule a
   repeating calendar reminder, receive taps.
3. **Progress tag** — a determinate/indeterminate `NSProgressIndicator`.

Ordered by leverage: (1) is the launcher's entire shell and unblocks the most;
(3) is a one-tag content addition; (2) is the largest new surface.

---

## 1. Status item + activation policy

### smithy (`smithy/ui.smithy`)

Add to `UiCommands` operations: `SetStatusItem`, `SetActivationPolicy`.

```smithy
// Scala -> Swift. Declares (or updates) the menu-bar status item. Sending
// again replaces title/glyph and the dropdown in place. Omit `menu` for a
// click-through item that fires `event/click` with `id`.
@jsonRpcNotification("ui/statusItem")
operation SetStatusItem {
    input := {
        title: String          // e.g. "💤 Sigbrowse"
        id: String             // click target when there's no dropdown
        menu: MenuItems        // dropdown; reuses the existing MenuItem shape
    }
}

@jsonRpcNotification("ui/activationPolicy")
operation SetActivationPolicy {
    input := {
        @required
        policy: ActivationPolicy
    }
}

enum ActivationPolicy {
    REGULAR      // normal windowed app (current hard-coded default)
    ACCESSORY    // menu-bar agent, no Dock icon — what the launcher needs
    PROHIBITED
}
```

`MenuItem` already carries `title / id / key / separator / children`. The
launcher also needs **disabled** items (status/header lines) and a **bold**
header. Add two optional fields rather than a new shape:

```smithy
structure MenuItem {
    @required
    title: String
    id: String
    key: String
    separator: Boolean
    children: MenuItems
    enabled: Boolean    // default true; false => greyed, non-clickable
    style: MenuItemStyle
}

enum MenuItemStyle {
    NORMAL
    HEADER    // bold, disabled — the "Sigbrowse Sync" title line
}
```

### host (`swift/main.swift`)

- Add `bridge.onSetActivationPolicy { p in NSApp.setActivationPolicy(...) }`
  and stop hard-coding `.regular` at the bottom of the file (default to
  `.regular` only if never set).
- Add `bridge.onSetStatusItem`: lazily create one
  `NSStatusItem(withLength: .variableLength)`, set `button?.title`, and (if
  `menu` present) build it via the existing `buildMenuItem` — extended to honor
  `enabled` and `style == .header` (bold `attributedTitle`, `isEnabled = false`).
  This is exactly what `MenuController.rebuildMenu` / `title()` / `disabled()`
  do today.
- Menu clicks already route through `ClosureMenuItem.onSelect ->
  bridge.sendClick(ClickInput(id:))`, so no new event op is needed for dropdown
  actions.

### DSL (`scala/lib/`)

`App` gains an optional status item, mirroring the existing
`window: Signal` / `menu: Signal` pattern:

```scala
final case class App(
  window: Signal[IO, SetWindowInput],
  menu: Signal[IO, List[MenuItem]],
  statusItem: Signal[IO, Option[StatusItem]],  // NEW; default Signal.constant(None)
  activationPolicy: ActivationPolicy,           // NEW; default Regular
  component: Component,
)
```

`Main.run` already reads `window` / `menu` via `getAndDiscreteUpdates` and
drives notifications from the update stream — `statusItem` slots into the same
loop (send `emit.setStatusItem` on each update). `activationPolicy` is sent once
before `ui/mount`. A menu-bar-only app returns `Signal.constant(None)` for
`window` handling — see "window is optional" below.

### window becomes optional

The launcher has no main window until "Show progress" is clicked. Today
`bootstrap` unconditionally `emit.setWindow(window0)` and mounts a tree.
Two options:

- **Minimal:** keep the single-window model; the progress window *is* the app
  window, shown/hidden by driving `SetWindow` (needs a `visible: Boolean` or a
  new `ui/window/close` — small). The status item lives independently.
- **Fuller:** introduce multi-window (`WindowId`) — larger, deferred. Not
  needed for a faithful launcher port if we add `visible`.

Recommend the minimal path: add `visible: Boolean` to `SetWindow` input.

---

## 2. Notifications

New service direction: notifications are Scala→Swift commands *and* Swift→Scala
events (taps). Add ops to both services.

### smithy

```smithy
// --- commands (UiCommands) ---

@jsonRpcNotification("ui/notify")
operation Notify {
    input := {
        @required
        title: String
        @required
        body: String
        categoryId: String   // ties to actions declared via SetNotificationCategories
    }
}

// Declare tappable actions once (e.g. a "Sync now" button on the reminder).
@jsonRpcNotification("ui/notificationCategories")
operation SetNotificationCategories {
    input := {
        @required
        categories: NotificationCategories
    }
}

structure NotificationCategory {
    @required
    id: String
    @required
    actions: NotificationActions
}

structure NotificationAction {
    @required
    id: String
    @required
    title: String
    foreground: Boolean   // .foreground option — bring app forward on tap
}

list NotificationCategories { member: NotificationCategory }
list NotificationActions { member: NotificationAction }

// Repeating daily calendar reminder (UNCalendarNotificationTrigger).
@jsonRpcNotification("ui/scheduleReminder")
operation ScheduleReminder {
    input := {
        @required
        id: String
        @required
        title: String
        @required
        body: String
        categoryId: String
        @required
        hour: Integer
        @required
        minute: Integer
    }
}

// Request auth up front; returns whether granted so Scala can log/branch.
@jsonRpcRequest("ui/requestNotificationAuth")
operation RequestNotificationAuth {
    output := {
        @required
        granted: Boolean
    }
}

// --- events (UiEvents) ---

// A notification (or one of its actions) was tapped.
@jsonRpcNotification("event/notificationAction")
operation NotificationAction2 {   // op name; wire stays "event/notificationAction"
    input := {
        @required
        categoryId: String
        @required
        actionId: String   // action id, or "default" for a body tap
    }
}
```

### host

Straight lift of `MenuController`'s notification code:
`requestNotificationAuth`, `notify`, `scheduleDailyReminder` (build a
`UNCalendarNotificationTrigger` from `hour`/`minute`), and the
`didReceive response` delegate → `bridge.sendNotificationAction(...)`. The
codegen plugin already emits typed `bridge.onNotify` / `bridge.onScheduleReminder`
extensions and, for the `@jsonRpcRequest`, `bridge.onRequestNotificationAuth {
... return RequestNotificationAuthOutput(granted:) }`.

### DSL

A thin `Notifications` capability on `SSR` (like `emit`), plus routing
`event/notificationAction` through the `EventBus` (register handlers by
`categoryId`, same mechanism as `QuitMenuId`). The launcher's "tap reminder →
syncNow" becomes `bus.register(reminderCategory, _ => syncNow)`.

---

## 3. Progress tag

### smithy

No protocol change to the node shape is strictly required — reuse `Node.value`
as the fraction string ("0.42") and a new tag. But a determinate/indeterminate
flag reads better as a dedicated field. Minimal version: **new tag only**,
`value` carries the fraction, empty `value` = indeterminate.

### host (`swift/main.swift`)

Add a `case "progress":` next to `"toggle"` building an `NSProgressIndicator`
(`style = .bar`), mapping `setValue`/`value` to
`isIndeterminate` + `doubleValue` exactly like `ProgressWindow.setBar`. Reactive
updates already work via the `setValue` patch path.

### DSL (`scala/lib/dsl.scala`)

```scala
def progress[M: Modifier](mods: M): Component = Component.el("progress", mods)
```

Bind a fraction with `attrs.value <-- signal` (String), or add a typed
`attrs.fraction: Attr[Double]`. Indeterminate = bind empty string.

---

## Effort / sequencing

| Piece | smithy | host | DSL | Notes |
|---|---|---|---|---|
| Progress tag | 0 (tag only) | ~15 lines | 1 line | Do first — trivial, self-contained |
| Status item + policy | 2 ops, 1 enum, 2 MenuItem fields | ~40 lines | `App` fields + run wiring | The unblocker |
| `visible` on SetWindow | 1 field | few lines | — | Lets a menu-bar app hide its window |
| Notifications | 4 ops + 1 event, 4 structs | ~70 lines (lift from launcher) | `Notifications` cap + bus routing | Largest surface |

After all four, `SigbrowseSync` is expressible: the ~500 lines of *UI* Swift
(`main.swift` menu/notification/status code + `ProgressWindow.swift`) collapse
into Scala components; the non-UI ~500 lines (`SyncRunner` process spawn,
`SSEClient`, `SyncStatus`, `LastSyncSummary` persistence) move to Scala on
cats-effect/fs2/http4s rather than being replaced by the API.

Nothing here changes an existing operation's shape (only *adds* optional
`MenuItem` fields and a `visible` field), so it's backward-compatible with the
current demos.
