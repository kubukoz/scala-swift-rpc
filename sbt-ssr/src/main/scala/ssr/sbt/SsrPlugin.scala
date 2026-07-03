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

package ssr.sbt

import sbt.*
import sbt.Keys.*

import java.net.URI
import java.net.URL
import scala.sys.process.*

/** sbt-ssr packages a user's Scala app together with the prebuilt Swift host
  * into a native-looking macOS `.app` bundle.
  *
  * The user provides the Scala child executable (`ssrChildBinary`) and,
  * optionally, an assets directory (`ssrAssetsDir`). The plugin obtains the
  * Swift host per `ssrHostSource`, then lays everything out under the bundle
  * contract the host's self-locating fallback expects:
  *
  * {{{
  *   <AppName>.app/
  *     Contents/
  *       Info.plist
  *       MacOS/ssr-host        (the Swift host — the bundle's executable)
  *       Resources/
  *         ssr-child           (the Scala child, exactly this name)
  *         assets/             (staged from ssrAssetsDir, exactly this name)
  * }}}
  *
  * `ssr-child` and `assets/` are a hard contract: the host, when its
  * `SCALA_APP_BIN` / `SSR_ASSETS_DIR` env vars are unset (as in a packaged
  * app), resolves both from `Bundle.main.resourcePath` under these names.
  */
object SsrPlugin extends AutoPlugin {

  override def trigger: PluginTrigger = noTrigger
  override def requires: Plugins = empty

  /** Where the Swift host binary comes from. */
  sealed trait SsrHostSource extends Product with Serializable
  object SsrHostSource {

    /** Download `ssr-app` from a GitHub release asset. The default. */
    final case class Release(tag: String) extends SsrHostSource

    /** Use a host binary already on disk (built out-of-band, vendored, etc.). */
    final case class LocalPath(binary: File) extends SsrHostSource

    /** Build the host from Swift sources via `swift build -c release`.
      * `packagePath` is the SwiftPM package directory; `product` is the built
      * executable's name under `.build/release/`.
      */
    final case class LocalBuild(packagePath: File, product: String) extends SsrHostSource
  }

  object autoImport {

    val ssrAppName = settingKey[String](
      "Display name of the produced .app bundle (e.g. \"Landmarks\")"
    )

    val ssrBundleIdentifier = settingKey[String](
      "CFBundleIdentifier for the .app (reverse-DNS, e.g. com.example.landmarks)"
    )

    val ssrChildBinary = taskKey[File](
      "The Scala child executable to stage as Contents/Resources/ssr-child " +
        "(Scala Native nativeLink output, or a launcher script for a JVM app)"
    )

    val ssrAssetsDir = settingKey[Option[File]](
      "Directory whose contents are staged under Contents/Resources/assets/ (None = no assets)"
    )

    val ssrGithubRepo = settingKey[String](
      "owner/repo the host release is fetched from (used by SsrHostSource.Release)"
    )

    val ssrHostVersion = settingKey[String](
      "Release tag to fetch the host from — defaults to \"v\" + version.value"
    )

    val ssrHostAssetName = settingKey[String](
      "Name of the release asset holding the host binary (default: ssr-app)"
    )

    val ssrHostSource = settingKey[SsrHostSource](
      "How to obtain the Swift host: Release (default), LocalPath, or LocalBuild"
    )

    val ssrHostBinary = taskKey[File](
      "Resolve the Swift host binary per ssrHostSource (fetch / locate / build)"
    )

    val ssrPackage = taskKey[File](
      "Assemble the .app bundle and return its path"
    )

