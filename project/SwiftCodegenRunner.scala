import sbt.*
import sbt.util.Logger

import java.io.File

/** Drives the SwiftCodegen smithy-build plugin in a forked JVM. The plugin
  * runtime classpath comes from the `swiftCodegen` sbt module; we run the
  * `ssr.codegen.SwiftCodegenMain` helper main with that classpath.
  */
object SwiftCodegenRunner {

  def run(
    smithyDir: File,
    classpath: Seq[File],
    outFile: File,
    log: Logger,
  ): Unit = {
    val cpString = classpath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val cmd = Seq(
      javaBin,
      "-cp",
      cpString,
      "ssr.codegen.SwiftCodegenMain",
      smithyDir.getAbsolutePath,
      outFile.getAbsolutePath,
    )
    val rc = scala.sys.process
      .Process(cmd)
      .!(scala.sys.process.ProcessLogger(log.info(_), log.error(_)))
    if (rc != 0) sys.error(s"swift codegen failed (exit $rc)")
  }

  private def javaBin: String = {
    val home = sys.props.getOrElse("java.home", sys.error("java.home not set"))
    val bin = new File(home, "bin/java")
    if (bin.exists) bin.getAbsolutePath else "java"
  }
}
