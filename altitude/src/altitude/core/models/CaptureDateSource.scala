package altitude.core.models

import altitude.core.util.JsonCodec

/** Which metadata rung produced a capture time. Persist dbValue, never the enum name. */
enum CaptureDateSource(val dbValue: String):
  case ExifOriginal extends CaptureDateSource("exif_original")
  case ExifDigitized extends CaptureDateSource("exif_digitized")
  case ExifFileChange extends CaptureDateSource("exif_file_change")
  case XmpCreateDate extends CaptureDateSource("xmp_create_date")
  case IptcCreated extends CaptureDateSource("iptc_created")
  case PngCreationTime extends CaptureDateSource("png_creation_time")
  case PngModified extends CaptureDateSource("png_modified")
  case GpsTimestamp extends CaptureDateSource("gps_timestamp")
  case FileName extends CaptureDateSource("filename")

object CaptureDateSource:
  def fromDbValue(value: String): Option[CaptureDateSource] = values.find(_.dbValue == value)

  // Stored unknown sources can degrade to None through fromDbValue; JSON requires a recognized source.
  given JsonCodec.ReadWriter[CaptureDateSource] = JsonCodec
    .readwriter[String]
    .bimap(
      _.dbValue,
      value => fromDbValue(value).getOrElse(throw IllegalArgumentException(s"Unknown capture date source: $value")))
