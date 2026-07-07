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
import cats.syntax.all.*
import fs2.concurrent.SignallingRef

import scala.concurrent.duration.*
import ssr.*
import ssr.internal.protocol.Node
import ssr.internal.protocol.SetStatusItemInput
import ssr.internal.protocol.SetWindowInput
import ssr.internal.protocol.WindowFrame

// A headless, in-process driver for an SSR `App`. It builds the component tree
// through the same runtime path the real host uses (`Component.build` +
// `markMounted`), but with a recording `UiCommands` in place of the JSON-RPC
// channel — so no Swift host launches, no window opens, and nothing steals
// focus. Fire synthetic events into the tree, then read back the live `Node`
// snapshot or the recorded protocol traffic.
//
//   TestHarness.of(MyApp.render).use { h =>
//     for {
//       _   <- h.click("btn-inc")            // route an event/click by node id
//       lbl <- h.textOf("count")             // read the current label text
//     } yield assertEquals(lbl, Some("1"))
//   }
//
// Node ids are runtime-assigned (`n1`, `n2`, …). Prefer the by-text helpers
// (`clickText`, `findByText`) or match on `tag` when you don't control ids.
trait TestHarness {

  // ---- driving events (mirrors Swift's UiEvents -> eventService) ----
  //
  // Each drive method fires the event and then `settle`s, so by the time the
  // effect completes any reactive propagation (signal -> setText/setValue,
  // which the runtime runs in background fibers) has caught up and the tree /
  // recorded patches reflect it.

  // Route an `event/click` to the node with this id.
  def click(id: String): IO[Unit]

  // Route an `event/input` (text field edit) to the node with this id.
  def input(id: String, value: String): IO[Unit]

  // Route an `event/toggle` (checkbox) to the node with this id.
  def toggle(id: String, value: Boolean): IO[Unit]

  // Update the window-frame signal, as the host does on `event/frame`.
  def setFrame(frame: WindowFrame): IO[Unit]

  // Wait until reactive propagation has quiesced: the `Node` snapshot stops
  // changing across successive polls. Drive methods call this for you; call it
  // directly if you mutate app state through a handle you captured yourself.
  def settle: IO[Unit]

  // ---- querying the live tree ----

  // The current component tree as a `Node` snapshot (reflects post-mount
  // mutations). This is the source of truth for "what would render now".
  def tree: IO[Node]

  // The current `text` of the node with this id, if any.
  def textOf(id: String): IO[Option[String]]

  // The current `value` of the node with this id, if any (text-field contents,
  // checkbox state as string, progress fraction).
  def valueOf(id: String): IO[Option[String]]

  // The first node whose text equals `text`, if any.
  def findByText(text: String): IO[Option[Node]]

  // All nodes in the tree (pre-order) matching the predicate.
  def findAll(p: Node => Boolean): IO[List[Node]]

  // Fire a click at the first node whose text equals `text`. Fails the effect
  // if no such node exists (or it carries no id) — a missing target in a test
  // is a bug, not a silent no-op.
  def clickText(text: String): IO[Unit]

  // ---- recorded protocol traffic (what would go on the wire) ----

  def recorded: IO[Recorded]
}

object TestHarness {

