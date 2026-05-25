package ssr.landmarks

import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import fs2.io.file.Path
import ssr.*
import ssr.internal.protocol.MenuItem
import ssr.internal.protocol.SetWindowInput

object LandmarksMain extends IOApp.Simple {

  def run: IO[Unit] = App.bootstrap(landmarksApp)

  private val DefaultWidth = 1100.0
  private val DefaultHeight = 720.0

  private def assetsDir: Path = Path(
    Option(java.lang.System.getenv("SSR_ASSETS_DIR")).getOrElse(
      java.lang.System.getProperty("user.dir") + "/assets"
    )
  )

  private def landmarksApp(ctx: SSR): Resource[IO, App] =
    for {
      initialFrame <- App.loadFrame.toResource
      initialLandmarks <- LandmarkLoader.loadAll(assetsDir / "landmarkData.json").toResource
      landmarksRef <- SignallingRef.of[IO, List[Landmark]](initialLandmarks).toResource
      selectedRef <- SignallingRef.of[IO, Option[Int]](None).toResource
      sectionRef <- SignallingRef.of[IO, Section](Section.All).toResource
      collectionRef <- SignallingRef.of[IO, Set[Int]](Set.empty).toResource
      queryRef <- SignallingRef.of[IO, String]("").toResource
    } yield {
      val byIdSig: Signal[IO, Map[Int, Landmark]] =
        landmarksRef.map(_.iterator.map(l => l.id -> l).toMap)

      val visibleIdsSig: Signal[IO, List[Int]] = (
        landmarksRef: Signal[IO, List[Landmark]],
        sectionRef: Signal[IO, Section],
        collectionRef: Signal[IO, Set[Int]],
        queryRef: Signal[IO, String],
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

      val menu = List(
        MenuItem(
          title = "Landmarks",
          children = Some(
            List(
              MenuItem(title = "Quit", id = Some(App.QuitMenuId), key = Some("cmd+q"))
            )
          ),
        )
      )

      App(
        window = Signal.constant(window),
        menu = Signal.constant(menu),
        component = ui.splitview(
          sidebar = Sidebar.render(byIdSig, visibleIdsSig, sectionRef, queryRef, selectedRef),
          detail = Detail.render(byIdSig, selectedRef, landmarksRef, collectionRef),
        ),
      )
    }

}
