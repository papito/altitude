package altitude.core.integration

import altitude.test.IntegrationTestUtil.withJvmTimeZone
import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, contain, empty, should, shouldBe, shouldEqual, theSameElementsAs }

import altitude.core.{ Altitude, Const, FieldConst, RequestContext }
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.models.*
import altitude.core.util.*

/**
 * Grouped search: date groups with full-day totals, page contents, and counts that honor every search filter; Location groups in
 * path order with an asset under each of its Locations; Sequence groups by the first capture of their matching frames; the
 * Location and bounding-box filters and the bare count.
 */
@DoNotDiscover class SearchGroupingTests(override val testApp: Altitude)
  extends IntegrationTestCore
  with TextSearchPaths
  with SearchPlans {

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
    searchGrouped(
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

  private val inTriage: Map[String, Any] = Map(FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)

  /** A frame in triage, taken at the given camera time or at no known time */
  private def persistFrame(taken: Option[String], filename: String): Asset = {
    val asset = testContext.persistAsset(isTriaged = true)
    testApp.service.asset.rename(asset.persistedId, filename)
    testContext.setAssetDates(asset.persistedId, taken.map(LocalDateTime.parse), OffsetDateTime.parse("2026-09-06T12:00:00Z"))
    asset
  }

  /** The frames stamped into one Sequence, whose ID is returned */
  private def inSequence(frames: List[Asset], sequenceId: String = BaseDao.genId): String = {
    testApp.txManager.withTransaction(testApp.DAO.asset.setSequence(frames.map(_.persistedId).toSet, sequenceId)) shouldBe
      frames.size
    sequenceId
  }

  /** A Sequence page as (Sequence ID, the first capture, the group's full count, the asset IDs on the page) per group */
  private def sequenceSummary(result: GroupedSearchResult): List[(Option[String], Option[String], Int, List[String])] =
    result.groups.map {
      group =>
        group.key match {
          case SearchGroupKey.Sequence(id, firstCapturedAt) =>
            (id, firstCapturedAt.map(_.toString), group.total, group.assets.map(_.persistedId))
          case other => throw IllegalStateException(s"Not a Sequence group: $other")
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

    /**
     * Setup:
     *
     * One asset whose capture time is cleared to NULL.
     *
     * Assertions:
     *
     * A date-grouped search still returns it, in a single No date group counted in both the group and the total.
     */
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

    /**
     * Setup:
     *
     * Two undated assets - one in a folder with the keywords "beach" and "sunset" - and three assets taken over two days,
     * searched in both group directions.
     *
     * Assertions:
     *
     * The No date group sits where the engine sorts nulls, first or last, holding and counting both undated assets among all
     * five. A folder scope, Search text and a metadata filter together narrow the group, its count and the total to the one asset
     * that matches all three.
     *
     * Edge cases:
     *
     * The null group's position differs by engine: PostgreSQL leads with it descending, SQLite ascending.
     */
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

    /**
     * Setup:
     *
     * Five undated assets, read two to a page in both group directions.
     *
     * Assertions:
     *
     * Walking three pages by cursor returns every asset once in filename order, the No date group showing its full count of five
     * on each page; the later pages continue the group and the last one ends the cursor.
     */
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

  test("Pages are grouped by capture day with full-day totals and continued by cursor to the end") {

    /**
     * Setup:
     *
     * Three assets taken on 2026-09-06, one of them at 23:59:59, and two on 2026-09-05, read two to a page in filename order.
     *
     * Assertions:
     *
     * Each page groups its assets by capture day with the whole day's count, not the page's share. The first page alone carries
     * the overall total, a continuation knows it continues the day the previous page ended in, and the cursor walks to the last
     * page and then ends.
     *
     * Edge cases:
     *
     * A capture a second before midnight stays on its day, a day split across a page boundary, and a search that matches nothing,
     * which is an empty first page with a zero total.
     */
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

    /**
     * Setup:
     *
     * Two assets on each of two days, taken at different hours.
     *
     * Assertions:
     *
     * The direction of the days and the sort within a day apply independently: ascending days can hold assets in descending
     * filename order, and descending days assets in ascending capture time.
     */
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

    /**
     * Setup:
     *
     * One asset taken ten minutes after midnight on 2026-09-06.
     *
     * Assertions:
     *
     * It is grouped under 2026-09-06 whatever the JVM's default time zone.
     *
     * Edge cases:
     *
     * The zones at both ends of the offset range, UTC+14 and UTC-12, where any instant conversion would move the capture to
     * another day.
     */
    val asset = persistDated("2026-09-06T00:10:00", "a.jpg")

    List("Pacific/Kiritimati", "Etc/GMT+12", "UTC").foreach {
      zone =>
        withJvmTimeZone(zone) {
          summary(grouped()) shouldEqual List((day("2026-09-06"), 1, List(asset.persistedId)))
        }
    }
  }

  test("A day larger than the page spans pages with the same total") {

    /**
     * Setup:
     *
     * Seven assets taken at the same moment on one day, read three to a page.
     *
     * Assertions:
     *
     * Three pages return all seven in filename order, each showing the day with its full count of seven; the later pages continue
     * the group and the last one ends the cursor.
     */
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

    /**
     * Setup:
     *
     * A keyword field and a number field, a folder with a subfolder, three assets taken on 2026-09-06 (in the subfolder, in the
     * folder, and outside both with only the "beach" keyword) and one taken on 2026-09-05. An album of two of them and a person
     * with a Face on a fresh asset dated 2026-09-04 are added along the way.
     *
     * Assertions:
     *
     * Each filter - metadata values, Search text, a folder, a subfolder with text, an album and a person - returns only its
     * matches, and the day groups' counts and the total count exactly those assets.
     *
     * Edge cases:
     *
     * An asset that matches two metadata filters is neither duplicated nor counted twice, and a folder scope includes its
     * subfolder.
     */
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

    /**
     * Setup:
     *
     * An asset in the root folder, a triaged one and a recycled one, all taken on the same day, then a second user and repository
     * with an asset of their own on that day.
     *
     * Assertions:
     *
     * The root folder scope is the whole library - triaged assets included, recycled ones not - and the trash view holds the
     * recycled asset alone. Each repository's day groups and totals count its own assets only.
     */
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

    /**
     * Setup:
     *
     * Four assets, each taken on a different day.
     *
     * Assertions:
     *
     * A grouped page holding all four days costs exactly one read statement.
     */
    (1 to 4).foreach(n => persistDated(s"2026-09-0${n}T10:00:00", s"d$n.jpg"))

    val before = RequestContext.readQueryCount.value
    val result = grouped(rpp = 10)
    RequestContext.readQueryCount.value - before shouldBe 1
    result.groups.length shouldBe 4
    result.assets.length shouldBe 4
  }

  if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
    test("A legacy null import time keeps its capture day") {

      /**
       * Setup:
       *
       * SQLite only: two assets taken on 2026-09-06, one of them with its import time (created_at) cleared to NULL as on a legacy
       * row.
       *
       * Assertions:
       *
       * Sorted by import time, descending, both assets are still grouped under their capture day and counted in it and in the
       * total, and the NULL import time sorts after the dated one, where SQLite puts nulls in a descending sort.
       *
       * Edge cases:
       *
       * The NULL import time, which only SQLite's legacy rows can have, so the test is registered on SQLite only.
       */
      val dated = persistDated("2026-09-06T10:00:00", "a1.jpg")
      val undated = persistDated("2026-09-06T11:00:00", "a2.jpg")
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.persistedId)
      }

      // The row is grouped by its capture day and counted like any other; sorted by import time it sits where SQLite puts nulls
      val byCapture = grouped(sort = SearchSort(FieldConst.CREATED_AT, SortDirection.DESC))
      ids(byCapture) shouldEqual List(dated.persistedId, undated.persistedId)
      byCapture.total shouldBe Some(2)
      byCapture.groups.map(group => (dayOf(group.key), group.total, group.assets.length)) shouldEqual List(
        (day("2026-09-06"), 2, 2))
    }
  }

  test("Location groups are in path order, hold an asset under each of its Locations, and end with No location") {

    /**
     * Setup:
     *
     * Rome and Alba under the Italy category, top-level Berlin and Kyoto, and four assets: one in Rome and Berlin, one in Alba,
     * one in Alba and Kyoto, and one in no Location.
     *
     * Assertions:
     *
     * A single statement reads the page. The groups follow the sidebar's path order and end with No location, an asset appears
     * under each of its Locations while the total counts assets, and each group's key carries the Location's ID and path key for
     * the cursor. The request's direction does not change the order, the sort applies within each group, and a search matching
     * nothing is an empty page with a zero count.
     *
     * Edge cases:
     *
     * A category's Locations sort at the category's name, so Italy's Alba and Rome fall between Berlin and Kyoto; an asset in two
     * Locations.
     */
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

    /**
     * Setup:
     *
     * Rome under the Italy category and top-level Berlin; six assets - three in Berlin, three in Rome (two of them shared with
     * Berlin) and two in no Location - read at several page sizes.
     *
     * Assertions:
     *
     * Walking by cursor visits every group in path order, each group showing the same full count on every page it spans;
     * continuation flags mark the pages that carry a group over, only the first page has the total, and the cursor ends after No
     * location.
     *
     * Edge cases:
     *
     * A Location that runs out exactly at a page boundary, a page that ends inside No location, and a page that crosses from the
     * last Location into No location.
     */
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

  test("Sequence groups are in the order of their first capture, whatever its frames, with No sequence last") {

    /**
     * Setup:
     *
     * In triage: a burst whose frames run past those of a later burst, a Sequence with one undated frame, a later Sequence, a
     * Sequence of one frame, two Sequences with the same first capture, a Sequence with no capture time at all, and two frames in
     * no Sequence, one of them captured before all the others. One frame of the later burst is recycled.
     *
     * Assertions:
     *
     * The groups run oldest first capture first, then by the Sequence's ID, a frame in one group, whichever way the request asks
     * for: the direction has no say. A Sequence with no capture time follows those with one on either engine, and No sequence is
     * last whatever its frames' times. Each group's count is its own, the cursor's two values are the first capture and the
     * Sequence, and it is all one statement.
     *
     * Edge cases:
     *
     * A recycled frame neither counts nor sets the first capture, an undated frame in a Sequence does not make its group undated,
     * and a search matching nothing is an empty first page.
     */
    val spreadOldest = persistFrame(Some("2026-09-06T09:00:00"), "a.jpg")
    val spreadNewest = persistFrame(Some("2026-09-06T13:00:00"), "b.jpg")
    val partlyUndated = persistFrame(None, "c.jpg")
    val partlyDated = persistFrame(Some("2026-09-06T10:00:00"), "d.jpg")
    val recycled = persistFrame(Some("2026-09-06T07:00:00"), "e.jpg")
    val later1 = persistFrame(Some("2026-09-06T11:00:05"), "f.jpg")
    val later2 = persistFrame(Some("2026-09-06T11:00:06"), "g.jpg")
    val lone = persistFrame(Some("2026-09-06T12:00:00"), "h.jpg")
    val tie1 = persistFrame(Some("2026-09-06T14:00:00"), "i.jpg")
    val tie2 = persistFrame(Some("2026-09-06T14:00:00"), "j.jpg")
    val undated1 = persistFrame(None, "k.jpg")
    val undated2 = persistFrame(None, "l.jpg")
    val looseOldest = persistFrame(Some("2026-09-06T06:00:00"), "m.jpg")
    val looseUndated = persistFrame(None, "n.jpg")

    val spread = inSequence(List(spreadOldest, spreadNewest))
    val partly = inSequence(List(partlyUndated, partlyDated))
    val later = inSequence(List(recycled, later1, later2))
    val single = inSequence(List(lone))
    val List(lowTie, highTie) = List(BaseDao.genId, BaseDao.genId).sorted: @unchecked
    inSequence(List(tie1), highTie)
    inSequence(List(tie2), lowTie)
    val undated = inSequence(List(undated1, undated2))
    testApp.service.library.recycleAssets(Set(recycled.persistedId))

    def ids(assets: Asset*): List[String] = assets.toList.map(_.persistedId)
    def at(time: String): Option[String] = Some(LocalDateTime.parse(s"2026-09-06T$time").toString)

    val before = RequestContext.readQueryCount.value
    val page = grouped(by = GroupBy.Sequence, params = inTriage)
    RequestContext.readQueryCount.value - before shouldBe 1

    sequenceSummary(page) shouldEqual List(
      (Some(spread), at("09:00:00"), 2, ids(spreadOldest, spreadNewest)),
      (Some(partly), at("10:00:00"), 2, ids(partlyUndated, partlyDated)),
      (Some(later), at("11:00:05"), 2, ids(later1, later2)),
      (Some(single), at("12:00:00"), 1, ids(lone)),
      (Some(lowTie), at("14:00:00"), 1, ids(tie2)),
      (Some(highTie), at("14:00:00"), 1, ids(tie1)),
      (Some(undated), None, 2, ids(undated1, undated2)),
      (None, None, 2, ids(looseOldest, looseUndated))
    )
    page.total shouldBe Some(13)
    page.continuesGroup shouldBe false
    page.nextCursor shouldBe None
    page.groups.map(_.key.cursorGroupId) shouldEqual
      List(Some(spread), Some(partly), Some(later), Some(single), Some(lowTie), Some(highTie), Some(undated), None)
    page.groups.head.key.cursorKey shouldBe at("09:00:00")

    // The direction of the request is ignored: the order is fixed
    sequenceSummary(grouped(by = GroupBy.Sequence, params = inTriage, direction = SortDirection.ASC)) shouldEqual
      sequenceSummary(page)

    // The sort applies within each group, and does not move the groups
    val byNameDescending =
      grouped(by = GroupBy.Sequence, params = inTriage, sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC))
    sequenceSummary(byNameDescending).map(group => (group._1, group._4)) shouldEqual List(
      (Some(spread), ids(spreadNewest, spreadOldest)),
      (Some(partly), ids(partlyDated, partlyUndated)),
      (Some(later), ids(later2, later1)),
      (Some(single), ids(lone)),
      (Some(lowTie), ids(tie2)),
      (Some(highTie), ids(tie1)),
      (Some(undated), ids(undated2, undated1)),
      (None, ids(looseUndated, looseOldest))
    )

    val none = grouped(by = GroupBy.Sequence, params = inTriage, text = Some("nothing"))
    none.isEmpty shouldBe true
    none.total shouldBe Some(0)
  }

  test("A search showing some of a Sequence's frames orders and counts its group by those frames") {

    /**
     * Setup:
     *
     * A Sequence of three frames, 8:00, 9:00 and 10:00, and a second of two, 8:30 and 8:40; an album holds the first Sequence's
     * last two frames, the second's two and a frame in no Sequence.
     *
     * Assertions:
     *
     * Over the album the first Sequence's group has the first capture of its matching frames, 9:00, and comes after the second,
     * though the whole Sequence captured first; its count is the frames shown. Over everything the order is the other way round.
     *
     * The Curation set of that Sequence holds all three frames, whatever a search shows of them.
     *
     * Edge cases:
     *
     * A filter leaving one frame of a Sequence gives a group of one, which is still that Sequence's and not No sequence.
     */
    val f1 = persistFrame(Some("2026-09-06T08:00:00"), "f1.jpg")
    val f2 = persistFrame(Some("2026-09-06T09:00:00"), "f2.jpg")
    val f3 = persistFrame(Some("2026-09-06T10:00:00"), "f3.jpg")
    val g1 = persistFrame(Some("2026-09-06T08:30:00"), "g1.jpg")
    val g2 = persistFrame(Some("2026-09-06T08:40:00"), "g2.jpg")
    val loose = persistFrame(Some("2026-09-06T07:00:00"), "loose.jpg")
    val first = inSequence(List(f1, f2, f3))
    val second = inSequence(List(g1, g2))
    val album = testApp.service.album.add("Picks")
    testApp.service.album
      .addAssets(album.persistedId, Set(f2.persistedId, f3.persistedId, g1.persistedId, g2.persistedId, loose.persistedId))
    val onlyOne = testApp.service.album.add("One")
    testApp.service.album.addAssets(onlyOne.persistedId, Set(f3.persistedId))

    def at(time: String): Option[String] = Some(LocalDateTime.parse(s"2026-09-06T$time").toString)

    sequenceSummary(grouped(by = GroupBy.Sequence, params = inTriage, albumIds = Set(album.persistedId))) shouldEqual List(
      (Some(second), at("08:30:00"), 2, List(g1.persistedId, g2.persistedId)),
      (Some(first), at("09:00:00"), 2, List(f2.persistedId, f3.persistedId)),
      (None, None, 1, List(loose.persistedId))
    )
    sequenceSummary(grouped(by = GroupBy.Sequence, params = inTriage)) shouldEqual List(
      (Some(first), at("08:00:00"), 3, List(f1.persistedId, f2.persistedId, f3.persistedId)),
      (Some(second), at("08:30:00"), 2, List(g1.persistedId, g2.persistedId)),
      (None, None, 1, List(loose.persistedId))
    )
    sequenceSummary(grouped(by = GroupBy.Sequence, params = inTriage, albumIds = Set(onlyOne.persistedId))) shouldEqual
      List((Some(first), at("10:00:00"), 1, List(f3.persistedId)))

    // The set the group's Curate opens is the whole Sequence, whatever the search shows of it
    testApp.service.curation.sequenceSet(first).map(_.frames.map(_.asset.persistedId).toSet) shouldBe
      Some(Set(f1.persistedId, f2.persistedId, f3.persistedId))
  }

  test("Under the Relevance sort a Sequence group reads best match first and is ordered as under any other sort") {

    /**
     * Setup:
     *
     * Triaged frames of two Sequences and two in none, named "beach"; an album named "Beach days" holds one frame of the first
     * Sequence and one of those in none. The first Sequence's oldest frame has no such name, so the text leaves it out.
     *
     * Assertions:
     *
     * Groups run by the first capture of the frames the text finds, and each group's count is theirs. Within a group the frame
     * matched in the album leads, and frames of one Relevance read newest capture first.
     */
    val album = testApp.service.album.add("Beach days")
    val a1 = persistFrame(Some("2026-09-06T09:00:00"), "beach_a1.jpg")
    val a2 = persistFrame(Some("2026-09-06T09:00:01"), "beach_a2.jpg")
    val other = persistFrame(Some("2026-09-06T08:00:00"), "street.jpg")
    val b1 = persistFrame(Some("2026-09-06T10:00:00"), "beach_b1.jpg")
    val b2 = persistFrame(Some("2026-09-06T10:00:01"), "beach_b2.jpg")
    val loose1 = persistFrame(Some("2026-09-06T07:00:00"), "beach_c1.jpg")
    val loose2 = persistFrame(Some("2026-09-06T07:00:01"), "beach_c2.jpg")
    val first = inSequence(List(a1, a2, other))
    val second = inSequence(List(b1, b2))
    testApp.service.album.addAssets(album.persistedId, Set(a1.persistedId, loose1.persistedId))

    def at(time: String): Option[String] = Some(LocalDateTime.parse(s"2026-09-06T$time").toString)

    sequenceSummary(
      grouped(by = GroupBy.Sequence, params = inTriage, sort = SearchSort.Relevance, text = Some("beach"))) shouldEqual List(
      (Some(first), at("09:00:00"), 2, List(a1.persistedId, a2.persistedId)),
      (Some(second), at("10:00:00"), 2, List(b2.persistedId, b1.persistedId)),
      (None, None, 2, List(loose1.persistedId, loose2.persistedId))
    )
  }

  test("The Location and day groupings are unchanged in Triage, where frames carry a Sequence") {

    /**
     * Setup:
     *
     * Two frames of one Sequence in triage, one of them in a Location, and a third frame in no Sequence.
     *
     * Assertions:
     *
     * Grouped by Location, the frames are under their Location and "No location", however their Sequences lie. Grouped by day,
     * they are under their day.
     */
    val rome = addLocation("Rome")
    val frame1 = persistFrame(Some("2026-09-06T10:00:00"), "a.jpg")
    val frame2 = persistFrame(Some("2026-09-06T10:00:01"), "b.jpg")
    val loose = persistFrame(Some("2026-09-05T10:00:00"), "c.jpg")
    inSequence(List(frame1, frame2))
    testApp.service.location.addAssets(rome.persistedId, Set(frame1.persistedId))

    locationSummary(grouped(by = GroupBy.Location, params = inTriage)) shouldEqual List(
      (Some("Rome"), None, 1, List(frame1.persistedId)),
      (None, None, 2, List(frame2.persistedId, loose.persistedId))
    )
    summary(grouped(params = inTriage)) shouldEqual List(
      (day("2026-09-06"), 2, List(frame1.persistedId, frame2.persistedId)),
      (day("2026-09-05"), 1, List(loose.persistedId))
    )
  }

  test("A page grouped by Sequence reads its frames and counts through the Sequence index") {

    /**
     * Setup:
     *
     * A Sequence of two frames in triage and one frame in none, seeded into 5,000 copies with no Sequence, analyzed and rolled
     * back once the plans are read: a library the planner has rows to choose by, in which the frames with a Sequence are few.
     *
     * Assertions:
     *
     * The plan of a first page and of a continuation inside the group each name `asset_sequence` twice: once for the candidates
     * and once for the count of the page's group, so neither costs the library.
     */
    val template = persistFrame(Some("2026-09-06T09:00:00"), "loose.jpg")
    val frame1 = persistFrame(Some("2026-09-06T10:00:00"), "a.jpg")
    val frame2 = persistFrame(Some("2026-09-06T10:00:01"), "b.jpg")
    inSequence(List(frame1, frame2))
    val sort = byFilename

    def query(cursor: Option[SearchCursor]): SearchQuery =
      new SearchQuery(
        params = inTriage,
        rpp = 1,
        searchSort = List(sort),
        grouping = Some(SearchGrouping(GroupBy.Sequence)),
        cursor = cursor)

    val cursor = searchGrouped(query(None)).nextCursor
    cursor.flatMap(_.groupId).isDefined shouldBe true

    val plans = atScale(seedCopies(template.persistedId, copies = 5000, folders = 1)) {
      List(query(None), query(cursor)).map(
        q => planOf(SearchQueries.groupedBySequence(searchDialect, q, RequestContext.getRepository.persistedId)))
    }
    plans.foreach(plan => withClue(plan)("asset_sequence".r.findAllIn(plan).size should be >= 2))
  }

  test("Under the Relevance sort every group reads best match first, then newest capture, and continues by cursor") {

    /**
     * Setup:
     *
     * Assets named with "beach" on two days and one undated, two assets whose names do not match but whose album "Beach days"
     * does, and an unrelated asset; later the Locations Rome and Berlin with overlapping members. Everything is searched for
     * "beach" under the Relevance sort, two to a page.
     *
     * Assertions:
     *
     * In every day group and every Location group the album matches lead the file-name matches and ties go to the newest capture.
     * Walking the pages by cursor returns the same assets in the same order as the unpaged search, with each group's full count
     * on every page it spans.
     *
     * Edge cases:
     *
     * The No date group's engine-dependent position, an undated asset ranking last within its Relevance in a Location group, and
     * an asset in two Locations.
     */
    val album = testApp.service.album.add("Beach days")
    val a1 = persistDated("2026-09-06T10:00:00", "beach-1.jpg").persistedId
    val a2 = persistDated("2026-09-06T09:00:00", "two.jpg").persistedId
    val a3 = persistDated("2026-09-06T11:00:00", "beach-3.jpg").persistedId
    val b1 = persistDated("2026-09-05T08:00:00", "beach-4.jpg").persistedId
    val b2 = persistDated("2026-09-05T07:00:00", "five.jpg").persistedId
    val undated = persistUndated("beach-6.jpg").persistedId
    persistDated("2026-09-06T12:00:00", "unrelated.jpg")
    // The album outranks a file name, so its assets lead their day although they are its oldest
    testApp.service.album.addAssets(album.persistedId, Set(a2, b2))
    val beach = Some("beach")

    def page(by: GroupBy, cursor: Option[SearchCursor]): GroupedSearchResult =
      grouped(by = by, sort = SearchSort.Relevance, rpp = 2, text = beach, cursor = cursor)

    /** Every page of the search, two assets each, walked by cursor */
    def pages(by: GroupBy): List[GroupedSearchResult] =
      List.unfold(Option(page(by, None)))(_.map(current => (current, current.nextCursor.map(cursor => page(by, Some(cursor))))))

    val days = List((day("2026-09-06"), 3, List(a2, a3, a1)), (day("2026-09-05"), 2, List(b2, b1)))
    val noDate = (None, 1, List(undated))
    val byDay = if (nullDaysFirst(SortDirection.DESC)) noDate :: days else days :+ noDate
    summary(grouped(sort = SearchSort.Relevance, text = beach)) shouldEqual byDay
    val dayPages = pages(GroupBy.DateTaken)
    dayPages.flatMap(ids) shouldEqual byDay.flatMap(_._3)
    dayPages.map(_.total) shouldEqual List(Some(6), None, None)
    // A day's count is the whole day's on every page it spans
    dayPages.flatMap(summary).map(group => group._1 -> group._2).distinct shouldEqual byDay.map(group => group._1 -> group._2)

    val rome = addLocation("Rome")
    val berlin = addLocation("Berlin")
    testApp.service.location.addAssets(rome.persistedId, Set(a1, a2, undated))
    testApp.service.location.addAssets(berlin.persistedId, Set(a3, b2, a2))
    // In a group the undated asset is the last of its Relevance on both engines; an asset in two Locations is in both
    val byLocation =
      List((Some("Berlin"), None, 3, List(a2, b2, a3)), (Some("Rome"), None, 3, List(a2, a1, undated)), (None, None, 1, List(b1)))
    locationSummary(grouped(by = GroupBy.Location, sort = SearchSort.Relevance, text = beach)) shouldEqual byLocation
    val locationPages = pages(GroupBy.Location)
    locationPages.flatMap(ids) shouldEqual byLocation.flatMap(_._4)
    // Each page but the one that opens the trailing group continues the group the page before it ended in
    locationPages.map(_.continuesGroup) shouldEqual List(false, true, true, false)
  }

  test("Every filter bounds the Location groups and their counts, and the Location filter scopes a search") {

    /**
     * Setup:
     *
     * Rome and Berlin, a folder holding one asset in Rome and one in no Location, and an asset outside the folder in both Rome
     * and Berlin.
     *
     * Assertions:
     *
     * A folder scope narrows the Location groups and their counts, and scoped to a Location the groups are the Locations of its
     * assets with no trailing No location group. The Location filter bounds a flat search, a day grouping and the bare count
     * alike, also combined with a folder, and recycling an asset drops it from its Locations' groups and counts.
     */
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
    search(query).records.map(_.persistedId) should contain theSameElementsAs
      List(inFolder.persistedId, elsewhere.persistedId)
    count(query) shouldBe 2
    summary(grouped(locationIds = Set(rome.persistedId), folderIds = Set(folder.persistedId))) shouldEqual
      List((day("2026-09-06"), 1, List(inFolder.persistedId)))
    count(flat(locationIds = Set(rome.persistedId), folderIds = Set(folder.persistedId))) shouldBe 1

    // Recycling drops the memberships, and with them the asset from the Location's group and count
    testApp.service.library.recycleAssets(Set(elsewhere.persistedId))
    locationSummary(grouped(by = GroupBy.Location)) shouldEqual
      List((Some("Rome"), None, 1, List(inFolder.persistedId)), (None, None, 1, List(unlocated.persistedId)))
    count(flat()) shouldBe 2
  }

  test("The bounding-box filter plots an asset at its own point, or at its Locations' pins without one") {

    /**
     * Setup:
     *
     * A Sydney Location and assets with their own points in Paris, Tokyo and on either side of the antimeridian; an asset without
     * a point is in Sydney, the Tokyo one is in Sydney too, and one with no point is added to Sydney and removed again.
     *
     * Assertions:
     *
     * A box finds an asset by its own point, or by a Location's pin only when it has no point of its own, and the same predicate
     * bounds the count and a grouped page.
     *
     * Edge cases:
     *
     * A box across the antimeridian (its west edge greater than its east edge) covers both sides while the same edges the other
     * way round find nothing, and an asset whose only membership was removed is plotted nowhere.
     */
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
      search(flat(bbox = Some(BoundingBox.parse(box)))).records.map(_.persistedId).sorted

    found("48,2,49,3") shouldEqual List(own.persistedId)
    // An asset with a point of its own is never plotted at its Location's pin
    found("-34,151,-33,152") shouldEqual List(pinned.persistedId)
    found("35,139,36,140") shouldEqual List(pointAndPin.persistedId)
    // A box across the antimeridian covers both sides of it; the same edges the other way round do not
    found("-1,179,1,-179") shouldEqual List(east.persistedId, west.persistedId).sorted
    found("-1,-179,1,179") shouldEqual Nil
    found("-90,-180,90,180") shouldEqual List(own, pinned, pointAndPin, east, west).map(_.persistedId).sorted

    // The same predicate bounds the count and a grouped page
    count(flat(bbox = Some(BoundingBox.parse("-1,179,1,-179")))) shouldBe 2
    summary(grouped(bbox = Some(BoundingBox.parse("-34,151,-33,152")))) shouldEqual
      List((day("2026-09-06"), 1, List(pinned.persistedId)))
    count(flat()) shouldBe 6
  }
}
