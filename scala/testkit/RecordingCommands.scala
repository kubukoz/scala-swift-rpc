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

package ssr.testkit

import cats.effect.*
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.Node
import ssr.internal.protocol.SetStatusItemInput
import ssr.internal.protocol.SetWindowInput
import ssr.internal.protocol.Style
import ssr.internal.protocol.UiCommands
import ssr.internal.protocol.UiCommandsGen

// A single `ui/patch` notification as captured by the harness. Mirrors the
// `PatchInput` fields the runtime emits from `NodeBuilder`'s setters.
final case class RecordedPatch(id: String, op: String, value: Option[String], style: Option[Style])

// Everything the app pushed toward the (absent) Swift host, in call order.
// The harness exposes this so tests can assert on protocol traffic without a
// real host process — the same commands `bootstrap` would put on the wire.
final case class Recorded(
  mounts: List[Node] = Nil,
  patches: List[RecordedPatch] = Nil,
  windows: List[SetWindowInput] = Nil,
  menus: List[List[MenuItem]] = Nil,
  statusItems: List[SetStatusItemInput] = Nil,
  quits: Int = 0,
)

object RecordedCommands {

  // A `UiCommands[IO]` that records the UI-driving notifications into `ref`
  // instead of sending them over JSON-RPC. Notification / open-panel ops fall
  // through to the `Default` stub (`IO.stub`) so an unexpected call fails
  // loudly rather than silently succeeding — tests that need those should use
  // the real `Notifications` recording pattern from the library's own tests.
  def apply(ref: Ref[IO, Recorded]): UiCommands[IO] =
    new UiCommandsGen.Default[IO](IO.stub) {

      override def mount(root: Node): IO[Unit] =
        ref.update(r => r.copy(mounts = r.mounts :+ root))

      override def patch(
        id: String,
        op: String,
        value: Option[String],
        style: Option[Style],
      ): IO[Unit] =
        ref.update(r => r.copy(patches = r.patches :+ RecordedPatch(id, op, value, style)))

      override def setWindow(
        width: Double,
        height: Double,
        x: Option[Double],
        y: Option[Double],
        screen: Option[String],
        visible: Option[Boolean],
      ): IO[Unit] =
        ref.update(r =>
          r.copy(windows = r.windows :+ SetWindowInput(width, height, x, y, screen, visible))
        )

      override def setMenu(menus: List[MenuItem]): IO[Unit] =
        ref.update(r => r.copy(menus = r.menus :+ menus))

      override def setStatusItem(
        title: Option[String],
        id: Option[String],
        menu: Option[List[MenuItem]],
      ): IO[Unit] =
        ref.update(r => r.copy(statusItems = r.statusItems :+ SetStatusItemInput(title, id, menu)))

      // Sent once before mount; harmless to ignore for querying purposes.
      override def setActivationPolicy(
        policy: ssr.internal.protocol.ActivationPolicy
      ): IO[Unit] = IO.unit

      override def quit(): IO[Unit] = ref.update(r => r.copy(quits = r.quits + 1))

    }

}
