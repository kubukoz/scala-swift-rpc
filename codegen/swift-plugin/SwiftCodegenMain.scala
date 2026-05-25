package ssr.codegen

import software.amazon.smithy.build.SmithyBuild
import software.amazon.smithy.build.model.SmithyBuildConfig
import software.amazon.smithy.model.node.{Node, ObjectNode}
import software.amazon.smithy.model.loader.ModelAssembler

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*

/** Drives the swift-codegen smithy-build plugin programmatically. Invoked by
  * the sbt build via a forked `java` process so the plugin lives on the JVM's
  * classpath (and is discovered via SPI).
  *
  * Usage: SwiftCodegenMain <smithyDir> <outFile>
  *
  * `smithyDir` is a directory containing `.smithy` sources; `outFile` is the
  * absolute path of the resulting `WireTypes.swift`.
  */
object SwiftCodegenMain:

  def main(args: Array[String]): Unit =
    args match
      case Array(smithyDirArg, outFileArg) => run(Paths.get(smithyDirArg), Paths.get(outFileArg))
      case _ =>
        Console.err.println("usage: SwiftCodegenMain <smithyDir> <outFile>")
        sys.exit(2)

  private def run(smithyDir: Path, outFile: Path): Unit =
    val tmp = Files.createTempDirectory("ssr-swift-codegen")
    try
      val configNode: ObjectNode = Node.objectNodeBuilder()
        .withMember("version", "1.0")
        .withMember(
          "plugins",
          Node.objectNodeBuilder().withMember("swift-codegen", Node.objectNode()).build(),
        )
        .build()

      val config = SmithyBuildConfig.fromNode(configNode)

      // Build a model from .smithy files in `smithyDir` and feed it to SmithyBuild.
      // Going through `registerSources(<dir>)` doesn't populate the model in the
      // current smithy version, so we assemble it ourselves and pass it in.
      val assembler = new ModelAssembler()
      assembler.discoverModels(classOf[SwiftCodegenMain.type].getClassLoader)
      Files
        .walk(smithyDir)
        .iterator
        .asScala
        .filter(p => p.toString.endsWith(".smithy") || p.toString.endsWith(".json"))
        .foreach(p => assembler.addImport(p))
      val model = assembler.assemble().unwrap()

      val build = SmithyBuild
        .create(classOf[SwiftCodegenMain.type].getClassLoader)
        .config(config)
        .outputDirectory(tmp)
        .model(model)

      val result = build.build()
      if result.anyBroken then
        result.getProjectionResults.asScala.foreach { pr =>
          pr.getEvents.asScala.foreach(ev => Console.err.println(ev))
        }
        sys.error("smithy-build produced validation errors")

      val produced = tmp.resolve("source").resolve("swift-codegen").resolve("WireTypes.swift")
      if !Files.exists(produced) then sys.error(s"plugin did not produce $produced")

      Files.createDirectories(outFile.getParent)
      Files.copy(produced, outFile, StandardCopyOption.REPLACE_EXISTING)
    finally deleteRecursive(tmp)

  private def deleteRecursive(p: Path): Unit =
    if Files.exists(p) then
      Files
        .walk(p)
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .iterator
        .asScala
        .foreach(Files.deleteIfExists(_))
