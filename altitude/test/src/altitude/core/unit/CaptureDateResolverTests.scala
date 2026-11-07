package altitude.core.unit

import altitude.test.IntegrationTestUtil.withJvmTimeZone
import altitude.test.TestFocus
import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.{ CaptureDateSource, ExtractedMetadata }
import altitude.core.util.{ CaptureDate, CaptureDateInputs, CaptureDateResolver, JsonCodec, WallClockParser }

@DoNotDiscover class CaptureDateResolverTests extends AnyFunSuite with TestFocus {
  private val ceiling = LocalDateTime.of(2026, 9, 7, 12, 0)

  private def original(raw: String): CaptureDateInputs =
    CaptureDateInputs(ExtractedMetadata(Map("Exif SubIFD" -> Map("Date/Time Original" -> raw))), "image.jpg")

  private def inputs(fields: (String, String, String)*): CaptureDateInputs = {
    val metadata = ExtractedMetadata()
    fields.foreach { case (directory, tag, value) => metadata.addValue(directory, tag, value) }
    CaptureDateInputs(metadata, "image.jpg")
  }

  test("Each metadata rung resolves alone, preserving local clocks and deterministic UTC clocks") {

    /**
     * Setup:
     *
     * Metadata holding a single date source at a time, each spelling 2024-07-04 08:09:10 its own way: the EXIF digitized and file
     * change dates, IPTC date and time pairs with +14:00 and -12:00 offsets, PNG tIME, a GPS date and time stamp, each of the
     * four XMP keys and each of the three PNG text chunks.
     *
     * Assertions:
     *
     * Each source alone resolves to that wall-clock time, recorded with that source; offsets are dropped, never converted.
     *
     * Edge cases:
     *
     * An IPTC date with no time resolves to midnight, while a GPS date stamp with no time stamp resolves to nothing.
     */
    val expected = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    val examples = List(
      CaptureDateSource.ExifDigitized -> inputs(("Exif SubIFD", "Date/Time Digitized", "2024:07:04 08:09:10")),
      CaptureDateSource.ExifFileChange -> inputs(("Exif IFD0", "Date/Time", "2024:07:04 08:09:10")),
      CaptureDateSource.IptcCreated -> inputs(("IPTC", "Date Created", "2024:07:04"), ("IPTC", "Time Created", "08:09:10+1400")),
      CaptureDateSource.IptcCreated -> inputs(
        ("IPTC", "Digital Date Created", "2024:07:04"),
        ("IPTC", "Digital Time Created", "08:09:10-1200")),
      CaptureDateSource.PngModified -> inputs(("PNG-tIME", "Last Modification Time", "2024:07:04 08:09:10")),
      CaptureDateSource.GpsTimestamp -> inputs(
        ("GPS", "GPS Date Stamp", "2024:07:04"),
        ("GPS", "GPS Time-Stamp", "08:09:10.123 UTC"))
    ) ++
      List("xmp:CreateDate", "photoshop:DateCreated", "exif:DateTimeOriginal", "xmp:ModifyDate").map {
        key => CaptureDateSource.XmpCreateDate -> inputs(("XMP", key, "2024-07-04T08:09:10-12:00"))
      } ++ List("PNG-tEXt", "PNG-iTXt", "PNG-zTXt").map {
        directory => CaptureDateSource.PngCreationTime -> inputs((directory, "Creation Time", "Thu, 4 Jul 2024 08:09:10 GMT"))
      }
    examples.foreach {
      case (source, data) =>
        CaptureDateResolver.resolve(data, ceiling) shouldBe Some(CaptureDate(expected, source))
    }
    CaptureDateResolver.resolve(inputs(("IPTC", "Date Created", "2024:07:04")), ceiling).map(_.at) shouldBe
      Some(LocalDateTime.of(2024, 7, 4, 0, 0))
    CaptureDateResolver.resolve(inputs(("GPS", "GPS Date Stamp", "2024:07:04")), ceiling) shouldBe None
  }

