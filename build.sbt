import scala.scalanative.build.BuildTarget

val scala3Version = "3.8.3"
val scala212Version = "2.12.21"

// ---------------------------------------------------------------------------
// iOS cross-compilation (opt-in, and implies the FFI static-library build).
// Instead of the host macOS triple, Scala Native emits arm64 iOS code and
// clang compiles / links against an iOS SDK. The resulting `.a` is linked into
// a UIKit host by the Xcode project under `ios/`. Two flavours:
//
//   -Dssr.ios=true         → iPhone Simulator (arm64-apple-ios*-simulator,
//                            iPhoneSimulator.sdk) — the default dev loop.
//   -Dssr.ios.device=true  → a physical device (arm64-apple-ios*, iPhoneOS.sdk).
//                            Needs code signing on the Xcode side.
//
// SDK paths are resolved from the active Xcode via `xcrun` at load time so we
// don't hardcode a version. Min-version and triple can be bumped freely.
// ---------------------------------------------------------------------------
val iosDevice: Boolean = sys.props.get("ssr.ios.device").contains("true")
// `iosSim` stays the umbrella "are we cross-compiling for iOS at all?" flag —
// true for either flavour — so the nativeConfig / mainClass guards below apply
// to both without churn. `iosDevice` then picks the device triple/sysroot.
val iosSim: Boolean = sys.props.get("ssr.ios").contains("true") || iosDevice

// The FFI transport (shared-memory ring doorbells) binds Mach kernel calls
// (semaphore_create/signal/wait, task_self_trap) that live only in Darwin's
// libSystem — they don't exist on Linux, so linking the Native test binary on
// a Linux CI runner fails with "undefined reference to semaphore_signal". FFI
// is a macOS/iOS-only feature by design (single-binary embedding, the App
// Store path), and iOS cross-builds only ever run on a Mac, so gating the
// whole FFI source set on the host OS being macOS keeps Linux CI's Native
// `test` link clean while leaving the macOS dev + host-release builds intact.
val isMac: Boolean = scala.util.Properties.isMac

val iosMinVersion = "17.0"

lazy val iosSysroot: String = {
  val sdk = if (iosDevice) "iphoneos" else "iphonesimulator"
  scala.sys.process.Process(Seq("xcrun", "--sdk", sdk, "--show-sdk-path")).!!.trim
}

val iosTriple =
  if (iosDevice) s"arm64-apple-ios$iosMinVersion"
  else s"arm64-apple-ios$iosMinVersion-simulator"

// Flags handed to BOTH the clang compile step and the link step: Scala Native
// shells out to clang for each, so the target/sysroot must be on both. Device
// uses -mios-version-min; simulator uses -mios-simulator-version-min.
lazy val iosFlags: Seq[String] = Seq(
  "-target", iosTriple,
  if (iosDevice) s"-mios-version-min=$iosMinVersion"
  else s"-mios-simulator-version-min=$iosMinVersion",
  "-isysroot", iosSysroot,
)

ThisBuild / tlBaseVersion := "0.1"
// MiMA derives its previous-artifact set from every git tag in the base-version
// series, but tags v0.1.1..v0.1.5 were cut while release CI was broken and never
// reached Maven Central — only 0.1.0 was ever published. Left to the default,
// `mimaReportBinaryIssues` tries to download the phantom 0.1.4 and fails with
// "not found". And 0.1.0 itself is a stale pre-POC baseline the public API has
// legitimately outgrown (App/SSR/MenuItem constructors all changed), so checking
// against it just reports noise. The published 0.1.x lineage is effectively a
// single dead point; reset the baseline and let the next release re-seed it.
ThisBuild / tlMimaPreviousVersions := Set.empty
ThisBuild / organization := "com.kubukoz"
ThisBuild / organizationName := "Jakub Kozłowski"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers := List(tlGitHubDev("kubukoz", "Jakub Kozłowski"))

ThisBuild / scalaVersion := scala3Version

// No .scalafmt.conf in this repo yet; keep the formatting gate out of CI rather
// than reformat the whole existing (Scala 3, significant-indentation) codebase.
ThisBuild / tlCiScalafmtCheck := false
ThisBuild / tlFatalWarnings := false
ThisBuild / tlJdkRelease := Some(21)

ThisBuild / githubWorkflowPublishTargetBranches := Seq(
  RefPredicate.Equals(Ref.Branch("main")),
  RefPredicate.StartsWith(Ref.Tag("v")),
)

ThisBuild / mergifyStewardConfig ~= (_.map(_.withMergeMinors(true)))

// Run the sbt-ssr plugin's scripted tests in CI, after the normal build.
ThisBuild / githubWorkflowBuildPostamble += WorkflowStep.Sbt(
  List("plugin/scripted"),
  name = Some("Scripted tests"),
)

val smithy4sVersion = "0.18.53"
val jsonrpclibVersion = "0.1.2"
val smithyVersion = "1.71.0"

lazy val root = (project in file("."))
  .aggregate(swiftCodegen)
  .aggregate(ssr.projectRefs *)
  .aggregate(testkit.projectRefs *)
  .aggregate(demos.projectRefs *)
  .aggregate(plugin)
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "ssr-root"
  )

