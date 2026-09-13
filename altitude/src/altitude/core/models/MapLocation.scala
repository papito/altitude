package altitude.core.models

/** A Location pinned inside the map's viewport that holds at least one matching asset, with that count and its parent's name */
case class MapLocation(id: String, name: String, parentName: Option[String], latitude: Double, longitude: Double, count: Int)
