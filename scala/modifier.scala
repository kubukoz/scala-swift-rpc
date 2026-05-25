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
  given signalStringModifier: Modifier[Signal[IO, String]] with

    def apply(s: Signal[IO, String], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      Resource.eval(s.get.flatMap(b.setText)) *>
        s.discrete.evalMap(b.setText).compile.drain.background.void

  // Reactive boolean (for `attrs.checked <-- signal`) — same pattern as the
  // String version.
  given signalBooleanModifier: Modifier[Signal[IO, Boolean]] with

    def apply(s: Signal[IO, Boolean], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      Resource.eval(s.get.flatMap(b.setChecked)) *>
        s.discrete.evalMap(b.setChecked).compile.drain.background.void

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

  given [A]: Modifier[AttrPair[A]] with

    def apply(p: AttrPair[A], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] = Resource.eval(
      p.attr.apply(p.value, b)
    )

  given [A]: Modifier[AttrSignalPair[A]] with

    def apply(p: AttrSignalPair[A], b: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      Resource.eval(p.signal.get.flatMap(p.attr.apply(_, b))) *>
        p.signal.discrete.evalMap(p.attr.apply(_, b)).compile.drain.background.void

  given Modifier[OnEvent] with

    def apply(e: OnEvent, b: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      // Mark the node clickable so the Swift host attaches a click
      // recognizer. Other events (input/toggle) get their event delivery
      // for free from native controls, so no flag needed for them.
      Resource.eval(IO.whenA(e.event == "click")(b.markClickable)) *>
        ctx
          .bus
          .register(b.id, ev => IO.whenA(ev.event == e.event)(e.handler(ev)))

  given [K]: Modifier[KeyedChildrenBinding[K]] with

    def apply(binding: KeyedChildrenBinding[K], parent: NodeBuilder, ctx: SSR): Resource[IO, Unit] =
      ResourceSupervisor[IO].flatMap { sup =>
        Resource.eval(Ref.of[IO, Map[K, (NodeBuilder, IO[Unit])]](Map.empty)).flatMap { rows =>
          def allocate(k: K): IO[NodeBuilder] = sup
            .allocate(Component.build(binding.builder(k), ctx))
            .flatMap { case (nb, releaseOne) =>
              rows.update(_.updated(k, (nb, releaseOne))).as(nb)
            }

          def reconcile(newKeys: List[K]): IO[Unit] = rows.get.flatMap { current =>
            val newKeySet = newKeys.toSet
            val departed = current.keysIterator.filterNot(newKeySet).toList
            for {
              freshlyBuilt <- newKeys
                .filterNot(current.contains)
                .traverse(k => allocate(k).tupleLeft(k))
              updatedRows <- rows.get
              allBuilders = newKeys.map(k => updatedRows(k)._1).toVector
              newBuilders = freshlyBuilt.map(_._2).toVector
              _ <- parent.replaceChildren(allBuilders, newBuilders)
              _ <- departed.traverse_ { k =>
                val (_, releaseOne) = current(k)
                releaseOne *> rows.update(_.removed(k))
              }
            } yield ()
          }

          binding
            .src
            .getAndDiscreteUpdates
            .flatMap { case (initialKeys, updates) =>
              // Initial: build each row and append to parent (matches the
              // existing-children pattern; no wire op, the parent snapshot
              // will pick them up at mount time).
              Resource
                .eval(initialKeys.traverse(allocate).flatMap(_.traverse_(parent.append)))
                .flatMap(_ => updates.evalMap(reconcile).compile.drain.background.void)
            }
        }
      }

}