  test("The camera's subsecond tag refines the EXIF original and digitized wall clocks, and no other") {

    /**
     * Setup:
     *
     * An EXIF original date with `Sub-Sec Time Original` spelled as cameras spell it: three digits, one digit, a leading zero,
     * trailing padding, nine or more digits; the same for the digitized date and `Sub-Sec Time Digitized`; and an original date
     * with a blank, non-numeric or missing subsecond.
     *
     * Assertions:
     *
     * The digits are the fraction of the second ("250" is .25, "7" is .7, "05" is .05), truncated to the microsecond, and the
     * calendar day and the whole second are what the date tag spells. Each date reads only its own subsecond tag: the original's
     * does not refine the digitized date, and neither refines the file change date.
     *
     * Edge cases:
     *
     * A subsecond that is blank, not digits, or absent leaves the whole second; a fraction in the date string itself is still
     * dropped, so only the dedicated tag counts.
     */
    val second = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    def originalWith(subsec: String): CaptureDateInputs =
      inputs(("Exif SubIFD", "Date/Time Original", "2024:07:04 08:09:10"), ("Exif SubIFD", "Sub-Sec Time Original", subsec))

    List("250" -> 250_000_000, "7" -> 700_000_000, "05" -> 50_000_000, "25 " -> 250_000_000, "1234567891" -> 123_456_000)
      .foreach {
        case (subsec, nanos) =>
          withClue(s"'$subsec': ") {
            CaptureDateResolver.resolve(originalWith(subsec), ceiling) shouldBe
              Some(CaptureDate(second.withNano(nanos), CaptureDateSource.ExifOriginal))
          }
      }
    List("", "  ", "abc", "1.5").foreach {
      subsec => CaptureDateResolver.resolve(originalWith(subsec), ceiling).map(_.at) shouldBe Some(second)
    }
    CaptureDateResolver.resolve(original("2024:07:04 08:09:10.999"), ceiling).map(_.at) shouldBe Some(second)

    val digitized = inputs(
      ("Exif SubIFD", "Date/Time Digitized", "2024:07:04 08:09:10"),
      ("Exif SubIFD", "Sub-Sec Time Digitized", "40"),
      ("Exif SubIFD", "Sub-Sec Time Original", "250")
    )
    CaptureDateResolver.resolve(digitized, ceiling) shouldBe
      Some(CaptureDate(second.withNano(400_000_000), CaptureDateSource.ExifDigitized))

    val fileChange = inputs(("Exif IFD0", "Date/Time", "2024:07:04 08:09:10"), ("Exif SubIFD", "Sub-Sec Time", "250"))
    CaptureDateResolver.resolve(fileChange, ceiling) shouldBe Some(CaptureDate(second, CaptureDateSource.ExifFileChange))
  }

  test("An XMP date keeps the fraction it spells, to the microsecond, where an EXIF date string drops it") {

    /**
     * Setup:
     *
     * `xmp:CreateDate` spelled with a fraction of .25, with seven fractional digits and with none; and an EXIF original date
     * spelled with a fraction of .999 and no subsecond tag.
     *
     * Assertions:
     *
     * The XMP rung keeps the fraction it carries, truncated to the microsecond, and a whole second stays whole; the EXIF rung
     * reads its fraction from the dedicated tag alone, so the one in the date string is dropped.
     */
    val second = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    def xmp(raw: String): CaptureDateInputs = inputs(("XMP", "xmp:CreateDate", raw))

    CaptureDateResolver.resolve(xmp("2024-07-04T08:09:10.25-12:00"), ceiling) shouldBe
      Some(CaptureDate(second.withNano(250_000_000), CaptureDateSource.XmpCreateDate))
    CaptureDateResolver.resolve(xmp("2024-07-04T08:09:10.1234567"), ceiling).map(_.at) shouldBe
      Some(second.withNano(123_456_000))
    CaptureDateResolver.resolve(xmp("2024-07-04T08:09:10"), ceiling).map(_.at) shouldBe Some(second)
    CaptureDateResolver.resolve(original("2024:07:04 08:09:10.999"), ceiling).map(_.at) shouldBe Some(second)
  }

