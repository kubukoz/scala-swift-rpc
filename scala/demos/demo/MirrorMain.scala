/*
 * Copyright 2026 Jakub Kozłowski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ssr.demo

import cats.effect.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import ssr.*
import ssr.internal.protocol.SetWindowInput

object MirrorMain extends SSRApp {

  def render(ctx: SSR): Resource[IO, App] = {
    given SSR = ctx
    for {
      initial <- App.loadFrame.toResource
      state <- SignallingRef.of[IO, String]("").toResource
      compact <- SignallingRef.of[IO, Boolean](false).toResource
      sizeLabel = ctx.windowFrame.map(f => f"${f.width}%.0f × ${f.height}%.0f")
      defaultW = 480.0
      defaultH = 240.0
      initialWindow = SetWindowInput(
        width = initial.map(_.width).getOrElse(defaultW),
        height = initial.map(_.height).getOrElse(defaultH),
        x = initial.map(_.x),
        y = initial.map(_.y),
        screen = if (initial.isEmpty) Some("main") else None,
      )
      windowSignal = compact.map { isCompact =>
        if (isCompact) initialWindow.copy(width = 320.0, height = 160.0, x = None, y = None)
        else initialWindow
      }
      menuSignal <- appMenu(
        state.map { typed =>
          val label = if (typed.isEmpty) "App" else typed
          List(
            menu.menu(label)(
              menu.item("Quit", key = Some("cmd+q"))(ctx.emit.quit().void)
            )
          )
        }
      )
      // High-level status item: click handlers inline, ids managed for us.
      statusSignal <- statusItem(
        title = "☰",
        state.map { typed =>
          List(
            menu.header("Mirror"),
            menu.text(if (typed.isEmpty) "(nothing typed)" else typed, enabled = false),
            menu.separator,
            menu.item("Clear")(state.set("")),
            menu.item("Toggle compact")(compact.update(!_)),
            menu.separator,
            menu.item("Quit")(ctx.emit.quit().void),
          )
        },
      )
    } yield App(
      window = windowSignal,
      menu = menuSignal,
      statusItem = statusSignal,
      component = ui.vstack(
        ui.label("Type below — the label mirrors the field (and the menu):"),
        ui.hstack(
          ui.textfield(onInput(state.set), attrs.value <-- state),
          ui.label(state),
        ),
        ui.label(sizeLabel),
        ui.button("Toggle compact", onClick(compact.update(!_))),
        ui.button("Quit", onClick(ctx.emit.quit().void)),
      ),
    )
  }

}
