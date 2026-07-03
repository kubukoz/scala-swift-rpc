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
import ssr.*

object Sidebar {

  def render(
    byId: Signal[IO, Map[Int, Landmark]],
    visibleIds: Signal[IO, List[Int]],
    section: SignallingRef[IO, Section],
    query: SignallingRef[IO, String],
    selected: SignallingRef[IO, Option[Int]],
  ): Component = ui.vstack(
    styles.padding := EdgeInsets.only(top = 40, leading = 12, bottom = 12, trailing = 12),
    styles.spacing := 10,
    styles.background := Background.material(Material.Sidebar),
    ui.label(
      "Landmarks",
      styles.font := Font.system(22, FontWeight.Bold),
    ),
    // Search field
    ui.textfield(
      attrs.value <-- query,
      onInput(query.set),
    ),
    // Section selector — three "pills" laid out horizontally
    sectionSelector(section),
    ui.divider,
    // Filtered list
    ui.scrollview(
      ui.vstack(
        styles.spacing := 2,
        ui.children[Int](id => row(byId, id, selected)) <-- visibleIds,
      )
    ),
  )

  private def sectionSelector(section: SignallingRef[IO, Section]): Component = ui.hstack(
    styles.spacing := 4,
    Section.values.toList.map(s => sectionButton(s, section)),
  )

  private def sectionButton(target: Section, section: SignallingRef[IO, Section]): Component = {
    val base = target match {
      case Section.All        => "All"
      case Section.Favorites  => "★ Favorites"
      case Section.Collection => "Collection"
    }
    val label: Signal[IO, String] =
      section.map(cur => if (cur == target) s"• $base" else base)
    ui.button(
      label,
      onClick(section.set(target)),
    )
  }

  private def row(
    byId: Signal[IO, Map[Int, Landmark]],
    id: Int,
    selected: SignallingRef[IO, Option[Int]],
  ): Component = {
    val landmark: Signal[IO, Landmark] = byId.map(_.apply(id))
    ui.hstack(
      styles.padding := EdgeInsets.symmetric(horizontal = 6, vertical = 4),
      styles.spacing := 10,
      ui.image(
        attrs.value <-- landmark.map(l => s"${l.imageName}-thumb"),
        styles.frame := Frame.fixed(44, 44),
        styles.cornerRadius := 6,
      ),
      ui.vstack(
        styles.spacing := 2,
        styles.alignment := Alignment.Leading,
        ui.label(
          landmark.map(_.name),
          styles.font := Font.system(14, FontWeight.Medium),
        ),
        ui.label(
          landmark.map(_.park),
          styles.font := Font.system(11),
          styles.foreground := Color.hex("#888888"),
        ),
      ),
      ui.spacer,
      ui.label(landmark.map(l => if (l.isFavorite) "★" else "")),
      onClick(selected.set(Some(id))),
    )
  }

}
