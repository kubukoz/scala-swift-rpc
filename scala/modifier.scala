package ssr

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.Signal

trait Modifier[-A] {
  def apply(a: A, b: NodeBuilder, ctx: SSR): Resource[IO, Unit]
}

object Modifier {

  def apply[A](using m: Modifier[A]): Modifier[A] = m

  given Modifier[Unit] with
    def apply(a: Unit, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = Resource.unit

  given [A: Modifier]: Modifier[Option[A]] with

    def apply(a: Option[A], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = a match {
      case Some(x) => Modifier[A].apply(x, b, ctx)
      case None    => Resource.unit
    }

  given [A: Modifier]: Modifier[List[A]] with

    def apply(a: List[A], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = a
      .traverse_(Modifier[A].apply(_, b, ctx))

  given [A: Modifier]: Modifier[Resource[IO, A]] with

    def apply(ra: Resource[IO, A], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = ra
      .flatMap(Modifier[A].apply(_, b, ctx))

  given Modifier[String] with
    def apply(s: String, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = Resource.eval(b.setText(s))

  // Reactive text. Apply the current value synchronously so the mount snapshot
  // sees it; then subscribe to changes for the component's lifetime.
  given Modifier[Signal[IO, String]] with

    def apply(s: Signal[IO, String], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      Resource.eval(s.get.flatMap(b.setText)) *>
        s.discrete.evalMap(b.setText).compile.drain.background.void

  given Modifier[EmptyTuple] with
    def apply(a: EmptyTuple, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = Resource.unit

  given [H, T <: Tuple](
    using mh: Modifier[H],
    mt: Modifier[T],
  ): Modifier[H *: T] with

    def apply(t: H *: T, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = mh.apply(t.head, b, ctx) *>
      mt.apply(t.tail, b, ctx)

  given Modifier[Component] with

    def apply(c: Component, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = Component
      .build(c, ctx)
      .evalMap(b.append)

}