  test("A video's container dates resolve: the phone's local creation date, then the UTC creation time in any zone") {

    /**
     * Setup:
     *
     * Video container metadata: the phone's local QuickTime creation date with a +02:00 offset, and the container's UTC creation
     * time as metadata-extractor describes it in the JVM's zone, in the MP4 and QuickTime track and container directories, with
     * numeric offsets and with zone names (EDT, JST, UTC).
     *
     * Assertions:
     *
     * The local creation date resolves to its own wall clock, and every description of the container's instant to the same UTC
     * wall clock. The phone's local date outranks the container's instant, which outranks GPS, and an EXIF original date outranks
     * the phone's local date.
     *
     * Edge cases:
     *
     * The container epoch, 1904-01-01 00:00:00 UTC, is a sentinel that resolves to nothing, even described in another zone as the
     * last evening of 1903.
     */
    val expected = LocalDateTime.of(2024, 7, 4, 8, 9, 10)
    val local = ("QuickTime Metadata", "Creation Date", "2024-07-04T08:09:10+0200")
    CaptureDateResolver.resolve(inputs(local), ceiling) shouldBe Some(
      CaptureDate(expected, CaptureDateSource.QuickTimeCreationDate))

    // metadata-extractor describes the container's UTC instant in the JVM's zone; the stored value is the UTC wall clock
    List(
      ("MP4 Video", "Thu Jul 04 04:09:10 -04:00 2024"),
      ("QuickTime Video", "Thu Jul 04 10:09:10 +02:00 2024"),
      ("MP4", "Thu Jul 04 04:09:10 EDT 2024"),
      ("QuickTime", "Thu Jul 04 17:09:10 JST 2024"),
      ("MP4", "Thu Jul 04 08:09:10 UTC 2024")
    ).foreach {
      case (directory, raw) =>
        withClue(s"$directory: $raw") {
          CaptureDateResolver.resolve(inputs((directory, "Creation Time", raw)), ceiling) shouldBe
            Some(CaptureDate(expected, CaptureDateSource.ContainerCreationTime))
        }
    }

    // The phone's local date outranks the container's UTC instant, which outranks GPS and the filename
    val container = ("MP4", "Creation Time", "Thu Jul 04 04:09:10 EDT 2024")
    CaptureDateResolver.resolve(inputs(local, container), ceiling).map(_.source) shouldBe Some(
      CaptureDateSource.QuickTimeCreationDate)
    val gps = List(("GPS", "GPS Date Stamp", "2024:07:05"), ("GPS", "GPS Time-Stamp", "01:00:00 UTC"))
    CaptureDateResolver.resolve(inputs(container :: gps*), ceiling).map(_.source) shouldBe Some(
      CaptureDateSource.ContainerCreationTime)
    CaptureDateResolver
      .resolve(inputs(("Exif SubIFD", "Date/Time Original", "2024:01:01 01:00:00"), local), ceiling)
      .map(_.source) shouldBe
      Some(CaptureDateSource.ExifOriginal)

    // The container epoch is a sentinel, in whichever zone it is described
    CaptureDateResolver.resolve(inputs(("MP4", "Creation Time", "Fri Jan 01 00:00:00 UTC 1904")), ceiling) shouldBe None
    CaptureDateResolver.resolve(inputs(("MP4 Video", "Creation Time", "Thu Dec 31 19:00:00 -05:00 1903")), ceiling) shouldBe None
  }

