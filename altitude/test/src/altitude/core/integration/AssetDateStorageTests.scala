package altitude.core.integration

import altitude.test.IntegrationTestUtil
import java.time.{ Duration, LocalDateTime, ZoneOffset }
import java.util.TimeZone
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe, shouldEqual }

import altitude.core.{ Altitude, Const }
import altitude.core.models.{ Asset, CaptureDateSource, ExtractedMetadata, PublicMetadata }
import altitude.core.models.{ ImportAsset, UserMetadata }
import altitude.core.util.{ GroupBy, SearchGrouping, SearchQuery, SearchSort, SortDirection }

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

  private def withJvmTimeZone[T](zoneId: String)(f: => T): T = {
    val original = TimeZone.getDefault
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    try f
    finally TimeZone.setDefault(original)
  }

  test("Capture timestamp is stored and read back as the camera's wall-clock time") {
    val asset = addAssetWithCaptureTime(Some("2024:03:10 23:59:59"))
    asset.originalCreatedAt shouldEqual Some(LocalDateTime.of(2024, 3, 10, 23, 59, 59))
  }

  test("Capture timestamp inside the server's DST gap is preserved, not shifted") {
    // 02:30 on 2026-03-08 does not exist in America/New_York (the default zone on the development machine)
    withJvmTimeZone("America/New_York") {
      val asset = addAssetWithCaptureTime(Some("2026:03:08 02:30:00"))
      asset.originalCreatedAt shouldEqual Some(LocalDateTime.of(2026, 3, 8, 2, 30, 0))
    }
  }

  test("Capture timestamp does not change with the JVM time zone") {
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

  test("The import result carries the resolved capture time and its persisted provenance") {
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
    val data = IntegrationTestUtil.pngWithTextChunk(
      IntegrationTestUtil.generateRandomImagBytesBgr(),
      "Creation Time",
      "Thu, 4 Jul 2024 08:09:10 GMT")
    val imported =
      testApp.service.library.addImportAsset(ImportAsset(fileName = "cactus.png", data = data, metadata = UserMetadata()))
    imported.originalCreatedAt shouldBe Some(LocalDateTime.of(2024, 7, 4, 8, 9, 10))
    imported.originalCreatedAtSource shouldBe Some(CaptureDateSource.PngCreationTime)
    val reread = testApp.service.asset.getById(imported.persistedId)
    reread.originalCreatedAt shouldBe imported.originalCreatedAt
    reread.originalCreatedAtSource shouldBe imported.originalCreatedAtSource
    reread.extractedMetadata.getFieldValues("PNG-tEXt").get("Creation Time") shouldBe Some("Thu, 4 Jul 2024 08:09:10 GMT")
  }

  test("An imported image without date metadata belongs to the No date group") {
    val imported = testApp.service.library.addImportAsset(IntegrationTestUtil.getImportAsset("images/3.png"))
    imported.originalCreatedAt shouldBe None
    imported.originalCreatedAtSource shouldBe None
    val grouped = testApp.service.library.searchGrouped(
      new SearchQuery(
        rpp = 50,
        searchSort = List(SearchSort("filename", SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.DateTaken))))
    grouped.groups.map(_.date) shouldBe List(None)
    grouped.assets.map(_.persistedId) shouldBe List(imported.persistedId)
    grouped.total shouldBe Some(1)
  }
}
