package htmxpoc

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import upickle.default.*

final case class App(window: WindowSpec, component: Component)

object Main extends IOApp.Simple {

  def app(ctx: Ctx): Resource[IO, App] = SignallingRef.of[IO, String]("").toResource.map { state =>
    val sizeLabel: Signal[IO, String] = ctx
      .windowSize
      .map { case (w, h) => f"$w%.0f × $h%.0f" }
    App(
      window = WindowSpec(width = 480, height = 240, screen = Some("main")),
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
      windowSize <- SignallingRef.of[IO, (Double, Double)]((0.0, 0.0))
      ctx = Ctx(bus, Emit.stdout, windowSize)
      _ <- app(ctx).use { a =>
        windowSize.set((a.window.width, a.window.height)) *>
          Component.build(a.component, ctx).use { root =>
            for {
              tree <- root.snapshot
              _ <- ctx.emit(Command.window(a.window))
              _ <- ctx.emit(Command.mount(tree))
              _ <- root.markMounted
              _ <- stdinEvents
                .evalMap { ev =>
                  if (ev.id == WindowSize.EventId && ev.event == WindowSize.EventName)
                    ev.value.flatMap(WindowSize.parse).traverse_(windowSize.set)
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
