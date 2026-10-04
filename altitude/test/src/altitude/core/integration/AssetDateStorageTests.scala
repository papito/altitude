package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.IntegrationTestUtil.withJvmTimeZone
import java.time.{ Duration, LocalDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe, shouldEqual }

import altitude.core.{ Altitude, Const }
import altitude.core.models.{ Asset, CaptureDateSource, ExtractedMetadata, PublicMetadata }
import altitude.core.util.{ GroupBy, SearchGrouping, SearchGroupKey, SearchQuery, SearchSort, SortDirection }

/**
 * Date storage semantics behind date grouping: a capture timestamp is the camera's wall-clock time and must survive storage
 * without any timezone conversion, while an import timestamp is written explicitly in UTC.
 */
@DoNotDiscover class AssetDateStorageTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addAssetWithCaptureTime(dateTimeOriginal: Option[String]): Asset = {
    val asset = testContext
      .makeAsset()
      .copy(
        originalCreatedAt = dateTimeOriginal.map(raw => LocalDateTime.parse(raw.replace(':', '-').take(10) + "T" + raw.drop(11))),
        originalCreatedAtSource = dateTimeOriginal.map(_ => CaptureDateSource.ExifOriginal)
      )
    val persisted = testApp.txManager.withTransaction {
      testApp.DAO.asset.add(asset)
    }
    testApp.service.asset.getById(persisted.persistedId)
  }

  test("Capture timestamp is stored and read back as the camera's wall-clock time") {

    /**
     * Setup:
     *
     * An asset written straight through the DAO with an EXIF-style capture time of 2024:03:10 23:59:59.
     *
     * Assertions:
     *
     * The capture time reads back as exactly that wall-clock time.
     *
     * Edge cases:
     *
     * The last second of a day, which any time zone shift would push into another day.
     */
    val asset = addAssetWithCaptureTime(Some("2024:03:10 23:59:59"))
    asset.originalCreatedAt shouldEqual Some(LocalDateTime.of(2024, 3, 10, 23, 59, 59))
  }

  test("Capture timestamp inside the server's DST gap is preserved, not shifted") {

    /**
     * Setup:
     *
     * With the JVM in America/New_York (the default zone on the development machine), an asset with a capture time of 2026:03:08
     * 02:30:00, which does not exist there: the clocks skip from 02:00 to 03:00 that night.
     *
     * Assertions:
     *
     * The capture time reads back unchanged instead of being shifted out of the gap.
     */
    withJvmTimeZone("America/New_York") {
      val asset = addAssetWithCaptureTime(Some("2026:03:08 02:30:00"))
      asset.originalCreatedAt shouldEqual Some(LocalDateTime.of(2026, 3, 8, 2, 30, 0))
    }
  }

  test("Capture timestamp does not change with the JVM time zone") {

    /**
     * Setup:
     *
     * An asset with a capture time of 2024:07:01 00:10:00, re-read with the JVM in Pacific/Kiritimati (UTC+14), Etc/GMT+12
     * (UTC-12) and UTC.
     *
     * Assertions:
     *
     * Every re-read returns the same wall-clock time.
     *
     * Edge cases:
     *
     * Zones at both extremes of the offset range, either of which would move ten past midnight into another day if the time were
     * converted.
     */
    val asset = addAssetWithCaptureTime(Some("2024:07:01 00:10:00"))
    val expected = Some(LocalDateTime.of(2024, 7, 1, 0, 10, 0))

    List("Pacific/Kiritimati", "Etc/GMT+12", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          val reRead: Asset = testApp.service.asset.getById(asset.persistedId)
          reRead.originalCreatedAt shouldEqual expected
        }
    }
  }

  test("Missing capture dates stay null and public metadata cannot supply one") {

    /**
     * Setup:
     *
     * An asset with no capture time, and two more whose public metadata carry a display date, one well-formed ("2024:07:04
     * 08:09:10") and one not a date at all; all three are written straight through the DAO.
     *
     * Assertions:
     *
     * None of them gets a capture time or a capture-time source: public metadata is never a source for one.
     *
     * Edge cases:
     *
     * A display date that does not parse.
     */
    val missing = addAssetWithCaptureTime(None)
    missing.originalCreatedAt shouldBe None
    missing.originalCreatedAtSource shouldBe None
    for (displayDate <- List("2024:07:04 08:09:10", "not a date")) {
      val untrusted = testContext.makeAsset().copy(publicMetadata = PublicMetadata(dateTimeOriginal = Some(displayDate)))
      val inserted = testApp.txManager.withTransaction(testApp.DAO.asset.add(untrusted))
      val reread = testApp.service.asset.getById(inserted.persistedId)
      reread.originalCreatedAt shouldBe None
      reread.originalCreatedAtSource shouldBe None
    }
  }

  test("Coordinates round-trip through storage and stay null when absent") {

    /**
     * Setup:
     *
     * Two assets written through the DAO and marked complete: one at -33.857, 151.2152 and one with no coordinates.
     *
     * Assertions:
     *
     * The coordinates read back as stored and the missing ones stay empty, both through the regular read and through the
     * hand-written SQL that locks assets for recycling, which builds the model from its own row map.
     */
    val located = testContext.makeAsset().copy(latitude = Some(-33.857), longitude = Some(151.2152))
    val unlocated = testContext.makeAsset()
    // Completed as an import completes them, since the locking read skips an unfinished import
    val ids = testApp.txManager.withTransaction {
      List(located, unlocated).map(asset => testApp.service.asset.markAsCompleted(testApp.DAO.asset.add(asset)).persistedId)
    }
    val reread = ids.map(testApp.service.asset.getById)
    reread.head.latitude shouldBe Some(-33.857)
    reread.head.longitude shouldBe Some(151.2152)
    reread.last.latitude shouldBe None
    reread.last.longitude shouldBe None
    // The hand-written SQL paths build the model from a row map and must read the same columns
    val locked =
      testApp.txManager.withTransaction(testApp.DAO.asset.getAssetsToRecycle(ids.toSet)).sortBy(a => ids.indexOf(a.persistedId))
    locked.map(_.latitude) shouldBe List(Some(-33.857), None)
    locked.map(_.longitude) shouldBe List(Some(151.2152), None)
  }

  test("The import result carries the resolved capture time and its persisted provenance") {

    /**
     * Setup:
     *
     * Two JPEGs with an EXIF original date, images/cactus.jpg (2011-05-16 17:46:24) and images/exif/DSCF1160.JPG (2008-04-17
     * 11:12:02), imported through the pipeline.
     *
     * Assertions:
     *
     * The asset the import returns carries the capture time with its EXIF-original source, and the same values are read back from
     * the model, its JSON and the raw column ("exif_original").
     */
    for (
      (file, expected) <- List(
        "images/cactus.jpg" -> LocalDateTime.of(2011, 5, 16, 17, 46, 24),
        "images/exif/DSCF1160.JPG" -> LocalDateTime.of(2008, 4, 17, 11, 12, 2))
    ) {
      val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset(file))
      imported.originalCreatedAt shouldBe Some(expected)
      imported.originalCreatedAtSource shouldBe Some(CaptureDateSource.ExifOriginal)
      val reread = testApp.service.asset.getById(imported.persistedId)
      reread.originalCreatedAt shouldBe imported.originalCreatedAt
      reread.originalCreatedAtSource shouldBe imported.originalCreatedAtSource
      reread.toJson("original_created_at_source").str shouldBe "exif_original"
      val raw = testApp.txManager.asReadOnly {
        query("SELECT original_created_at_source FROM asset WHERE id = ?", imported.persistedId).head(
          "original_created_at_source")
      }
      raw shouldBe "exif_original"
    }
  }

  test("Metadata extraction merges upstream inputs and preserves them through storage") {

    /**
     * Setup:
     *
     * An asset over a 150 px random image, its extracted metadata seeded upstream with an "Altitude Import" field and a PNG-IHDR
     * directory holding a wrong "Image Width" and an extra note, imported through the pipeline.
     *
     * Assertions:
     *
     * The seeded fields survive extraction and storage, a value the extractor reads from the file replaces the seeded one, and
     * the seeded metadata itself is left unchanged.
     *
     * Edge cases:
     *
     * A seeded field that collides with one the extractor reads from the file.
     */
    val seeded = ExtractedMetadata(
      Map(
        "Altitude Import" -> Map("File System Created" -> "2020-01-01"),
        "PNG-IHDR" -> Map("Image Width" -> "wrong", "Upstream Note" -> "retained")))
    val data = testContext.makeAssetWithData(Some(testContext.makeAsset().copy(extractedMetadata = seeded)))
    val imported = testApp.service.library.addAsset(data)
    val reread = testApp.service.asset.getById(imported.persistedId)
    reread.extractedMetadata.getFieldValues("Altitude Import").get("File System Created") shouldBe Some("2020-01-01")
    reread.extractedMetadata.getFieldValues("PNG-IHDR").get("Upstream Note") shouldBe Some("retained")
    reread.extractedMetadata.getFieldValues("PNG-IHDR").get("Image Width") shouldBe Some("150")
    seeded.getFieldValues("PNG-IHDR").get("Image Width") shouldBe Some("wrong")
  }

  test("Import timestamp is written in UTC regardless of the JVM time zone") {

    /**
     * Setup:
     *
     * An asset written through the DAO with the JVM in Pacific/Kiritimati (UTC+14).
     *
     * Assertions:
     *
     * The stored import time, read as UTC in each engine's own way, is within 30 seconds of the current UTC time.
     */
    val asset = withJvmTimeZone("Pacific/Kiritimati") {
      addAssetWithCaptureTime(None)
    }

    val storedUtc: LocalDateTime = testApp.txManager.asReadOnly {
      testApp.dataSourceType match {
        case Const.DbEngineName.SQLITE =>
          val raw = query("SELECT created_at FROM asset WHERE id = ?", asset.persistedId).head("created_at").toString
          LocalDateTime.parse(raw.take(19).replace(' ', 'T'))
        case Const.DbEngineName.POSTGRES =>
          val raw = query("SELECT created_at AT TIME ZONE 'UTC' AS utc FROM asset WHERE id = ?", asset.persistedId).head("utc")
          raw.asInstanceOf[java.sql.Timestamp].toLocalDateTime
      }
    }

    val nowUtc = LocalDateTime.now(ZoneOffset.UTC)
    Duration.between(storedUtc, nowUtc).abs().getSeconds should be <= 30L
  }

  test("A PNG creation-time chunk is resolved and persisted through the real import") {

    /**
     * Setup:
     *
     * A random PNG with a tEXt "Creation Time" chunk of "Thu, 4 Jul 2024 08:09:10 GMT", staged and imported through the pipeline.
     *
     * Assertions:
     *
     * The capture time resolves to 2024-07-04 08:09:10 from the PNG creation-time chunk, and the time, its source and the raw
     * chunk are all read back from storage.
     */
    val data = IntegrationTestUtil.pngWithTextChunk(
      IntegrationTestUtil.generateRandomImagBytesBgr(),
      "Creation Time",
      "Thu, 4 Jul 2024 08:09:10 GMT")
    val staged = testApp.service.staging.stage(data)
    val imported = testApp.service.library.addAsset(testApp.service.library.stagedFileToAsset("cactus.png", staged))
    imported.originalCreatedAt shouldBe Some(LocalDateTime.of(2024, 7, 4, 8, 9, 10))
    imported.originalCreatedAtSource shouldBe Some(CaptureDateSource.PngCreationTime)
    val reread = testApp.service.asset.getById(imported.persistedId)
    reread.originalCreatedAt shouldBe imported.originalCreatedAt
    reread.originalCreatedAtSource shouldBe imported.originalCreatedAtSource
    reread.extractedMetadata.getFieldValues("PNG-tEXt").get("Creation Time") shouldBe Some("Thu, 4 Jul 2024 08:09:10 GMT")
  }

  test("An imported image without date metadata belongs to the No date group") {

    /**
     * Setup:
     *
     * A PNG with no date metadata (images/3.png), imported through the pipeline.
     *
     * Assertions:
     *
     * It has no capture time and no source, and a search grouped by Date Taken puts it, alone, in the No date group.
     */
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/3.png"))
    imported.originalCreatedAt shouldBe None
    imported.originalCreatedAtSource shouldBe None
    val grouped = testApp.service.library.searchGrouped(
      new SearchQuery(
        rpp = 50,
        searchSort = List(SearchSort("filename", SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.DateTaken))))
    grouped.groups.map(_.key) shouldBe List(SearchGroupKey.Day(None))
    grouped.assets.map(_.persistedId) shouldBe List(imported.persistedId)
    grouped.total shouldBe Some(1)
  }
}
