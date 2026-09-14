package altitude.core.util

import scala.util.Try

import altitude.core.models.ExtractedMetadata
import altitude.core.models.GeoPoint

/**
 * Where an asset was captured, from its persisted metadata alone, so resolution can be replayed later without the file. The EXIF
 * `GPS` directory is tried first, then a phone video's `QuickTime Metadata` / `ISO 6709` string, then the `MP4` directory's
 * decimal `Latitude` and `Longitude`.
 *
 * metadata-extractor describes `GPS Latitude` / `GPS Longitude` as signed degrees-minutes-seconds strings, already converted
 * through the ref (`33° 51' 25.2"`, `-33° 51' 25.2"`). The degree part is an integer, so a value between -1 and 0 is described
 * without its sign; the `GPS Latitude Ref` / `GPS Longitude Ref` tags (`N`/`S`, `E`/`W`) are stored beside it and decide the
 * hemisphere whenever present.
 */
object GeoLocationResolver:
  // Degrees, minutes and seconds in the extractor's own format; the decimal separator follows the JVM's formatting locale
  private val Dms = """^(-?)(\d+(?:[.,]\d+)?)°\s*(\d+(?:[.,]\d+)?)'\s*(\d+(?:[.,]\d+)?)"$""".r

  // ISO 6709 as a phone writes it, `+37.3318-122.0312+015.000/`: a signed latitude, a signed longitude, an optional altitude
  private val Iso6709 = """^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)(?:[+-]\d+(?:\.\d+)?)?/?$""".r

  /** Decimal degrees, or None when no source has both coordinates in range, or both are 0 (no fix) */
  def resolve(extractedMetadata: ExtractedMetadata): Option[GeoPoint] =
    gps(extractedMetadata).orElse(iso6709(extractedMetadata)).orElse(mp4(extractedMetadata))

  private def gps(extractedMetadata: ExtractedMetadata): Option[GeoPoint] =
    val fields = extractedMetadata.getFieldValues("GPS")
    point(
      dms(fields.get("GPS Latitude"), fields.get("GPS Latitude Ref"), negativeRef = "S"),
      dms(fields.get("GPS Longitude"), fields.get("GPS Longitude Ref"), negativeRef = "W")
    )

  private def iso6709(extractedMetadata: ExtractedMetadata): Option[GeoPoint] =
    extractedMetadata.getFieldValues("QuickTime Metadata").get("ISO 6709").map(_.trim).flatMap {
      case Iso6709(latitude, longitude) =>
        point(Some(sexagesimal(latitude, degreeDigits = 2)), Some(sexagesimal(longitude, degreeDigits = 3)))
      case _ => None
    }

  private def mp4(extractedMetadata: ExtractedMetadata): Option[GeoPoint] =
    val fields = extractedMetadata.getFieldValues("MP4")
    point(fields.get("Latitude").flatMap(decimalDegrees), fields.get("Longitude").flatMap(decimalDegrees))

  private def point(latitude: Option[Double], longitude: Option[Double]): Option[GeoPoint] =
    for
      lat <- latitude if lat.abs <= 90
      lon <- longitude if lon.abs <= 180
      if lat != 0 || lon != 0
    yield GeoPoint(lat, lon)

  private def dms(description: Option[String], ref: Option[String], negativeRef: String): Option[Double] =
    description.map(_.trim).collect {
      case Dms(sign, degrees, minutes, seconds) =>
        val magnitude = decimal(degrees) + decimal(minutes) / 60 + decimal(seconds) / 3600
        // Like the extractor itself: the ref is negative only when it names the southern / western hemisphere
        val negative = ref.map(_.trim.equalsIgnoreCase(negativeRef)).getOrElse(sign == "-")
        if negative then -magnitude else magnitude
    }

  /**
   * An ISO 6709 coordinate is degrees, or degrees and minutes, or degrees, minutes and seconds, told apart by how many digits
   * precede the decimal point beyond the degrees: `+4012.22` is 40° 12.22'
   */
  private def sexagesimal(raw: String, degreeDigits: Int): Double =
    val sign = if raw.startsWith("-") then -1 else 1
    val unsigned = raw.drop(1)
    val integerDigits = unsigned.takeWhile(_.isDigit)
    val magnitude = integerDigits.length - degreeDigits match
      case 2 => unsigned.take(degreeDigits).toDouble + unsigned.drop(degreeDigits).toDouble / 60
      case 4 =>
        unsigned.take(degreeDigits).toDouble + unsigned.slice(degreeDigits, degreeDigits + 2).toDouble / 60 +
          unsigned.drop(degreeDigits + 2).toDouble / 3600
      case _ => unsigned.toDouble
    sign * magnitude

  private def decimalDegrees(raw: String): Option[Double] = Try(raw.trim.replace(',', '.').toDouble).toOption

  private def decimal(raw: String): Double = raw.replace(',', '.').toDouble
