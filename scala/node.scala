package htmxpoc

import cats.effect.*
import cats.syntax.all.*
import htmxpoc.ui.Node
import htmxpoc.ui.UiCommands

// NodeBuilder owns mutable state and emits targeted patches whenever it
// mutates after the initial mount.
final class NodeBuilder(
  val id: String,
  val tag: String,
  text: Ref[IO, Option[String]],
  value: Ref[IO, Option[String]],
  children: Ref[IO, Vector[NodeBuilder]],
  mounted: Ref[IO, Boolean],
  emit: UiCommands[IO],
) {

  def setText(s: String): IO[Unit] = text.set(Some(s)) *>
    mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setText", Some(s))))

  def setValue(s: String): IO[Unit] = value.set(Some(s)) *>
    mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setValue", Some(s))))

  def append(child: NodeBuilder): IO[Unit] = children.update(_ :+ child)

  def snapshot: IO[Node] =
    for {
      t <- text.get
      v <- value.get
      cs <- children.get
      kids <- cs.toList.traverse(_.snapshot)
    } yield Node(tag = tag, id = Some(id), text = t, value = v, children = Some(kids))

  def markMounted: IO[Unit] = mounted.set(true) *> children.get.flatMap(_.traverse_(_.markMounted))

}

object NodeBuilder {

  def make(tag: String, emit: UiCommands[IO]): IO[NodeBuilder] =
    for {
      id <- IdGen.next
      text <- Ref.of[IO, Option[String]](None)
      value <- Ref.of[IO, Option[String]](None)
      children <- Ref.of[IO, Vector[NodeBuilder]](Vector.empty)
      mounted <- Ref.of[IO, Boolean](false)
    } yield new NodeBuilder(id, tag, text, value, children, mounted, emit)

}
