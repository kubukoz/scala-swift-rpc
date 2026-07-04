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

package ssr.counter

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import ssr.*
import ssr.internal.protocol.SetWindowInput

import scala.scalanative.unsafe.*

// A deliberately tiny SSR app for the iOS bring-up: a counter plus a text
// field that mirrors into a label. No images, no menus, no status item — just
// the core tags (vstack / hstack / label / button / textfield) so the minimal
// UIKit renderer under `ios/` has the smallest possible surface to support.
//
// `window` / `menu` are required by `App` but ignored by the iOS host (there
// is no NSWindow or menu bar on iOS), so they're trivial constants.
object CounterMain {

  def render(ctx: SSR): Resource[IO, App] = {
    given SSR = ctx
    for {
      count <- SignallingRef.of[IO, Int](0).toResource
      text <- SignallingRef.of[IO, String]("").toResource
      countLabel = count.map(n => s"Count: $n")
    } yield App(
      window = Signal.constant(SetWindowInput(width = 390.0, height = 700.0)),
      menu = Signal.constant(Nil),
      component = ui.vstack(
        ui.label("SSR on iOS 🎉"),
        ui.label(countLabel),
        ui.hstack(
          ui.button("−", onClick(count.update(_ - 1))),
          ui.button("+", onClick(count.update(_ + 1))),
        ),
        ui.button("Reset", onClick(count.set(0))),
        ui.label("Type below — the label mirrors it:"),
        ui.textfield(onInput(text.set), attrs.value <-- text),
        ui.label(text),
      ),
    )
  }

}

// The FFI entry point: exposes `ssr_init` so the UIKit host can link this app
// in as a static library and boot it in-process (no subprocess — iOS forbids
// spawning children). Same shape as `LandmarksFfi`, but this is the app the
// iOS build links (see build.sbt's `ssr.ios` demo-selection).
object CounterFfi extends SsrFfiApp {
  def render(ctx: SSR): Resource[IO, App] = CounterMain.render(ctx)

  @exported("ssr_init")
  def ssrInit(): Ptr[Byte] = boot()
}