  test("The first plausible rung wins and diagnostics retain implausible parsed candidates") {

    /**
     * Setup:
     *
     * Metadata with a date in every metadata source, each on a different day of January 2024; and a second set with an EXIF
     * original at the 1970 epoch sentinel, an XMP create date three days past the ceiling, and a plausible Photoshop date.
     *
     * Assertions:
     *
     * The candidates come back in the resolver's priority order, one for every source but the filename, and dropping the sources
     * one at a time from the top always lets the next one win. Implausible values stay among the candidates, while resolution
     * skips them for the first plausible one.
     *
     * Edge cases:
     *
     * A sentinel and a future date, both ranked above the plausible date.
     */
    val fields = List(
      ("Exif SubIFD", "Date/Time Original", "2024:01:01 01:00:00"),
      ("Exif SubIFD", "Date/Time Digitized", "2024:01:02 01:00:00"),
      ("Exif IFD0", "Date/Time", "2024:01:03 01:00:00"),
      ("XMP", "xmp:CreateDate", "2024-01-04T01:00:00Z"),
      ("IPTC", "Date Created", "2024:01:05"),
      ("PNG-tEXt", "Creation Time", "2024:01:06"),
      ("QuickTime Metadata", "Creation Date", "2024-01-07T01:00:00+0100"),
      ("PNG-tIME", "Last Modification Time", "2024:01:08 01:00:00"),
      ("MP4", "Creation Time", "Tue Jan 09 01:00:00 UTC 2024"),
      ("GPS", "GPS Date Stamp", "2024:01:10"),
      ("GPS", "GPS Time-Stamp", "01:00:00 UTC")
    )
    val expectedSources = CaptureDateSource.values.toList.filterNot(_ == CaptureDateSource.FileName)
    CaptureDateResolver.candidates(inputs(fields*)).map(_.source) shouldBe expectedSources
    expectedSources.indices.foreach {
      n => CaptureDateResolver.resolve(inputs(fields.drop(n)*), ceiling).map(_.source) shouldBe Some(expectedSources(n))
    }
    val fallback = inputs(
      ("Exif SubIFD", "Date/Time Original", "1970:01:01 00:00:00"),
      ("XMP", "xmp:CreateDate", ceiling.plusDays(3).toString),
      ("XMP", "photoshop:DateCreated", "2024-07-04")
    )
    CaptureDateResolver.candidates(fallback).size shouldBe 3
    CaptureDateResolver.resolve(fallback, ceiling) shouldBe
      Some(CaptureDate(LocalDateTime.of(2024, 7, 4, 0, 0), CaptureDateSource.XmpCreateDate))
  }

  test("Capture metadata spells a wall clock, regardless of offset, precision or separators") {

    /**
     * Setup:
     *
     * One wall-clock time spelled the ways metadata spells it: EXIF colons, ISO dashes with a T or a space, offsets in either
     * form or Z, nanoseconds, stray whitespace, and RFC 1123; plus spellings without seconds, and dates without a time.
     *
     * Assertions:
     *
     * Each parses to its wall clock with the offset and the fraction dropped; missing seconds are zero, and a missing time of day
     * is midnight.
     */
    val expected = Some(LocalDateTime.of(2024, 7, 4, 8, 9, 10))
    List(
      "2024:07:04 08:09:10",
      " 2024:07:04   08:09:10.123456789+14:00 ",
      "2024-07-04T08:09:10-12:00",
      "2024-07-04 08:09:10Z",
      "2024:07:04 08:09:10+1400",
      "Thu, 4 Jul 2024 08:09:10 GMT"
    ).foreach(raw => WallClockParser.parse(raw) shouldBe expected)
    List("2024:07:04 08:09", "2024-07-04T08:09").foreach {
      raw => WallClockParser.parse(raw) shouldBe Some(LocalDateTime.of(2024, 7, 4, 8, 9))
    }
    List("2024:07:04", "2024-07-04").foreach(raw => WallClockParser.parse(raw) shouldBe Some(LocalDateTime.of(2024, 7, 4, 0, 0)))
  }