    // Re-export so users can write `SsrHostSource.LocalPath(...)` in build.sbt
    // without an extra import.
    val SsrHostSource: SsrPlugin.SsrHostSource.type = SsrPlugin.SsrHostSource
  }

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] =
    Seq(
      ssrAppName := name.value,
      ssrBundleIdentifier := {
        val slug = ssrAppName.value.toLowerCase.filter(c => c.isLetterOrDigit)
        s"${organization.value}.$slug"
      },
      ssrAssetsDir := None,
      ssrHostAssetName := "ssr-app",
      ssrHostVersion := "v" + version.value,
      ssrGithubRepo := "kubukoz/scala-swift-rpc",
      ssrHostSource := SsrHostSource.Release(ssrHostVersion.value),
      ssrHostBinary := resolveHost.value,
      ssrPackage := assembleApp.value,
    )

  /** Resolve the host binary according to `ssrHostSource`. Downloads are cached
    * by asset name + tag under `target/ssr-host/`.
    */
  private def resolveHost: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val cacheDir = target.value / "ssr-host"
    ssrHostSource.value match {
      case SsrHostSource.LocalPath(binary) =>
        if (!binary.exists) sys.error(s"[sbt-ssr] host binary not found: $binary")
        log.info(s"[sbt-ssr] using host binary at $binary")
        binary

      case SsrHostSource.Release(tag) =>
        val asset = ssrHostAssetName.value
        val repo = ssrGithubRepo.value
        val dest = cacheDir / tag / asset
        if (dest.exists) {
          log.info(s"[sbt-ssr] host $repo@$tag/$asset (cached)")
        } else {
          val url =
            s"https://github.com/$repo/releases/download/$tag/$asset"
          log.info(s"[sbt-ssr] downloading host <- $url")
          IO.createDirectory(dest.getParentFile)
          download(new URI(url).toURL, dest)
        }
        dest.setExecutable(true)
        dest

      case SsrHostSource.LocalBuild(packagePath, product) =>
        log.info(s"[sbt-ssr] swift build -c release ($packagePath)")
        val rc = Process(
          Seq("swift", "build", "--package-path", packagePath.getAbsolutePath, "-c", "release")
        ).!(ProcessLogger(log.info(_), log.error(_)))
        if (rc != 0) sys.error(s"[sbt-ssr] swift build failed (exit $rc)")
        val built = packagePath / ".build" / "release" / product
        if (!built.exists) sys.error(s"[sbt-ssr] swift build produced no $built")
        built
    }
  }

  private def assembleApp: Def.Initialize[Task[File]] = Def.task {
    val log = streams.value.log
    val appName = ssrAppName.value
    val host = ssrHostBinary.value
    val child = ssrChildBinary.value
    val assets = ssrAssetsDir.value
    val bundleId = ssrBundleIdentifier.value
    val ver = version.value

    val app = target.value / s"$appName.app"
    val contents = app / "Contents"
    val macos = contents / "MacOS"
    val resources = contents / "Resources"

    // Start clean so stale files from a previous layout never linger.
    IO.delete(app)
    IO.createDirectory(macos)
    IO.createDirectory(resources)

    // Host is the bundle's executable, under a stable name.
    val hostDest = macos / "ssr-host"
    IO.copyFile(host, hostDest, preserveExecutable = true)
    hostDest.setExecutable(true)

    // Child + assets under the exact names the host's fallback expects.
    val childDest = resources / "ssr-child"
    IO.copyFile(child, childDest, preserveExecutable = true)
    childDest.setExecutable(true)

    assets match {
      case Some(dir) if dir.isDirectory =>
        val assetsDest = resources / "assets"
        IO.copyDirectory(dir, assetsDest, overwrite = true)
      case Some(dir) =>
        sys.error(s"[sbt-ssr] ssrAssetsDir is not a directory: $dir")
      case None => ()
    }

    IO.write(contents / "Info.plist", infoPlist(appName, bundleId, ver))
    IO.write(contents / "PkgInfo", "APPL????")

    log.info(s"[sbt-ssr] assembled ${app}")
    app
  }

  private def infoPlist(appName: String, bundleId: String, version: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
       |<plist version="1.0">
       |<dict>
       |  <key>CFBundleName</key>
       |  <string>$appName</string>
       |  <key>CFBundleDisplayName</key>
       |  <string>$appName</string>
       |  <key>CFBundleIdentifier</key>
       |  <string>$bundleId</string>
       |  <key>CFBundleVersion</key>
       |  <string>$version</string>
       |  <key>CFBundleShortVersionString</key>
       |  <string>$version</string>
       |  <key>CFBundleExecutable</key>
       |  <string>ssr-host</string>
       |  <key>CFBundlePackageType</key>
       |  <string>APPL</string>
       |  <key>LSMinimumSystemVersion</key>
       |  <string>13.0</string>
       |  <key>NSHighResolutionCapable</key>
       |  <true/>
       |</dict>
       |</plist>
       |""".stripMargin

  /** Follow-redirect download to `dest`. Fails loudly on non-200 so a missing
    * release surfaces as a build error rather than a truncated binary.
    */
  private def download(url: URL, dest: File): Unit = {
    var current = url
    var redirects = 0
    var done = false
    while (!done) {
      val conn = current.openConnection() match {
        case http: java.net.HttpURLConnection => http
        case other => sys.error(s"[sbt-ssr] unexpected connection: $other")
      }
      conn.setInstanceFollowRedirects(false)
      conn.setRequestProperty("User-Agent", "sbt-ssr")
      conn.getResponseCode match {
        case code if code / 100 == 3 =>
          redirects += 1
          if (redirects > 10) sys.error(s"[sbt-ssr] too many redirects fetching $url")
          val loc = conn.getHeaderField("Location")
          if (loc == null) sys.error(s"[sbt-ssr] redirect with no Location fetching $url")
          conn.disconnect()
          current = new URI(loc).toURL
        case 200 =>
          val in = conn.getInputStream
          try IO.transfer(in, dest)
          finally in.close()
          conn.disconnect()
          done = true
        case code =>
          conn.disconnect()
          sys.error(s"[sbt-ssr] host download failed: HTTP $code for $url")
      }
    }
  }

}
