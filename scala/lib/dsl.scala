package ssr

import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.Signal

// ---------- Attributes ----------

final case class AttrPair[A](attr: Attr[A], value: A)
final case class AttrSignalPair[A](attr: Attr[A], signal: Signal[IO, A])

final case class Attr[A](apply: (A, NodeBuilder) => IO[Unit]) {
  def :=(v: A): AttrPair[A] = AttrPair(this, v)
  def <--(s: Signal[IO, A]): AttrSignalPair[A] = AttrSignalPair(this, s)
}

object attrs {
  val value: Attr[String] = Attr((v, b) => b.setValue(v))
  val name: Attr[String] = Attr((v, b) => b.setValue(v))
  val checked: Attr[Boolean] = Attr((v, b) => b.setChecked(v))
}

// ---------- Events ----------

final case class OnEvent(event: String, handler: UiEvent => IO[Unit])

def onInput(f: String => IO[Unit]): OnEvent = OnEvent("input", ev => ev.value.traverse_(f))
def onClick(f: => IO[Unit]): OnEvent = OnEvent("click", _ => f)
def onToggle(f: Boolean => IO[Unit]): OnEvent =
  OnEvent("toggle", ev => ev.value.traverse_(s => f(s.toBoolean)))

// ---------- Tag helpers ----------

object ui {
  def vstack[M: Modifier](mods: M): Component = Component.el("vstack", mods)
  def hstack[M: Modifier](mods: M): Component = Component.el("hstack", mods)
  def zstack[M: Modifier](mods: M): Component = Component.el("zstack", mods)
  def label[M: Modifier](mods: M): Component = Component.el("label", mods)
  def button[M: Modifier](mods: M): Component = Component.el("button", mods)
  def textfield[M: Modifier](mods: M): Component = Component.el("textfield", mods)
  def image[M: Modifier](mods: M): Component = Component.el("image", mods)
  def scrollview[M: Modifier](mods: M): Component = Component.el("scrollview", mods)
  def hscrollview[M: Modifier](mods: M): Component = Component.el("hscrollview", mods)
  def toggle[M: Modifier](mods: M): Component = Component.el("toggle", mods)
  def divider: Component = Component.el("divider", ())

  def splitview(sidebar: Component, detail: Component): Component =
    Component.el("splitview", (sidebar, detail))

  def spacer: Component = Component.el("spacer", ())

  // Keyed children — Calico-shaped. `builder` is called once per genuinely
  // new key; surviving keys keep their NodeBuilder and any per-row state.
  def children[K](builder: K => Component): KeyedChildren[K] = KeyedChildren(builder)
}

final class KeyedChildren[K] private[ssr] (val builder: K => Component) {
  def <--(src: Signal[IO, List[K]]): KeyedChildrenBinding[K] = KeyedChildrenBinding(builder, src)
}

object KeyedChildren {
  def apply[K](builder: K => Component): KeyedChildren[K] = new KeyedChildren(builder)
}

final case class KeyedChildrenBinding[K](
  builder: K => Component,
  src: Signal[IO, List[K]],
)
