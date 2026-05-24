package htmxpoc

import upickle.default.*

case class Node(
  tag: String,
  id: Option[String] = None,
  text: Option[String] = None,
  value: Option[String] = None,
  children: List[Node] = Nil,
) derives ReadWriter

case class WindowSpec(
  width: Double,
  height: Double,
  x: Option[Double] = None,
  y: Option[Double] = None,
  screen: Option[String] = None,
) derives ReadWriter

case class Command(
  cmd: String,
  root: Option[Node] = None,
  id: Option[String] = None,
  op: Option[String] = None,
  value: Option[String] = None,
  window: Option[WindowSpec] = None,
) derives ReadWriter

object Command {
  def mount(n: Node): Command = Command(cmd = "mount", root = Some(n))
  def setText(id: String, v: String): Command = Command(
    cmd = "patch",
    id = Some(id),
    op = Some("setText"),
    value = Some(v),
  )
  def setValue(id: String, v: String): Command = Command(
    cmd = "patch",
    id = Some(id),
    op = Some("setValue"),
    value = Some(v),
  )
  def window(spec: WindowSpec): Command = Command(cmd = "window", window = Some(spec))
  def quit: Command = Command(cmd = "quit")
}

case class Event(
  event: String,
  id: String,
  value: Option[String] = None,
) derives ReadWriter
