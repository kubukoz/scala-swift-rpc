package htmxpoc

import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import upickle.default.*

object IdGen {
  private val counter = new java.util.concurrent.atomic.AtomicLong(0)
  def next: IO[String] = IO(s"n${counter.incrementAndGet()}")
}

trait Emit {
  def apply(c: Command): IO[Unit]
}

object Emit {

  val stdout: Emit =
    new Emit {

      def apply(c: Command): IO[Unit] = IO {
        java.lang.System.out.println(write(c))
        java.lang.System.out.flush()
      }

    }

}

trait EventBus {
  def fire(ev: Event): IO[Unit]
  def register(id: String, handler: Event => IO[Unit]): Resource[IO, Unit]
}

object EventBus {

  def make: IO[EventBus] = Ref.of[IO, Map[String, Event => IO[Unit]]](Map.empty).map { ref =>
    new EventBus {

      def fire(ev: Event): IO[Unit] = ref
        .get
        .flatMap(_.get(ev.id).traverse_(_(ev)))

      def register(id: String, handler: Event => IO[Unit]): Resource[IO, Unit] = Resource.make(
        ref.update(_.updated(id, handler))
      )(_ => ref.update(_.removed(id)))

    }
  }

}

final case class Ctx(
  bus: EventBus,
  emit: Emit,
  windowFrame: Signal[IO, WindowFrame],
)

final case class WindowFrame(x: Double, y: Double, width: Double, height: Double)

object WindowFrame {
  val EventId: String = "__window__"
  val EventName: String = "frame"

  def parse(s: String): Option[WindowFrame] = s.split('x') match {
    case Array(x, y, w, h) =>
      (x.toDoubleOption, y.toDoubleOption, w.toDoubleOption, h.toDoubleOption).tupled.map {
        case (xv, yv, wv, hv) => WindowFrame(xv, yv, wv, hv)
      }
    case _ => None
  }
}
