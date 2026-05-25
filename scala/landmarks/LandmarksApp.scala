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

  private val DefaultWidth = 900.0
  private val DefaultHeight = 600.0

  private def assetsDir: Path = Path(
    Option(java.lang.System.getenv("SSR_ASSETS_DIR")).getOrElse(
      java.lang.System.getProperty("user.dir") + "/assets"
    )
  )

  private def landmarksApp(ctx: SSR): Resource[IO, App] =
    for {
      initialFrame <- App.loadFrame.toResource
      initialLandmarks <- LandmarkLoader.loadAll(assetsDir / "landmarkData.json").toResource
      // The mutable source of truth: the full list, with isFavorite toggleable.
      landmarksRef <- SignallingRef.of[IO, List[Landmark]](initialLandmarks).toResource
      selectedRef <- SignallingRef.of[IO, Option[Int]](None).toResource
      favOnlyRef <- SignallingRef.of[IO, Boolean](false).toResource
    } yield {
      val byIdSig: Signal[IO, Map[Int, Landmark]] = landmarksRef.map(_.iterator.map(l => l.id -> l).toMap)
      val keysSig: Signal[IO, List[Int]] = (landmarksRef: Signal[IO, List[Landmark]], favOnlyRef: Signal[IO, Boolean]).mapN { (all, favOnly) =>
        val filtered = if (favOnly) all.filter(_.isFavorite) else all
        filtered.map(_.id)
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
          sidebar = LandmarksApp.sidebar(byIdSig, keysSig, favOnlyRef, selectedRef),
          detail = LandmarksApp.detail(byIdSig, selectedRef, landmarksRef),
        ),
      )
    }

}

object LandmarksApp {

  def sidebar(
    byId: Signal[IO, Map[Int, Landmark]],
    keys: Signal[IO, List[Int]],
    favOnly: SignallingRef[IO, Boolean],
    selected: SignallingRef[IO, Option[Int]],
  ): Component = ui.vstack(
    (
      // Extra top padding so the title clears the window's titlebar
      // traffic-light buttons (window uses .fullSizeContentView).
      styles.padding := EdgeInsets.only(top = 40, leading = 12, bottom = 12, trailing = 12),
      styles.spacing := 8,
      styles.background := Background.material(Material.Sidebar),
      ui.label(("Landmarks", styles.font := Font.system(18, FontWeight.Semibold))),
      ui.toggle(
        (
          "Favorites only",
          attrs.checked <-- favOnly,
          onToggle(favOnly.set),
        )
      ),
      ui.scrollview(
        ui.vstack(
          (
            styles.spacing := 4,
            ui.children[Int](id => row(byId, id, selected)) <-- keys,
          )
        )
      ),
    )
  )

  private def row(
    byId: Signal[IO, Map[Int, Landmark]],
    id: Int,
    selected: SignallingRef[IO, Option[Int]],
  ): Component = {
    // Convert byId(id) into per-row reactive signals. The Map will always
    // contain `id` as long as this row is alive (departed keys get their
    // row component released).
    val landmark: Signal[IO, Landmark] = byId.map(_.apply(id))
    ui.hstack(
      (
        styles.padding := EdgeInsets.symmetric(horizontal = 6, vertical = 4),
        styles.spacing := 8,
        ui.image(
          (
            attrs.value <-- landmark.map(_.imageName),
            styles.frame := Frame.fixed(40, 40),
            styles.cornerRadius := 4,
          )
        ),
        ui.label(landmark.map(_.name)),
        ui.spacer,
        ui.label(landmark.map(l => if (l.isFavorite) "★" else "  ")),
        onClick(selected.set(Some(id))),
      )
    )
  }

  def detail(
    byId: Signal[IO, Map[Int, Landmark]],
    selected: SignallingRef[IO, Option[Int]],
    all: SignallingRef[IO, List[Landmark]],
  ): Component = {
    val current: Signal[IO, Option[Landmark]] =
      (selected: Signal[IO, Option[Int]], byId).mapN((sel, map) => sel.flatMap(map.get))
    val title: Signal[IO, String] = current.map(_.map(_.name).getOrElse("Select a landmark"))
    val parkLine: Signal[IO, String] = current.map(_.fold("")(l => s"${l.park} • ${l.state}"))
    val imageName: Signal[IO, String] = current.map(_.fold("")(_.imageName))
    val favoriteLabel: Signal[IO, String] = current.map {
      case None    => "Favorite"
      case Some(l) => if (l.isFavorite) "Remove from favorites" else "Add to favorites"
    }

    ui.vstack(
      (
        styles.padding := EdgeInsets.only(top = 40, leading = 24, bottom = 24, trailing = 24),
        styles.spacing := 12,
        ui.image(
          (
            attrs.value <-- imageName,
            styles.frame := Frame(width = Some(360), height = Some(240)),
            styles.cornerRadius := 8,
          )
        ),
        ui.label((title, styles.font := Font.system(24, FontWeight.Bold))),
        ui.label(parkLine),
        ui.button(
          (
            favoriteLabel,
            onClick(
              selected
                .get
                .flatMap {
                  case None => IO.unit
                  case Some(id) =>
                    all.update(_.map { l => if (l.id == id) l.copy(isFavorite = !l.isFavorite) else l })
                }
            ),
          )
        ),
      )
    )
  }

}
