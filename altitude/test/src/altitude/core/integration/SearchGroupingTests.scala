package altitude.core.integration

import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import java.util.TimeZone
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, empty, should, shouldBe, shouldEqual, theSameElementsAs }

import altitude.core.{ Altitude, Const, FieldConst, RequestContext }
import altitude.core.models.*
import altitude.core.util.*

/** Grouped ID search: date groups with full-day totals, page ranges, and counts that honor every search filter */
@DoNotDiscover class SearchGroupingTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val byFilename = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)

  private def day(iso: String): LocalDate = LocalDate.parse(iso)

  /** An asset taken at the given camera time and imported at noon UTC the same day, named so filename order is predictable */
  private def persistDated(
      taken: String,
      filename: String,
      folder: Option[Folder] = None,
      metadata: UserMetadata = UserMetadata()): Asset = {
    val asset = testContext.persistAsset(folder = folder, metadata = metadata)
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    testContext.setAssetDates(asset.persistedId, takenAt, OffsetDateTime.of(takenAt.toLocalDate.atTime(12, 0), ZoneOffset.UTC))
    asset
  }

  private def grouped(
      by: GroupBy = GroupBy.DateTaken,
      direction: SortDirection = SortDirection.DESC,
      sort: SearchSort = byFilename,
      rpp: Int = 50,
      page: Int = 1,
      text: Option[String] = None,
      params: Map[String, Any] = Map(FieldConst.Asset.IS_RECYCLED -> false),
      metadataFilters: Map[String, Any] = Map(),
      folderIds: Set[String] = Set(),
      personIds: Set[String] = Set(),
      albumIds: Set[String] = Set()): IdSearchResult =
    testApp.service.library.searchIds(
      new SearchQuery(
        text = text,
        params = params,
        metadataFilters = metadataFilters,
        folderIds = folderIds,
        personIds = personIds,
        albumIds = albumIds,
        rpp = rpp,
        page = page,
        searchSort = List(sort),
        grouping = Some(SearchGrouping(by, direction))
      ))

  private def withJvmTimeZone[T](zoneId: String)(f: => T): T = {
    val original = TimeZone.getDefault
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    try f
    finally TimeZone.setDefault(original)
  }

  test("A page is grouped by capture day with full-day totals and contiguous ranges") {
    val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
    val a3 = persistDated("2026-09-06T23:59:59", "a3.jpg")
    val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")
    val b2 = persistDated("2026-09-05T07:00:00", "b2.jpg")

    val page1 = grouped(rpp = 2, page = 1)
    page1.ids shouldEqual List(a1.persistedId, a2.persistedId)
    page1.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 2, 3))
    page1.total shouldBe 5
    page1.totalPages shouldBe 3
    page1.page shouldBe 1

    val page2 = grouped(rpp = 2, page = 2)
    page2.ids shouldEqual List(a3.persistedId, b1.persistedId)
    page2.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 3), IdSearchGroup(day("2026-09-05"), 1, 1, 2))

    val page3 = grouped(rpp = 2, page = 3)
    page3.ids shouldEqual List(b2.persistedId)
    page3.groups shouldEqual List(IdSearchGroup(day("2026-09-05"), 0, 1, 2))

    val page4 = grouped(rpp = 2, page = 4)
    page4.ids shouldBe empty
    page4.groups shouldBe empty
    page4.total shouldBe 5
    page4.totalPages shouldBe 3
  }

  test("Group direction and the sort within a day are independent") {
    val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
    val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")
    val b2 = persistDated("2026-09-05T07:00:00", "b2.jpg")

    val oldestFirst = grouped(direction = SortDirection.ASC, sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC))
    oldestFirst.ids shouldEqual List(b2.persistedId, b1.persistedId, a2.persistedId, a1.persistedId)
    oldestFirst.groups shouldEqual List(IdSearchGroup(day("2026-09-05"), 0, 2, 2), IdSearchGroup(day("2026-09-06"), 2, 2, 2))

    val newestFirstByTime =
      grouped(direction = SortDirection.DESC, sort = SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.ASC))
    newestFirstByTime.ids shouldEqual List(a2.persistedId, a1.persistedId, b2.persistedId, b1.persistedId)
  }

  test("Capture days follow the camera's calendar date under any JVM time zone") {
    val asset = persistDated("2026-09-06T00:10:00", "a.jpg")

    List("Pacific/Kiritimati", "Etc/GMT+12", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          val result = grouped()
          result.ids shouldEqual List(asset.persistedId)
          result.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1))
        }
    }
  }

  test("Import days follow UTC under any JVM time zone") {
    val late = testContext.persistAsset()
    val early = testContext.persistAsset()
    testContext.setAssetDates(
      late.persistedId,
      LocalDateTime.parse("2020-01-01T00:00:00"),
      OffsetDateTime.parse("2026-09-07T00:30:00Z"))
    testContext.setAssetDates(
      early.persistedId,
      LocalDateTime.parse("2020-01-01T00:00:00"),
      OffsetDateTime.parse("2026-09-06T23:30:00Z"))

    List("America/New_York", "Pacific/Kiritimati", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          val result = grouped(by = GroupBy.DateImported, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
          result.ids shouldEqual List(late.persistedId, early.persistedId)
          result.groups shouldEqual List(IdSearchGroup(day("2026-09-07"), 0, 1, 1), IdSearchGroup(day("2026-09-06"), 1, 1, 1))
        }
    }
  }

  test("A day larger than the page spans pages with the same total") {
    val assets = (1 to 7).map(n => persistDated("2026-09-06T10:00:00", f"img$n%02d.jpg"))

    val pages = (1 to 3).map(p => grouped(rpp = 3, page = p))
    pages.map(_.ids).flatten shouldEqual assets.map(_.persistedId)
    pages.map(_.groups) shouldEqual Seq(
      List(IdSearchGroup(day("2026-09-06"), 0, 3, 7)),
      List(IdSearchGroup(day("2026-09-06"), 0, 3, 7)),
      List(IdSearchGroup(day("2026-09-06"), 0, 1, 7)))
  }

  test("Text, metadata, folder, album and person filters bound both the IDs and every count") {
    val keywords = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))
    val rating = testApp.service.metadata.addField(UserMetadataField(name = "rating", fieldType = FieldType.NUMBER))
    val both = UserMetadata(Map(keywords.persistedId -> Set("beach", "sunset"), rating.persistedId -> Set("5")))
    val keywordOnly = UserMetadata(Map(keywords.persistedId -> Set("beach")))

    val folder = testApp.service.folder.add("trip")
    val subFolder = testApp.service.folder.add("day 1", parentId = folder.id)

    val inSub = persistDated("2026-09-06T10:00:00", "a1.jpg", folder = Some(subFolder), metadata = both)
    val inFolder = persistDated("2026-09-06T11:00:00", "a2.jpg", folder = Some(folder), metadata = both)
    val elsewhere = persistDated("2026-09-06T12:00:00", "a3.jpg", metadata = keywordOnly)
    val older = persistDated("2026-09-05T12:00:00", "b1.jpg", metadata = both)

    // Two matching metadata values per asset must not duplicate an ID or inflate a count
    val byMetadata = grouped(metadataFilters = Map(keywords.persistedId -> "beach", rating.persistedId -> 5))
    byMetadata.ids shouldEqual List(inSub.persistedId, inFolder.persistedId, older.persistedId)
    byMetadata.total shouldBe 3
    byMetadata.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 2, 2), IdSearchGroup(day("2026-09-05"), 2, 1, 1))

    val byText = grouped(text = Some("sunset"))
    byText.ids shouldEqual List(inSub.persistedId, inFolder.persistedId, older.persistedId)
    byText.total shouldBe 3

    val byFolder = grouped(folderIds = Set(folder.persistedId))
    byFolder.ids shouldEqual List(inSub.persistedId, inFolder.persistedId)
    byFolder.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 2, 2))

    val bySubFolder = grouped(folderIds = Set(subFolder.persistedId), text = Some("beach"))
    bySubFolder.ids shouldEqual List(inSub.persistedId)
    bySubFolder.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1))

    val album = testApp.service.album.add("album")
    testApp.service.album.addAssets(album.persistedId, Set(elsewhere.persistedId, older.persistedId))
    val byAlbum = grouped(albumIds = Set(album.persistedId))
    byAlbum.ids shouldEqual List(elsewhere.persistedId, older.persistedId)
    byAlbum.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1), IdSearchGroup(day("2026-09-05"), 1, 1, 1))

    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person)
    val withFace = testContext.assets.last
    testContext.setAssetDates(
      withFace.persistedId,
      LocalDateTime.parse("2026-09-04T09:00:00"),
      OffsetDateTime.parse("2026-09-04T09:00:00Z"))
    val byPerson = grouped(personIds = Set(person.persistedId))
    byPerson.ids shouldEqual List(withFace.persistedId)
    byPerson.groups shouldEqual List(IdSearchGroup(day("2026-09-04"), 0, 1, 1))
    byPerson.total shouldBe 1
  }

  test("Root folder scope, view and repository isolation bound the counts") {
    val sorted = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val triaged = testContext.persistAsset(isTriaged = true)
    testContext.setAssetDates(
      triaged.persistedId,
      LocalDateTime.parse("2026-09-06T11:00:00"),
      OffsetDateTime.parse("2026-09-06T11:00:00Z"))
    val recycled = persistDated("2026-09-06T12:00:00", "a3.jpg")
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val rootScope = grouped(folderIds = Set(testContext.repository.rootFolderId))
    rootScope.ids should contain theSameElementsAs List(sorted.persistedId, triaged.persistedId)
    rootScope.groups.map(_.total) shouldEqual List(2)

    val trash = grouped(params = Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false))
    trash.ids shouldEqual List(recycled.persistedId)
    trash.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1))

    // Another repository's asset on the same day is invisible to this repository's counts
    val otherUser = testContext.persistUser(Some(testContext.makeUser()))
    val otherRepo = testContext.persistRepository(Some(otherUser))
    switchContextRepo(otherRepo)
    switchContextUser(otherUser)
    val foreign = testContext.persistAsset(repository = Some(otherRepo), user = Some(otherUser))
    testContext.setAssetDates(
      foreign.persistedId,
      LocalDateTime.parse("2026-09-06T13:00:00"),
      OffsetDateTime.parse("2026-09-06T13:00:00Z"))
    grouped().groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1))

    switchContextRepo(testContext.repositories.head)
    switchContextUser(testContext.users.head)
    val home = grouped()
    home.total shouldBe 2
    home.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 2, 2))
  }

  test("A grouped page is one statement regardless of the number of days on it") {
    (1 to 4).foreach(n => persistDated(s"2026-09-0${n}T10:00:00", s"d$n.jpg"))

    val before = RequestContext.readQueryCount.value
    val result = grouped(rpp = 10)
    RequestContext.readQueryCount.value - before shouldBe 1
    result.groups.length shouldBe 4
  }

  test("A legacy null import time leaves import-day grouping but keeps its capture day") {
    if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
      val dated = persistDated("2026-09-06T10:00:00", "a1.jpg")
      val undated = persistDated("2026-09-06T11:00:00", "a2.jpg")
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.persistedId)
      }

      val byImport = grouped(by = GroupBy.DateImported, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      byImport.ids shouldEqual List(dated.persistedId)
      byImport.total shouldBe 1
      byImport.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 1, 1))

      val byCapture = grouped(by = GroupBy.DateTaken, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      byCapture.ids should contain theSameElementsAs List(dated.persistedId, undated.persistedId)
      byCapture.total shouldBe 2
      byCapture.groups shouldEqual List(IdSearchGroup(day("2026-09-06"), 0, 2, 2))
    }
  }
}
