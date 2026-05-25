$version: "2.0"

namespace ssr.landmarks

structure Landmark {
    @required
    id: Integer
    @required
    name: String
    @required
    park: String
    @required
    state: String
    @required
    continent: String
    @required
    imageName: String
    @required
    isFavorite: Boolean
    @required
    isFeatured: Boolean
    @required
    description: String
}

list Landmarks {
    member: Landmark
}
