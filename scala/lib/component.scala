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

opaque type Component = SSR => Resource[IO, NodeBuilder]

object Component {

  def build(c: Component, ctx: SSR): Resource[IO, NodeBuilder] = c(ctx)

  def el[M](tag: String, mods: M)(using m: Modifier[M]): Component =
    ctx =>
      for {
        b <- Resource.eval(NodeBuilder.make(tag, ctx.emit, ctx.idGen))
        _ <- m.apply(mods, b, ctx)
      } yield b

}
