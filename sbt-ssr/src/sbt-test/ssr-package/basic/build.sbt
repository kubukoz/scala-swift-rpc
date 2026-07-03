import ssr.sbt.SsrPlugin.SsrHostSource

lazy val root = project
  .in(file("."))
  .enablePlugins(SsrPlugin)
  .settings(
    scalaVersion := "2.12.21",
    version := "9.9.9",
    organization := "com.example",
    ssrAppName := "Demo",
    // Use the LocalPath host tier so the test needs neither network nor swiftc.
    ssrHostSource := SsrHostSource.LocalPath(baseDirectory.value / "stub-host"),
    ssrChildBinary := baseDirectory.value / "stub-child",
    ssrAssetsDir := Some(baseDirectory.value / "assets"),
  )

// --- assertions, driven from the `test` script ---

val checkLayout = taskKey[Unit]("The assembled .app matches the bundle contract")
checkLayout := {
  val app = ssrPackage.value
  val contents = app / "Contents"
  def must(f: File) = assert(f.exists, s"missing: $f")

  must(app)
  must(contents / "Info.plist")
  must(contents / "MacOS" / "ssr-host")
  must(contents / "Resources" / "ssr-child")
  must(contents / "Resources" / "assets" / "hello.txt")

  // Host and child are staged verbatim under their contract names.
  assert(
    IO.read(contents / "MacOS" / "ssr-host") == IO.read(baseDirectory.value / "stub-host"),
    "host not staged verbatim",
  )
  assert(
    IO.read(contents / "Resources" / "ssr-child") == IO.read(baseDirectory.value / "stub-child"),
    "child not staged verbatim",
  )

  // Info.plist wires the host as the bundle executable and carries our version.
  val plist = IO.read(contents / "Info.plist")
  assert(plist.contains("<string>ssr-host</string>"), s"plist missing CFBundleExecutable:\n$plist")
  assert(plist.contains("<string>9.9.9</string>"), s"plist missing version:\n$plist")
  assert(plist.contains("com.example.demo"), s"plist missing bundle id:\n$plist")

  // The staged executables keep the executable bit.
  assert((contents / "MacOS" / "ssr-host").canExecute, "host not executable")
  assert((contents / "Resources" / "ssr-child").canExecute, "child not executable")
}

val plantStray = taskKey[Unit]("Drop a stray file into a previously assembled bundle")
plantStray := {
  val stray = target.value / "Demo.app" / "Contents" / "Resources" / "STALE"
  IO.write(stray, "old")
  assert(stray.exists, "failed to plant stray file")
}

val checkNoStray = taskKey[Unit]("The stray file must be gone after re-packaging")
checkNoStray := {
  val stray = target.value / "Demo.app" / "Contents" / "Resources" / "STALE"
  assert(!stray.exists, "stale file survived re-package")
}
