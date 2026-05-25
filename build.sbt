ThisBuild / scalaVersion := "3.8.3"
ThisBuild / organization := "ssr"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val smithy4sVersion = "0.18.53"
val jsonrpclibVersion = "0.1.2"
val smithyVersion = "1.71.0"

lazy val root = (project in file("."))
  .aggregate(swiftCodegen)
  .aggregate(ssr.projectRefs *)
  .aggregate(demos.projectRefs *)
  .settings(
    name := "ssr-root",
    publish / skip := true,
  )

// ---------------------------------------------------------------------------
// Swift codegen plugin (smithy-build plugin that emits WireTypes.swift)
// ---------------------------------------------------------------------------

lazy val swiftCodegen = (project in file("codegen/swift-plugin"))
  .settings(
    name := "swift-codegen",
    Compile / scalaSource := baseDirectory.value,
    Compile / unmanagedResourceDirectories := Seq(baseDirectory.value / "resources"),
    libraryDependencies ++= Seq(
      "software.amazon.smithy" % "smithy-build" % smithyVersion,
      "software.amazon.smithy" % "smithy-codegen-core" % smithyVersion,
      "tech.neander" % "jsonrpclib-smithy" % jsonrpclibVersion,
    ),
  )

// ---------------------------------------------------------------------------
// Swift codegen + build tasks (cacheable)
// ---------------------------------------------------------------------------

lazy val swiftSourceDir = settingKey[File]("Swift source directory")
lazy val swiftGeneratedDir = settingKey[File]("Where to place generated Swift wire types")
lazy val swiftCodegenOutput = taskKey[File]("Generate WireTypes.swift from smithy/")
lazy val swiftBuild = taskKey[File]("Compile the Swift host app; returns the binary")
lazy val smithySourceDir = settingKey[File]("Smithy source directory")

ThisBuild / smithySourceDir := (ThisBuild / baseDirectory).value / "smithy"
ThisBuild / swiftSourceDir := (ThisBuild / baseDirectory).value / "swift"
ThisBuild / swiftGeneratedDir := (ThisBuild / swiftSourceDir).value / "generated"

swiftCodegenOutput := {
  val log = streams.value.log
  val smithyDir = (ThisBuild / smithySourceDir).value
  val outDir = (ThisBuild / swiftGeneratedDir).value
  val outFile = outDir / "WireTypes.swift"
  val cacheDir = streams.value.cacheDirectory / "swift-codegen"

  val pluginCp = (swiftCodegen / Runtime / fullClasspath).value.map(_.data)

  val smithyInputs = (smithyDir ** "*.smithy").get.toSet
  val pluginInputs = pluginCp.flatMap { f =>
    if (f.isDirectory) (f ** "*").filter(_.isFile).get
    else Seq(f)
  }.toSet

  val cached = FileFunction.cached(
    cacheDir,
    inStyle = FilesInfo.hash,
    outStyle = FilesInfo.exists,
  ) { _ =>
    log.info(s"swift codegen <- ${smithyDir}")
    IO.delete(outDir)
    IO.createDirectory(outDir)
    SwiftCodegenRunner.run(
      smithyDir = smithyDir,
      classpath = pluginCp,
      outFile = outFile,
      log = log,
    )
    Set(outFile)
  }
  cached(smithyInputs ++ pluginInputs)
  outFile
}

swiftBuild := {
  val log = streams.value.log
  val swiftDir = (ThisBuild / swiftSourceDir).value
  val generated = swiftCodegenOutput.value
  val cacheDir = streams.value.cacheDirectory / "swift-build"
  val outBin = (ThisBuild / baseDirectory).value / "build" / "ssr-app"

  val swiftSources =
    ((swiftDir * "*.swift").get ++ (swiftDir / "generated" * "*.swift").get :+ swiftDir / "Package.swift").toSet

  val cached = FileFunction.cached(
    cacheDir,
    inStyle = FilesInfo.hash,
    outStyle = FilesInfo.exists,
  ) { _ =>
    log.info("swift build -c release")
    val rc = scala.sys.process
      .Process(Seq("swift", "build", "--package-path", swiftDir.getAbsolutePath, "-c", "release"))
      .!(scala.sys.process.ProcessLogger(log.info(_), log.error(_)))
    if (rc != 0) sys.error(s"swift build failed (exit $rc)")
    val built = swiftDir / ".build" / "release" / "ssr-host"
    if (!built.exists) sys.error(s"swift build did not produce $built")
    IO.createDirectory(outBin.getParentFile)
    IO.copyFile(built, outBin)
    outBin.setExecutable(true)
    Set(outBin)
  }
  cached(swiftSources)
  outBin
}

// ---------------------------------------------------------------------------
// Scala modules. projectmatrix: JVM + Native axes.
//   ssr   — library (component tree, FRP runtime, JSON-RPC client/server)
//   demos — demo apps (landmarks, mirror) built against the library
// ---------------------------------------------------------------------------

