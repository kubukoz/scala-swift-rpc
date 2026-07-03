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

package ssr

import cats.effect.*
import cats.effect.std.Hotswap
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.MenuItemStyle
import ssr.internal.protocol.SetStatusItemInput

// High-level menu-bar status item. Instead of hand-building `SetStatusItemInput`
// / `List[MenuItem]` with string ids and registering matching bus handlers
// (the pre-`onClick` model), you describe entries with their click handlers
// inline — same ergonomics as `onClick` on a button.
//
//   for {
//     si <- ssr.statusItem("☰",
//       menu.item("Refresh")(refresh),
//       menu.separator,
//       menu.item("Quit")(ctx.emit.quit().void),
//     )
//   } yield App(statusItem = si, ...)
//
// `statusItem(...)` returns a `Resource[IO, Signal[IO, Option[SetStatusItemInput]]]`:
// hand the signal to `App(statusItem = ...)`; the click handlers stay registered
// on the bus for the resource's scope.

// One entry in a menu — shared by the menu-bar status item (`statusItem`) and
// the application menu (`appMenu`).
sealed trait StatusMenuItem

object StatusMenuItem {

  // A clickable row. `onClick = None` renders a plain disabled-looking row that
  // does nothing (combine with `enabled = false` for a greyed-out entry). `key`
  // is a keyboard-shortcut spec like "cmd+q" (honored in the app menu).
  final case class Item(
    title: String,
    enabled: Boolean = true,
    key: Option[String] = None,
    onClick: Option[IO[Unit]] = None,
  ) extends StatusMenuItem

  // A bold, non-clickable section title (MenuItemStyle.HEADER).
  final case class Header(title: String) extends StatusMenuItem

  // A divider line.
  case object Separator extends StatusMenuItem

  // A nested dropdown; its children carry their own handlers. Also the shape of
  // a top-level app menu (e.g. the "File" menu), built via `menu.menu`.
  final case class Submenu(title: String, children: List[StatusMenuItem]) extends StatusMenuItem
}

// DSL for building menu entries. Mirrors the tag DSL in `object ui`. Shared by
// `statusItem` (menu-bar dropdown) and `appMenu` (application menu bar).
object menu {

  def item(
    title: String,
    enabled: Boolean = true,
    key: Option[String] = None,
  )(
    onClick: IO[Unit]
  ): StatusMenuItem = StatusMenuItem.Item(title, enabled, key, Some(onClick))

  // A non-clickable row (no handler). Useful for informational lines.
  def text(title: String, enabled: Boolean = true): StatusMenuItem =
    StatusMenuItem.Item(title, enabled, None, None)

  def header(title: String): StatusMenuItem = StatusMenuItem.Header(title)

  val separator: StatusMenuItem = StatusMenuItem.Separator

  def submenu(title: String)(children: StatusMenuItem*): StatusMenuItem =
    StatusMenuItem.Submenu(title, children.toList)

  // A top-level application menu (e.g. "File", "Edit"). Same shape as a submenu;
  // named for readability at the `appMenu(...)` call site.
  def menu(title: String)(children: StatusMenuItem*): StatusMenuItem =
    StatusMenuItem.Submenu(title, children.toList)
}

// Shared renderer for the `menu` DSL: assigns ids depth-first, registers each
// handler-bearing item on the bus for the render's Resource scope, and yields
// the wire `MenuItem` tree. Both `statusItem` and `appMenu` build on this.
private object MenuRender {

  def render(entries: List[StatusMenuItem])(using ctx: SSR): Resource[IO, List[MenuItem]] =
    entries.traverse(one)

