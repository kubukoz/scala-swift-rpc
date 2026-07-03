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
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import munit.CatsEffectSuite
import ssr.*

class TestHarnessTests extends CatsEffectSuite {

  // A tiny counter app: a reactive label bound to a Ref, and a button that
  // increments it. This is exactly the shape a downstream app author would
  // test through the harness.
  private def counterApp: SSR => Resource[IO, App] = { _ =>
    SignallingRef.of[IO, Int](0).toResource.map { count =>
      App(
        window = Signal.constant(ssr.internal.protocol.SetWindowInput(300, 200)),
        menu = Signal.constant(Nil),
        component = ui.vstack(
          ui.label(count.map(_.toString)),
          ui.button("Increment", onClick(count.update(_ + 1))),
        ),
      )
    }
  }

  test("initial mount snapshot reflects the app's starting state") {
    TestHarness.of(counterApp).use { h =>
      for {
        rec <- h.recorded
        _ = assertEquals(rec.mounts.length, 1)
        _ = assertEquals(rec.windows.headOption.map(_.width), Some(300.0))
        label <- h.findByText("0")
      } yield assert(label.isDefined, "expected a label showing 0")
    }
  }

  test("clicking the button drives the reactive label and emits a patch") {
    TestHarness.of(counterApp).use { h =>
      for {
        _ <- h.clickText("Increment")
        _ <- h.clickText("Increment")
        // The live tree reflects the new value...
        one <- h.findByText("2")
        _ = assert(one.isDefined, "label should show 2 after two clicks")
        // ...and the runtime emitted setText patches post-mount.
        rec <- h.recorded
        setTexts = rec.patches.filter(_.op == "setText").map(_.value)
      } yield assertEquals(setTexts, List(Some("1"), Some("2")))
    }
  }

  test("input events route to onInput handlers") {
    val app: SSR => Resource[IO, App] = { _ =>
      SignallingRef.of[IO, String]("").toResource.map { typed =>
        App(
          window = Signal.constant(ssr.internal.protocol.SetWindowInput(300, 200)),
          menu = Signal.constant(Nil),
          component = ui.vstack(
            ui.textfield(onInput(typed.set)),
            ui.label(typed.map(s => s"you typed: $s")),
          ),
        )
      }
    }
    TestHarness.of(app).use { h =>
      for {
        // The textfield is the first node with a registered input handler; find
        // it by tag and grab its id.
        fields <- h.findAll(_.tag == "textfield")
        id = fields.head.id.get
        _ <- h.input(id, "hello")
        echoed <- h.findByText("you typed: hello")
      } yield assert(echoed.isDefined, "label should echo the typed text")
    }
  }

}
