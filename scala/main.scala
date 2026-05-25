package htmxpoc

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import fs2.io.file.Path
import upickle.default.*

final case class App(window: WindowSpec, menu: List[MenuItemSpec], component: Component)

object App {
  val QuitMenuId: String = "app:quit"

  given ReadWriter[WindowFrame] = macroRW

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
        .map(_.toOption.flatMap(s => scala.util.Try(read[WindowFrame](s)).toOption)),
      IO.pure(None),
    )

  def saveFrame(f: WindowFrame): IO[Unit] =
    Files[IO].createDirectories(stateDir) *>
      Stream
        .emit(write(f))
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
        window = WindowSpec(
          width = initial.map(_.width).getOrElse(defaultW),
          height = initial.map(_.height).getOrElse(defaultH),
          x = initial.map(_.x),
          y = initial.map(_.y),
          screen = if (initial.isEmpty) Some("main") else None,
        ),
        menu = List(
          MenuItemSpec(
            title = "App",
            children = Some(
              List(
                MenuItemSpec(title = "Quit", id = Some(App.QuitMenuId), key = Some("cmd+q"))
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
            html.button(("Quit", onClick(Emit.stdout(Command.quit)))),
          )
        ),
      )
    }

  def stdinEvents: Stream[IO, Event] = fs2
    .io
    .stdinUtf8[IO](4096)
    .through(fs2.text.lines)
    .filter(_.nonEmpty)
    .evalMap { line =>
      IO(try Some(read[Event](line))
      catch { case _: Throwable => None })
    }
    .unNone

  def run: IO[Unit] =
    for {
      bus <- EventBus.make
      initial <- App.loadFrame
      windowFrame <- SignallingRef.of[IO, WindowFrame](
        initial.getOrElse(WindowFrame(0, 0, 0, 0))
      )
      ctx = Ctx(bus, Emit.stdout, windowFrame)
      _ <- app(ctx, initial).use { a =>
        Component.build(a.component, ctx).use { root =>
          for {
            tree <- root.snapshot
            _ <- ctx.emit(Command.window(a.window))
            _ <- ctx.emit(Command.menu(a.menu))
            _ <- ctx.emit(Command.mount(tree))
            _ <- root.markMounted
            _ <- stdinEvents
              .evalMap { ev =>
                if (ev.id == WindowFrame.EventId && ev.event == WindowFrame.EventName)
                  ev.value.flatMap(WindowFrame.parse).traverse_ { f =>
                    windowFrame.set(f) *> App.saveFrame(f)
                  }
                else if (ev.id == App.QuitMenuId && ev.event == "click")
                  ctx.emit(Command.quit)
                else
                  bus.fire(ev)
              }
              .compile
              .drain
          } yield ()
        }
      }
    } yield ()

}