  private def one(entry: StatusMenuItem)(using ctx: SSR): Resource[IO, MenuItem] = entry match {
    case StatusMenuItem.Separator =>
      Resource.pure(MenuItem(title = "", separator = Some(true)))

    case StatusMenuItem.Header(t) =>
      Resource.pure(MenuItem(title = t, style = Some(MenuItemStyle.HEADER)))

    case StatusMenuItem.Submenu(t, children) =>
      render(children).map(cs => MenuItem(title = t, children = Some(cs)))

    case StatusMenuItem.Item(t, enabled, key, onClick) =>
      onClick match {
        case None =>
          Resource.pure(MenuItem(title = t, key = key, enabled = Some(enabled)))
        case Some(handler) =>
          Resource.eval(ctx.idGen.next).flatMap { id =>
            ctx
              .bus
              .register(id, ev => IO.whenA(ev.event == "click")(handler))
              .as(MenuItem(title = t, id = Some(id), key = key, enabled = Some(enabled)))
          }
      }
  }

  // Drive a reactive `Signal[IO, List[StatusMenuItem]]` through the renderer:
  // each update produces a fresh handler batch installed via Hotswap (releasing
  // the previous one atomically), and its rendered value is pushed to the
  // returned output signal. `wrap` maps a rendered tree to the output element.
  def driven[A](
    items: Signal[IO, List[StatusMenuItem]]
  )(
    wrap: List[MenuItem] => A
  )(
    using ctx: SSR
  ): Resource[IO, Signal[IO, A]] =
    (Hotswap.create[IO, List[MenuItem]], items.getAndDiscreteUpdates).flatMapN {
      (hotswap, initialAndUpdates) =>
        val (initial, updates) = initialAndUpdates
        Resource.eval(hotswap.swap(render(initial))).flatMap { first =>
          Resource.eval(SignallingRef.of[IO, A](wrap(first))).flatMap { out =>
            updates
              .evalMap(entries => hotswap.swap(render(entries)).flatMap(mis => out.set(wrap(mis))))
              .compile
              .drain
              .background
              .as(out)
          }
        }
    }
}

object statusItem {

  // Static content — the common case.
  def apply(
    title: String,
    items: StatusMenuItem*
  )(
    using ctx: SSR
  ): Resource[IO, Signal[IO, Option[SetStatusItemInput]]] =
    apply(title, Signal.constant[IO, List[StatusMenuItem]](items.toList))

  // Reactive content: the menu re-renders whenever `items` changes. Handlers are
  // installed for the current render and swapped atomically on each update (see
  // `MenuRender.driven`). Re-renders are cheap, so no keyed diffing is needed.
  def apply(
    title: String,
    items: Signal[IO, List[StatusMenuItem]],
  )(
    using ctx: SSR
  ): Resource[IO, Signal[IO, Option[SetStatusItemInput]]] =
    MenuRender.driven(items)(mis => Some(SetStatusItemInput(title = Some(title), menu = Some(mis))))
}

// High-level application menu (the OS menu bar). Same `menu` DSL as `statusItem`
// with inline click handlers — no more hand-built `MenuItem` ids matched against
// manual `bus.register` calls. Top-level menus are `menu.menu("File")(...)`.
//
//   for {
//     m <- appMenu(
//       menu.menu("MyApp")(
//         menu.item("Settings…", key = Some("cmd+,"))(openSettings),
//         menu.separator,
//         menu.item("Quit", key = Some("cmd+q"))(ctx.emit.quit().void),
//       )
//     )
//   } yield App(menu = m, ...)
//
// Returns `Resource[IO, Signal[IO, List[MenuItem]]]` — hand the signal to
// `App(menu = ...)`; handlers stay registered for the resource's scope.
object appMenu {

  // Static content — the common case.
  def apply(
    menus: StatusMenuItem*
  )(
    using ctx: SSR
  ): Resource[IO, Signal[IO, List[MenuItem]]] =
    apply(Signal.constant[IO, List[StatusMenuItem]](menus.toList))

  // Reactive content: the menu re-renders whenever `menus` changes.
  def apply(
    menus: Signal[IO, List[StatusMenuItem]]
  )(
    using ctx: SSR
  ): Resource[IO, Signal[IO, List[MenuItem]]] = MenuRender.driven(menus)(identity)
}