  // Build a harness for the given app factory. The returned `Resource` runs the
  // app's setup and reactive background fibers (window/menu/status updates) for
  // its scope, exactly like `App.bootstrap` — just without the stdio pipe.
  def of(factory: SSR => Resource[IO, App]): Resource[IO, TestHarness] =
    for {
      state <- Ref.of[IO, Recorded](Recorded()).toResource
      emit = RecordedCommands(state)
      bus <- EventBus.make.toResource
      idGen <- IdGen.make.toResource
      frameRef <- SignallingRef.of[IO, WindowFrame](WindowFrame(0, 0, 0, 0)).toResource
      ctx = SSR(bus, emit, frameRef, idGen, Notifications(emit, bus))
      app <- factory(ctx)
      root <- Component.build(app.component, ctx)
      // Replay bootstrap's send-initial-then-drive-updates dance so reactive
      // window/menu/status bindings behave as they would against a real host.
      windowStream <- app.window.getAndDiscreteUpdates
      (window0, windowUpdates) = windowStream
      menuStream <- app.menu.getAndDiscreteUpdates
      (menu0, menuUpdates) = menuStream
      statusStream <- app.statusItem.getAndDiscreteUpdates
      (status0, statusUpdates) = statusStream
      sendWindow = (w: SetWindowInput) =>
        emit.setWindow(w.width, w.height, w.x, w.y, w.screen, w.visible).void
      sendStatus = (s: Option[SetStatusItemInput]) =>
        emit.setStatusItem(s.flatMap(_.title), s.flatMap(_.id), s.flatMap(_.menu)).void
      _ <- (sendWindow(window0) *> emit.setMenu(menu0) *> sendStatus(status0)).toResource
      tree0 <- root.snapshot.toResource
      _ <- emit.mount(tree0).toResource
      _ <- root.markMounted.toResource
      _ <- windowUpdates.evalMap(sendWindow).compile.drain.background.void
      _ <- menuUpdates.evalMap(emit.setMenu(_).void).compile.drain.background.void
      _ <- statusUpdates.evalMap(sendStatus).compile.drain.background.void
    } yield new Impl(bus, frameRef, root, state)

  private def allNodes(n: Node): List[Node] =
    n :: n.children.getOrElse(Nil).flatMap(allNodes)

  private final class Impl(
    bus: EventBus,
    frameRef: SignallingRef[IO, WindowFrame],
    root: NodeBuilder,
    state: Ref[IO, Recorded],
  ) extends TestHarness {

    def click(id: String): IO[Unit] =
      bus.fire(UiEvent(id = id, event = "click")) *> settle

    def input(id: String, value: String): IO[Unit] =
      bus.fire(UiEvent(id = id, event = "input", value = Some(value))) *> settle

    def toggle(id: String, value: Boolean): IO[Unit] =
      bus.fire(UiEvent(id = id, event = "toggle", value = Some(value.toString))) *> settle

    def setFrame(frame: WindowFrame): IO[Unit] = frameRef.set(frame) *> settle

    // Poll the snapshot until it has been stable for `stableWindow` of wall
    // time — reactive propagation runs in background fibers (signal -> setText),
    // so a just-fired event may not have touched the tree yet when we first
    // sample. A stable *old* tree is indistinguishable from a truly-quiesced one
    // by content alone, so we can't trust a tiny 2-3ms window: on a loaded CI
    // runner the propagation fiber can take longer than that just to get
    // scheduled, and we'd declare quiescence on the stale tree (the flaky
    // failure this replaces). Instead require the tree to hold steady for a
    // comfortably-longer window, and `cede` between polls so pending background
    // fibers actually get a turn to run before we sample again. Capped by
    // `budget` so a genuinely-live (self-updating) app can't hang forever.
    def settle: IO[Unit] = {
      val step = 2.millis
      val stableWindow = 50.millis
      val stableReads = (stableWindow / step).toInt
      def loop(prev: Node, stable: Int, budget: Int): IO[Unit] =
        if (budget <= 0) IO.unit
        else
          IO.cede *> IO.sleep(step) *> tree.flatMap { cur =>
            if (cur != prev) loop(cur, stable = 0, budget - 1)
            else if (stable + 1 >= stableReads) IO.unit
            else loop(cur, stable + 1, budget - 1)
          }
      tree.flatMap(loop(_, stable = 0, budget = 5000))
    }

    def tree: IO[Node] = root.snapshot

    def textOf(id: String): IO[Option[String]] =
      findAll(_.id.contains(id)).map(_.headOption.flatMap(_.text))

    def valueOf(id: String): IO[Option[String]] =
      findAll(_.id.contains(id)).map(_.headOption.flatMap(_.value))

    def findByText(text: String): IO[Option[Node]] =
      findAll(_.text.contains(text)).map(_.headOption)

    def findAll(p: Node => Boolean): IO[List[Node]] = tree.map(allNodes(_).filter(p))

    def clickText(text: String): IO[Unit] =
      findByText(text).flatMap {
        case Some(n) =>
          n.id match {
            case Some(id) => click(id)
            case None => IO.raiseError(new NoSuchElementException(s"node with text '$text' has no id"))
          }
        case None => IO.raiseError(new NoSuchElementException(s"no node with text '$text'"))
      }

    def recorded: IO[Recorded] = state.get

  }

}