  test("Strict parsing rejects invalid calendar dates and zero metadata") {

    /**
     * Setup:
     *
     * An all-zero EXIF date, February 30th, February 29th of a non-leap year, hour 25, an empty string and text that is not a
     * date.
     *
     * Assertions:
     *
     * None of them parses.
     */
    List("0000:00:00 00:00:00", "2024:02:30", "2023-02-29", "2024:07:04 25:00:00", "", "not a date")
      .foreach(raw => WallClockParser.parse(raw) shouldBe None)
  }

  test("Resolution records provenance and does not invent a date") {

    /**
     * Setup:
     *
     * EXIF original dates around the plausibility limits - 1899, the last second of 1825, the 1970, 1904 and 1980 sentinels, the
     * 1970 and 1980 sentinels with a Sub-Sec Time Original beside them, two and three days past the ceiling, and one second into
     * 1970 - and metadata with no date at all.
     *
     * Assertions:
     *
     * A plausible date resolves with its EXIF-original provenance, and nothing is resolved from missing metadata or from an
     * implausible date.
     *
     * Edge cases:
     *
     * The bounds: the last second before 1826 is rejected, two days past the ceiling is tolerated but three are not, and a
     * sentinel is rejected only at its exact second, not a second later: an unset clock stays one whatever subsecond the camera
     * adds to it.
     */
    CaptureDateResolver.resolve(original("1899:06:01 12:00:00"), ceiling) shouldBe
      Some(CaptureDate(LocalDateTime.of(1899, 6, 1, 12, 0), CaptureDateSource.ExifOriginal))
    CaptureDateResolver.resolve(CaptureDateInputs(ExtractedMetadata(), "image.jpg"), ceiling) shouldBe None
    List("1825:12:31 23:59:59", "1970:01:01 00:00:00", "1904:01:01 00:00:00", "1980:01:01 00:00:00", ceiling.plusDays(3).toString)
      .foreach(raw => CaptureDateResolver.resolve(original(raw), ceiling) shouldBe None)
    List("1970:01:01 00:00:00", "1980:01:01 00:00:00").foreach {
      raw =>
        val unsetClock =
          inputs(("Exif SubIFD", "Date/Time Original", raw), ("Exif SubIFD", "Sub-Sec Time Original", "250"))
        CaptureDateResolver.resolve(unsetClock, ceiling) shouldBe None
    }
    CaptureDateResolver.resolve(original(ceiling.plusDays(2).toString), ceiling).map(_.at) shouldBe Some(ceiling.plusDays(2))
    CaptureDateResolver.resolve(original("1970:01:01 00:00:01"), ceiling).isDefined shouldBe true
  }

  test("Capture source JSON uses stable database values and rejects unknown names") {

    /**
     * Setup:
     *
     * Every capture-date source, and an unknown name ("future_source").
     *
     * Assertions:
     *
     * Each source is written to JSON as its database value and read back both from the JSON and from that value, and the unknown
     * name is rejected on both paths.
     */
    CaptureDateSource.values.foreach {
      source =>
        JsonCodec.write(source) shouldBe s"\"${source.dbValue}\""
        JsonCodec.read[CaptureDateSource](JsonCodec.write(source)) shouldBe source
        CaptureDateSource.fromDbValue(source.dbValue) shouldBe Some(source)
    }
    CaptureDateSource.fromDbValue("future_source") shouldBe None
    intercept[Exception](JsonCodec.read[CaptureDateSource]("\"future_source\""))
  }

  private def named(name: String): CaptureDateInputs = CaptureDateInputs(ExtractedMetadata(), name)

