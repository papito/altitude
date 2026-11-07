package altitude.core.util

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale

import scala.util.Try
import scala.util.matching.Regex

import altitude.core.models.CaptureDateSource
import altitude.core.models.ExtractedMetadata

case class CaptureDate(at: LocalDateTime, source: CaptureDateSource)

/** Only persisted inputs: resolution can be replayed later without reading the original image. */
case class CaptureDateInputs(extractedMetadata: ExtractedMetadata, fileName: String)

object CaptureDateResolver:
  /** The first surviving photograph; older values cannot describe a photographic capture. */
  val EarliestPlausible: LocalDateTime = LocalDateTime.of(1826, 1, 1, 0, 0)

  /** Allows a camera in UTC+14 to read ahead of the UTC import moment, plus clock drift. */
  val FutureTolerance: Duration = Duration.ofDays(2)
  val Sentinels: Set[LocalDateTime] = Set(1970, 1904, 1980).map(LocalDateTime.of(_, 1, 1, 0, 0))

  /** The camera's wall-clock capture time, or None when no candidate is plausible. No import-time fallback. */
  def resolve(inputs: CaptureDateInputs, notLaterThan: LocalDateTime): Option[CaptureDate] =
    candidates(inputs).find {
      candidate =>
        // An unset clock is its sentinel second whatever subsecond the camera adds to it
        !candidate.at.isBefore(EarliestPlausible) && !candidate.at.isAfter(notLaterThan.plus(FutureTolerance)) &&
        !Sentinels.contains(candidate.at.withNano(0))
    }

  /** Parsed candidates in priority order, before plausibility filtering; useful for diagnostics. */
  private[core] def candidates(inputs: CaptureDateInputs): List[CaptureDate] =
    import CaptureDateSource.*
    def field(directory: String, key: String): Option[String] = inputs.extractedMetadata.getFieldValues(directory).get(key)
    def parsed(raw: Option[String], source: CaptureDateSource, keepFraction: Boolean = false): List[CaptureDate] =
      raw.flatMap(WallClockParser.parse(_, keepFraction)).map(CaptureDate(_, source)).toList
    def tag(directory: String, key: String, source: CaptureDateSource): List[CaptureDate] = parsed(field(directory, key), source)
    def iptc(date: String, time: String): List[CaptureDate] =
      parsed(field("IPTC", date).map(d => d + field("IPTC", time).map(" " + _).getOrElse("")), IptcCreated)

    // An EXIF date is whole seconds; the camera's subsecond tag beside it holds the fraction, so burst frames keep their order
    def exif(key: String, subSecondKey: String, source: CaptureDateSource): List[CaptureDate] =
      val fraction = field("Exif SubIFD", subSecondKey).flatMap(WallClockParser.parseSubSecond)
      tag("Exif SubIFD", key, source).map(
        candidate => fraction.fold(candidate)(nanos => candidate.copy(at = candidate.at.withNano(nanos))))

    // Order is semantic priority, not chronological order. A bad candidate never hides a later key in the same rung.
    exif("Date/Time Original", "Sub-Sec Time Original", ExifOriginal) ++
      exif("Date/Time Digitized", "Sub-Sec Time Digitized", ExifDigitized) ++
      tag("Exif IFD0", "Date/Time", ExifFileChange) ++
      // An XMP date is ISO 8601 and may carry its fraction itself, which spaces the frames of a burst as the EXIF subsecond tag does
      List("xmp:CreateDate", "photoshop:DateCreated", "exif:DateTimeOriginal", "xmp:ModifyDate").flatMap(
        key => parsed(field("XMP", key), XmpCreateDate, keepFraction = true)) ++
      iptc("Date Created", "Time Created") ++ iptc("Digital Date Created", "Digital Time Created") ++
      List("PNG-tEXt", "PNG-iTXt", "PNG-zTXt").flatMap(tag(_, "Creation Time", PngCreationTime)) ++
      // A phone's own recording date: a local wall clock with an offset, discarded like every other offset
      tag("QuickTime Metadata", "Creation Date", QuickTimeCreationDate) ++
      // PNG tIME, the container's creation time and GPS explicitly describe UTC. Store that UTC wall clock below all local
      // sources, without zone guessing.
      tag("PNG-tIME", "Last Modification Time", PngModified) ++
      containerCreationTime(field) ++
      parsed(
        for
          date <- field("GPS", "GPS Date Stamp")
          time <- field("GPS", "GPS Time-Stamp")
        yield s"$date ${time.trim.stripSuffix(" UTC")}",
        GpsTimestamp) ++ filenameCandidates(inputs.fileName)

  /**
   * The MP4 / QuickTime creation time, a UTC instant that metadata-extractor describes in the JVM's zone: the track directories
   * spell it with a numeric offset, the container directories with a zone name. Either is turned back into the UTC wall clock, so
   * the stored value does not depend on the server's zone. The track directories come first for that reason.
   */
  private def containerCreationTime(field: (String, String) => Option[String]): List[CaptureDate] =
    List("MP4 Video", "QuickTime Video", "MP4", "QuickTime").flatMap {
      directory =>
        field(directory, "Creation Time")
          .flatMap(WallClockParser.parseUtcInstant)
          .map(CaptureDate(_, CaptureDateSource.ContainerCreationTime))
    }

  /**
   * A shape of file name that spells a time to the second: a regex anchored at the start of the name, and the ISO wall clock its
   * match spells, which [[WallClockParser.parse]] reads, so every shape passes the same strict calendar check
   */
  private case class NameShape(pattern: Regex, wallClock: Regex.Match => Option[String])

  // Shared by the shapes: a compact YYYYMMDD, a separator and HHMMSS; and the boundary that must follow the seconds, so an
  // incidental digit run never reads as a time. A duplicate's suffix is a boundary too: Google Camera's `~2`, other tools' `(1)`
  private val CompactTimestamp = """(\d{4})(\d{2})(\d{2})[_T -](\d{2})(\d{2})(\d{2})"""
  private val Boundary = """(?=$|[._ ~(-])"""
  private val Months = List("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

  // AM or PM after a plain space or the narrow no-break space (U+202F) macOS writes since Ventura; the escape keeps the source
  // ASCII, and the regex engine reads it
  private val Meridiem = """[ \u202F](AM|PM)"""

  private def iso(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
    f"$year%04d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d"

  /**
   * The hour of day of a clock that may be a 12-hour one: 12 AM is 0 and 12 PM is 12, and an AM or PM hour outside 1 to 12 is
   * none
   */
  private def hourOfDay(hour: Int, meridiem: Option[String]): Option[Int] =
    meridiem match
      case None => Some(hour)
      case Some(_) if hour < 1 || hour > 12 => None
      case Some(m) => Some(hour % 12 + (if m.equalsIgnoreCase("PM") then 12 else 0))

  private def compactWallClock(m: Regex.Match): Option[String] =
    Some(iso(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, m.group(4).toInt, m.group(5).toInt, m.group(6).toInt))

  private val nameShapes: List[NameShape] = List(
    // Compact camera: an optional app prefix, YYYYMMDD, a separator, HHMMSS (IMG_20240704_080910, Screenshot_20240704-080910)
    NameShape(s"""(?i)^(?:(?:IMG|VID|MVIMG|Screenshot|signal|photo)[_ -]?)?$CompactTimestamp$Boundary""".r, compactWallClock),
    // A Pixel name is the compact shape with the milliseconds right after the seconds (PXL_20240704_080910123), the one prefix
    // that allows digits past the seconds; they are dropped, since no name gives a fraction of a second
    NameShape(s"""(?i)^PXL[_ -]?$CompactTimestamp(?:\\d{3})?$Boundary""".r, compactWallClock),
    // Dashed timestamp: an optional app prefix, YYYY-MM-DD, a separator or " at ", then the time with its parts dashed, dotted or
    // coloned, on a 12-hour clock when AM or PM follows (2024-07-04 08.09.10, Screenshot 2024-07-04 at 08.09.10,
    // Screen Shot 2024-07-04 at 8.09.10 AM, Screenshot_2024-07-04-08-09-10, photo_2024-07-04_08-09-10,
    // WhatsApp Image 2024-07-04 at 08.09.10). Since Ventura, macOS writes a narrow no-break space (U+202F) before AM or PM
    NameShape(
      s"""(?i)^(?:(?:Screenshot|Screen Shot|WhatsApp Image|photo|Image)[_ -]?)?(\\d{4})-(\\d{2})-(\\d{2})(?: at |[ _T-])(\\d{1,2})[-.:](\\d{2})[-.:](\\d{2})(?:$Meridiem)?$Boundary""".r,
      m =>
        hourOfDay(m.group(4).toInt, Option(m.group(7))).map(
          hour => iso(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, hour, m.group(5).toInt, m.group(6).toInt))
    ),
    // Apple export: Photo, Image or Video, the English month abbreviation, the day, the year, a comma, then the 12-hour time with
    // its parts spaced (Photo Dec 24 2023, 2 55 06 PM); macOS may write a narrow no-break space (U+202F) before AM or PM here too
    NameShape(
      s"""(?i)^(?:Photo|Image|Video) (${Months.mkString("|")}) (\\d{1,2}) (\\d{4}), (\\d{1,2}) (\\d{2}) (\\d{2})$Meridiem$Boundary""".r,
      m =>
        hourOfDay(m.group(4).toInt, Some(m.group(7))).map(
          hour =>
            iso(
              m.group(3).toInt,
              Months.indexOf(m.group(1).toLowerCase(Locale.ENGLISH)) + 1,
              m.group(2).toInt,
              hour,
              m.group(5).toInt,
              m.group(6).toInt))
    ),
    // Epoch: FB_IMG_ and the instant in milliseconds (13 digits) or seconds (10), read as its UTC wall clock, as the container and
    // GPS rungs are, whatever the JVM's zone; the milliseconds are dropped
    NameShape(
      s"""^FB_IMG_(\\d{13}|\\d{10})$Boundary""".r,
      m =>
        val digits = m.group(1)
        val seconds = if digits.length == 13 then digits.toLong / 1000 else digits.toLong
        val at = LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC)
        Some(iso(at.getYear, at.getMonthValue, at.getDayOfMonth, at.getHour, at.getMinute, at.getSecond))
    )
  )

  /** Filename guesses rank below every metadata source and pass the same strict calendar and plausibility checks. */
  private def filenameCandidates(fileName: String): List[CaptureDate] =
    nameShapes.flatMap {
      shape =>
        shape.pattern
          .findFirstMatchIn(fileName)
          .flatMap(shape.wallClock)
          .flatMap(WallClockParser.parse(_))
          .map(CaptureDate(_, CaptureDateSource.FileName))
    }

/** Parses the wall clock a metadata string spells. Parsed offsets are discarded, never converted to the server zone. */
private[core] object WallClockParser:
  private def dateBuilder(separator: Char): DateTimeFormatterBuilder =
    new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .appendValue(ChronoField.YEAR, 4)
      .appendLiteral(separator)
      .appendValue(ChronoField.MONTH_OF_YEAR, 2)
      .appendLiteral(separator)
      .appendValue(ChronoField.DAY_OF_MONTH, 2)

  private def strict(builder: DateTimeFormatterBuilder): DateTimeFormatter =
    builder.toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)

  // Keep separators intact: replacing date dashes would also corrupt a negative timezone offset.
  private val formats: List[DateTimeFormatter] =
    (for
      (dateSeparator, timeSeparator) <- List((':', ' '), ('-', 'T'), ('-', ' '))
      offset <- List("+HH:MM", "+HHMM")
    yield strict(
      dateBuilder(dateSeparator)
        .appendLiteral(timeSeparator)
        .appendPattern("HH:mm[:ss]")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .optionalStart()
        .appendOffset(offset, "Z")
        .optionalEnd())) ++
      List(
        strict(dateBuilder(':')),
        strict(dateBuilder('-')),
        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
      )

  // metadata-extractor describes a Date tag in the JVM's zone and default locale, "Fri Jun 09 08:34:56 -04:00 2023"; a
  // container's own creation time is a Date's toString, "Fri Jun 09 08:34:56 EDT 2023", always in English
  private val instantWithOffset: List[DateTimeFormatter] =
    List(Locale.getDefault(Locale.Category.FORMAT), Locale.ENGLISH).distinct.map {
      locale => DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss xxx yyyy", locale)
    }
  private val instantWithZoneName: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)

  /** The UTC wall clock of an instant a description spells with its offset or zone; the JVM's zone plays no part */
  def parseUtcInstant(raw: String): Option[LocalDateTime] =
    Option(raw).flatMap {
      value =>
        val trimmed = value.trim.replaceAll("\\s+", " ")
        (instantWithOffset :+ instantWithZoneName).iterator
          .flatMap(formatter => Try(ZonedDateTime.parse(trimmed, formatter)).toOption)
          .map(_.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime.truncatedTo(ChronoUnit.SECONDS))
          .nextOption()
    }

  /**
   * The nanoseconds an EXIF `SubSecTime` tag spells: the digits of the fraction of a second ("250" is .25, "7" is .7), read to
   * the microsecond, the precision a PostgreSQL TIMESTAMP keeps, so both engines store what was resolved. Anything else is no
   * fraction, so the date keeps its whole second.
   */
  def parseSubSecond(raw: String): Option[Int] =
    Option(raw).map(_.trim).filter(digits => digits.nonEmpty && digits.forall(_.isDigit)).map {
      digits => digits.take(6).padTo(6, '0').toInt * 1000
    }

  /**
   * The wall clock a string spells, to the whole second: a fraction in the string is dropped unless `keepFraction`, and then kept
   * to the microsecond, the precision both engines store, as [[parseSubSecond]] keeps the EXIF tag's
   */
  def parse(raw: String, keepFraction: Boolean = false): Option[LocalDateTime] =
    Option(raw).flatMap {
      value =>
        val trimmed = value.trim.replaceAll("\\s+", " ")
        formats.iterator
          .flatMap {
            formatter =>
              Try {
                val parsed = formatter.parse(trimmed)
                val local =
                  if parsed.isSupported(ChronoField.HOUR_OF_DAY) then LocalDateTime.from(parsed)
                  else LocalDate.from(parsed).atStartOfDay()
                local.truncatedTo(if keepFraction then ChronoUnit.MICROS else ChronoUnit.SECONDS)
              }.toOption
          }
          .nextOption()
    }
