package ssr.landmarks

import cats.effect.*
import cats.syntax.all.*
import fs2.concurrent.Signal
import fs2.concurrent.SignallingRef
import ssr.*
import ssr.internal.protocol.UiCommands

object Detail {

  def render(
    byId: Signal[IO, Map[Int, Landmark]],
    selected: SignallingRef[IO, Option[Int]],
    all: SignallingRef[IO, List[Landmark]],
    collection: SignallingRef[IO, Set[Int]],
    openPanelResult: SignallingRef[IO, Option[String]],
    emit: UiCommands[IO],
  ): Component = ui.vstack(
    (
      styles.padding := EdgeInsets.only(top = 40, leading = 24, bottom = 24, trailing = 24),
      styles.spacing := 18,
      // Swap the body when the selection changes. Single-key children — when
      // the key changes, the previous body is fully released and a fresh
      // body is allocated for the new key.
      ui.children[Option[Int]] { key =>
        key match {
          case None     => welcome(byId, all, selected)
          case Some(id) => landmark(byId, id, all, collection, openPanelResult, emit)
        }
      } <-- (selected: Signal[IO, Option[Int]]).map(List(_)),
    )
  )

  // ------------------ Welcome / Featured landing ------------------

  private def welcome(
    byId: Signal[IO, Map[Int, Landmark]],
    all: SignallingRef[IO, List[Landmark]],
    selected: SignallingRef[IO, Option[Int]],
  ): Component = {
    val featuredIds: Signal[IO, List[Int]] =
      all.map(_.filter(_.isFeatured).map(_.id))
    ui.vstack(
      (
        styles.spacing := 18,
        styles.alignment := Alignment.Leading,
        ui.label(
          (
            "Welcome to Landmarks",
            styles.font := Font.system(34, FontWeight.Bold),
          )
        ),
        ui.label(
          (
            "Explore extraordinary places around the world. Pick a landmark from the sidebar to start, or jump in below.",
            styles.font := Font.system(14),
            styles.foreground := Color.hex("#666666"),
            styles.frame := Frame(maxWidth = Some(620)),
          )
        ),
        ui.label(
          (
            "Featured",
            styles.font := Font.system(20, FontWeight.Semibold),
          )
        ),
        ui.hscrollview(
          (
            styles.spacing := 14,
            ui.children[Int](id => featuredCard(byId, id, selected)) <-- featuredIds,
          )
        ),
      )
    )
  }

  private def featuredCard(
    byId: Signal[IO, Map[Int, Landmark]],
    id: Int,
    selected: SignallingRef[IO, Option[Int]],
  ): Component = {
    val landmark: Signal[IO, Landmark] = byId.map(_.apply(id))
    ui.vstack(
      (
        styles.spacing := 6,
        styles.frame := Frame(width = Some(260)),
        ui.zstack(
          (
            ui.image(
              (
                attrs.value <-- landmark.map(_.imageName),
                styles.frame := Frame.fixed(260, 170),
                styles.cornerRadius := 12,
              )
            ),
            ui.vstack(
              (
                styles.padding := EdgeInsets.all(10),
                styles.alignment := Alignment.Leading,
                styles.spacing := 2,
                ui.label(
                  (
                    landmark.map(_.name),
                    styles.font := Font.system(15, FontWeight.Semibold),
                    styles.foreground := Color.hex("#ffffff"),
                  )
                ),
                ui.label(
                  (
                    landmark.map(_.state),
                    styles.font := Font.system(11),
                    styles.foreground := Color.hex("#eeeeee"),
                  )
                ),
              )
            ),
          )
        ),
        ui.button(("View", onClick(selected.set(Some(id))))),
      )
    )
  }

  // ------------------ Landmark detail ------------------

