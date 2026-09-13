package altitude.core.models

import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Location:
  given JsonCodec.ReadWriter[Location] = JsonCodec.macroRW
  given Conversion[ujson.Value, Location] = json => JsonCodec.read[Location](json)

/**
 * A row of the `location` table: a parent (`kind` Parent: a named container, one level deep, no pin, no assets) or a Location
 * (`kind` Location: a pin with an optional radius, optionally under a parent). Both kinds share one case-insensitive name pool
 * per repository. Membership lives in `location_asset`; an asset can be in any number of Locations. `numOfAssets` and
 * `parentName` are computed on read (`LocationDao.getAll`) and never stored.
 */
case class Location(
    id: Option[String] = None,
    name: String,
    kind: LocationKind,
    parentId: Option[String] = None,
    latitude: Option[Double] = None,
    longitude: Option[Double] = None,
    radiusM: Option[Int] = None,
    numOfAssets: Int = 0,
    parentName: Option[String] = None)
  extends BaseModel
  with NoDates:

  if name.isEmpty then throw ValidationException("Location name cannot be empty")

  kind match
    case LocationKind.Parent =>
      if parentId.isDefined || latitude.isDefined || longitude.isDefined || radiusM.isDefined then
        throw ValidationException("A parent has no pin, no radius and no parent of its own")
    case LocationKind.Location =>
      if latitude.isEmpty || longitude.isEmpty then throw ValidationException("A Location needs a pin")

  // The comparisons are false for NaN, so it is rejected as out of range
  if latitude.exists(lat => !(lat >= -90 && lat <= 90)) then throw ValidationException("Latitude must be between -90 and 90")
  if longitude.exists(lng => !(lng >= -180 && lng <= 180)) then
    throw ValidationException("Longitude must be between -180 and 180")
  if radiusM.exists(_ <= 0) then throw ValidationException("Radius must be positive")

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
