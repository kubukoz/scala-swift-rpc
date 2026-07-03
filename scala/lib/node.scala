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
import ssr.internal.protocol.Node
import ssr.internal.protocol.Style
import ssr.internal.protocol.UiCommands

// NodeBuilder owns mutable state and emits targeted patches whenever it
// mutates after the initial mount.
final class NodeBuilder(
  val id: String,
  val tag: String,
  text: Ref[IO, Option[String]],
  value: Ref[IO, Option[String]],
  style: Ref[IO, Option[Style]],
  clickable: Ref[IO, Boolean],
  children: Ref[IO, Vector[NodeBuilder]],
  mounted: Ref[IO, Boolean],
  emit: UiCommands[IO],
) {

  // Mark this node as a click target. Flipped from the OnEvent modifier
  // when a click handler is registered, and serialized in the mount
  // snapshot so the Swift host knows to attach a click recognizer.
  // Called pre-mount only; no patch op for now.
  def markClickable: IO[Unit] = clickable.set(true)

  def setText(s: String): IO[Unit] = text.set(Some(s)) *>
    mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setText", Some(s))))

  def setValue(s: String): IO[Unit] = value.set(Some(s)) *>
    mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setValue", Some(s))))

  def setChecked(b: Boolean): IO[Unit] = value.set(Some(b.toString)) *>
    mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setChecked", Some(b.toString))))

  def setStyle(f: Style => Style): IO[Unit] = style
    .updateAndGet(s => Some(f(s.getOrElse(Style()))))
    .flatMap(updated =>
      mounted.get.flatMap(IO.whenA(_)(emit.patch(id, "setStyle", None, updated)))
    )

  def append(child: NodeBuilder): IO[Unit] = children.update(_ :+ child)

  // Used by the keyed-children Modifier. `allBuilders` is the full new vector
  // (used for ordering); `newBuilders` is the subset whose subtrees haven't
  // been mounted yet (their full snapshots go on the wire). Surviving
  // children keep their existing Swift-side views (looked up by id from
  // `order`).
  def replaceChildren(
    allBuilders: Vector[NodeBuilder],
    newBuilders: Vector[NodeBuilder],
  ): IO[Unit] = children.set(allBuilders) *>
    mounted
      .get
      .flatMap(
        IO.whenA(_)(
          for {
            mountedSubtrees <- newBuilders.toList.traverse(_.snapshot)
            _ <- emit.replaceChildren(id, mountedSubtrees, allBuilders.map(_.id).toList)
            _ <- newBuilders.traverse_(_.markMounted)
          } yield ()
        )
      )

  def snapshot: IO[Node] =
    for {
      t <- text.get
      v <- value.get
      s <- style.get
      c <- clickable.get
      cs <- children.get
      kids <- cs.toList.traverse(_.snapshot)
    } yield Node(
      tag = tag,
      id = Some(id),
      text = t,
      value = v,
      style = s,
      clickable = Option.when(c)(true),
      children = Some(kids),
    )

  def markMounted: IO[Unit] = mounted.set(true) *> children.get.flatMap(_.traverse_(_.markMounted))

}

object NodeBuilder {

  def make(tag: String, emit: UiCommands[IO], idGen: IdGen): IO[NodeBuilder] =
    for {
      id <- idGen.next
      text <- Ref.of[IO, Option[String]](None)
      value <- Ref.of[IO, Option[String]](None)
      style <- Ref.of[IO, Option[Style]](None)
      clickable <- Ref.of[IO, Boolean](false)
      children <- Ref.of[IO, Vector[NodeBuilder]](Vector.empty)
      mounted <- Ref.of[IO, Boolean](false)
    } yield new NodeBuilder(id, tag, text, value, style, clickable, children, mounted, emit)

}
