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

import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Path
import ssr.*
import ssr.internal.protocol.SetWindowInput

object LandmarksMain extends SSRApp {

  private val DefaultWidth = 1100.0
  private val DefaultHeight = 720.0

  private def assetsDir: Path = Path(
    Option(java.lang.System.getenv("SSR_ASSETS_DIR")).getOrElse(
      java.lang.System.getProperty("user.dir") + "/assets"
    )
  )

  def render(ctx: SSR): Resource[IO, App] = {
    given SSR = ctx
    for {
      initialFrame <- App.loadFrame.toResource
      initialLandmarks <- LandmarkLoader.loadAll(assetsDir / "landmarkData.json").toResource
      landmarksRef <- SignallingRef.of[IO, List[Landmark]](initialLandmarks).toResource
      selectedRef <- SignallingRef.of[IO, Option[Int]](None).toResource
      sectionRef <- SignallingRef.of[IO, Section](Section.All).toResource
      collectionRef <- SignallingRef.of[IO, Set[Int]](Set.empty).toResource
      queryRef <- SignallingRef.of[IO, String]("").toResource
      openPanelResult <- SignallingRef.of[IO, Option[String]](None).toResource
      menuSignal <- appMenu(
        menu.menu("Landmarks")(
          menu.item("Quit", key = Some("cmd+q"))(ctx.emit.quit().void)
        )
      )
    } yield {
      val byIdSig: Signal[IO, Map[Int, Landmark]] =
        landmarksRef.map(_.iterator.map(l => l.id -> l).toMap)

      val visibleIdsSig: Signal[IO, List[Int]] = (
        landmarksRef: Signal[IO, List[Landmark]],
        sectionRef,
        collectionRef,
        queryRef,
      ).mapN { (all, section, coll, q) =>
        val filteredBySection = section match {
          case Section.All        => all
          case Section.Favorites  => all.filter(_.isFavorite)
          case Section.Collection => all.filter(l => coll.contains(l.id))
        }
        val needle = q.trim.toLowerCase
        val filteredByQuery =
          if (needle.isEmpty) filteredBySection
          else filteredBySection.filter(_.name.toLowerCase.contains(needle))
        filteredByQuery.map(_.id)
      }

      val window = SetWindowInput(
        width = initialFrame.map(_.width).getOrElse(DefaultWidth),
        height = initialFrame.map(_.height).getOrElse(DefaultHeight),
        x = initialFrame.map(_.x),
        y = initialFrame.map(_.y),
        screen = if (initialFrame.isEmpty) Some("main") else None,
      )

      App(
        window = Signal.constant(window),
        menu = menuSignal,
        component = ui.splitview(
          sidebar = Sidebar.render(byIdSig, visibleIdsSig, sectionRef, queryRef, selectedRef),
          detail = Detail.render(
            byIdSig,
            selectedRef,
            landmarksRef,
            collectionRef,
            openPanelResult,
            ctx.emit,
          ),
        ),
      )
    }
  }

}
