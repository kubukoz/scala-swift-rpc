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
import ssr.internal.protocol.UiCommands
import ssr.internal.protocol.UiCommandsGen
import ssr.internal.protocol.WindowFrame
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.fs2.catsMonadic
import jsonrpclib.smithy4sinterop.ClientStub

trait IdGen {
  def next: IO[String]
}

object IdGen {
  def make: IO[IdGen] = Ref.of[IO, Long](0L).map { ref =>
    new IdGen {
      def next: IO[String] = ref.updateAndGet(_ + 1).map(n => s"n$n")
    }
  }
}

// An event flowing from the host into a node. Routed by id.
final case class UiEvent(id: String, event: String, value: Option[String] = None)

object Emit {

  def fromChannel(ch: FS2Channel[IO]): IO[UiCommands[IO]] = IO.fromEither(
    ClientStub(UiCommandsGen, ch).leftMap(err => new RuntimeException(err.toString))
  )

}

trait EventBus {
  def fire(ev: UiEvent): IO[Unit]
  def register(id: String, handler: UiEvent => IO[Unit]): Resource[IO, Unit]
}

object EventBus {

  def make: IO[EventBus] = Ref.of[IO, Map[String, UiEvent => IO[Unit]]](Map.empty).map { ref =>
    new EventBus {

      def fire(ev: UiEvent): IO[Unit] = ref.get.flatMap(_.get(ev.id).traverse_(_(ev)))

      def register(id: String, handler: UiEvent => IO[Unit]): Resource[IO, Unit] = Resource.make(
        ref.update(_.updated(id, handler))
      )(_ => ref.update(_.removed(id)))

    }
  }

}

final case class SSR(
  bus: EventBus,
  emit: UiCommands[IO],
  windowFrame: Signal[IO, WindowFrame],
  idGen: IdGen,
)
