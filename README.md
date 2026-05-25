# SSR — Scala ↔ Swift RPC UI

A proof-of-concept FRP UI framework for native-looking macOS apps, written in Scala. A Swift `AppKit` host process renders the UI; a Scala child process owns application logic and the component tree. The two communicate over JSON-RPC (LSP framing) on stdin/stdout, with the wire protocol defined in Smithy.

The component DSL is shamelessly inspired by [Calico](https://www.armanbilge.com/calico/) — `Resource[IO, _]`-based components, `Signal`-driven reactivity, the same `:=` / `<--` split for static vs. reactive attributes.

> Status: experimental POC. Nothing is published to Maven or anywhere else — clone the repo and run it locally.

![Landmarks demo](docs/landmarks.png)

## What it looks like

The DSL composes AppKit nodes with `cats-effect` `Resource` and `fs2` `Signal`. Here's a slice of the Landmarks sidebar:

```scala
ui.vstack(
  styles.padding := EdgeInsets.only(top = 40, leading = 12, bottom = 12, trailing = 12),
  styles.spacing := 10,
  styles.background := Background.material(Material.Sidebar),
  ui.label(
    "Landmarks",
    styles.font := Font.system(22, FontWeight.Bold),
  ),
  ui.textfield(
    attrs.value <-- query,
    onInput(query.set),
  ),
  ui.divider,
  ui.scrollview(
    ui.vstack(
      styles.spacing := 2,
      ui.children[Int](id => row(byId, id, selected)) <-- visibleIds,
    )
  ),
)
```

A row driven entirely by signals — the image, title and subtitle all swap when the underlying `Landmark` changes:

```scala
ui.hstack(
  styles.padding := EdgeInsets.symmetric(horizontal = 6, vertical = 4),
  styles.spacing := 10,
  ui.image(
    attrs.value <-- landmark.map(l => s"${l.imageName}-thumb"),
    styles.frame := Frame.fixed(44, 44),
    styles.cornerRadius := 6,
  ),
  ui.vstack(
    styles.spacing := 2,
    styles.alignment := Alignment.Leading,
    ui.label(landmark.map(_.name), styles.font := Font.system(14, FontWeight.Medium)),
    ui.label(landmark.map(_.park),  styles.font := Font.system(11), styles.foreground := Color.hex("#888888")),
  ),
  ui.spacer,
  ui.label(landmark.map(l => if (l.isFavorite) "★" else "")),
  onClick(selected.set(Some(id))),
)
```

Keyed children swap detail bodies cleanly when the selection changes — when the key changes the previous body is released and a fresh one is allocated:

```scala
ui.children[Option[Int]] {
  case None     => welcome(byId, all, selected)
  case Some(id) => landmark(byId, id, all, collection, openPanelResult, emit)
} <-- selected.map(List(_))
```

Bidirectional RPC works too — Scala can call Swift for things like an open panel and `await` the chosen path:

```scala
ui.button(
  "📁 Open…",
  onClick(
    emit
      .openPanel(title = Some("Choose a file"))
      .flatMap(out => openPanelResult.set(out.path))
  ),
)
```

App entry point:

```scala
object LandmarksMain extends SSRApp {
  def render(ctx: SSR): Resource[IO, App] =
    for {
      // … signalling refs …
    } yield App(
      window = Signal.constant(window),
      menu = Signal.constant(menu),
      component = ui.splitview(sidebar = Sidebar.render(...), detail = Detail.render(...)),
    )
}
```

## Running it

```bash
sbt runJVM     # build everything and launch with the JVM Scala child
sbt runNative  # same, but the Scala Native child
```

Requires `sbt` and `swiftc`. See [CLAUDE.md](CLAUDE.md) for the architecture deep-dive — process model, mount/patch lifecycle, FRP runtime details and conventions.