  test("Filename dates accept every name shape as the last rung, with the fraction of a second at zero") {

    /**
     * Setup:
     *
     * File names that spell 2024-07-04 08:09:10 in each shape, with no metadata: the compact camera form (IMG_, VID, MVIMG_,
     * Screenshot with a space or an underscore and a dash, signal-, photo_, a bare compact timestamp, PXL_ with a dotted suffix
     * and PXL_ with its milliseconds after the seconds, and the duplicate suffixes `~2` after the Pixel milliseconds and `(1)`
     * after IMG_'s seconds), the dashed timestamp (bare, with dotted or dashed times, the macOS screenshot with ` at ` and with a
     * one-digit 12-hour hour and AM after a plain space and after Ventura's narrow no-break space (U+202F), the Android
     * screenshot, Telegram's photo_, WhatsApp desktop's and a plain Image prefix), the Apple export form with Photo, Image and
     * Video, the month in either case and the narrow no-break space before AM, and Facebook's epoch name with thirteen and with
     * ten digits.
     *
     * Assertions:
     *
     * Each name resolves to that time, to the whole second, with the filename as its source. With metadata present, a plausible
     * EXIF date wins over a name of every shape, while an implausible one falls through to the name.
     *
     * Edge cases:
     *
     * The Pixel milliseconds are dropped, not read as a fraction.
     */
    val expected = Some(CaptureDate(LocalDateTime.of(2024, 7, 4, 8, 9, 10), CaptureDateSource.FileName))
    val names = List(
      "IMG_20240704_080910.jpg",
      "VID20240704_080910.mp4",
      "PXL_20240704_080910.123.MP.jpg",
      "PXL_20240704_080910123.jpg",
      "PXL_20240704_080910123~2.jpg",
      "IMG_20240704_080910(1).jpg",
      "MVIMG_20240704_080910.jpg",
      "Screenshot 20240704 080910.png",
      "Screenshot_20240704-080910.png",
      "signal-20240704-080910.png",
      "photo_20240704_080910.jpg",
      "20240704T080910.png",
      "2024-07-04 08.09.10.png",
      "2024-07-04_08-09-10.png",
      "Screenshot 2024-07-04 at 08.09.10.png",
      "Screen Shot 2024-07-04 at 8.09.10 AM.png",
      "Screenshot 2024-07-04 at 8.09.10\u202FAM.png",
      "Screenshot_2024-07-04-08-09-10.png",
      "photo_2024-07-04_08-09-10.jpg",
      "WhatsApp Image 2024-07-04 at 08.09.10.jpeg",
      "Image 2024-07-04 at 08.09.10.jpeg",
      "Photo Jul 4 2024, 8 09 10 AM.jpg",
      "Image JUL 04 2024, 8 09 10 am.jpg",
      "Video Jul 4 2024, 8 09 10 AM.mov",
      "Photo Jul 4 2024, 8 09 10\u202FAM.jpg",
      "FB_IMG_1720080550123.jpg",
      "FB_IMG_1720080550.jpg"
    )
    names.foreach {
      name =>
        withClue(s"'$name': ") {
          val resolved = CaptureDateResolver.resolve(named(name), ceiling)
          resolved shouldBe expected
          resolved.map(_.at.getNano) shouldBe Some(0)
          CaptureDateResolver.resolve(original("2020:01:01 12:00:00").copy(fileName = name), ceiling).map(_.source) shouldBe
            Some(CaptureDateSource.ExifOriginal)
        }
    }
    CaptureDateResolver.resolve(original("1970:01:01 00:00:00").copy(fileName = names.head), ceiling) shouldBe expected
  }