  private def landmark(
    byId: Signal[IO, Map[Int, Landmark]],
    id: Int,
    all: SignallingRef[IO, List[Landmark]],
    collection: SignallingRef[IO, Set[Int]],
    openPanelResult: SignallingRef[IO, Option[String]],
    emit: UiCommands[IO],
  ): Component = {
    val landmark: Signal[IO, Landmark] = byId.map(_.apply(id))
    val isFav: Signal[IO, Boolean] = landmark.map(_.isFavorite)
    val inCollection: Signal[IO, Boolean] = collection.map(_.contains(id))

    def toggleFav: IO[Unit] = all.update(
      _.map(l => if (l.id == id) l.copy(isFavorite = !l.isFavorite) else l)
    )
    def toggleCollection: IO[Unit] = collection.update(s =>
      if (s.contains(id)) s - id else s + id
    )

    ui.vstack(
      (
        styles.spacing := 16,
        styles.alignment := Alignment.Leading,
        // Hero with overlaid title
        ui.zstack(
          (
            ui.image(
              (
                attrs.value <-- landmark.map(_.imageName),
                styles.frame := Frame.fixed(640, 320),
                styles.cornerRadius := 14,
              )
            ),
            ui.vstack(
              (
                styles.padding := EdgeInsets.all(18),
                styles.alignment := Alignment.Leading,
                styles.spacing := 4,
                ui.label(
                  (
                    landmark.map(_.name),
                    styles.font := Font.system(30, FontWeight.Bold),
                    styles.foreground := Color.hex("#ffffff"),
                  )
                ),
                ui.label(
                  (
                    landmark.map(_.continent),
                    styles.font := Font.system(13, FontWeight.Medium),
                    styles.foreground := Color.hex("#eeeeee"),
                  )
                ),
              )
            ),
          )
        ),
        // Location line
        ui.label(
          (
            landmark.map(_.state),
            styles.font := Font.system(15, FontWeight.Medium),
            styles.foreground := Color.hex("#aaaaaa"),
          )
        ),
        // Description (multi-line)
        ui.label(
          (
            landmark.map(_.description),
            styles.font := Font.system(14),
            styles.frame := Frame(maxWidth = Some(640)),
          )
        ),
        // Badges row with liquid glass background
        badgesRow(landmark),
        // Actions row
        ui.hstack(
          (
            styles.spacing := 10,
            ui.button(
              (
                isFav.map(b => if (b) "★ Remove from Favorites" else "☆ Add to Favorites"),
                onClick(toggleFav),
              )
            ),
            ui.button(
              (
                inCollection.map(b => if (b) "✓ In Collection" else "+ Add to Collection"),
                onClick(toggleCollection),
              )
            ),
            ui.button(
              (
                "📁 Open…",
                onClick(
                  emit
                    .openPanel(title = Some("Choose a file"))
                    .flatMap(out => openPanelResult.set(out.path))
                ),
              )
            ),
          )
        ),
        // Selected-file readout (demo for Scala→Swift request/response)
        ui.label(
          (
            (openPanelResult: Signal[IO, Option[String]]).map {
              case Some(p) => s"Selected: $p"
              case None    => ""
            },
            styles.font := Font.system(12),
            styles.foreground := Color.hex("#888888"),
          )
        ),
      )
    )
  }

  // Pseudo-badges — small chips highlighting categories. The container uses
  // a glass-effect background so Landmarks on macOS 26+ shows true Liquid
  // Glass; older systems get a frosted approximation.
  private def badgesRow(landmark: Signal[IO, Landmark]): Component = ui.hstack(
    (
      styles.spacing := 8,
      styles.padding := EdgeInsets.symmetric(horizontal = 14, vertical = 10),
      styles.background := Background.material(Material.Glass),
      styles.cornerRadius := 14,
      badge("Visited", landmark.map(_ => true)),
      badge("Photographed", landmark.map(l => l.isFeatured)),
      badge("Hiked", landmark.map(l => l.isFavorite)),
      badge("Camped", landmark.map(_ => false)),
    )
  )

  private def badge(name: String, earned: Signal[IO, Boolean]): Component = ui.vstack(
    (
      styles.spacing := 2,
      styles.padding := EdgeInsets.symmetric(horizontal = 10, vertical = 6),
      styles.alignment := Alignment.Center,
      ui.label(
        (
          earned.map(b => if (b) "●" else "○"),
          styles.font := Font.system(18, FontWeight.Bold),
        )
      ),
      ui.label(
        (
          name,
          styles.font := Font.system(10, FontWeight.Medium),
        )
      ),
    )
  )

}
