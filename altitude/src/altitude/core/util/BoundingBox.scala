package altitude.core.util

/**
 * A rectangle on the map in WGS84 decimal degrees: a viewport, or the bounds of one of its cells. `west > east` means the box
 * crosses the antimeridian and covers the longitudes from `west` to 180 and from -180 to `east`.
 */
case class BoundingBox(south: Double, west: Double, north: Double, east: Double):

  // The comparisons are false for NaN, so it is rejected as out of range
  if !(south >= -90 && south <= 90 && north >= -90 && north <= 90) then
    throw IllegalArgumentException("Latitude must be between -90 and 90")
  if !(west >= -180 && west <= 180 && east >= -180 && east <= 180) then
    throw IllegalArgumentException("Longitude must be between -180 and 180")
  if south > north then throw IllegalArgumentException("The south edge must not be north of the north edge")

  val crossesAntimeridian: Boolean = west > east

  /** The `south,west,north,east` form the client sends, so a box round-trips through [[BoundingBox.parse]] */
  override def toString: String = s"$south,$west,$north,$east"

object BoundingBox:

  /** Parses `south,west,north,east`; an `IllegalArgumentException` says what is wrong with it */
  def parse(text: String): BoundingBox =
    val parts = text.split(",", -1).map(_.trim)
    if parts.length != 4 then throw IllegalArgumentException("A bounding box is south,west,north,east")
    val values = parts.map(part => part.toDoubleOption.getOrElse(throw IllegalArgumentException(s"Not a coordinate: $part")))
    BoundingBox(south = values(0), west = values(1), north = values(2), east = values(3))
