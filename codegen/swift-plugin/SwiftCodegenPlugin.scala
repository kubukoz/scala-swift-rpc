package ssr.codegen

import software.amazon.smithy.build.{PluginContext, SmithyBuildPlugin}
import software.amazon.smithy.codegen.core.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.*

import java.nio.file.Paths
import java.util.function.BiFunction
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SwiftCodegenPlugin extends SmithyBuildPlugin:

  private val Namespace                = "ssr.internal.protocol"
  private val SwiftNamespace           = ""
  private val JsonRpcNotificationTrait = ShapeId.from("jsonrpclib#jsonRpcNotification")
  private val RequiredTrait            = ShapeId.from("smithy.api#required")

  override def getName: String = "swift-codegen"

  override def execute(ctx: PluginContext): Unit =
    ctx.getFileManifest.writeFile(Paths.get("WireTypes.swift"), generate(ctx.getModel))

  // ----- Symbols ------------------------------------------------------------

  private final class SwiftSymbolProvider(model: Model) extends SymbolProvider:
    private def primitive(name: String): Symbol =
      Symbol.builder().name(name).build()

    private def named(name: String): Symbol =
      Symbol.builder().name(name).namespace(SwiftNamespace, ".").build()

    private val visitor = new ShapeVisitor.Default[Symbol]:
      protected def getDefault(shape: Shape): Symbol     = named(shape.getId.getName)
      override def stringShape(s: StringShape): Symbol   = primitive("String")
      override def booleanShape(s: BooleanShape): Symbol = primitive("Bool")
      override def integerShape(s: IntegerShape): Symbol = primitive("Int")
      override def longShape(s: LongShape): Symbol       = primitive("Int")
      override def floatShape(s: FloatShape): Symbol     = primitive("Double")
      override def doubleShape(s: DoubleShape): Symbol   = primitive("Double")
      // EnumShape extends StringShape, so it would otherwise be resolved as a primitive String.
      override def enumShape(s: EnumShape): Symbol = named(s.getId.getName)
      override def listShape(s: ListShape): Symbol =
        val elem = toSymbol(model.expectShape(s.getMember.getTarget))
        Symbol.builder()
          .name(s.getId.getName)
          .namespace(SwiftNamespace, ".")
          .putProperty("element", elem)
          .addReference(elem)
          .build()

    def toSymbol(shape: Shape): Symbol = shape.accept(visitor)

  // ----- Writer -------------------------------------------------------------

  private final class SwiftImports extends ImportContainer:
    private val imports = scala.collection.mutable.TreeSet.empty[String]
    def importSymbol(symbol: Symbol, alias: String): Unit =
      val ns = symbol.getNamespace
      if ns != null && ns.nonEmpty && ns != SwiftNamespace then imports += ns

  private final class SwiftWriter extends SymbolWriter[SwiftWriter, SwiftImports](SwiftImports()):
    putFormatter('T', SwiftTypeFormatter)
    trimTrailingSpaces()

    /** `openBlock(String, String, Runnable)` wrapper. Scala 3 occasionally resolves the underlying
      * Java overload to the varargs form `openBlock(String, Object*)` (treating the close-text and
      * lambda as format args, which then fails at format time). Routing through an explicit
      * `Runnable` avoids that.
      */
    def block(open: String, close: String)(body: => Unit): Unit =
      openBlock(open, close, (() => body): Runnable)
      ()

  private object SwiftTypeFormatter extends BiFunction[Object, String, String]:
    def apply(value: Object, indent: String): String = value match
      case s: Symbol => formatSymbol(s)
      case other     => other.toString

    private def formatSymbol(s: Symbol): String =
      Option(s.getProperty("element").orElse(null).asInstanceOf[Symbol]) match
        case Some(elem) => s"[${formatSymbol(elem)}]"
        case None       => s.getName

  // ----- Generation ---------------------------------------------------------

  private def lowerCamel(s: String): String =
    if s.isEmpty then s
    else
      val parts = s.split("_").toList
      parts match
        case Nil       => s
        case h :: tail => h.toLowerCase + tail.map(_.toLowerCase.capitalize).mkString

  private def generate(model: Model): String =
    val inNamespace: Shape => Boolean = _.getId.getNamespace == Namespace

    val operations = model.getOperationShapes.asScala.toList.filter(inNamespace)

    def notification(op: OperationShape): Option[String] =
      op.findTrait(JsonRpcNotificationTrait).toScala.map(_.toNode.expectStringNode.getValue)

    def inputsFor(pred: String => Boolean): Set[ShapeId] =
      operations.flatMap { op =>
        notification(op).filter(pred).flatMap(_ => op.getInput.toScala).toList
      }.toSet

    val eventInputs   = inputsFor(_.startsWith("event/"))
    val commandInputs = inputsFor(!_.startsWith("event/"))

    val conformancesFor: ShapeId => String = id =>
      if eventInputs.contains(id) then "Encodable, Sendable"
      else if commandInputs.contains(id) then "Decodable, Sendable"
      else "Codable, Sendable"

    val symbols = SwiftSymbolProvider(model)

    val namespaceShapes = model.shapes.iterator.asScala.toList
      .filter(inNamespace)
      .sortBy(_.getId.toString)

    val methods = operations.flatMap(notification).sorted

    val writer = SwiftWriter()

    writer.write("// Generated by codegen/swift-plugin — do not edit.")
    writer.write("// Source of truth: smithy/ui.smithy")
    writer.write("")
    writer.write("import Foundation")

    writer.write("")
    writer.write("// MARK: - Wire types (generated from smithy/ui.smithy)")
    namespaceShapes.foreach(writeShape(writer, model, symbols, _, conformancesFor))

    writer.write("")
    writer.write("// MARK: - Method name constants (generated from smithy/ui.smithy)")
    writer.write("")
    writer.block("enum Methods {", "}") {
      methods.foreach { method =>
        val constName = method.split("/", 2).last
        writer.write("static let $L = $S", constName, method)
      }
    }

    writer.toString

  /** Writes a top-level decl for the shape, preceded by a blank line. No-op for unsupported shapes. */
  private def writeShape(
      writer: SwiftWriter,
      model: Model,
      symbols: SwiftSymbolProvider,
      shape: Shape,
      conformancesFor: ShapeId => String,
  ): Unit = shape match
    case s: StructureShape =>
      writer.write("")
      writeStruct(writer, model, symbols, s, conformancesFor(s.getId))
    case s: ListShape =>
      writer.write("")
      val sym = symbols.toSymbol(s)
      writer.write("typealias $L = $T", sym.getName, sym)
    case s: EnumShape =>
      writer.write("")
      writeEnum(writer, s, conformancesFor(s.getId))
    case s: UnionShape =>
      writer.write("")
      writeUnion(writer, model, symbols, s, conformancesFor(s.getId))
    case _ => ()

  private def writeStruct(
      writer: SwiftWriter,
      model: Model,
      symbols: SwiftSymbolProvider,
      struct: StructureShape,
      conformances: String,
  ): Unit =
    val name = struct.getId.getName
    writer.block(s"struct $name: $conformances {", "}") {
      struct.getAllMembers.asScala.foreach { case (fieldName, member) =>
        val target = model.expectShape(member.getTarget)
        val sym    = symbols.toSymbol(target)
        if sym.getName != "Void" then
          val opt = if member.hasTrait(RequiredTrait) then "" else "?"
          writer.write(s"let $fieldName: $$T$opt", sym)
      }
    }

  private def writeEnum(writer: SwiftWriter, shape: EnumShape, conformances: String): Unit =
    val name = shape.getId.getName
    writer.block(s"enum $name: String, $conformances {", "}") {
      shape.getEnumValues.asScala.foreach { case (memberName, value) =>
        writer.write("case $L = $S", lowerCamel(memberName), value)
      }
    }

  /** Union encoding mirrors smithy4s-json's single-key object form: `{"color": "..."}`. */
  private def writeUnion(
      writer: SwiftWriter,
      model: Model,
      symbols: SwiftSymbolProvider,
      shape: UnionShape,
      conformances: String,
  ): Unit =
    val name = shape.getId.getName
    val members = shape.getAllMembers.asScala.toList.map { case (memberName, member) =>
      val sym = symbols.toSymbol(model.expectShape(member.getTarget))
      (memberName, lowerCamel(memberName), sym)
    }

    writer.block(s"enum $name: $conformances {", "}") {
      members.foreach { case (_, swiftCase, sym) =>
        writer.write(s"case $swiftCase($$T)", sym)
      }

      writer.write("")
      writer.block("private enum CodingKeys: String, CodingKey {", "}") {
        members.foreach { case (wireKey, swiftCase, _) =>
          if wireKey == swiftCase then writer.write(s"case $swiftCase")
          else writer.write(s"""case $swiftCase = $$S""", wireKey)
        }
      }

      writer.write("")
      writer.block("init(from decoder: Decoder) throws {", "}") {
        writer.write("let c = try decoder.container(keyedBy: CodingKeys.self)")
        members.foreach { case (_, swiftCase, sym) =>
          writer.write(
            s"if let v = try c.decodeIfPresent($$T.self, forKey: .$swiftCase) { self = .$swiftCase(v); return }",
            sym,
          )
        }
        writer.write(
          s"""throw DecodingError.dataCorruptedError(forKey: CodingKeys.${members.head._2}, in: c, debugDescription: $$S)""",
          s"no known $name variant present",
        )
      }

      writer.write("")
      writer.block("func encode(to encoder: Encoder) throws {", "}") {
        writer.write("var c = encoder.container(keyedBy: CodingKeys.self)")
        writer.write("switch self {")
        members.foreach { case (_, swiftCase, _) =>
          writer.write(s"case .$swiftCase(let v): try c.encode(v, forKey: .$swiftCase)")
        }
        writer.write("}")
      }
    }