  test("A 12-hour clock in a file name reads 12 AM as midnight and 12 PM as noon, hours 1 to 12 only") {

    /**
     * Setup:
     *
     * Apple export names on 2023-12-24 at 12 00 00 AM, 12 00 00 PM, 1 00 00 PM and the plan's 2 55 06 PM, a macOS screenshot at
     * 12.00.00 AM and at 1.00.00 PM, and the same shapes with a 13-hour PM and a 0-hour AM.
     *
     * Assertions:
     *
     * 12 AM is 00:00:00, 12 PM is 12:00:00 and 1 PM is 13:00:00, in both shapes; an hour outside 1 to 12 resolves nothing.
     */
    val day = LocalDateTime.of(2023, 12, 24, 0, 0)
    List(
      "Photo Dec 24 2023, 12 00 00 AM.jpg" -> day,
      "Photo Dec 24 2023, 12 00 00 PM.jpg" -> day.withHour(12),
      "Photo Dec 24 2023, 1 00 00 PM.jpg" -> day.withHour(13),
      "Photo Dec 24 2023, 2 55 06 PM.jpg" -> day.withHour(14).withMinute(55).withSecond(6),
      "Screen Shot 2023-12-24 at 12.00.00 AM.png" -> day,
      "Screen Shot 2023-12-24 at 1.00.00 PM.png" -> day.withHour(13)
    ).foreach {
      case (name, at) =>
        withClue(s"'$name': ") {
          CaptureDateResolver.resolve(named(name), ceiling) shouldBe Some(CaptureDate(at, CaptureDateSource.FileName))
        }
    }
    List(
      "Photo Dec 24 2023, 13 00 00 PM.jpg",
      "Photo Dec 24 2023, 0 00 00 AM.jpg",
      "Screen Shot 2023-12-24 at 13.00.00 PM.png",
      "Screen Shot 2023-12-24 at 0.00.00 AM.png"
    ).foreach(name => withClue(s"'$name': ")(CaptureDateResolver.resolve(named(name), ceiling) shouldBe None))
  }

  test("An epoch file name reads as the UTC wall clock of its instant, whatever the JVM's zone") {

    /**
     * Setup:
     *
     * Facebook's epoch names for 2023-12-24 14:55:06 UTC, in milliseconds (thirteen digits) and in seconds (ten digits), resolved
     * with the JVM's default zone set to UTC+14 and then to UTC-11.
     *
     * Assertions:
     *
     * Both resolve to 14:55:06 under either zone, to the whole second.
     *
     * Edge cases:
     *
     * Milliseconds that round up to the next second are truncated, not rounded.
     */
    val expected = Some(CaptureDate(LocalDateTime.of(2023, 12, 24, 14, 55, 6), CaptureDateSource.FileName))
    val names = List("FB_IMG_1703429706123.jpg", "FB_IMG_1703429706999.jpg", "FB_IMG_1703429706.jpg")
    List("Pacific/Kiritimati", "Pacific/Pago_Pago").foreach {
      id =>
        withJvmTimeZone(id) {
          names.foreach(name => withClue(s"$id, '$name': ")(CaptureDateResolver.resolve(named(name), ceiling) shouldBe expected))
        }
    }
  }

  test("Filename dates reject incidental digit runs, malformed dates, sentinels and future clocks") {

    /**
     * Setup:
     *
     * File names whose digits are not a capture time: a prefix outside the known set, a timestamp with no separator, a date
     * alone, eight bare digits, seconds followed by two more digits, a Pixel name with two or four digits after the seconds,
     * February 30th in the compact and in the Apple export form, the 1970 epoch in the compact form, as ten zero digits and as
     * the bare `FB_IMG_0`, an epoch run of eleven digits, a date in 2099, and the names that spell a date and no time: WhatsApp
     * mobile's `IMG-20240704-WA0001` and an Apple export without its clock.
     *
     * Assertions:
     *
     * None of them resolves to a capture time.
     */
    List(
      "order20240704_080910.jpg",
      "20240704080910.jpg",
      "20240704.jpg",
      "12345678.png",
      "IMG_20240704_08091099.jpg",
      "IMG_20240704_080910123.jpg",
      "PXL_20240704_08091012.jpg",
      "PXL_20240704_0809101234.jpg",
      "IMG_20240230_010203.jpg",
      "Photo Feb 30 2023, 1 00 00 PM.jpg",
      "IMG_19700101_000000.jpg",
      "FB_IMG_0000000000.jpg",
      "FB_IMG_0.jpg",
      "FB_IMG_17034297061.jpg",
      "IMG_20990101_000000.jpg",
      "IMG-20240704-WA0001.jpg",
      "Photo Dec 24 2023.jpg"
    )
      .foreach(name => withClue(s"'$name': ")(CaptureDateResolver.resolve(named(name), ceiling) shouldBe None))
  }
}
