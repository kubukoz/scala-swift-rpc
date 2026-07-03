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

// A scope into which Resources can be allocated dynamically and released
// individually, while any still-live entries are released when the
// supervisor itself is finalized.
//
// Stand-in for the missing primitive tracked in:
//   https://github.com/typelevel/cats-effect/issues/3376
//
// Each `allocate` returns the value plus an `F[Unit]` that releases just
// that entry (and removes it from the supervisor). Releasing the
// supervisor's outer Resource finalizes everything still registered.
trait ResourceSupervisor[F[_]] {
  def allocate[A](r: Resource[F, A]): F[(A, F[Unit])]
}

object ResourceSupervisor {

  def apply[F[_]](using F: Concurrent[F]): Resource[F, ResourceSupervisor[F]] = Resource
    .eval((Ref.of[F, Map[Long, F[Unit]]](Map.empty), Ref.of[F, Long](0L)).tupled)
    .flatMap { case (finalizers, counter) =>
      val release: F[Unit] = finalizers
        .getAndSet(Map.empty)
        .flatMap(_.values.toList.traverse_(_.attempt.void))

      Resource.make(F.pure(new Impl(finalizers, counter): ResourceSupervisor[F]))(_ => release)
    }

  private final class Impl[F[_]](
    finalizers: Ref[F, Map[Long, F[Unit]]],
    counter: Ref[F, Long],
  )(
    using F: Concurrent[F]
  ) extends ResourceSupervisor[F] {

    def allocate[A](r: Resource[F, A]): F[(A, F[Unit])] = F.uncancelable { _ =>
      counter.updateAndGet(_ + 1).flatMap { key =>
        r.allocated.flatMap { case (a, fin) =>
          val releaseOne: F[Unit] = finalizers.update(_.removed(key)) *> fin
          finalizers.update(_.updated(key, fin)).as(a -> releaseOne)
        }
      }
    }

  }

}
