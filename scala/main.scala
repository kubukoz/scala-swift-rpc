package ssr

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import fs2.io.file.Path
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.SetWindowInput
import ssr.internal.protocol.UiEvents
import ssr.internal.protocol.WindowFrame
import io.circe.Printer
import io.circe.parser
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.fs2.catsMonadic
import jsonrpclib.fs2.lsp
import jsonrpclib.smithy4sinterop.CirceJsonCodec
import jsonrpclib.smithy4sinterop.ServerEndpoints

final case class App(
  window: Signal[IO, SetWindowInput],
  menu: Signal[IO, List[MenuItem]],
  component: Component,
)

object App {
  val QuitMenuId: String = "app:quit"

  private val frameCodec: io.circe.Codec[WindowFrame] = CirceJsonCodec.fromSchema[WindowFrame]

  private val stateDir: Path =
    Path(java.lang.System.getProperty("user.home")) / ".local" / "state" / "ssr"

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

  def app(ctx: SSR, initial: Option[WindowFrame]): Resource[IO, App] =
    for {
      state <- SignallingRef.of[IO, String]("").toResource
      compact <- SignallingRef.of[IO, Boolean](false).toResource
    } yield {
      val sizeLabel: Signal[IO, String] = ctx
        .windowFrame
        .map(f => f"${f.width}%.0f × ${f.height}%.0f")
      val defaultW = 480.0
      val defaultH = 240.0
      val initialWindow = SetWindowInput(
        width = initial.map(_.width).getOrElse(defaultW),
        height = initial.map(_.height).getOrElse(defaultH),
        x = initial.map(_.x),
        y = initial.map(_.y),
        screen = if (initial.isEmpty) Some("main") else None,
      )
      val windowSignal: Signal[IO, SetWindowInput] = compact.map { isCompact =>
        if (isCompact) initialWindow.copy(width = 320.0, height = 160.0, x = None, y = None)
        else initialWindow
      }
      val menuSignal: Signal[IO, List[MenuItem]] = state.map { typed =>
        val label = if (typed.isEmpty) "App" else typed
        List(
          MenuItem(
            title = label,
            children = Some(
              List(
                MenuItem(title = "Quit", id = Some(App.QuitMenuId), key = Some("cmd+q"))
              )
            ),
          )
        )
      }
      App(
        window = windowSignal,
        menu = menuSignal,
        component = ui.vstack(
          (
            ui.label("Type below — the label mirrors the field (and the menu):"),
            ui.hstack(
              (
                ui.textfield((onInput(state.set), attrs.value <-- state)),
                ui.label(state: Signal[IO, String]),
              )
            ),
            ui.label(sizeLabel),
            ui.button(("Toggle compact", onClick(compact.update(!_)))),
            ui.button(("Quit", onClick(ctx.emit.quit().void))),
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
      ctx = SSR(bus, emit, windowFrame)
      // Wire the App > Quit menu item to the quit notification.
      _ <- Stream.resource(
        bus.register(App.QuitMenuId, ev => IO.whenA(ev.event == "click")(emit.quit().void))
      )
      a <- Stream.resource(app(ctx, initial))
      root <- Stream.resource(Component.build(a.component, ctx))
      tree <- Stream.eval(root.snapshot)
      sendWindow = (w: SetWindowInput) => emit.setWindow(w.width, w.height, w.x, w.y, w.screen).void
      windowStream <- Stream.resource(a.window.getAndDiscreteUpdates)
      (window0, windowUpdates) = windowStream
      menuStream <- Stream.resource(a.menu.getAndDiscreteUpdates)
      (menu0, menuUpdates) = menuStream
      _ <- Stream.eval(sendWindow(window0))
      _ <- Stream.eval(emit.setMenu(menu0))
      _ <- Stream.eval(emit.mount(tree))
      _ <- Stream.eval(root.markMounted)
      _ <- Stream.resource(windowUpdates.evalMap(sendWindow).compile.drain.background.void)
      _ <- Stream.resource(menuUpdates.evalMap(emit.setMenu).compile.drain.background.void)
      stdoutPipe = ch.output.through(lsp.encodeMessages).through(fs2.io.stdout[IO])
      stdinPipe = fs2.io.stdin[IO](4096).through(lsp.decodeMessages).through(ch.inputOrBounce)
      _ <- stdoutPipe.concurrently(stdinPipe)
    } yield ()

    program.compile.drain
  }

}
