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

package ssr.codegen

import jsonrpclib.{JsonRpcNotificationTrait, JsonRpcRequestTrait}
import software.amazon.smithy.build.{PluginContext, SmithyBuildPlugin}
import software.amazon.smithy.codegen.core.*
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.*

import java.nio.file.Paths
import java.util.function.BiFunction
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SwiftCodegenPlugin extends SmithyBuildPlugin:

  private val Namespace      = "ssr.internal.protocol"
  private val SwiftNamespace = ""
  private val RequiredTrait  = ShapeId.from("smithy.api#required")

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
      op.getTrait(classOf[JsonRpcNotificationTrait]).toScala.map(_.getValue)

    def request(op: OperationShape): Option[String] =
      op.getTrait(classOf[JsonRpcRequestTrait]).toScala.map(_.getValue)

    def methodOf(op: OperationShape): Option[String] =
      notification(op).orElse(request(op))

    // Swift-as-client (event/*): Swift sends the input and receives the output.
    // Swift-as-server (ui/*):    Swift receives the input and sends the output.
    def shapesFor(pred: String => Boolean, side: OperationShape => java.util.Optional[ShapeId]): Set[ShapeId] =
      operations.flatMap { op =>
        methodOf(op).filter(pred).flatMap(_ => side(op).toScala).toList
      }.toSet

    val encodableShapes =
      shapesFor(_.startsWith("event/"), _.getInput) ++
        shapesFor(!_.startsWith("event/"), _.getOutput)

    val decodableShapes =
      shapesFor(!_.startsWith("event/"), _.getInput) ++
        shapesFor(_.startsWith("event/"), _.getOutput)

    val conformancesFor: ShapeId => String = id =>
      (encodableShapes.contains(id), decodableShapes.contains(id)) match
        case (true, false) => "Encodable, Sendable"
        case (false, true) => "Decodable, Sendable"
        case _             => "Codable, Sendable"

    val symbols = SwiftSymbolProvider(model)

    val namespaceShapes = model.shapes.iterator.asScala.toList
      .filter(inNamespace)
      .sortBy(_.getId.toString)

    val writer = SwiftWriter()

    writer.write("// Generated by codegen/swift-plugin — do not edit.")
    writer.write("// Source of truth: smithy/ui.smithy")
    writer.write("")
    writer.write("import Foundation")

    writer.write("")
    writer.write("// MARK: - Wire types (generated from smithy/ui.smithy)")
    namespaceShapes.foreach(writeShape(writer, model, symbols, _, conformancesFor))

    // Per-op typed wrappers on JSONRPCBridge. Direction maps to role:
    // - event/* ops:  Swift is the client. Notifications get `sendOp(_:)`;
    //                 (no request/response event/* ops yet.)
    // - ui/*    ops:  Swift is the server. Notifications get `onOp(_:)`;
    //                 request/response ops get `onOp(_:)` with async output.
    //
    // Adding a new op needs only a smithy entry + a handler body — no Swift
    // boilerplate touching method-name strings.

    enum OpKind:
      case SendNotification(input: ShapeId)             // event/* notif: Swift sends
      case ReceiveNotification(input: Option[ShapeId])  // ui/*    notif: Swift handles
      case ServeRequest(input: ShapeId, output: ShapeId)// ui/*    req:   Swift answers

    def classify(op: OperationShape): Option[(String, String, OpKind)] =
      val opName = op.getId.getName
      methodOf(op).map { method =>
        val kind: OpKind =
          if request(op).isDefined then
            (op.getInput.toScala, op.getOutput.toScala) match
              case (Some(i), Some(o)) => OpKind.ServeRequest(i, o)
              case _ =>
                throw new IllegalStateException(
                  s"request op $opName must declare both input and output"
                )
          else if method.startsWith("event/") then
            op.getInput.toScala match
              case Some(i) => OpKind.SendNotification(i)
              case None =>
                throw new IllegalStateException(
                  s"event/* notification $opName must declare an input"
                )
          else OpKind.ReceiveNotification(op.getInput.toScala)
        (opName, method, kind)
      }

    val classified = operations.flatMap(classify).sortBy(_._1)

    if classified.nonEmpty then
      writer.write("")
      writer.write("// MARK: - Per-op typed bridge API (generated from smithy/ui.smithy)")
      writer.write("")
      writer.block("extension JSONRPCBridge {", "}") {
        classified.foreach { case (opName, method, kind) =>
          kind match
            case OpKind.SendNotification(inputId) =>
              val inputName = symbols.toSymbol(model.expectShape(inputId)).getName
              val funcName  = "send" + opName
              writer.block(s"func $funcName(_ input: $inputName) {", "}") {
                writer.write(s"""sendNotification(method: "$method", params: input)""")
              }
            case OpKind.ReceiveNotification(Some(inputId)) =>
              val inputName = symbols.toSymbol(model.expectShape(inputId)).getName
              val funcName  = "on" + opName
              writer.block(
                s"func $funcName(_ handler: @escaping ($inputName) -> Void) {",
                "}",
              ) {
                writer.write(s"""on("$method", handler)""")
              }
            case OpKind.ReceiveNotification(None) =>
              val funcName = "on" + opName
              writer.block(
                s"func $funcName(_ handler: @escaping () -> Void) {",
                "}",
              ) {
                writer.write(s"""on("$method", handler)""")
              }
            case OpKind.ServeRequest(inputId, outputId) =>
              val inputName = symbols.toSymbol(model.expectShape(inputId)).getName
              val outName   = symbols.toSymbol(model.expectShape(outputId)).getName
              val funcName  = "on" + opName
              writer.block(
                s"func $funcName(_ handler: @escaping ($inputName) async throws -> $outName) {",
                "}",
              ) {
                writer.block(s"""registerRequest("$method") { data in""", "}") {
                  writer.write(s"let input = try JSONDecoder().decode($inputName.self, from: data)")
                  writer.write("let output = try await handler(input)")
                  writer.write("return try JSONEncoder().encode(output)")
                }
              }
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
