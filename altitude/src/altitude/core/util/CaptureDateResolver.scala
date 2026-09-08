package altitude.core.util

import java.time.{ Duration, LocalDate, LocalDateTime }
import java.time.format.{ DateTimeFormatter, DateTimeFormatterBuilder, ResolverStyle }
import java.time.temporal.{ ChronoField, ChronoUnit }
import java.util.Locale

import scala.util.Try

import altitude.core.models.{ CaptureDateSource, ExtractedMetadata }

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
        !candidate.at.isBefore(EarliestPlausible) && !candidate.at.isAfter(notLaterThan.plus(FutureTolerance)) &&
        !Sentinels.contains(candidate.at)
    }

  /** Parsed candidates in priority order, before plausibility filtering; useful for diagnostics. */
  private[core] def candidates(inputs: CaptureDateInputs): List[CaptureDate] =
    import CaptureDateSource.*
    def field(directory: String, key: String): Option[String] = inputs.extractedMetadata.getFieldValues(directory).get(key)
    def parsed(raw: Option[String], source: CaptureDateSource): List[CaptureDate] =
      raw.flatMap(WallClockParser.parse).map(CaptureDate(_, source)).toList
    def tag(directory: String, key: String, source: CaptureDateSource): List[CaptureDate] = parsed(field(directory, key), source)
    def iptc(date: String, time: String): List[CaptureDate] =
      parsed(field("IPTC", date).map(d => d + field("IPTC", time).map(" " + _).getOrElse("")), IptcCreated)

    // Order is semantic priority, not chronological order. A bad candidate never hides a later key in the same rung.
    tag("Exif SubIFD", "Date/Time Original", ExifOriginal) ++
      tag("Exif SubIFD", "Date/Time Digitized", ExifDigitized) ++
      tag("Exif IFD0", "Date/Time", ExifFileChange) ++
      List("xmp:CreateDate", "photoshop:DateCreated", "exif:DateTimeOriginal", "xmp:ModifyDate").flatMap(
        tag("XMP", _, XmpCreateDate)) ++
      iptc("Date Created", "Time Created") ++ iptc("Digital Date Created", "Digital Time Created") ++
      List("PNG-tEXt", "PNG-iTXt", "PNG-zTXt").flatMap(tag(_, "Creation Time", PngCreationTime)) ++
      // PNG tIME and GPS explicitly describe UTC. Store that UTC wall clock below all local sources, without zone guessing.
      tag("PNG-tIME", "Last Modification Time", PngModified) ++
      parsed(
        for
          date <- field("GPS", "GPS Date Stamp")
          time <- field("GPS", "GPS Time-Stamp")
        yield s"$date ${time.trim.stripSuffix(" UTC")}",
        GpsTimestamp) ++ filenameCandidates(inputs.fileName)

  // Anchor at the start and require a boundary after seconds: incidental digit runs are too weak to infer a capture time.
  private val filenamePatterns = List(
    """(?i)^(?:(?:IMG|VID|PXL|MVIMG|Screenshot|signal|photo)[_ -]?)?(\d{4})(\d{2})(\d{2})[_T -](\d{2})(\d{2})(\d{2})(?=$|[._ -])""".r,
    """^(\d{4})-(\d{2})-(\d{2})[ _T-](\d{2})[-.:](\d{2})[-.:](\d{2})(?=$|[._ -])""".r
  )

  /** Filename guesses rank below every metadata source and pass the same strict calendar and plausibility checks. */
  private def filenameCandidates(fileName: String): List[CaptureDate] =
    filenamePatterns.flatMap(_.findFirstMatchIn(fileName)).flatMap {
      matched =>
        val raw =
          s"${matched.group(1)}-${matched.group(2)}-${matched.group(3)}T${matched.group(4)}:${matched.group(5)}:${matched.group(6)}"
        WallClockParser.parse(raw).map(CaptureDate(_, CaptureDateSource.FileName))
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

  def parse(raw: String): Option[LocalDateTime] =
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
                local.truncatedTo(ChronoUnit.SECONDS)
              }.toOption
          }
          .nextOption()
    }
