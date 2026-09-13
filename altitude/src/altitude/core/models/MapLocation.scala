package altitude.core.models

/** A Location pinned inside the map's viewport that holds at least one matching asset, with that count and its category's name */
case class MapLocation(id: String, name: String, categoryName: Option[String], latitude: Double, longitude: Double, count: Int)
