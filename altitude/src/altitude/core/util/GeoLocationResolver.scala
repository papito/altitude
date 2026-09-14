package altitude.core.util

import altitude.core.models.ExtractedMetadata
import altitude.core.models.GeoPoint

/**
 * Where a photo was taken, from the persisted GPS metadata alone, so resolution can be replayed later without the file.
 *
 * metadata-extractor describes `GPS Latitude` / `GPS Longitude` as signed degrees-minutes-seconds strings, already converted
 * through the ref (`33° 51' 25.2"`, `-33° 51' 25.2"`). The degree part is an integer, so a value between -1 and 0 is described
 * without its sign; the `GPS Latitude Ref` / `GPS Longitude Ref` tags (`N`/`S`, `E`/`W`) are stored beside it and decide the
 * hemisphere whenever present.
 */
object GeoLocationResolver:
  private val Directory = "GPS"

  // Degrees, minutes and seconds in the extractor's own format; the decimal separator follows the JVM's formatting locale
  private val Dms = """^(-?)(\d+(?:[.,]\d+)?)°\s*(\d+(?:[.,]\d+)?)'\s*(\d+(?:[.,]\d+)?)"$""".r

  /** Decimal degrees, or None when either coordinate is missing, unparseable or out of range, or both are 0 (no fix) */
  def resolve(extractedMetadata: ExtractedMetadata): Option[GeoPoint] =
    val gps = extractedMetadata.getFieldValues(Directory)
    for
      latitude <- coordinate(gps.get("GPS Latitude"), gps.get("GPS Latitude Ref"), negativeRef = "S", limit = 90)
      longitude <- coordinate(gps.get("GPS Longitude"), gps.get("GPS Longitude Ref"), negativeRef = "W", limit = 180)
      if latitude != 0 || longitude != 0
    yield GeoPoint(latitude, longitude)

  private def coordinate(description: Option[String], ref: Option[String], negativeRef: String, limit: Double): Option[Double] =
    description
      .map(_.trim)
      .collect {
        case Dms(sign, degrees, minutes, seconds) =>
          val magnitude = decimal(degrees) + decimal(minutes) / 60 + decimal(seconds) / 3600
          // Like the extractor itself: the ref is negative only when it names the southern / western hemisphere
          val negative = ref.map(_.trim.equalsIgnoreCase(negativeRef)).getOrElse(sign == "-")
          if negative then -magnitude else magnitude
      }
      .filter(value => value.abs <= limit)

  private def decimal(raw: String): Double = raw.replace(',', '.').toDouble
