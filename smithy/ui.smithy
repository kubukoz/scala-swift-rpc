$version: "2.0"

namespace htmxpoc.ui

use jsonrpclib#jsonRpc
use jsonrpclib#jsonRpcNotification

@jsonRpc
service UiCommands {
    version: "1"
    operations: [Mount, Patch, SetWindow, SetMenu, Quit]
}

@jsonRpc
service UiEvents {
    version: "1"
    operations: [Click, Input, Frame]
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
    children: Nodes
}

list Nodes {
    member: Node
}