lazy val mainClassName = "ssr.landmarks.LandmarksMain"

def excludeAxisTarget(srcDir: File): FileFilter => FileFilter = prev => {
  // scalaSource is the project root, which also contains the per-axis target/
  // directories. Exclude target/ so each axis only sees the hand-written sources.
  val target = (srcDir / "target").getAbsolutePath
  prev || new SimpleFileFilter(_.getAbsolutePath.startsWith(target))
}

lazy val commonScalaSettings = Seq(
  scalacOptions ++= Seq("-Wconf:cat=deprecation:silent"),
  libraryDependencies ++= Seq(
    "tech.neander" %%% "jsonrpclib-fs2" % jsonrpclibVersion,
    "tech.neander" %%% "jsonrpclib-smithy4s" % jsonrpclibVersion,
    "com.disneystreaming.smithy4s" %%% "smithy4s-core" % smithy4sVersion,
    "com.disneystreaming.smithy4s" %%% "smithy4s-json" % smithy4sVersion,
    "io.circe" %%% "circe-parser" % "0.14.15",
    "org.typelevel" %%% "cats-effect" % "3.7.0",
    "co.fs2" %%% "fs2-core" % "3.13.0",
    "co.fs2" %%% "fs2-io" % "3.13.0",
  ),
)

lazy val ssr = (projectMatrix in file("scala/lib"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .settings(commonScalaSettings)
  .settings(
    name := "ssr",
    Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala" / "lib",
    Compile / unmanagedSources / excludeFilter :=
      excludeAxisTarget((ThisBuild / baseDirectory).value / "scala" / "lib")(
        (Compile / unmanagedSources / excludeFilter).value
      ),
    Compile / smithy4sInputDirs := Seq((ThisBuild / smithySourceDir).value),
    libraryDependencies ++= Seq(
      "tech.neander" % "jsonrpclib-smithy" % jsonrpclibVersion % Smithy4s,
    ),
  )
  .jvmPlatform(scalaVersions = Seq("3.8.3"))
  .nativePlatform(
    scalaVersions = Seq("3.8.3"),
  )

lazy val demos = (projectMatrix in file("scala/demos"))
  .dependsOn(ssr)
  .settings(commonScalaSettings)
  .settings(
    name := "ssr-demos",
    Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala" / "demos",
    Compile / unmanagedSources / excludeFilter :=
      excludeAxisTarget((ThisBuild / baseDirectory).value / "scala" / "demos")(
        (Compile / unmanagedSources / excludeFilter).value
      ),
    Compile / mainClass := Some(mainClassName),
  )
  .jvmPlatform(scalaVersions = Seq("3.8.3"))
  .nativePlatform(
    scalaVersions = Seq("3.8.3"),
  )

lazy val demosJVM    = demos.jvm("3.8.3")
lazy val demosNative = demos.native("3.8.3")


// ---------------------------------------------------------------------------
// Run tasks (root)
// ---------------------------------------------------------------------------

lazy val runJVM    = taskKey[Unit]("Build everything and launch the host with the JVM Scala child")
lazy val runNative = taskKey[Unit]("Build everything and launch the host with the Scala Native child")

def launchHost(host: File, childBin: File, log: sbt.util.Logger, baseDir: File): Unit = {
  val env = Seq(
    "SCALA_APP_BIN"  -> childBin.getAbsolutePath,
    "SSR_ASSETS_DIR" -> (baseDir / "assets").getAbsolutePath,
  )
  log.info(s"launching $host (child: $childBin)")
  val rc = scala.sys.process.Process(Seq(host.getAbsolutePath), baseDir, env *).!
  if (rc != 0) sys.error(s"host exited $rc")
}

runJVM := {
  val host = swiftBuild.value
  val log = streams.value.log
  val baseDir = (ThisBuild / baseDirectory).value
  // The Swift parent expects a single executable in SCALA_APP_BIN, so stage a
  // tiny shim that exec's `java -cp ... <main>` for it.
  val cp = (demosJVM / Compile / fullClasspath).value.map(_.data).mkString(java.io.File.pathSeparator)
  val javaBin = sys.props.getOrElse("java.home", sys.error("java.home missing")) + "/bin/java"
  val shim = baseDir / "build" / "scala-jvm-shim.sh"
  IO.createDirectory(shim.getParentFile)
  IO.write(
    shim,
    s"""#!/usr/bin/env bash
       |exec "$javaBin" -cp "$cp" "$mainClassName" "$$@"
       |""".stripMargin,
  )
  shim.setExecutable(true)
  launchHost(host, shim, log, baseDir)
}

runNative := {
  val host = swiftBuild.value
  val nativeBin = (demosNative / Compile / nativeLink).value
  launchHost(host, nativeBin, streams.value.log, (ThisBuild / baseDirectory).value)
}