// ---------------------------------------------------------------------------
// Swift codegen plugin (smithy-build plugin that emits WireTypes.swift)
// ---------------------------------------------------------------------------

lazy val swiftCodegen = (project in file("codegen/swift-plugin"))
  .enablePlugins(NoPublishPlugin)
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
    Test / scalaSource := (ThisBuild / baseDirectory).value / "scala" / "lib-test",
    libraryDependencies ++= Seq(
      "tech.neander" % "jsonrpclib-smithy" % jsonrpclibVersion % Smithy4s,
      "org.scalameta" %%% "munit" % "1.2.1" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
  )
  .jvmPlatform(scalaVersions = Seq(scala3Version))
  .nativePlatform(
    scalaVersions = Seq(scala3Version),
    settings = Seq(
      // Native-only sources: the FFI transport + exported C entry points that
      // let the Swift host embed the Scala app instead of spawning it. Uses
      // scala.scalanative.* APIs, so it must never reach the JVM axis — and
      // Darwin-only Mach calls, so it must never reach a non-macOS Native link
      // (see `isMac`).
      Compile / unmanagedSourceDirectories ++=
        (if (isMac) Seq((ThisBuild / baseDirectory).value / "scala" / "lib-native") else Nil),
    ),
  )

// ---------------------------------------------------------------------------
// ssr-testkit: a headless, in-process test harness for SSR apps. Builds the
// component tree through the real runtime path with a recording UiCommands in
// place of the JSON-RPC channel — no Swift host, no window, no stolen focus.
// Published as a Test-scope dependency for downstream apps.
// ---------------------------------------------------------------------------

lazy val testkit = (projectMatrix in file("scala/testkit"))
  .dependsOn(ssr)
  .settings(commonScalaSettings)
  .settings(
    name := "ssr-testkit",
    Compile / scalaSource := (ThisBuild / baseDirectory).value / "scala" / "testkit",
    Compile / unmanagedSources / excludeFilter :=
      excludeAxisTarget((ThisBuild / baseDirectory).value / "scala" / "testkit")(
        (Compile / unmanagedSources / excludeFilter).value
      ),
    Test / scalaSource := (ThisBuild / baseDirectory).value / "scala" / "testkit-test",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % "1.2.1" % Test,
      "org.typelevel" %%% "munit-cats-effect" % "2.2.0" % Test,
    ),
  )
  .jvmPlatform(scalaVersions = Seq(scala3Version))
  .nativePlatform(
    scalaVersions = Seq(scala3Version)
  )

lazy val demos = (projectMatrix in file("scala/demos"))
  .dependsOn(ssr)
  .enablePlugins(NoPublishPlugin)
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
  .jvmPlatform(scalaVersions = Seq(scala3Version))
  .nativePlatform(
    scalaVersions = Seq(scala3Version),
    settings = Seq(
      // Native-only demo sources: the `@exported ssr_init` FFI entry points so
      // the host can embed a demo instead of spawning it. They mix in
      // `SsrFfiApp` from `lib-native`, so gate them on the same macOS predicate
      // — on Linux the FFI stack is absent and these would fail to compile.
      Compile / unmanagedSourceDirectories ++=
        (if (isMac) Seq((ThisBuild / baseDirectory).value / "scala" / "demos-native") else Nil),
      // Exactly one `@exported ssr_init` may be linked (duplicate C symbol
      // otherwise). `LandmarksFfi` is the macOS FFI entry; `CounterFfi` is the
      // minimal iOS one. Select by build mode: iOS drops Landmarks (its FFI
      // entry *and* its whole component tree, which pulls in image assets),
      // everything else drops the iOS counter.
      Compile / unmanagedSources / excludeFilter := {
        val prev = (Compile / unmanagedSources / excludeFilter).value
        if (iosSim) prev || new SimpleFileFilter(_.getName == "LandmarksFfi.scala")
        else prev || new SimpleFileFilter(_.getName == "CounterFfi.scala")
      },
      // With -Dssr.ffi=true, link a static library (libssr-demos.a exporting
      // ssr_init + ScalaNativeInit) for the host to link straight into its
      // binary, instead of a standalone executable. Default (unset) keeps the
      // executable that `runNative` launches.
      //
      // Static (single binary, nothing to ship alongside — the App Store / iOS
      // endgame). Scala Native's runtime bootstrap `ScalaNativeInit` (GC,
      // threads) normally runs from a dylib-only constructor, so with a static
      // lib the host must call `ScalaNativeInit()` itself once before
      // `ssr_init()` — see swift/main.swift.
      nativeConfig := {
        val cfg = nativeConfig.value
        // -Dssr.ios=true implies the FFI static-library target (it makes no
        // sense to cross-compile an iOS executable that spawns a subprocess).
        if (iosSim) {
          import scala.scalanative.build.{GC, LTO}
          cfg
            .withBuildTarget(BuildTarget.libraryStatic)
            .withTargetTriple(iosTriple)
            .withCompileOptions(_ ++ iosFlags)
            .withLinkingOptions(_ ++ iosFlags)
            // Bring-up choices: keep the GC/LTO surface minimal on a platform
            // Scala Native doesn't officially target. Boehm would need an
            // iOS-cross-built libgc (`gc/gc.h` isn't in the simulator SDK), so
            // use Immix — Scala Native's own built-in collector, self-contained
            // with no external headers. Multithreading stays on so the embedded
            // Cats Effect pool works exactly as it does in the macOS FFI host.
            .withGC(GC.immix)
            .withLTO(LTO.none)
        } else if (sys.props.get("ssr.ffi").contains("true"))
          cfg.withBuildTarget(BuildTarget.libraryStatic)
        else cfg
      },
      // In FFI (library) mode there is no `main` entry: Scala Native only emits
      // the runtime bootstrap `ScalaNativeInit` when NO main class is set —
      // `entry.fold(genLibraryInit)(genMain)` in the toolchain. Keeping a
      // mainClass would emit `main` instead, and there'd be no ScalaNativeInit
      // for the host to call.
      Compile / mainClass := {
        if (iosSim || sys.props.get("ssr.ffi").contains("true")) None
        else (Compile / mainClass).value
      },
      // Also hide the discovered main classes in FFI mode. With mainClass unset
      // AND two candidates present (LandmarksMain + LandmarksFfi), nativeLink's
      // entry discovery would otherwise prompt "Multiple main classes detected"
      // — which stalls a batch/CI run. An empty list forces the library path.
      Compile / discoveredMainClasses := {
        if (iosSim || sys.props.get("ssr.ffi").contains("true")) Nil
        else (Compile / discoveredMainClasses).value
      },
    ),
  )

