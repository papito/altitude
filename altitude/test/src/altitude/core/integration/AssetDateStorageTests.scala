package altitude.core.integration

import java.time.{ Duration, LocalDateTime, ZoneOffset }
import java.util.TimeZone
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe, shouldEqual }

import altitude.core.{ Altitude, Const }
import altitude.core.models.{ Album, Asset, Folder, PublicMetadata }

/**
 * Date storage semantics behind date grouping: a capture timestamp is the camera's wall-clock time and must survive storage
 * without any timezone conversion, while an import timestamp is written explicitly in UTC.
 */
@DoNotDiscover class AssetDateStorageTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addAssetWithCaptureTime(dateTimeOriginal: Option[String]): Asset = {
    val asset = testContext.makeAsset().copy(publicMetadata = PublicMetadata(dateTimeOriginal = dateTimeOriginal))
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

  test("Missing or invalid capture metadata falls back to the local import time") {
    val before = LocalDateTime.now().minusSeconds(5)
    val missing = addAssetWithCaptureTime(None)
    val invalid = addAssetWithCaptureTime(Some("not a date"))
    val after = LocalDateTime.now().plusSeconds(5)

    List(missing, invalid).foreach {
      asset =>
        asset.originalCreatedAt.isDefined shouldBe true
        asset.originalCreatedAt.get.isAfter(before) shouldBe true
        asset.originalCreatedAt.get.isBefore(after) shouldBe true
    }
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

  test("Upgrading a version 2 database adds the date indexes and preserves stored data") {
    val folder: Folder = testApp.service.folder.add("dated")
    val album: Album = testApp.service.album.add("dated album")
    val inFolder: Asset = testContext.persistAsset(folder = Some(folder))
    // A time the version 2 schema can represent: on PostgreSQL that schema held instants, so a DST-gap wall time is out of scope
    val captured: Asset = addAssetWithCaptureTime(Some("2024:03:10 23:59:59"))
    testApp.service.album.addAssets(album.persistedId, Set(inFolder.persistedId, captured.persistedId))

    def rawDates(): List[(String, String)] = testApp.txManager.asReadOnly {
      query("SELECT id, original_created_at, created_at FROM asset ORDER BY id").map {
        rec => (rec("original_created_at").toString, rec("created_at").toString)
      }
    }
    val datesBefore = rawDates()

    // Roll the schema back to the version 2 shape
    testApp.txManager.withTransaction {
      update("DROP INDEX asset_search_date_taken")
      update("DROP INDEX asset_search_date_imported")
      if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
        update(
          "ALTER TABLE asset ALTER COLUMN original_created_at TYPE TIMESTAMP WITH TIME ZONE " +
            "USING original_created_at AT TIME ZONE current_setting('TimeZone')")
      }
      update("UPDATE system SET version = 2")
    }
    testApp.service.system.version shouldBe 2

    testApp.service.migrationService.migrate()

    testApp.service.system.version shouldBe 3

    val indexNames = testApp.txManager.asReadOnly {
      testApp.dataSourceType match {
        case Const.DbEngineName.SQLITE =>
          query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'asset_search_date_%' ORDER BY name")
            .map(_("name").toString)
        case Const.DbEngineName.POSTGRES =>
          query(
            "SELECT indexname AS name FROM pg_indexes WHERE tablename = 'asset' AND indexname LIKE 'asset_search_date_%' ORDER BY indexname")
            .map(_("name").toString)
      }
    }
    indexNames shouldEqual List("asset_search_date_imported", "asset_search_date_taken")

    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
      val columnType = testApp.txManager.asReadOnly {
        query(
          "SELECT data_type FROM information_schema.columns WHERE table_name = 'asset' AND column_name = 'original_created_at'")
          .head("data_type")
          .toString
      }
      columnType shouldBe "timestamp without time zone"
    }

    rawDates() shouldEqual datesBefore
    (testApp.service.asset.getById(captured.persistedId): Asset).originalCreatedAt shouldEqual Some(
      LocalDateTime.of(2024, 3, 10, 23, 59, 59))
    (testApp.service.asset.getById(inFolder.persistedId): Asset).folderId shouldBe folder.persistedId
    testApp.service.album.getAssetIds(album.persistedId) shouldEqual Set(inFolder.persistedId, captured.persistedId)
  }
}
