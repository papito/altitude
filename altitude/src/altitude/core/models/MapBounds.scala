package altitude.core.models

/**
 * The box around every point a search plots and how many there are, for fitting the map to a result. A set of points on both
 * sides of the antimeridian spans the whole longitude range, which is the wider of the two boxes that contain it.
 */
case class MapBounds(south: Double, west: Double, north: Double, east: Double, count: Int)