// ---------------------------------------------------------------------------
// sbt-ssr: the packaging plugin. Bundles a user's Scala app + the prebuilt
// Swift host into a macOS .app. sbt plugins are Scala 2.12 only, so this lives
// on its own axis and does NOT dependOn the Scala 3 `ssr` module (it only
// orchestrates packaging — it has no compile-time need for the library).
// ---------------------------------------------------------------------------

lazy val plugin = (project in file("sbt-ssr"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-ssr",
    scalaVersion := scala212Version,
    crossScalaVersions := Seq(scala212Version),
    // The .app fallback contract the host relies on (see host self-locating
    // fallback): child binary `ssr-child`, assets under `assets/`. The plugin
    // stakes these names, the host reads them.
    pluginCrossBuild / sbtVersion := "1.9.8",
    scriptedLaunchOpts ++= Seq("-Xmx1024M", "-Dplugin.version=" + version.value),
    scriptedBufferLog := false,
  )

lazy val demosJVM    = demos.jvm(scala3Version)
lazy val demosNative = demos.native(scala3Version)


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

// ---------------------------------------------------------------------------
// FFI (embedded) build & run. The Scala Native app is linked into the Swift
// host as a static library exporting `ssr_init`; no subprocess. This is the
// single-binary path toward App Store / iOS distribution.
//
// The native-library build target is gated behind -Dssr.ffi=true (see the
// demos native `nativeConfig`), so this task chain must be run in an sbt
// launched with that flag:
//
//   sbt -Dssr.ffi=true runFfi
// ---------------------------------------------------------------------------

lazy val runFfi = taskKey[Unit]("Build the embedded (FFI) host with the Scala app linked in, and launch it")

def buildFfiHost(swiftDir: File, staticLib: File, log: sbt.util.Logger): File = {
  if (!staticLib.getName.endsWith(".a"))
    sys.error(s"expected a static library (.a), got $staticLib — run with -Dssr.ffi=true")
  log.info(s"swift build -c release (FFI, linking ${staticLib.getName})")
  val rc = scala.sys.process
    .Process(
      Seq("swift", "build", "--package-path", swiftDir.getAbsolutePath, "-c", "release"),
      swiftDir,
      "SSR_FFI_LIB" -> staticLib.getAbsolutePath,
    )
    .!(scala.sys.process.ProcessLogger(log.info(_), log.error(_)))
  if (rc != 0) sys.error(s"swift build (FFI) failed (exit $rc)")
  val built = swiftDir / ".build" / "release" / "ssr-host"
  if (!built.exists) sys.error(s"swift build did not produce $built")
  built
}

runFfi := {
  if (!sys.props.get("ssr.ffi").contains("true"))
    sys.error("runFfi requires the static-library build target — start sbt with -Dssr.ffi=true")
  val log = streams.value.log
  val baseDir = (ThisBuild / baseDirectory).value
  val swiftDir = (ThisBuild / swiftSourceDir).value
  // Ensure the Swift wire types exist before building the host.
  val _ = swiftCodegenOutput.value
  val staticLib = (demosNative / Compile / nativeLink).value
  val host = buildFfiHost(swiftDir, staticLib, log)
  val env = Seq(
    "SSR_FFI"        -> "1",
    "SSR_ASSETS_DIR" -> (baseDir / "assets").getAbsolutePath,
  )
  log.info(s"launching embedded host $host")
  val rc = scala.sys.process.Process(Seq(host.getAbsolutePath), baseDir, env *).!
  if (rc != 0) sys.error(s"host exited $rc")
}
