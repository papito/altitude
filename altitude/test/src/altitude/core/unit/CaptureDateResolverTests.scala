package altitude.core.unit

import java.time.LocalDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.models.{ CaptureDateSource, ExtractedMetadata }
import altitude.core.util.{ CaptureDate, CaptureDateInputs, CaptureDateResolver, JsonCodec, WallClockParser }

@DoNotDiscover class CaptureDateResolverTests extends AnyFunSuite {
  private val ceiling = LocalDateTime.of(2026, 9, 7, 12, 0)

  private def original(raw: String): CaptureDateInputs =
    CaptureDateInputs(ExtractedMetadata(Map("Exif SubIFD" -> Map("Date/Time Original" -> raw))), "image.jpg")

  private def inputs(fields: (String, String, String)*): CaptureDateInputs = {
    val metadata = ExtractedMetadata()
    fields.foreach { case (directory, tag, value) => metadata.addValue(directory, tag, value) }
    CaptureDateInputs(metadata, "image.jpg")
  }

  test("Each metadata rung resolves alone, preserving local clocks and deterministic UTC clocks") {
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

  test("The first plausible rung wins and diagnostics retain implausible parsed candidates") {
    val fields = List(
      ("Exif SubIFD", "Date/Time Original", "2024:01:01 01:00:00"),
      ("Exif SubIFD", "Date/Time Digitized", "2024:01:02 01:00:00"),
      ("Exif IFD0", "Date/Time", "2024:01:03 01:00:00"),
      ("XMP", "xmp:CreateDate", "2024-01-04T01:00:00Z"),
      ("IPTC", "Date Created", "2024:01:05"),
      ("PNG-tEXt", "Creation Time", "2024:01:06"),
      ("PNG-tIME", "Last Modification Time", "2024:01:07 01:00:00"),
      ("GPS", "GPS Date Stamp", "2024:01:08"),
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
    List("0000:00:00 00:00:00", "2024:02:30", "2023-02-29", "2024:07:04 25:00:00", "", "not a date")
      .foreach(raw => WallClockParser.parse(raw) shouldBe None)
  }

  test("Resolution records provenance and does not invent a date") {
    CaptureDateResolver.resolve(original("1899:06:01 12:00:00"), ceiling) shouldBe
      Some(CaptureDate(LocalDateTime.of(1899, 6, 1, 12, 0), CaptureDateSource.ExifOriginal))
    CaptureDateResolver.resolve(CaptureDateInputs(ExtractedMetadata(), "image.jpg"), ceiling) shouldBe None
    List("1825:12:31 23:59:59", "1970:01:01 00:00:00", "1904:01:01 00:00:00", "1980:01:01 00:00:00", ceiling.plusDays(3).toString)
      .foreach(raw => CaptureDateResolver.resolve(original(raw), ceiling) shouldBe None)
    CaptureDateResolver.resolve(original(ceiling.plusDays(2).toString), ceiling).map(_.at) shouldBe Some(ceiling.plusDays(2))
    CaptureDateResolver.resolve(original("1970:01:01 00:00:01"), ceiling).isDefined shouldBe true
  }

  test("Capture source JSON uses stable database values and rejects unknown names") {
    CaptureDateSource.values.foreach {
      source =>
        JsonCodec.write(source) shouldBe s"\"${source.dbValue}\""
        JsonCodec.read[CaptureDateSource](JsonCodec.write(source)) shouldBe source
        CaptureDateSource.fromDbValue(source.dbValue) shouldBe Some(source)
    }
    CaptureDateSource.fromDbValue("future_source") shouldBe None
    intercept[Exception](JsonCodec.read[CaptureDateSource]("\"future_source\""))
  }
}
