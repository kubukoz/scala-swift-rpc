package ssr

import cats.effect.*
import ssr.internal.protocol.Alignment as WireAlignment
import ssr.internal.protocol.Background as WireBackground
import ssr.internal.protocol.Color as WireColor
import ssr.internal.protocol.EdgeInsets as WireEdgeInsets
import ssr.internal.protocol.Font as WireFont
import ssr.internal.protocol.FontWeight as WireFontWeight
import ssr.internal.protocol.Material as WireMaterial
import ssr.internal.protocol.SizeFrame
import ssr.internal.protocol.Style

// Re-export wire types under friendlier names + helper constructors.

type EdgeInsets = WireEdgeInsets
object EdgeInsets {
  def all(d: Double): EdgeInsets = WireEdgeInsets(d, d, d, d)
  def symmetric(horizontal: Double, vertical: Double): EdgeInsets =
    WireEdgeInsets(vertical, horizontal, vertical, horizontal)
  def only(top: Double = 0, leading: Double = 0, bottom: Double = 0, trailing: Double = 0): EdgeInsets =
    WireEdgeInsets(top, leading, bottom, trailing)
  def bottom(d: Double): EdgeInsets = only(bottom = d)
  def top(d: Double): EdgeInsets = only(top = d)
  def leading(d: Double): EdgeInsets = only(leading = d)
  def trailing(d: Double): EdgeInsets = only(trailing = d)
}

type Color = WireColor
object Color {
  def hex(s: String): Color = WireColor(s)
}

type FontWeight = WireFontWeight
object FontWeight {
  val Regular: FontWeight = WireFontWeight.REGULAR
  val Medium: FontWeight = WireFontWeight.MEDIUM
  val Semibold: FontWeight = WireFontWeight.SEMIBOLD
  val Bold: FontWeight = WireFontWeight.BOLD
}

type Font = WireFont
object Font {
  def system(size: Double, weight: FontWeight = FontWeight.Regular): Font = WireFont(size, weight)
}

type Material = WireMaterial
object Material {
  val Sidebar: Material = WireMaterial.SIDEBAR
  val Glass: Material = WireMaterial.GLASS
  val Hud: Material = WireMaterial.HUD
  val Regular: Material = WireMaterial.REGULAR
}

type Background = WireBackground
object Background {
  def color(c: Color): Background = WireBackground.color(c)
  def material(m: Material): Background = WireBackground.material(m)
}

type Frame = SizeFrame
object Frame {
  def apply(
    width: Option[Double] = None,
    height: Option[Double] = None,
    minWidth: Option[Double] = None,
    maxWidth: Option[Double] = None,
    minHeight: Option[Double] = None,
    maxHeight: Option[Double] = None,
  ): Frame = SizeFrame(width, height, minWidth, maxWidth, minHeight, maxHeight)

  def fixed(width: Double, height: Double): Frame =
    SizeFrame(width = Some(width), height = Some(height))

  def size(w: Double, h: Double): Frame = fixed(w, h)
}

type Alignment = WireAlignment
object Alignment {
  val Leading: Alignment = WireAlignment.LEADING
  val Center: Alignment = WireAlignment.CENTER
  val Trailing: Alignment = WireAlignment.TRAILING
}

// Style attributes. Each one updates a single field on the NodeBuilder's
// Style ref and emits a setStyle patch (post-mount) carrying the whole
// updated Style struct (matches the smithy `Patch.style` field).

object styles {
  val padding: Attr[EdgeInsets] = Attr((v, b) => b.setStyle(_.copy(padding = Some(v))))
  val spacing: Attr[Double] = Attr((v, b) => b.setStyle(_.copy(spacing = Some(v))))
  val font: Attr[Font] = Attr((v, b) => b.setStyle(_.copy(font = Some(v))))
  val foreground: Attr[Color] = Attr((v, b) => b.setStyle(_.copy(foreground = Some(v))))
  val background: Attr[Background] = Attr((v, b) => b.setStyle(_.copy(background = Some(v))))
  val cornerRadius: Attr[Double] = Attr((v, b) => b.setStyle(_.copy(cornerRadius = Some(v))))
  val frame: Attr[Frame] = Attr((v, b) => b.setStyle(_.copy(frame = Some(v))))
  val alignment: Attr[Alignment] = Attr((v, b) => b.setStyle(_.copy(alignment = Some(v))))
}
