package ssr.landmarks

import cats.effect.*
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.file.Path
import smithy4s.Blob
import smithy4s.json.Json

object LandmarkLoader {

  def loadAll(file: Path): IO[List[Landmark]] = Files[IO]
    .readAll(file)
    .compile
    .to(Array)
    .flatMap(bytes =>
      IO.fromEither(Json.read[Landmarks](Blob(bytes)).leftMap(e => new RuntimeException(e.toString)))
    )
    .map(_.value)

}
