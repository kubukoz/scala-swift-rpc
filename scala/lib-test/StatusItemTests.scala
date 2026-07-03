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
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import munit.CatsEffectSuite
import ssr.internal.protocol.SetStatusItemInput

class StatusItemTests extends CatsEffectSuite {

  // Build a minimal SSR context. `statusItem` only touches `bus` and `idGen`;
  // `emit`, `windowFrame` and `notifications` are never called here, so stubs
  // that throw make an accidental dependency on them fail loudly.
  private def mkCtx: IO[SSR] =
    (EventBus.make, IdGen.make).mapN { (bus, idGen) =>
      SSR(
        bus = bus,
        emit = null,
        windowFrame = null,
        idGen = idGen,
        notifications = null,
      )
    }

  // Pull the menu-item ids (handler-bearing entries carry an id) out of a
  // rendered status input, in order.
  private def idsOf(si: Option[SetStatusItemInput]): List[String] =
    si.flatMap(_.menu).getOrElse(Nil).flatMap(_.id)

  test("click on a live menu item fires its inline handler") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        statusItem("☰", menu.item("Go")(clicks.update(_ + 1))).use { sig =>
          for {
            si <- sig.get
            id = idsOf(si).head
            _ <- ctx.bus.fire(UiEvent(id = id, event = "click"))
            n <- clicks.get
          } yield assertEquals(n, 1)
        }
      }
    }
  }

  test("handlers are released when the Resource scope closes") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        // Capture the id inside the scope, then fire it after the scope closes.
        statusItem("☰", menu.item("Go")(clicks.update(_ + 1)))
          .use(_.get.map(idsOf(_).head))
          .flatMap { id =>
            ctx.bus.fire(UiEvent(id = id, event = "click")) *>
              clicks.get.map(assertEquals(_, 0, "handler should be deregistered after scope"))
          }
      }
    }
  }

  test("signal update re-renders, releases old handlers and registers new ids") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      (
        SignallingRef.of[IO, String]("a"),
        Ref.of[IO, List[String]](Nil),
      ).flatMapN { (state, log) =>
        val items: Signal[IO, List[StatusMenuItem]] =
          state.map(s => List(menu.item(s)(log.update(_ :+ s))))

        statusItem("☰", items).use { sig =>
          for {
            si0 <- sig.get
            id0 = idsOf(si0).head
            // Change the source signal → menu re-renders.
            _ <- state.set("b")
            si1 <- sig.discrete.dropWhile(idsOf(_) == List(id0)).head.compile.lastOrError
            id1 = idsOf(si1).head
            _ <- IO(assertNotEquals(id0, id1, "re-render allocates a fresh id"))
            // Old id no longer routes; new id fires the new handler.
            _ <- ctx.bus.fire(UiEvent(id = id0, event = "click"))
            _ <- ctx.bus.fire(UiEvent(id = id1, event = "click"))
            fired <- log.get
          } yield assertEquals(fired, List("b"), "only the current handler should fire")
        }
      }
    }
  }

  test("non-click events do not fire the click handler") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        statusItem("☰", menu.item("Go")(clicks.update(_ + 1))).use { sig =>
          for {
            si <- sig.get
            id = idsOf(si).head
            _ <- ctx.bus.fire(UiEvent(id = id, event = "input", value = Some("x")))
            n <- clicks.get
          } yield assertEquals(n, 0)
        }
      }
    }
  }

  test("headers/separators/text carry no id; submenu handlers are registered") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        statusItem(
          "☰",
          menu.header("Section"),
          menu.text("info"),
          menu.separator,
          menu.submenu("More")(menu.item("Nested")(clicks.update(_ + 1))),
        ).use { sig =>
          for {
            si <- sig.get
            menuItems = si.flatMap(_.menu).getOrElse(Nil)
            // header/text/separator have no ids; only the nested item does.
            _ <- IO(assertEquals(idsOf(si), Nil, "top-level entries carry no ids"))
            nestedId = menuItems
              .flatMap(_.children.getOrElse(Nil))
              .flatMap(_.id)
              .head
            _ <- ctx.bus.fire(UiEvent(id = nestedId, event = "click"))
            n <- clicks.get
          } yield assertEquals(n, 1, "submenu item handler should fire")
        }
      }
    }
  }

  test("menu.item carries its key through to the wire MenuItem") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      statusItem("☰", menu.item("Quit", key = Some("cmd+q"))(IO.unit)).use { sig =>
        sig.get.map { si =>
          val keys = si.flatMap(_.menu).getOrElse(Nil).flatMap(_.key)
          assertEquals(keys, List("cmd+q"))
        }
      }
    }
  }

  test("appMenu: click on a live item in a top-level menu fires its handler") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        appMenu(
          menu.menu("App")(
            menu.item("Quit", key = Some("cmd+q"))(clicks.update(_ + 1))
          )
        ).use { sig =>
          for {
            menus <- sig.get
            // top-level "App" menu → its child "Quit" carries the handler id.
            quitId = menus.flatMap(_.children.getOrElse(Nil)).flatMap(_.id).head
            _ <- ctx.bus.fire(UiEvent(id = quitId, event = "click"))
            n <- clicks.get
          } yield assertEquals(n, 1)
        }
      }
    }
  }

  test("appMenu: handlers released when scope closes") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      Ref.of[IO, Int](0).flatMap { clicks =>
        appMenu(menu.menu("App")(menu.item("Go")(clicks.update(_ + 1))))
          .use(_.get.map(_.flatMap(_.children.getOrElse(Nil)).flatMap(_.id).head))
          .flatMap { id =>
            ctx.bus.fire(UiEvent(id = id, event = "click")) *>
              clicks.get.map(assertEquals(_, 0, "handler should be deregistered after scope"))
          }
      }
    }
  }

  test("appMenu: reactive update releases old handlers and registers new ids") {
    mkCtx.flatMap { ctx =>
      given SSR = ctx
      (
        SignallingRef.of[IO, String]("a"),
        Ref.of[IO, List[String]](Nil),
      ).flatMapN { (state, log) =>
        def childId(menus: List[ssr.internal.protocol.MenuItem]): String =
          menus.flatMap(_.children.getOrElse(Nil)).flatMap(_.id).head

        val menus = state.map(s => List(menu.menu("App")(menu.item(s)(log.update(_ :+ s)))))

        appMenu(menus).use { sig =>
          for {
            m0 <- sig.get
            id0 = childId(m0)
            _ <- state.set("b")
            m1 <- sig.discrete.dropWhile(childId(_) == id0).head.compile.lastOrError
            id1 = childId(m1)
            _ <- IO(assertNotEquals(id0, id1))
            _ <- ctx.bus.fire(UiEvent(id = id0, event = "click"))
            _ <- ctx.bus.fire(UiEvent(id = id1, event = "click"))
            fired <- log.get
          } yield assertEquals(fired, List("b"), "only the current handler should fire")
        }
      }
    }
  }

}
