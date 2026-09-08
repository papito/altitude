package altitude.core.integration

import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import java.util.TimeZone
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, empty, should, shouldBe, shouldEqual, theSameElementsAs }

import altitude.core.{ Altitude, Const, FieldConst, RequestContext }
import altitude.core.models.*
import altitude.core.util.*

/** Grouped search: date groups with full-day totals, page contents, and counts that honor every search filter */
@DoNotDiscover class SearchGroupingTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val byFilename = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)

  private def day(iso: String): Option[LocalDate] = Some(LocalDate.parse(iso))

  /** An asset taken at the given camera time and imported at noon UTC the same day, named so filename order is predictable */
  private def persistDated(
      taken: String,
      filename: String,
      folder: Option[Folder] = None,
      metadata: UserMetadata = UserMetadata()): Asset = {
    val asset = testContext.persistAsset(folder = folder, metadata = metadata)
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    testContext.setAssetDates(
      asset.persistedId,
      Some(takenAt),
      OffsetDateTime.of(takenAt.toLocalDate.atTime(12, 0), ZoneOffset.UTC))
    asset
  }

  private def grouped(
      by: GroupBy = GroupBy.DateTaken,
      direction: SortDirection = SortDirection.DESC,
      sort: SearchSort = byFilename,
      rpp: Int = 50,
      cursor: Option[SearchCursor] = None,
      text: Option[String] = None,
      params: Map[String, Any] = Map(FieldConst.Asset.IS_RECYCLED -> false),
      metadataFilters: Map[String, Any] = Map(),
      folderIds: Set[String] = Set(),
      personIds: Set[String] = Set(),
      albumIds: Set[String] = Set()): GroupedSearchResult =
    testApp.service.library.searchGrouped(
      new SearchQuery(
        text = text,
        params = params,
        metadataFilters = metadataFilters,
        folderIds = folderIds,
        personIds = personIds,
        albumIds = albumIds,
        rpp = rpp,
        searchSort = List(sort),
        grouping = Some(SearchGrouping(by, direction)),
        cursor = cursor
      ))

  /** The page as (day, the day's full count, the asset IDs on the page) per group, in page order */
  private def summary(result: GroupedSearchResult): List[(Option[LocalDate], Int, List[String])] =
    result.groups.map(group => (group.date, group.total, group.assets.map(_.persistedId)))

  private def ids(result: GroupedSearchResult): List[String] = result.assets.map(_.persistedId)

  test("Assets without a capture date remain in grouped results") {
    val undated = testContext.persistAsset()
    testApp.txManager.withTransaction {
      update("UPDATE asset SET original_created_at = NULL WHERE id = ?", undated.persistedId)
    }
    val result = grouped()
    ids(result) shouldEqual List(undated.persistedId)
    result.total shouldBe Some(1)
    result.groups.head.total shouldBe 1
    result.groups.head.date shouldBe None
  }

  private def nullDaysFirst(direction: SortDirection): Boolean =
    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) direction == SortDirection.DESC else direction == SortDirection.ASC

  private def persistUndated(filename: String, folder: Option[Folder] = None, metadata: UserMetadata = UserMetadata()): Asset = {
    val asset = persistDated("2026-09-06T10:00:00", filename, folder, metadata)
    testContext.setAssetDates(asset.persistedId, None, OffsetDateTime.parse("2026-09-06T12:00:00Z"))
    asset
  }

  test("The No date group follows native ordering and every filter bounds its count") {
    val folder = testApp.service.folder.add("undated")
    val keyword = testApp.service.metadata.addField(UserMetadataField(name = "keywords", fieldType = FieldType.KEYWORD))
    val metadata = UserMetadata(Map(keyword.persistedId -> Set("beach", "sunset")))
    val unknown = persistUndated("a.jpg", Some(folder), metadata)
    val other = persistUndated("b.jpg")
    val dated = List(
      persistDated("2026-09-05T10:00:00", "c.jpg"),
      persistDated("2026-09-06T10:00:00", "d.jpg"),
      persistDated("2026-09-06T11:00:00", "e.jpg"))
    for (direction <- SortDirection.values.toList) {
      val page = grouped(direction = direction)
      page.total shouldBe Some(5)
      val nullGroup = (None, 2, List(unknown.persistedId, other.persistedId))
      (if (nullDaysFirst(direction)) summary(page).head else summary(page).last) shouldEqual nullGroup
      ids(page) should contain theSameElementsAs (unknown :: other :: dated).map(_.persistedId)
      val filtered = grouped(
        direction = direction,
        folderIds = Set(folder.persistedId),
        text = Some("sunset"),
        metadataFilters = Map(keyword.persistedId -> "beach"))
      summary(filtered) shouldEqual List((None, 1, List(unknown.persistedId)))
      filtered.total shouldBe Some(1)
    }
  }

  test("The No date group spans full pages with one consistent count") {
    val assets = (1 to 5).map(n => persistUndated(s"$n.jpg"))
    for (direction <- SortDirection.values.toList) {
      val first = grouped(direction = direction, rpp = 2)
      val second = grouped(direction = direction, rpp = 2, cursor = first.nextCursor)
      val third = grouped(direction = direction, rpp = 2, cursor = second.nextCursor)
      val pages = List(first, second, third)
      pages.flatMap(ids) shouldEqual assets.map(_.persistedId)
      pages.map(_.groups.map(g => (g.date, g.total, g.assets.size))) shouldEqual
        List(List((None, 5, 2)), List((None, 5, 2)), List((None, 5, 1)))
      second.continuesGroup shouldBe true
      third.continuesGroup shouldBe true
      third.nextCursor shouldBe None
    }
  }

  test("Undated assets retain their UTC import day when sorted by capture time") {
    val dated = persistDated("2026-09-06T10:00:00", "a.jpg")
    val undated = persistUndated("b.jpg")
    for (direction <- SortDirection.values.toList) {
      val page = grouped(by = GroupBy.DateImported, sort = SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, direction))
      val order = if (nullDaysFirst(direction)) List(undated, dated) else List(dated, undated)
      summary(page) shouldEqual List((day("2026-09-06"), 2, order.map(_.persistedId)))
      page.total shouldBe Some(2)
    }
  }

  private def withJvmTimeZone[T](zoneId: String)(f: => T): T = {
    val original = TimeZone.getDefault
    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
    try f
    finally TimeZone.setDefault(original)
  }

  test("Pages are grouped by capture day with full-day totals and continued by cursor to the end") {
    val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
    val a3 = persistDated("2026-09-06T23:59:59", "a3.jpg")
    val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")
    val b2 = persistDated("2026-09-05T07:00:00", "b2.jpg")

    val page1 = grouped(rpp = 2)
    summary(page1) shouldEqual List((day("2026-09-06"), 3, List(a1.persistedId, a2.persistedId)))
    page1.assets.map(_.fileName) shouldEqual List("a1.jpg", "a2.jpg")
    page1.total shouldBe Some(5)
    page1.continuesGroup shouldBe false
    page1.nextCursor.isDefined shouldBe true

    // A continuation completes the day and opens the next one; it knows the day it continues and skips the overall count
    val page2 = grouped(rpp = 2, cursor = page1.nextCursor)
    summary(page2) shouldEqual List((day("2026-09-06"), 3, List(a3.persistedId)), (day("2026-09-05"), 2, List(b1.persistedId)))
    page2.continuesGroup shouldBe true
    page2.total shouldBe None

    val page3 = grouped(rpp = 2, cursor = page2.nextCursor)
    summary(page3) shouldEqual List((day("2026-09-05"), 2, List(b2.persistedId)))
    page3.nextCursor shouldBe None

    // Nothing matches: an empty first page with a zero count
    val none = grouped(text = Some("nothing"))
    none.isEmpty shouldBe true
    none.assets shouldBe empty
    none.total shouldBe Some(0)
    none.nextCursor shouldBe None
  }

  test("Group direction and the sort within a day are independent") {
    val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
    val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")
    val b2 = persistDated("2026-09-05T07:00:00", "b2.jpg")

    val oldestFirst = grouped(direction = SortDirection.ASC, sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC))
    summary(oldestFirst) shouldEqual List(
      (day("2026-09-05"), 2, List(b2.persistedId, b1.persistedId)),
      (day("2026-09-06"), 2, List(a2.persistedId, a1.persistedId)))

    val newestFirstByTime =
      grouped(direction = SortDirection.DESC, sort = SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.ASC))
    ids(newestFirstByTime) shouldEqual List(a2.persistedId, a1.persistedId, b2.persistedId, b1.persistedId)
  }

  test("Capture days follow the camera's calendar date under any JVM time zone") {
    val asset = persistDated("2026-09-06T00:10:00", "a.jpg")

    List("Pacific/Kiritimati", "Etc/GMT+12", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          summary(grouped()) shouldEqual List((day("2026-09-06"), 1, List(asset.persistedId)))
        }
    }
  }

  test("Import days follow UTC under any JVM time zone") {
    val late = testContext.persistAsset()
    val early = testContext.persistAsset()
    testContext.setAssetDates(
      late.persistedId,
      Some(LocalDateTime.parse("2020-01-01T00:00:00")),
      OffsetDateTime.parse("2026-09-07T00:30:00Z"))
    testContext.setAssetDates(
      early.persistedId,
      Some(LocalDateTime.parse("2020-01-01T00:00:00")),
      OffsetDateTime.parse("2026-09-06T23:30:00Z"))

    List("America/New_York", "Pacific/Kiritimati", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          val result = grouped(by = GroupBy.DateImported, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
          summary(result) shouldEqual List(
            (day("2026-09-07"), 1, List(late.persistedId)),
            (day("2026-09-06"), 1, List(early.persistedId)))
        }
    }
  }

  test("A day larger than the page spans pages with the same total") {
    val assets = (1 to 7).map(n => persistDated("2026-09-06T10:00:00", f"img$n%02d.jpg"))

    val page1 = grouped(rpp = 3)
    val page2 = grouped(rpp = 3, cursor = page1.nextCursor)
    val page3 = grouped(rpp = 3, cursor = page2.nextCursor)
    val pages = List(page1, page2, page3)

    pages.flatMap(ids) shouldEqual assets.map(_.persistedId)
    pages.map(_.groups.map(group => (group.date, group.total, group.assets.length))) shouldEqual List(
      List((day("2026-09-06"), 7, 3)),
      List((day("2026-09-06"), 7, 3)),
      List((day("2026-09-06"), 7, 1)))
    page2.continuesGroup shouldBe true
    page3.continuesGroup shouldBe true
    page3.nextCursor shouldBe None
  }

  test("Text, metadata, folder, album and person filters bound both the assets and every count") {
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

    // Two matching metadata values per asset must not duplicate an asset or inflate a count
    val byMetadata = grouped(metadataFilters = Map(keywords.persistedId -> "beach", rating.persistedId -> 5))
    summary(byMetadata) shouldEqual List(
      (day("2026-09-06"), 2, List(inSub.persistedId, inFolder.persistedId)),
      (day("2026-09-05"), 1, List(older.persistedId)))
    byMetadata.total shouldBe Some(3)

    val byText = grouped(text = Some("sunset"))
    ids(byText) shouldEqual List(inSub.persistedId, inFolder.persistedId, older.persistedId)
    byText.total shouldBe Some(3)

    val byFolder = grouped(folderIds = Set(folder.persistedId))
    summary(byFolder) shouldEqual List((day("2026-09-06"), 2, List(inSub.persistedId, inFolder.persistedId)))

    val bySubFolder = grouped(folderIds = Set(subFolder.persistedId), text = Some("beach"))
    summary(bySubFolder) shouldEqual List((day("2026-09-06"), 1, List(inSub.persistedId)))

    val album = testApp.service.album.add("album")
    testApp.service.album.addAssets(album.persistedId, Set(elsewhere.persistedId, older.persistedId))
    val byAlbum = grouped(albumIds = Set(album.persistedId))
    summary(byAlbum) shouldEqual List(
      (day("2026-09-06"), 1, List(elsewhere.persistedId)),
      (day("2026-09-05"), 1, List(older.persistedId)))

    val person = testApp.service.person.addPerson(Person())
    testContext.addTestFacesAndAssets(person)
    val withFace = testContext.assets.last
    testContext.setAssetDates(
      withFace.persistedId,
      Some(LocalDateTime.parse("2026-09-04T09:00:00")),
      OffsetDateTime.parse("2026-09-04T09:00:00Z"))
    val byPerson = grouped(personIds = Set(person.persistedId))
    summary(byPerson) shouldEqual List((day("2026-09-04"), 1, List(withFace.persistedId)))
    byPerson.total shouldBe Some(1)
  }

  test("Root folder scope, view and repository isolation bound the counts") {
    val sorted = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val triaged = testContext.persistAsset(isTriaged = true)
    testContext.setAssetDates(
      triaged.persistedId,
      Some(LocalDateTime.parse("2026-09-06T11:00:00")),
      OffsetDateTime.parse("2026-09-06T11:00:00Z"))
    val recycled = persistDated("2026-09-06T12:00:00", "a3.jpg")
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    val rootScope = grouped(folderIds = Set(testContext.repository.rootFolderId))
    ids(rootScope) should contain theSameElementsAs List(sorted.persistedId, triaged.persistedId)
    rootScope.groups.map(_.total) shouldEqual List(2)

    val trash = grouped(params = Map(FieldConst.Asset.IS_RECYCLED -> true, FieldConst.Asset.IS_PURGED -> false))
    summary(trash) shouldEqual List((day("2026-09-06"), 1, List(recycled.persistedId)))

    // Another repository's asset on the same day is invisible to this repository's counts
    val otherUser = testContext.persistUser(Some(testContext.makeUser()))
    val otherRepo = testContext.persistRepository(Some(otherUser))
    switchContextRepo(otherRepo)
    switchContextUser(otherUser)
    val foreign = testContext.persistAsset(repository = Some(otherRepo), user = Some(otherUser))
    testContext.setAssetDates(
      foreign.persistedId,
      Some(LocalDateTime.parse("2026-09-06T13:00:00")),
      OffsetDateTime.parse("2026-09-06T13:00:00Z"))
    summary(grouped()) shouldEqual List((day("2026-09-06"), 1, List(foreign.persistedId)))

    switchContextRepo(testContext.repositories.head)
    switchContextUser(testContext.users.head)
    val home = grouped()
    home.total shouldBe Some(2)
    home.groups.map(group => (group.date, group.total, group.assets.length)) shouldEqual List((day("2026-09-06"), 2, 2))
  }

  test("A grouped page is one statement regardless of the number of days on it") {
    (1 to 4).foreach(n => persistDated(s"2026-09-0${n}T10:00:00", s"d$n.jpg"))

    val before = RequestContext.readQueryCount.value
    val result = grouped(rpp = 10)
    RequestContext.readQueryCount.value - before shouldBe 1
    result.groups.length shouldBe 4
    result.assets.length shouldBe 4
  }

  test("A legacy null import time leaves import-day grouping but keeps its capture day") {
    if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
      val dated = persistDated("2026-09-06T10:00:00", "a1.jpg")
      val undated = persistDated("2026-09-06T11:00:00", "a2.jpg")
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.persistedId)
      }

      val byImport = grouped(by = GroupBy.DateImported, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      summary(byImport) shouldEqual List((day("2026-09-06"), 1, List(dated.persistedId)))
      byImport.total shouldBe Some(1)

      val byCapture = grouped(by = GroupBy.DateTaken, sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      ids(byCapture) should contain theSameElementsAs List(dated.persistedId, undated.persistedId)
      byCapture.total shouldBe Some(2)
      byCapture.groups.map(group => (group.date, group.total, group.assets.length)) shouldEqual List((day("2026-09-06"), 2, 2))
    }
  }
}
