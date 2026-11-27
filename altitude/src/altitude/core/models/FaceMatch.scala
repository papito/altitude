package altitude.core.models

/** A stored Face near a detection, as recognition votes with it: whose Face it is and its cosine distance */
case class FaceMatch(personId: String, distance: Double)
