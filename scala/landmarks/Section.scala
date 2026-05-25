package ssr.landmarks

enum Section(val label: String) {
  case All extends Section("All Landmarks")
  case Favorites extends Section("Favorites")
  case Collection extends Section("Collection")
}
