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
