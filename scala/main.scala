package htmxpoc

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import fs2.io.file.Path
import htmxpoc.ui.MenuItem
import htmxpoc.ui.SetWindowInput
import htmxpoc.ui.UiEvents
import htmxpoc.ui.WindowFrame
import io.circe.Printer
import io.circe.parser
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.fs2.catsMonadic
import jsonrpclib.fs2.lsp
import jsonrpclib.smithy4sinterop.CirceJsonCodec
import jsonrpclib.smithy4sinterop.ServerEndpoints

final case class App(window: SetWindowInput, menu: List[MenuItem], component: Component)

object App {
  val QuitMenuId: String = "app:quit"

  private val frameCodec: io.circe.Codec[WindowFrame] = CirceJsonCodec.fromSchema[WindowFrame]

  private val stateDir: Path =
    Path(java.lang.System.getProperty("user.home")) / ".local" / "state" / "htmx-poc"

  private val stateFile: Path = stateDir / "window.json"

  def loadFrame: IO[Option[WindowFrame]] = Files[IO]
    .exists(stateFile)
    .ifM(
      Files[IO]
        .readUtf8(stateFile)
        .compile
        .string
        .attempt
        .map(_.toOption.flatMap(s => parser.decode(s)(using frameCodec).toOption)),
      IO.pure(None),
    )

  def saveFrame(f: WindowFrame): IO[Unit] =
    Files[IO].createDirectories(stateDir) *>
      Stream
        .emit(Printer.noSpaces.print(frameCodec(f)))
        .through(Files[IO].writeUtf8(stateFile))
        .compile
        .drain
}

object Main extends IOApp.Simple {

  def app(ctx: Ctx, initial: Option[WindowFrame]): Resource[IO, App] = SignallingRef
    .of[IO, String]("")
    .toResource
    .map { state =>
      val sizeLabel: Signal[IO, String] = ctx
        .windowFrame
        .map(f => f"${f.width}%.0f × ${f.height}%.0f")
      val defaultW = 480.0
      val defaultH = 240.0
      App(
        window = SetWindowInput(
          width = initial.map(_.width).getOrElse(defaultW),
          height = initial.map(_.height).getOrElse(defaultH),
          x = initial.map(_.x),
          y = initial.map(_.y),
          screen = if (initial.isEmpty) Some("main") else None,
        ),
        menu = List(
          MenuItem(
            title = "App",
            children = Some(
              List(
                MenuItem(title = "Quit", id = Some(App.QuitMenuId), key = Some("cmd+q"))
              )
            ),
          )
        ),
        component = html.vstack(
          (
            html.label("Type below — the label mirrors the field:"),
            html.hstack(
              (
                html.textfield((onInput(state.set), attrs.value <-- state)),
                html.label(state: Signal[IO, String]),
              )
            ),
            html.label(sizeLabel),
            html.button(("Quit", onClick(ctx.emit.quit().void))),
          )
        ),
      )
    }

  def eventService(
    bus: EventBus,
    windowFrame: Ref[IO, WindowFrame],
    onFrame: WindowFrame => IO[Unit],
  ): UiEvents[IO] =
    new UiEvents[IO] {
      def click(id: String): IO[Unit] = bus.fire(UiEvent(id = id, event = "click"))
      def input(id: String, value: String): IO[Unit] = bus
        .fire(UiEvent(id = id, event = "input", value = Some(value)))
      def frame(x: Double, y: Double, width: Double, height: Double): IO[Unit] = {
        val f = WindowFrame(x, y, width, height)
        windowFrame.set(f) *> onFrame(f)
      }
    }

  def run: IO[Unit] = {
    val program = for {
      bus <- Stream.eval(EventBus.make)
      initial <- Stream.eval(App.loadFrame)
      windowFrame <- Stream.eval(
        SignallingRef.of[IO, WindowFrame](initial.getOrElse(WindowFrame(0, 0, 0, 0)))
      )
      endpoints <- Stream.eval(
        IO.fromEither(
          ServerEndpoints(eventService(bus, windowFrame, App.saveFrame)).leftMap(err =>
            new RuntimeException(err.toString)
          )
        )
      )
      ch <- FS2Channel[IO]()
      _ <- Stream.resource(ch.withEndpoints(endpoints))
      emit <- Stream.eval(Emit.fromChannel(ch))
      ctx = Ctx(bus, emit, windowFrame)
      // Wire the App > Quit menu item to the quit notification.
      _ <- Stream.resource(
        bus.register(App.QuitMenuId, ev => IO.whenA(ev.event == "click")(emit.quit().void))
      )
      a <- Stream.resource(app(ctx, initial))
      root <- Stream.resource(Component.build(a.component, ctx))
      tree <- Stream.eval(root.snapshot)
      _ <- Stream.eval(
        emit.setWindow(a.window.width, a.window.height, a.window.x, a.window.y, a.window.screen)
      )
      _ <- Stream.eval(emit.setMenu(a.menu))
      _ <- Stream.eval(emit.mount(tree))
      _ <- Stream.eval(root.markMounted)
      stdoutPipe = ch.output.through(lsp.encodeMessages).through(fs2.io.stdout[IO])
      stdinPipe = fs2.io.stdin[IO](4096).through(lsp.decodeMessages).through(ch.inputOrBounce)
      _ <- stdoutPipe.concurrently(stdinPipe)
    } yield ()

    program.compile.drain
  }

}
