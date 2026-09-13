package altitude.core.integration

import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import java.util.TimeZone
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, empty, should, shouldBe, shouldEqual, theSameElementsAs }

import altitude.core.{ Altitude, Const, FieldConst, RequestContext }
import altitude.core.models.*
import altitude.core.util.*

/**
 * Grouped search: date groups with full-day totals, page contents, and counts that honor every search filter; Location groups in
 * path order with an asset under each of its Locations; the Location and bounding-box filters and the bare count.
 */
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
      direction: SortDirection = SortDirection.DESC,
      sort: SearchSort = byFilename,
      rpp: Int = 50,
      cursor: Option[SearchCursor] = None,
      text: Option[String] = None,
      params: Map[String, Any] = Map(FieldConst.Asset.IS_RECYCLED -> false),
      metadataFilters: Map[String, Any] = Map(),
      folderIds: Set[String] = Set(),
      personIds: Set[String] = Set(),
      albumIds: Set[String] = Set(),
      locationIds: Set[String] = Set(),
      bbox: Option[BoundingBox] = None,
      by: GroupBy = GroupBy.DateTaken): GroupedSearchResult =
    testApp.service.library.searchGrouped(
      new SearchQuery(
        text = text,
        params = params,
        metadataFilters = metadataFilters,
        folderIds = folderIds,
        personIds = personIds,
        albumIds = albumIds,
        locationIds = locationIds,
        bbox = bbox,
        rpp = rpp,
        searchSort = List(sort),
        grouping = Some(SearchGrouping(by, direction)),
        cursor = cursor
      ))

  private def dayOf(key: SearchGroupKey): Option[LocalDate] = key match {
    case SearchGroupKey.Day(date) => date
    case other => throw IllegalStateException(s"Not a day group: $other")
  }

  /** The page as (day, the day's full count, the asset IDs on the page) per group, in page order */
  private def summary(result: GroupedSearchResult): List[(Option[LocalDate], Int, List[String])] =
    result.groups.map(group => (dayOf(group.key), group.total, group.assets.map(_.persistedId)))

  /** A Location page as (Location name, category name, the group's full count, the asset IDs on the page) per group */
  private def locationSummary(result: GroupedSearchResult): List[(Option[String], Option[String], Int, List[String])] =
    result.groups.map {
      group =>
        group.key match {
          case SearchGroupKey.Location(_, _, name, categoryName) =>
            (name, categoryName, group.total, group.assets.map(_.persistedId))
          case other => throw IllegalStateException(s"Not a Location group: $other")
        }
    }

  private val paris = (48.8566, 2.3522)

  private def addLocation(name: String, categoryId: Option[String] = None, pin: (Double, Double) = paris): Location =
    testApp.service.location.addLocation(name, pin._1, pin._2, categoryId)

  private def setCoordinates(asset: Asset, latitude: Double, longitude: Double): Unit =
    testContext.setAssetCoordinates(asset.persistedId, latitude, longitude)

  private def flat(
      params: Map[String, Any] = Map(FieldConst.Asset.IS_RECYCLED -> false),
      locationIds: Set[String] = Set(),
      bbox: Option[BoundingBox] = None,
      folderIds: Set[String] = Set()): SearchQuery =
    new SearchQuery(params = params, locationIds = locationIds, bbox = bbox, folderIds = folderIds, rpp = 100)

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
    dayOf(result.groups.head.key) shouldBe None
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
      pages.map(_.groups.map(g => (dayOf(g.key), g.total, g.assets.size))) shouldEqual
        List(List((None, 5, 2)), List((None, 5, 2)), List((None, 5, 1)))
      second.continuesGroup shouldBe true
      third.continuesGroup shouldBe true
      third.nextCursor shouldBe None
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

  test("A day larger than the page spans pages with the same total") {
    val assets = (1 to 7).map(n => persistDated("2026-09-06T10:00:00", f"img$n%02d.jpg"))

    val page1 = grouped(rpp = 3)
    val page2 = grouped(rpp = 3, cursor = page1.nextCursor)
    val page3 = grouped(rpp = 3, cursor = page2.nextCursor)
    val pages = List(page1, page2, page3)

    pages.flatMap(ids) shouldEqual assets.map(_.persistedId)
    pages.map(_.groups.map(group => (dayOf(group.key), group.total, group.assets.length))) shouldEqual List(
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
    home.groups.map(group => (dayOf(group.key), group.total, group.assets.length)) shouldEqual List((day("2026-09-06"), 2, 2))
  }

  test("A grouped page is one statement regardless of the number of days on it") {
    (1 to 4).foreach(n => persistDated(s"2026-09-0${n}T10:00:00", s"d$n.jpg"))

    val before = RequestContext.readQueryCount.value
    val result = grouped(rpp = 10)
    RequestContext.readQueryCount.value - before shouldBe 1
    result.groups.length shouldBe 4
    result.assets.length shouldBe 4
  }

  test("A legacy null import time keeps its capture day") {
    if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
      val dated = persistDated("2026-09-06T10:00:00", "a1.jpg")
      val undated = persistDated("2026-09-06T11:00:00", "a2.jpg")
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.persistedId)
      }

      // The row is grouped by its capture day and counted like any other; sorted by import time it sits where SQLite puts nulls
      val byCapture = grouped(sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      ids(byCapture) should contain theSameElementsAs List(dated.persistedId, undated.persistedId)
      byCapture.total shouldBe Some(2)
      byCapture.groups.map(group => (dayOf(group.key), group.total, group.assets.length)) shouldEqual List(
        (day("2026-09-06"), 2, 2))
    }
  }

  test("Location groups are in path order, hold an asset under each of its Locations, and end with No location") {
    val italy = testApp.service.location.addCategory("Italy")
    val rome = addLocation("Rome", Some(italy.persistedId))
    val alba = addLocation("Alba", Some(italy.persistedId))
    val berlin = addLocation("Berlin")
    val kyoto = addLocation("Kyoto")
    val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
    val a2 = persistDated("2026-09-06T11:00:00", "a2.jpg")
    val a3 = persistDated("2026-09-06T12:00:00", "a3.jpg")
    val a4 = persistDated("2026-09-06T13:00:00", "a4.jpg")
    testApp.service.location.addAssets(rome.persistedId, Set(a1.persistedId))
    testApp.service.location.addAssets(berlin.persistedId, Set(a1.persistedId))
    testApp.service.location.addAssets(alba.persistedId, Set(a2.persistedId, a4.persistedId))
    testApp.service.location.addAssets(kyoto.persistedId, Set(a4.persistedId))

    val before = RequestContext.readQueryCount.value
    val page = grouped(by = GroupBy.Location)
    RequestContext.readQueryCount.value - before shouldBe 1

    // A category's Locations sort at the category's name, by their own; an asset in two Locations is under both; the total counts assets
    locationSummary(page) shouldEqual List(
      (Some("Berlin"), None, 1, List(a1.persistedId)),
      (Some("Alba"), Some("Italy"), 2, List(a2.persistedId, a4.persistedId)),
      (Some("Rome"), Some("Italy"), 1, List(a1.persistedId)),
      (Some("Kyoto"), None, 1, List(a4.persistedId)),
      (None, None, 1, List(a3.persistedId))
    )
    page.total shouldBe Some(4)
    page.groups.map(_.key.cursorGroupId) shouldEqual
      List(Some(berlin.persistedId), Some(alba.persistedId), Some(rome.persistedId), Some(kyoto.persistedId), None)
    page.groups.head.key.cursorKey shouldBe Some("berlin")
    page.continuesGroup shouldBe false
    page.nextCursor shouldBe None

    // The direction of the request is ignored: the order is fixed
    locationSummary(grouped(by = GroupBy.Location, direction = SortDirection.ASC)) shouldEqual locationSummary(page)

    // The sort applies within each group
    val newestFirst = grouped(by = GroupBy.Location, sort = SearchSort(FieldConst.Asset.ORIGINAL_CREATED_AT, SortDirection.DESC))
    locationSummary(newestFirst).map(_._4) shouldEqual List(
      List(a1.persistedId),
      List(a4.persistedId, a2.persistedId),
      List(a1.persistedId),
      List(a4.persistedId),
      List(a3.persistedId))

    // Nothing matches: an empty first page with a zero count
    val none = grouped(by = GroupBy.Location, text = Some("nothing"))
    none.isEmpty shouldBe true
    none.total shouldBe Some(0)
  }

  test("Location groups span pages with one consistent count and continue into No location") {
    val italy = testApp.service.location.addCategory("Italy")
    val rome = addLocation("Rome", Some(italy.persistedId))
    val berlin = addLocation("Berlin")
    val a1 = persistDated("2026-09-06T10:00:00", "1.jpg").persistedId
    val a2 = persistDated("2026-09-06T10:00:00", "2.jpg").persistedId
    val a3 = persistDated("2026-09-06T10:00:00", "3.jpg").persistedId
    val a4 = persistDated("2026-09-06T10:00:00", "4.jpg").persistedId
    val a5 = persistDated("2026-09-06T10:00:00", "5.jpg").persistedId
    val a6 = persistDated("2026-09-06T10:00:00", "6.jpg").persistedId
    testApp.service.location.addAssets(berlin.persistedId, Set(a1, a2, a3))
    testApp.service.location.addAssets(rome.persistedId, Set(a2, a3, a4))

    // Berlin, Rome, No location: a1 a2 a3 | a2 a3 a4 | a5 a6
    val page1 = grouped(by = GroupBy.Location, rpp = 2)
    val page2 = grouped(by = GroupBy.Location, rpp = 2, cursor = page1.nextCursor)
    val page3 = grouped(by = GroupBy.Location, rpp = 2, cursor = page2.nextCursor)
    val page4 = grouped(by = GroupBy.Location, rpp = 2, cursor = page3.nextCursor)

    locationSummary(page1) shouldEqual List((Some("Berlin"), None, 3, List(a1, a2)))
    // Berlin completes and Rome opens; the count of a group is the same on every page it spans
    locationSummary(page2) shouldEqual List((Some("Berlin"), None, 3, List(a3)), (Some("Rome"), Some("Italy"), 3, List(a2)))
    locationSummary(page3) shouldEqual List((Some("Rome"), Some("Italy"), 3, List(a3, a4)))
    // Rome ran out exactly at a page boundary: the next page is the trailing group alone
    locationSummary(page4) shouldEqual List((None, None, 2, List(a5, a6)))
    page2.continuesGroup shouldBe true
    page3.continuesGroup shouldBe true
    page4.continuesGroup shouldBe false
    List(page2, page3, page4).map(_.total) shouldEqual List(None, None, None)
    page4.nextCursor shouldBe None

    // A page ending inside No location continues there, with the group's count unchanged
    val page5 = grouped(by = GroupBy.Location, rpp = 7)
    page5.nextCursor.map(cursor => (cursor.key, cursor.groupId, cursor.id)) shouldBe Some((None, None, a5))
    val tail = grouped(by = GroupBy.Location, rpp = 7, cursor = page5.nextCursor)
    locationSummary(tail) shouldEqual List((None, None, 2, List(a6)))
    tail.continuesGroup shouldBe true
    tail.nextCursor shouldBe None

    // A page can cross from the last Location into No location
    val page6 = grouped(by = GroupBy.Location, rpp = 4)
    locationSummary(grouped(by = GroupBy.Location, rpp = 4, cursor = page6.nextCursor)) shouldEqual
      List((Some("Rome"), Some("Italy"), 3, List(a3, a4)), (None, None, 2, List(a5, a6)))
  }

  test("Every filter bounds the Location groups and their counts, and the Location filter scopes a search") {
    val rome = addLocation("Rome")
    val berlin = addLocation("Berlin")
    val folder = testApp.service.folder.add("trip")
    val inFolder = persistDated("2026-09-06T10:00:00", "a1.jpg", folder = Some(folder))
    val elsewhere = persistDated("2026-09-06T11:00:00", "a2.jpg")
    val unlocated = persistDated("2026-09-06T12:00:00", "a3.jpg", folder = Some(folder))
    testApp.service.location.addAssets(rome.persistedId, Set(inFolder.persistedId, elsewhere.persistedId))
    testApp.service.location.addAssets(berlin.persistedId, Set(elsewhere.persistedId))

    val byFolder = grouped(by = GroupBy.Location, folderIds = Set(folder.persistedId))
    locationSummary(byFolder) shouldEqual
      List((Some("Rome"), None, 1, List(inFolder.persistedId)), (None, None, 1, List(unlocated.persistedId)))
    byFolder.total shouldBe Some(2)

    // Scoped to a Location, the groups are the Locations of its assets and there is no trailing group
    val byLocation = grouped(by = GroupBy.Location, locationIds = Set(berlin.persistedId))
    locationSummary(byLocation) shouldEqual
      List((Some("Berlin"), None, 1, List(elsewhere.persistedId)), (Some("Rome"), None, 1, List(elsewhere.persistedId)))
    byLocation.total shouldBe Some(1)

    // The Location filter on a flat search, on a day grouping, and on the bare count
    val query = flat(locationIds = Set(rome.persistedId))
    testApp.service.library.search(query).records.map(_.persistedId) should contain theSameElementsAs
      List(inFolder.persistedId, elsewhere.persistedId)
    testApp.service.library.count(query) shouldBe 2
    summary(grouped(locationIds = Set(rome.persistedId), folderIds = Set(folder.persistedId))) shouldEqual
      List((day("2026-09-06"), 1, List(inFolder.persistedId)))
    testApp.service.library.count(flat(locationIds = Set(rome.persistedId), folderIds = Set(folder.persistedId))) shouldBe 1

    // Recycling drops the memberships, and with them the asset from the Location's group and count
    testApp.service.library.recycleAssets(Set(elsewhere.persistedId))
    locationSummary(grouped(by = GroupBy.Location)) shouldEqual
      List((Some("Rome"), None, 1, List(inFolder.persistedId)), (None, None, 1, List(unlocated.persistedId)))
    testApp.service.library.count(flat()) shouldBe 2
  }

  test("The bounding-box filter plots an asset at its own point, or at its Locations' pins without one") {
    val sydney = addLocation("Sydney", pin = (-33.8688, 151.2093))
    val own = persistDated("2026-09-06T10:00:00", "paris.jpg")
    setCoordinates(own, paris._1, paris._2)
    val pinned = persistDated("2026-09-06T11:00:00", "sydney.jpg")
    val pointAndPin = persistDated("2026-09-06T12:00:00", "tokyo.jpg")
    setCoordinates(pointAndPin, 35.6762, 139.6503)
    val east = persistDated("2026-09-06T13:00:00", "east.jpg")
    setCoordinates(east, 0.5, 179.5)
    val west = persistDated("2026-09-06T14:00:00", "west.jpg")
    setCoordinates(west, -0.5, -179.5)
    val nowhere = persistDated("2026-09-06T15:00:00", "nowhere.jpg")
    testApp.service.location.addAssets(sydney.persistedId, Set(pinned.persistedId, pointAndPin.persistedId, nowhere.persistedId))
    testApp.service.location.removeAssets(sydney.persistedId, Set(nowhere.persistedId))

    def found(box: String): List[String] =
      testApp.service.library.search(flat(bbox = Some(BoundingBox.parse(box)))).records.map(_.persistedId).sorted

    found("48,2,49,3") shouldEqual List(own.persistedId)
    // An asset with a point of its own is never plotted at its Location's pin
    found("-34,151,-33,152") shouldEqual List(pinned.persistedId)
    found("35,139,36,140") shouldEqual List(pointAndPin.persistedId)
    // A box across the antimeridian covers both sides of it; the same edges the other way round do not
    found("-1,179,1,-179") shouldEqual List(east.persistedId, west.persistedId).sorted
    found("-1,-179,1,179") shouldEqual Nil
    found("-90,-180,90,180") shouldEqual List(own, pinned, pointAndPin, east, west).map(_.persistedId).sorted

    // The same predicate bounds the count and a grouped page
    testApp.service.library.count(flat(bbox = Some(BoundingBox.parse("-1,179,1,-179")))) shouldBe 2
    summary(grouped(bbox = Some(BoundingBox.parse("-34,151,-33,152")))) shouldEqual
      List((day("2026-09-06"), 1, List(pinned.persistedId)))
    testApp.service.library.count(flat()) shouldBe 6
  }
}
