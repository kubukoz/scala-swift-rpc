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

import cats.effect.IO
import fs2.Pipe
import fs2.Stream

// The raw byte channel between the Scala child and the Swift host. Carries
// LSP-framed JSON-RPC messages (Content-Length + body); the framing itself is
// applied one layer up (see `Main.run`). Two transports exist:
//
//   - `Transport.stdio` — the classic model, Scala runs as a child process and
//     the host pipes over stdin/stdout.
//   - the FFI transport (native only, `ssr.ffi`) — Scala is linked into the
//     host binary as a library and messages flow through shared ring buffers,
//     no subprocess. This is what enables single-binary distribution (and,
//     eventually, iOS / the App Store where subprocesses aren't allowed).
trait Transport {
  // Framed message bytes arriving FROM the host.
  def fromHost: Stream[IO, Byte]
  // Framed message bytes going TO the host.
  def toHost: Pipe[IO, Byte, Nothing]
}

object Transport {

  val stdio: Transport =
    new Transport {
      def fromHost: Stream[IO, Byte] = fs2.io.stdin[IO](4096)
      def toHost: Pipe[IO, Byte, Nothing] = fs2.io.stdout[IO]
    }

}
