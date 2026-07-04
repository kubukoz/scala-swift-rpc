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
import cats.effect.unsafe.implicits.global
import fs2.Chunk
import fs2.Pipe
import fs2.Stream

import scala.scalanative.libc.stdlib
import scala.scalanative.unsafe.*

// Direct binding to Scala Native's GC thread-state switch. The runtime uses
// this internally around `@blocking` extern calls; we need it because the
// FOREIGN (Swift) thread that calls our exported `ssr_init` gets registered as
// a Managed mutator, then returns into AppKit's run loop and blocks in
// `mach_msg` forever — never polling a GC safepoint, which deadlocks
// stop-the-world collection. Switching it to Unmanaged (state 1) before we
// hand control back tells the GC to ignore it. The app itself runs on Cats
// Effect's own worker threads, which poll safepoints normally.
@extern
private object GCState {
  @name("scalanative_GC_set_mutator_thread_state")
  def setMutatorThreadState(newState: CInt): Unit = extern
}

// ============================================================================
// FFI transport (Scala Native only).
//
// Instead of running as a child process talking over stdin/stdout, the whole
// Scala app is linked into the Swift host as a native library and messages
// flow through two shared-memory ring buffers — no subprocess, one binary.
// This is what makes single-binary distribution possible, and is the path to
// platforms (iOS / the App Store) where spawning subprocesses isn't allowed.
//
// Memory ownership: both rings are malloc'd on the native heap by `ssr_init`,
// so they never move (no GC relocation) and outlive individual calls. Swift
// receives their base pointers and reads/writes them directly. Each ring is
// single-producer / single-consumer with atomic head/tail — lock-free, no
// mutex crosses the language boundary.
//
//   inRing  — host -> Scala  (Swift is producer, Scala consumer)
//   outRing — Scala -> host  (Scala is producer, Swift consumer)
//
// Bytes on the rings are LSP-framed JSON-RPC messages, exactly what stdio
// carried; `Main.run` layers framing/decoding on top identically.
// ============================================================================

// Layout of a ring in native memory, as a byte offset table. Both sides
// (this file and swift/main.swift) MUST agree on this. A ring is a header
// followed by `capacity` bytes of data:
//
//   offset 0  : Long capacity   (data byte count, power of two)
//   offset 8  : Long head       (read index, monotonically increasing)
//   offset 16 : Long tail       (write index, monotonically increasing)
//   offset 24 : data[capacity]
//
// head/tail are free-running counters; the live region is [head, tail) and the
// physical slot for index i is data[i & (capacity - 1)]. A single producer
// only advances tail; a single consumer only advances head. Because there is
// exactly one writer per index, plain aligned 64-bit loads/stores with the
// right ordering are sufficient (the producer publishes data before bumping
// tail; the consumer reads head before consuming) — we use atomic
// load/store via the Native intrinsics to get that ordering.
private final class SpscRing(val base: Ptr[Byte], val capacity: Long) {
  private val headPtr: Ptr[Long] = (base + 8).asInstanceOf[Ptr[Long]]
  private val tailPtr: Ptr[Long] = (base + 16).asInstanceOf[Ptr[Long]]
  private val data: Ptr[Byte] = base + 24
  private val mask: Long = capacity - 1

  // head/tail are single-writer counters. The producer writes the data byte
  // then bumps tail; the consumer reads head, reads the data, then bumps head.
  // Program order plus the one-writer-per-index invariant makes plain aligned
  // 64-bit access sufficient here — no torn reads, and each side only ever
  // sees the other's counter move forward.
  private def head: Long = !headPtr
  private def tail: Long = !tailPtr
  private def publishHead(v: Long): Unit = !headPtr = v
  private def publishTail(v: Long): Unit = !tailPtr = v

  def isEmpty: Boolean = head == tail

  // Consumer side: copy up to the available bytes into a fresh Array[Byte].
  // Returns an empty array when the ring is drained.
  def read(): Array[Byte] = {
    val h = head
    val t = tail
    val avail = (t - h).toInt
    if (avail <= 0) Array.emptyByteArray
    else {
      val out = new Array[Byte](avail)
      var i = 0
      while (i < avail) {
        val slot = ((h + i) & mask).toInt
        out(i) = data(slot)
        i += 1
      }
      // publish consumption AFTER reading the data
      publishHead(h + avail)
      out
    }
  }

