$version: "2.0"

namespace ssr.internal.protocol

use jsonrpclib#jsonRpc
use jsonrpclib#jsonRpcNotification
use jsonrpclib#jsonRpcRequest

@jsonRpc
service UiCommands {
    version: "1"
    operations: [Mount, Patch, ReplaceChildren, SetWindow, SetMenu, Quit, OpenPanel]
}

@jsonRpc
service UiEvents {
    version: "1"
    operations: [Click, Input, Toggle, Frame]
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
    }
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
