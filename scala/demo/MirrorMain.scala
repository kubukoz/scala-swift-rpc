package ssr.demo

import cats.effect.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import ssr.*
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.SetWindowInput

object MirrorMain extends IOApp.Simple {

  def run: IO[Unit] = App.bootstrap(mirrorApp)

  private def mirrorApp(ctx: SSR): Resource[IO, App] =
    for {
      initial <- App.loadFrame.toResource
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

}