  // Producer side: write the whole chunk, blocking (spin) while the ring is
  // full. Messages are small and the consumer is fast, so contention is rare.
  def write(bytes: Array[Byte]): Unit = {
    var i = 0
    val n = bytes.length
    while (i < n) {
      val h = head
      val t = tail
      val used = t - h
      if (used >= capacity) {
        // full — busy-spin briefly while the consumer drains. Messages are
        // small and the consumer is a tight loop, so this is rarely hit.
        ()
      } else {
        val slot = (t & mask).toInt
        data(slot) = bytes(i)
        // publish this byte AFTER writing it
        publishTail(t + 1)
        i += 1
      }
    }
  }
}

private object SpscRing {
  // 1 MiB of data per ring by default — comfortably larger than any single
  // mount snapshot, and cheap.
  val Capacity: Long = 1L << 20
  val HeaderBytes: Long = 24

  def alloc(): SpscRing = {
    val total = HeaderBytes + Capacity
    val base = stdlib.malloc(total)
    // zero the header (capacity/head/tail)
    val cap = base.asInstanceOf[Ptr[Long]]
    !cap = Capacity
    !((base + 8).asInstanceOf[Ptr[Long]]) = 0L
    !((base + 16).asInstanceOf[Ptr[Long]]) = 0L
    new SpscRing(base, Capacity)
  }
}

// A Transport backed by two SPSC rings. Reading from the host = draining the
// in-ring; writing = pushing into the out-ring.
private final class FfiTransport(inRing: SpscRing, outRing: SpscRing) extends Transport {
  import scala.concurrent.duration.*

  def fromHost: Stream[IO, Byte] =
    Stream
      .repeatEval(IO.blocking(inRing.read()))
      .flatMap { arr =>
        if (arr.isEmpty) Stream.exec(IO.sleep(1.millis))
        else Stream.chunk(Chunk.array(arr))
      }

  def toHost: Pipe[IO, Byte, Nothing] =
    _.chunks.foreach(chunk => IO.blocking(outRing.write(chunk.toArray))).drain
}

// ============================================================================
// The embedding entry point. An app links itself as a static library and
// exposes a single C function the host calls:
//
//   ssr_init() -> handle Ptr to a native struct { inRing*, outRing* }
//
// It allocates the rings, starts the Cats Effect runtime + the app on a
// background thread (so it returns immediately and the host's run loop keeps
// spinning), and hands the ring pointers back. The host then reads them at
// fixed offsets — no further exports needed. See `SsrFfiApp` for how an app
// declares its `@exported ssr_init`.
//
// Handle layout (what the host reads):
//   offset 0 : Ptr  inRing  base   (host -> Scala ring)
//   offset 8 : Ptr  outRing base   (Scala -> host ring)
// ============================================================================
object Ffi {

  // Allocate the rings, boot the app on the CE runtime, and return the handle
  // struct. Called from an app's `@exported ssr_init`.
  def boot(factory: SSR => cats.effect.Resource[IO, App]): Ptr[Byte] = {
    val in = SpscRing.alloc()
    val out = SpscRing.alloc()
    val transport = new FfiTransport(in, out)
    // Fire-and-forget the app on the CE runtime; it runs until the host exits.
    App.run(factory, transport).unsafeRunAndForget()

    val handle = stdlib.malloc(sizeof[Ptr[Byte]].toLong * 2L)
    val slots = handle.asInstanceOf[Ptr[Ptr[Byte]]]
    slots(0) = in.base
    slots(1) = out.base

    // The caller is Swift's thread; it's about to return into AppKit and never
    // run Scala again. Detach it from the GC so stop-the-world never waits on
    // it. (Managed = 0, Unmanaged = 1.)
    GCState.setMutatorThreadState(1)
    handle
  }
}

// Mix this into an app object to give it an `@exported ssr_init` for the host
// to call — the FFI analogue of `SSRApp`'s `IOApp.Simple` main. `@exported`
// must sit on a concrete method, so the app writes the one-line forwarder:
//
//   object MyApp extends SsrFfiApp {
//     def render(ctx: SSR): Resource[IO, App] = ...
//     @exported("ssr_init") def ssrInit(): Ptr[Byte] = boot()
//   }
trait SsrFfiApp {
  def render(ctx: SSR): cats.effect.Resource[IO, App]
  final def boot(): Ptr[Byte] = Ffi.boot(render)
}
