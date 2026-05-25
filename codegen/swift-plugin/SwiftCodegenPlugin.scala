package ssr.codegen

import software.amazon.smithy.build.{PluginContext, SmithyBuildPlugin}
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.*

import java.nio.file.Paths
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SwiftCodegenPlugin extends SmithyBuildPlugin:

  private val Namespace = "ssr.internal.protocol"
  private val JsonRpcNotificationTrait = ShapeId.from("jsonrpclib#jsonRpcNotification")
  private val RequiredTrait = ShapeId.from("smithy.api#required")

  override def getName: String = "swift-codegen"

  override def execute(ctx: PluginContext): Unit =
    val output = generate(ctx.getModel)
    ctx.getFileManifest.writeFile(Paths.get("WireTypes.swift"), output)

  /** Renders a Swift type reference for a shape (primitive, list, named struct, enum, union). */
  private final class SwiftTypeRef(model: Model) extends ShapeVisitor.Default[String]:
    protected def getDefault(shape: Shape): String = shape.getId.getName
    override def stringShape(s: StringShape): String     = "String"
    override def booleanShape(s: BooleanShape): String   = "Bool"
    override def integerShape(s: IntegerShape): String   = "Int"
    override def longShape(s: LongShape): String         = "Int"
    override def floatShape(s: FloatShape): String       = "Double"
    override def doubleShape(s: DoubleShape): String     = "Double"
    override def enumShape(s: EnumShape): String         = s.getId.getName
    override def unionShape(s: UnionShape): String       = s.getId.getName
    override def listShape(s: ListShape): String =
      val elem = model.expectShape(s.getMember.getTarget).accept(this)
      s"[$elem]"

  /** Renders a top-level Swift declaration for a shape (struct, enum, union, typealias). */
  private final class SwiftDeclaration(
      model: Model,
      typeRef: SwiftTypeRef,
      conformancesFor: ShapeId => String,
  ) extends ShapeVisitor.Default[Option[String]]:
    protected def getDefault(shape: Shape): Option[String] = None

    override def structureShape(s: StructureShape): Option[String] =
      val name         = s.getId.getName
      val conformances = conformancesFor(s.getId)
      val fields = s.getAllMembers.asScala.toList.flatMap { case (fieldName, member) =>
        val target = model.expectShape(member.getTarget)
        target.accept(typeRef) match
          case "Void" => None
          case t      =>
            val opt = if member.hasTrait(RequiredTrait) then "" else "?"
            Some(s"    let $fieldName: $t$opt")
      }
      Some((s"struct $name: $conformances {" :: fields ::: List("}")).mkString("\n"))

    override def listShape(s: ListShape): Option[String] =
      val elem = model.expectShape(s.getMember.getTarget).accept(typeRef)
      Some(s"typealias ${s.getId.getName} = [$elem]")

    override def enumShape(s: EnumShape): Option[String] =
      val name         = s.getId.getName
      val conformances = conformancesFor(s.getId)
      val cases = s.getEnumValues.asScala.toList.map { case (memberName, value) =>
        s"""    case ${lowerCamel(memberName)} = "$value""""
      }
      Some((s"enum $name: String, $conformances {" :: cases ::: List("}")).mkString("\n"))

    /** Union encoding mirrors smithy4s-json's single-key object form: `{"color": "..."}`. */
    override def unionShape(s: UnionShape): Option[String] =
      val name         = s.getId.getName
      val conformances = conformancesFor(s.getId)
      val members = s.getAllMembers.asScala.toList.map { case (memberName, member) =>
        val target  = model.expectShape(member.getTarget)
        val swiftTy = target.accept(typeRef)
        (memberName, lowerCamel(memberName), swiftTy)
      }

      val cases = members.map { case (_, swiftCase, swiftTy) =>
        s"    case $swiftCase($swiftTy)"
      }

      val codingKeys =
        ("    private enum CodingKeys: String, CodingKey {" ::
          members.map { case (wireKey, swiftCase, _) =>
            if wireKey == swiftCase then s"        case $swiftCase"
            else s"""        case $swiftCase = "$wireKey""""
          }) :+ "    }"

      val initBody = List(
        "    init(from decoder: Decoder) throws {",
        "        let c = try decoder.container(keyedBy: CodingKeys.self)",
      ) ++ members.map { case (_, swiftCase, swiftTy) =>
        s"""        if let v = try c.decodeIfPresent($swiftTy.self, forKey: .$swiftCase) { self = .$swiftCase(v); return }"""
      } ++ List(
        s"""        throw DecodingError.dataCorruptedError(forKey: CodingKeys.${members.head._2}, in: c, debugDescription: "no known $name variant present")""",
        "    }",
      )

      val encodeBody = List(
        "    func encode(to encoder: Encoder) throws {",
        "        var c = encoder.container(keyedBy: CodingKeys.self)",
        "        switch self {",
      ) ++ members.map { case (_, swiftCase, _) =>
        s"        case .$swiftCase(let v): try c.encode(v, forKey: .$swiftCase)"
      } ++ List(
        "        }",
        "    }",
      )

      Some(
        (s"enum $name: $conformances {" :: cases ::: List("") ::: codingKeys ::: List("") ::: initBody ::: List("") ::: encodeBody ::: List("}"))
          .mkString("\n")
      )

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

    val typeRef     = SwiftTypeRef(model)
    val declVisitor = SwiftDeclaration(model, typeRef, conformancesFor)

    val namespaceShapes = model.shapes.iterator.asScala.toList
      .filter(inNamespace)
      .sortBy(_.getId.toString)

    val declarations = namespaceShapes.flatMap(_.accept(declVisitor))

    val notifications = operations.flatMap(notification).sorted
    val methodConsts = notifications.map { method =>
      val constName = method.split("/", 2).last
      s"""    static let $constName = "$method""""
    }

    val sections = scala.collection.mutable.ArrayBuffer.empty[String]
    sections += "// Generated by codegen/swift-plugin — do not edit.\n// Source of truth: smithy/ui.smithy\n\nimport Foundation"

    sections += ("// MARK: - Wire types (generated from smithy/ui.smithy)\n\n" + declarations.mkString("\n\n"))
    sections += ("// MARK: - Method name constants (generated from smithy/ui.smithy)\n\nenum Methods {\n" + methodConsts.mkString("\n") + "\n}")

    sections.mkString("\n\n") + "\n"
