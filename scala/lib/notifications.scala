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
import ssr.internal.protocol.NotificationCategory
import ssr.internal.protocol.UiCommands

// User-facing notification capability. Wraps the notification commands on the
// emit client and routes taps (event/notificationTapped) back through the
// EventBus, keyed by categoryId — the same mechanism onClick uses for node ids.
trait Notifications {

  // Ask the OS for notification authorization; true if granted.
  def requestAuth: IO[Boolean]

  // Declare tappable action buttons once, grouped by category. Send before the
  // first `notify`/`scheduleReminder` that references a category.
  def setCategories(categories: List[NotificationCategory]): IO[Unit]

  // Post a banner now. `categoryId` ties it to actions from `setCategories`.
  def notify(title: String, body: String, categoryId: Option[String] = None): IO[Unit]

  // Register a repeating daily reminder at hour:minute. Idempotent per `id`.
  def scheduleReminder(
    id: String,
    title: String,
    body: String,
    hour: Int,
    minute: Int,
    categoryId: Option[String] = None,
  ): IO[Unit]

  // Handle taps for a category. `actionId` is an action's id, or "default" for
  // a tap on the notification body. Lives for the returned Resource's scope.
  def onTapped(categoryId: String)(handler: String => IO[Unit]): Resource[IO, Unit]

}

object Notifications {

  def apply(emit: UiCommands[IO], bus: EventBus): Notifications =
    new Notifications {

      def requestAuth: IO[Boolean] = emit.requestNotificationAuth().map(_.granted)

      def setCategories(categories: List[NotificationCategory]): IO[Unit] =
        emit.setNotificationCategories(categories).void

      def notify(title: String, body: String, categoryId: Option[String]): IO[Unit] =
        emit._notify(title, body, categoryId).void

      def scheduleReminder(
        id: String,
        title: String,
        body: String,
        hour: Int,
        minute: Int,
        categoryId: Option[String],
      ): IO[Unit] = emit.scheduleReminder(id, title, body, hour, minute, categoryId).void

      def onTapped(categoryId: String)(handler: String => IO[Unit]): Resource[IO, Unit] =
        bus.register(
          categoryId,
          ev => IO.whenA(ev.event == "notificationTapped")(handler(ev.value.getOrElse("default"))),
        )

    }

}
