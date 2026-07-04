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

package ssr.landmarks

import cats.effect.IO
import cats.effect.Resource
import ssr.App
import ssr.SSR
import ssr.SsrFfiApp

import scala.scalanative.unsafe.*

// FFI entry point for the Landmarks demo: the same `render` as `LandmarksMain`
// (the stdio/subprocess build), but exposed as an `@exported ssr_init` so the
// Swift host can link this app in as a static library and boot it in-process
// instead of spawning it. Build with `demosNative3/nativeLink` under the
// library-static build target (see build.sbt `ssrFfi`).
object LandmarksFfi extends SsrFfiApp {
  def render(ctx: SSR): Resource[IO, App] = LandmarksMain.render(ctx)

  @exported("ssr_init")
  def ssrInit(): Ptr[Byte] = boot()
}
