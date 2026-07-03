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
import fs2.Stream
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Files
import fs2.io.file.Path
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.SetWindowInput
import ssr.internal.protocol.UiEvents
import ssr.internal.protocol.WindowFrame
import io.circe.Printer
import io.circe.parser
import jsonrpclib.fs2.FS2Channel
import jsonrpclib.fs2.catsMonadic
import jsonrpclib.fs2.lsp
import jsonrpclib.smithy4sinterop.CirceJsonCodec
import jsonrpclib.smithy4sinterop.ServerEndpoints

final case class App(
  window: Signal[IO, SetWindowInput],
  menu: Signal[IO, List[MenuItem]],
  component: Component,
)

object App {
  val QuitMenuId: String = "app:quit"

  private val frameCodec: io.circe.Codec[WindowFrame] = CirceJsonCodec.fromSchema[WindowFrame]

  private val stateDir: Path =
    Path(java.lang.System.getProperty("user.home")) / ".local" / "state" / "ssr"

  private val stateFile: Path = stateDir / "window.json"

  def loadFrame: IO[Option[WindowFrame]] = Files[IO]
    .exists(stateFile)
    .ifM(
      Files[IO]
        .readUtf8(stateFile)
        .compile
        .string
        .attempt
        .map(_.toOption.flatMap(s => parser.decode(s)(using frameCodec).toOption)),
      IO.pure(None),
    )

  def saveFrame(f: WindowFrame): IO[Unit] =
    Files[IO].createDirectories(stateDir) *>
      Stream
        .emit(Printer.noSpaces.print(frameCodec(f)))
        .through(Files[IO].writeUtf8(stateFile))
        .compile
        .drain

  // Wires up the JSON-RPC channel, builds the App via the factory, mounts
  // it, and pipes stdin/stdout for the lifetime of the process.
  def bootstrap(factory: SSR => Resource[IO, App]): IO[Unit] = {
    val program = for {
      bus <- Stream.eval(EventBus.make)
      idGen <- Stream.eval(IdGen.make)
      initial <- Stream.eval(loadFrame)
      windowFrame <- Stream.eval(
        SignallingRef.of[IO, WindowFrame](initial.getOrElse(WindowFrame(0, 0, 0, 0)))
      )
      endpoints <- Stream.eval(
        IO.fromEither(
          ServerEndpoints(Main.eventService(bus, windowFrame, saveFrame)).leftMap(err =>
            new RuntimeException(err.toString)
          )
        )
      )
      ch <- FS2Channel[IO]()
      _ <- Stream.resource(ch.withEndpoints(endpoints))
      emit <- Stream.eval(Emit.fromChannel(ch))
      ctx = SSR(bus, emit, windowFrame, idGen)
      _ <- Stream.resource(
        bus.register(QuitMenuId, ev => IO.whenA(ev.event == "click")(emit.quit().void))
      )
      a <- Stream.resource(factory(ctx))
      root <- Stream.resource(Component.build(a.component, ctx))
      tree <- Stream.eval(root.snapshot)
      sendWindow = (w: SetWindowInput) => emit.setWindow(w.width, w.height, w.x, w.y, w.screen).void
      windowStream <- Stream.resource(a.window.getAndDiscreteUpdates)
      (window0, windowUpdates) = windowStream
      menuStream <- Stream.resource(a.menu.getAndDiscreteUpdates)
      (menu0, menuUpdates) = menuStream
      _ <- Stream.eval(sendWindow(window0))
      _ <- Stream.eval(emit.setMenu(menu0))
      _ <- Stream.eval(emit.mount(tree))
      _ <- Stream.eval(root.markMounted)
      _ <- Stream.resource(windowUpdates.evalMap(sendWindow).compile.drain.background.void)
      _ <- Stream.resource(menuUpdates.evalMap(emit.setMenu).compile.drain.background.void)
      stdoutPipe = ch.output.through(lsp.encodeMessages).through(fs2.io.stdout[IO])
      stdinPipe = fs2.io.stdin[IO](4096).through(lsp.decodeMessages).through(ch.inputOrBounce)
      _ <- stdinPipe.mergeHaltBoth(stdoutPipe)
    } yield ()

    program.compile.drain
  }
}

trait SSRApp extends IOApp.Simple {
  def render(ctx: SSR): Resource[IO, App]
  final def run: IO[Unit] = App.bootstrap(render)
}

private[ssr] object Main {

  def eventService(
    bus: EventBus,
    windowFrame: Ref[IO, WindowFrame],
    onFrame: WindowFrame => IO[Unit],
  ): UiEvents[IO] =
    new UiEvents[IO] {
      def click(id: String): IO[Unit] = bus.fire(UiEvent(id = id, event = "click"))
      def input(id: String, value: String): IO[Unit] = bus
        .fire(UiEvent(id = id, event = "input", value = Some(value)))
      def toggle(id: String, value: Boolean): IO[Unit] = bus
        .fire(UiEvent(id = id, event = "toggle", value = Some(value.toString)))
      def frame(x: Double, y: Double, width: Double, height: Double): IO[Unit] = {
        val f = WindowFrame(x, y, width, height)
        windowFrame.set(f) *> onFrame(f)
      }
    }

}
