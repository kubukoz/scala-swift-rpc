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
import munit.CatsEffectSuite
import ssr.internal.protocol.NotificationCategory
import ssr.internal.protocol.RequestNotificationAuthOutput
import ssr.internal.protocol.UiCommands
import ssr.internal.protocol.UiCommandsGen

class NotificationsTests extends CatsEffectSuite {

  // A recording `UiCommands[IO]` that captures the notification-related calls
  // the `Notifications` wrapper makes. Every other operation is left as the
  // `Default` stub (returns `IO.stub`), so an accidental call fails loudly.
  final case class Recorded(
    categories: List[List[NotificationCategory]] = Nil,
    notified: List[(String, String, Option[String])] = Nil,
    reminders: List[(String, String, String, Int, Int, Option[String])] = Nil,
    authRequested: Int = 0,
  )

  private def recordingClient(ref: Ref[IO, Recorded], granted: Boolean): UiCommands[IO] =
    new UiCommandsGen.Default[IO](IO.stub) {
      override def requestNotificationAuth()
        : IO[RequestNotificationAuthOutput] =
        ref.update(r => r.copy(authRequested = r.authRequested + 1)) *>
          IO.pure(RequestNotificationAuthOutput(granted))

      override def setNotificationCategories(
        categories: List[NotificationCategory]
      ): IO[Unit] = ref.update(r => r.copy(categories = r.categories :+ categories))

      override def _notify(
        title: String,
        body: String,
        categoryId: Option[String],
      ): IO[Unit] = ref.update(r => r.copy(notified = r.notified :+ (title, body, categoryId)))

      override def scheduleReminder(
        id: String,
        title: String,
        body: String,
        hour: Int,
        minute: Int,
        categoryId: Option[String],
      ): IO[Unit] =
        ref.update(r => r.copy(reminders = r.reminders :+ (id, title, body, hour, minute, categoryId)))
    }

  private def setup(granted: Boolean = true): IO[(Notifications, EventBus, Ref[IO, Recorded])] =
    (Ref.of[IO, Recorded](Recorded()), EventBus.make).flatMapN { (ref, bus) =>
      IO.pure((Notifications(recordingClient(ref, granted), bus), bus, ref))
    }

  test("requestAuth forwards the granted flag from the client") {
    setup(granted = true).flatMap { case (n, _, ref) =>
      for {
        g <- n.requestAuth
        rec <- ref.get
      } yield {
        assertEquals(g, true)
        assertEquals(rec.authRequested, 1)
      }
    } *>
      setup(granted = false).flatMap(_._1.requestAuth.map(assertEquals(_, false)))
  }

  test("setCategories / notify / scheduleReminder reach the client verbatim") {
    setup().flatMap { case (n, _, ref) =>
      val cats = List(NotificationCategory(id = "c", actions = Nil))
      for {
        _ <- n.setCategories(cats)
        _ <- n.notify("Hi", "there", Some("c"))
        _ <- n.scheduleReminder("r1", "Daily", "body", 9, 30, Some("c"))
        rec <- ref.get
      } yield {
        assertEquals(rec.categories, List(cats))
        assertEquals(rec.notified, List(("Hi", "there", Some("c"))))
        assertEquals(rec.reminders, List(("r1", "Daily", "body", 9, 30, Some("c"))))
      }
    }
  }

  test("onTapped handler fires for a matching category with the action id") {
    setup().flatMap { case (n, bus, _) =>
      Ref.of[IO, List[String]](Nil).flatMap { taps =>
        n.onTapped("cat")(a => taps.update(_ :+ a)).use { _ =>
          for {
            _ <- bus.fire(UiEvent(id = "cat", event = "notificationTapped", value = Some("snooze")))
            got <- taps.get
          } yield assertEquals(got, List("snooze"))
        }
      }
    }
  }

  test("onTapped defaults actionId to \"default\" when the value is absent") {
    setup().flatMap { case (n, bus, _) =>
      Ref.of[IO, List[String]](Nil).flatMap { taps =>
        n.onTapped("cat")(a => taps.update(_ :+ a)).use { _ =>
          bus.fire(UiEvent(id = "cat", event = "notificationTapped", value = None)) *>
            taps.get.map(assertEquals(_, List("default")))
        }
      }
    }
  }

  test("onTapped ignores non-tap events and other categories") {
    setup().flatMap { case (n, bus, _) =>
      Ref.of[IO, Int](0).flatMap { count =>
        n.onTapped("cat")(_ => count.update(_ + 1)).use { _ =>
          for {
            // wrong event on the right id
            _ <- bus.fire(UiEvent(id = "cat", event = "click"))
            // right event on a different id (no handler registered there)
            _ <- bus.fire(UiEvent(id = "other", event = "notificationTapped", value = Some("x")))
            n0 <- count.get
          } yield assertEquals(n0, 0)
        }
      }
    }
  }

  test("onTapped deregisters the handler when its scope closes") {
    setup().flatMap { case (n, bus, _) =>
      Ref.of[IO, Int](0).flatMap { count =>
        n.onTapped("cat")(_ => count.update(_ + 1)).use_ *>
          bus.fire(UiEvent(id = "cat", event = "notificationTapped", value = Some("x"))) *>
          count.get.map(assertEquals(_, 0, "handler should be gone after scope"))
      }
    }
  }

}
