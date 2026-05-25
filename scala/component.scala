package ssr

import cats.effect.*

opaque type Component = SSR => Resource[IO, NodeBuilder]

object Component {

  def build(c: Component, ctx: SSR): Resource[IO, NodeBuilder] = c(ctx)

  def el[M](tag: String, mods: M)(using m: Modifier[M]): Component =
    ctx =>
      for {
        b <- Resource.eval(NodeBuilder.make(tag, ctx.emit))
        _ <- m.apply(mods, b, ctx)
      } yield b

}
