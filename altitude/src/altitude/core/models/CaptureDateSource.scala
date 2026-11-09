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
  case QuickTimeCreationDate extends CaptureDateSource("qt_creation_date")
  case PngModified extends CaptureDateSource("png_modified")
  case ContainerCreationTime extends CaptureDateSource("container_creation_time")
  case GpsTimestamp extends CaptureDateSource("gps_timestamp")
  case FileName extends CaptureDateSource("filename")

  /** Where the Date Taken came from, as the Detail panel says it: "from EXIF original" */
  def label: String = this match
    case ExifOriginal => "EXIF original"
    case ExifDigitized => "EXIF digitized"
    case ExifFileChange => "EXIF file change"
    case XmpCreateDate => "XMP"
    case IptcCreated => "IPTC"
    case PngCreationTime => "PNG creation time"
    case QuickTimeCreationDate => "QuickTime"
    case PngModified => "PNG modified"
    case ContainerCreationTime => "the container"
    case GpsTimestamp => "GPS"
    case FileName => "the file name"

object CaptureDateSource:
  def fromDbValue(value: String): Option[CaptureDateSource] = values.find(_.dbValue == value)

  // Stored unknown sources can degrade to None through fromDbValue; JSON requires a recognized source.
  given JsonCodec.ReadWriter[CaptureDateSource] = JsonCodec
    .readwriter[String]
    .bimap(
      _.dbValue,
      value => fromDbValue(value).getOrElse(throw IllegalArgumentException(s"Unknown capture date source: $value")))
