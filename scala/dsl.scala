package htmxpoc

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.concurrent.Signal

// ---------- Attributes ----------

final case class AttrPair[A](attr: Attr[A], value: A)
final case class AttrSignalPair[A](attr: Attr[A], signal: Signal[IO, A])

final case class Attr[A](apply: (A, NodeBuilder) => IO[Unit]) {
  def :=(v: A): AttrPair[A] = AttrPair(this, v)
  def <--(s: Signal[IO, A]): AttrSignalPair[A] = AttrSignalPair(this, s)
}

given [A]: Modifier[AttrPair[A]] with

  def apply(p: AttrPair[A], b: NodeBuilder, ctx: Ctx): Resource[IO, Unit] = Resource.eval(
    p.attr.apply(p.value, b)
  )

given [A]: Modifier[AttrSignalPair[A]] with

  def apply(p: AttrSignalPair[A], b: NodeBuilder, ctx: Ctx): Resource[IO, Unit] =
    Resource.eval(p.signal.get.flatMap(p.attr.apply(_, b))) *>
      p.signal.discrete.evalMap(p.attr.apply(_, b)).compile.drain.background.void

object attrs {
  val value: Attr[String] = Attr((v, b) => b.setValue(v))
}

// ---------- Events ----------

final case class OnEvent(event: String, handler: UiEvent => IO[Unit])

given Modifier[OnEvent] with

  def apply(e: OnEvent, b: NodeBuilder, ctx: Ctx): Resource[IO, Unit] = ctx
    .bus
    .register(b.id, ev => IO.whenA(ev.event == e.event)(e.handler(ev)))

def onInput(f: String => IO[Unit]): OnEvent = OnEvent("input", ev => ev.value.traverse_(f))
def onClick(f: => IO[Unit]): OnEvent = OnEvent("click", _ => f)

// ---------- Tag helpers ----------

object ssr {
  def vstack[M: Modifier](mods: M): Component = Component.el("vstack", mods)
  def hstack[M: Modifier](mods: M): Component = Component.el("hstack", mods)
  def label[M: Modifier](mods: M): Component = Component.el("label", mods)
  def button[M: Modifier](mods: M): Component = Component.el("button", mods)
  def textfield[M: Modifier](mods: M): Component = Component.el("textfield", mods)
}
