$version: "2.0"

namespace ssr.internal.protocol

use jsonrpclib#jsonRpc
use jsonrpclib#jsonRpcNotification
use jsonrpclib#jsonRpcRequest

@jsonRpc
service UiCommands {
    version: "1"
    operations: [Mount, Patch, ReplaceChildren, SetWindow, SetMenu, SetStatusItem, SetActivationPolicy, Notify, SetNotificationCategories, ScheduleReminder, RequestNotificationAuth, Quit, OpenPanel]
}

@jsonRpc
service UiEvents {
    version: "1"
    operations: [Click, Input, Toggle, Frame, NotificationTapped]
}

// Scala -> Swift

@jsonRpcNotification("ui/mount")
operation Mount {
    input := {
        @required
        root: Node
    }
}

@jsonRpcNotification("ui/patch")
operation Patch {
    input := {
        @required
        id: String
        @required
        op: String
        value: String
        style: Style
    }
}

@jsonRpcNotification("ui/replaceChildren")
operation ReplaceChildren {
    input := {
        @required
        parent: String
        @required
        mounted: Nodes
        @required
        order: Strings
    }
}

@jsonRpcNotification("ui/window")
operation SetWindow {
    input := {
        @required
        width: Double
        @required
        height: Double
        x: Double
        y: Double
        screen: String
        // Show / hide the main window without tearing it down. Absent = visible
        // (back-compat). A menu-bar app hides its window until asked to show it.
        visible: Boolean
    }
}

// Declares (or updates) the menu-bar status item. Sending again replaces
// title/glyph and the dropdown in place. Omit `menu` for a click-through item
// that fires `event/click` with `id`. Send with an empty title to remove it.
@jsonRpcNotification("ui/statusItem")
operation SetStatusItem {
    input := {
        title: String
        id: String
        menu: MenuItems
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
    REGULAR
    ACCESSORY
    PROHIBITED
}

@jsonRpcNotification("ui/menu")
operation SetMenu {
    input := {
        @required
        menus: MenuItems
    }
}

structure MenuItem {
    @required
    title: String
    id: String
    key: String
    separator: Boolean
    children: MenuItems
    // Absent = enabled. A disabled item is greyed and non-clickable — used for
    // status/header lines in a status-item dropdown.
    enabled: Boolean
    style: MenuItemStyle
}

enum MenuItemStyle {
    NORMAL
    // Bold, disabled — the app-name title line at the top of a dropdown.
    HEADER
}

list MenuItems {
    member: MenuItem
}

@jsonRpcNotification("ui/quit")
operation Quit {}

@jsonRpcRequest("ui/openPanel")
operation OpenPanel {
    input := {
        title: String
        allowedExtensions: Strings
    }
    output := {
        path: String
    }
}

// Notifications (UNUserNotificationCenter).

// Post a banner now. `categoryId` ties it to actions declared via
// SetNotificationCategories (its buttons + tap route back as event/notificationTapped).
@jsonRpcNotification("ui/notify")
operation Notify {
    input := {
        @required
        title: String
        @required
        body: String
        categoryId: String
    }
}

// Declare tappable action buttons once, grouped by category.
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
    // Bring the app to the foreground when tapped (.foreground option).
    foreground: Boolean
}

list NotificationCategories {
    member: NotificationCategory
}

list NotificationActions {
    member: NotificationAction
}

// Register a repeating daily reminder at hour:minute (UNCalendarNotificationTrigger).
// Idempotent per `id` — safe to call every launch.
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

// Request notification authorization; returns whether it was granted.
@jsonRpcRequest("ui/requestNotificationAuth")
operation RequestNotificationAuth {
    input := {}
    output := {
        @required
        granted: Boolean
    }
}

// Swift -> Scala

@jsonRpcNotification("event/click")
operation Click {
    input := {
        @required
        id: String
    }
}

@jsonRpcNotification("event/input")
operation Input {
    input := {
        @required
        id: String
        @required
        value: String
    }
}

@jsonRpcNotification("event/toggle")
operation Toggle {
    input := {
        @required
        id: String
        @required
        value: Boolean
    }
}

@jsonRpcNotification("event/frame")
operation Frame {
    input: WindowFrame
}

// A notification (or one of its action buttons) was tapped. `actionId` is the
// action's id, or "default" for a tap on the notification body.
@jsonRpcNotification("event/notificationTapped")
operation NotificationTapped {
    input := {
        @required
        categoryId: String
        @required
        actionId: String
    }
}

// Shared

structure WindowFrame {
    @required
    x: Double
    @required
    y: Double
    @required
    width: Double
    @required
    height: Double
}

structure Node {
    @required
    tag: String
    id: String
    text: String
    value: String
    style: Style
    clickable: Boolean
    children: Nodes
}

list Nodes {
    member: Node
}

list Strings {
    member: String
}

// Style: typed, Scala-driven styling surface (Landmarks-minimal subset).

structure Style {
    padding: EdgeInsets
    spacing: Double
    font: Font
    foreground: Color
    background: Background
    cornerRadius: Double
    frame: SizeFrame
    alignment: Alignment
}

enum Alignment {
    LEADING
    CENTER
    TRAILING
}

structure EdgeInsets {
    @required
    top: Double
    @required
    leading: Double
    @required
    bottom: Double
    @required
    trailing: Double
}

structure Font {
    @required
    size: Double
    @required
    weight: FontWeight
}

enum FontWeight {
    REGULAR
    MEDIUM
    SEMIBOLD
    BOLD
}

// "#RRGGBB" or "#RRGGBBAA"
string Color

union Background {
    color: Color
    material: Material
}

enum Material {
    SIDEBAR
    GLASS
    HUD
    REGULAR
}

structure SizeFrame {
    width: Double
    height: Double
    minWidth: Double
    maxWidth: Double
    minHeight: Double
    maxHeight: Double
}
