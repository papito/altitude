package altitude.core.integration

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe, shouldEqual }

import scala.util.Random

import altitude.core.{ Altitude, Const, FieldConst, SearchCursorException }
import altitude.core.models.{ Asset, Person }
import altitude.core.service.FaceDetectionService
import altitude.core.util.*

/** Cursor continuation of a flat and of a grouped search: every supported ordering, live changes around the anchor, scope checks */
@DoNotDiscover class SearchCursorTests(override val testApp: Altitude) extends IntegrationTestCore with TextSearchPaths {

  /** What a fixture asset was given, so the expected order can be computed independently of SQL */
  private case class Dated(id: String, taken: Option[LocalDateTime], imported: OffsetDateTime, filename: String)

  private val sortFields =
    List(
      FieldConst.Asset.ORIGINAL_CREATED_AT,
      FieldConst.CREATED_AT,
      FieldConst.Asset.FILENAME,
      FieldConst.Asset.SIZE_BYTES,
      FieldConst.Asset.AREA_SIZE)

  private def persistDated(taken: String, filename: String): Dated = {
    val asset = testContext.persistAsset()
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    // Imported an hour after capture, as a UTC instant
    val importedAt = OffsetDateTime.of(takenAt.plusHours(1), ZoneOffset.UTC)
    testContext.setAssetDates(asset.persistedId, Some(takenAt), importedAt)
    Dated(asset.persistedId, Some(takenAt), importedAt, filename)
  }

  private def nullsFirst(direction: SortDirection): Boolean =
    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) direction == SortDirection.DESC else direction == SortDirection.ASC

  private def persistUndated(filename: String): Dated = {
    val asset = testContext.persistAsset()
    testApp.service.asset.rename(asset.persistedId, filename)
    val imported = OffsetDateTime.parse("2026-09-06T12:00:00Z")
    testContext.setAssetDates(asset.persistedId, None, imported)
    Dated(asset.persistedId, None, imported, filename)
  }

  /** Four days, three images each, with ties on every sort key so the ID tiebreaker is exercised */
  private def fixture(includeUndated: Boolean = true): List[Dated] = List(
    persistDated("2026-09-06T10:00:00", "img01.jpg"),
    persistDated("2026-09-06T10:00:00", "img01.jpg"),
    persistDated("2026-09-06T11:00:00", "img02.jpg"),
    persistDated("2026-09-05T09:00:00", "img03.jpg"),
    persistDated("2026-09-05T09:00:00", "img02.jpg"),
    persistDated("2026-09-05T08:00:00", "img02.jpg"),
    persistDated("2026-09-04T12:00:00", "img01.jpg"),
    persistDated("2026-09-04T13:00:00", "img05.jpg"),
    persistDated("2026-09-04T14:00:00", "img05.jpg"),
    persistDated("2026-09-03T07:00:00", "img09.jpg"),
    persistDated("2026-09-03T07:30:00", "img00.jpg"),
    persistDated("2026-09-03T07:30:00", "img00.jpg")
  ) ++ (if (includeUndated)
          List(
            persistUndated("img01.jpg"),
            persistUndated("img01.jpg"),
            persistUndated("img00.jpg"),
            persistUndated("img09.jpg")
          )
        else Nil)

  private def nativeOrder(direction: SortDirection): Ordering[Option[String]] = new Ordering[Option[String]] {
    override def compare(a: Option[String], b: Option[String]): Int = (a, b) match {
      case (None, None) => 0
      case (None, _) => if (nullsFirst(direction)) -1 else 1
      case (_, None) => if (nullsFirst(direction)) 1 else -1
      case (Some(x), Some(y)) => if (direction == SortDirection.ASC) x.compareTo(y) else y.compareTo(x)
    }
  }

  /** The order within a group: the sort, nulls ranked natively, then the ID. Equal size and area leave those sorts to the ID. */
  private def sortThenId(sort: SearchSort): Ordering[Dated] = {
    def sortKey(a: Dated): Option[String] = sort.field match {
      case FieldConst.Asset.ORIGINAL_CREATED_AT => a.taken.map(_.toString)
      case FieldConst.CREATED_AT => Some(a.imported.withOffsetSameInstant(ZoneOffset.UTC).toString)
      case FieldConst.Asset.FILENAME => Some(a.filename)
      case _ => Some("")
    }
    Ordering.by[Dated, Option[String]](sortKey)(nativeOrder(sort.direction)).orElse(Ordering.by[Dated, String](_.id))
  }

  private def expectedOrder(assets: List[Dated], grouping: SearchGrouping, sort: SearchSort): List[String] = {
    def day(a: Dated): Option[String] = a.taken.map(_.toLocalDate.toString)
    // Nulls are ranked independently at each ordering level
    val dayOrdering = Ordering.by[Dated, Option[String]](day)(nativeOrder(grouping.direction))
    assets.sorted(dayOrdering.orElse(sortThenId(sort))).map(_.id)
  }

  /** Members of each Location in path order, then the assets in none, each block in sort order then by ID */
  private def expectedLocationOrder(blocks: List[List[Dated]], sort: SearchSort): List[String] =
    blocks.flatMap(_.sorted(sortThenId(sort)).map(_.id))

  private def firstPage(grouping: SearchGrouping, sort: SearchSort, rpp: Int, text: Option[String] = None): GroupedSearchResult =
    searchGrouped(
      new SearchQuery(
        text = text,
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        rpp = rpp,
        searchSort = List(sort),
        grouping = Some(grouping)))

  private def continue(
      cursor: SearchCursor,
      grouping: SearchGrouping,
      sort: SearchSort,
      rpp: Int,
      text: Option[String] = None): GroupedSearchResult =
    searchGrouped(
      new SearchQuery(
        text = text,
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        rpp = rpp,
        searchSort = List(sort),
        grouping = Some(grouping),
        cursor = Some(cursor)
      ))

  private def ids(result: GroupedSearchResult): List[String] = result.assets.map(_.persistedId)

  /** Walks every page through encoded cursors, as a client would, and returns the IDs in order */
  private def traverse(grouping: SearchGrouping, sort: SearchSort, rpp: Int, text: Option[String] = None): List[String] = {
    var page = firstPage(grouping, sort, rpp, text)
    var walked = ids(page)
    while (page.nextCursor.isDefined) {
      val cursor = SearchCursor.decode(page.nextCursor.get.encode)
      cursor shouldEqual page.nextCursor.get
      page = continue(cursor, grouping, sort, rpp, text)
      page.continuesGroup shouldBe page.groups.headOption.exists(_.key.continues(cursor))
      page.assets.nonEmpty shouldBe true
      walked.size should be < 100
      walked = walked ++ ids(page)
    }
    walked
  }

  test("Cursor traversal matches the complete order for every grouping, sort field and direction") {

    /**
     * Setup:
     *
     * The fixture: 12 assets captured over four days (2026-09-03 to 2026-09-06), three a day and each imported an hour after
     * capture, with repeated capture times and file names, plus 4 undated assets sharing one import time. It is walked page by
     * page through encoded cursors, grouped by Date Taken.
     *
     * Assertions:
     *
     * For both group directions, every sort field (capture time, import time, file name, size, area) and both sort directions,
     * the walk reproduces the complete order computed independently of SQL: the day, the sort with nulls where the engine puts
     * them, then the ID. Every cursor survives encoding, and every continuation is non-empty and flags whether it continues the
     * anchor's group.
     *
     * Edge cases:
     *
     * Ties on every sort key leave the order to the ID, and size and area tie for every asset. Walking one asset a page makes
     * every position an anchor, including both ends and each tie inside the undated group.
     */
    val assets = fixture()

    for {
      groupDirection <- SortDirection.values.toList
      field <- sortFields
      sortDirection <- SortDirection.values.toList
    } {
      val grouping = SearchGrouping(GroupBy.DateTaken, groupDirection)
      val sort = SearchSort(field, sortDirection)
      val expected = expectedOrder(assets, grouping, sort)
      withClue(s"$grouping $sort: ") {
        traverse(grouping, sort, rpp = 5) shouldEqual expected
      }
    }

    // Every position becomes an anchor, including both boundaries and every tie inside the null group.
    for (direction <- SortDirection.values.toList; field <- sortFields; sortDirection <- SortDirection.values.toList) {
      val grouping = SearchGrouping(GroupBy.DateTaken, direction)
      val sort = SearchSort(field, sortDirection)
      traverse(grouping, sort, rpp = 1) shouldEqual expectedOrder(assets, grouping, sort)
    }
  }

  private def flatPage(
      sort: SearchSort,
      rpp: Int,
      cursor: Option[SearchCursor] = None,
      text: Option[String] = None): SearchResult =
    search(
      new SearchQuery(
        text = text,
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        rpp = rpp,
        searchSort = List(sort),
        cursor = cursor))

  /** Walks every page of a flat search through encoded cursors, as a client would, and returns the IDs in order */
  private def traverseFlat(sort: SearchSort, rpp: Int, text: Option[String] = None): List[String] = {
    val pages = List.unfold(Option(flatPage(sort, rpp, text = text))) {
      _.map {
        page =>
          page.nextCursor.foreach(cursor => SearchCursor.decode(cursor.encode) shouldEqual cursor)
          (page, page.nextCursor.map(cursor => flatPage(sort, rpp, Some(cursor), text)))
      }
    }
    pages.size should be < 100
    // Only a first page counts the matches, and only a last page may be short
    pages.map(_.total.isDefined) shouldEqual (true :: List.fill(pages.size - 1)(false))
    pages.init.foreach(_.records.size shouldBe rpp)
    pages.flatMap(_.records.map(_.persistedId))
  }

  test("A flat search walked by cursor matches the complete order for every sort field and direction") {

    /**
     * Setup:
     *
     * The fixture: 12 assets captured over four days, three a day and each imported an hour after capture, with repeated capture
     * times and file names, plus 4 undated assets sharing one import time. It is walked ungrouped, page by page through encoded
     * cursors, five assets a page and then one.
     *
     * Assertions:
     *
     * For every sort field (capture time, import time, file name, size, area) and both directions the walk reproduces the
     * complete order computed independently of SQL: the sort with nulls where the engine puts them, then the ID. Every cursor
     * survives encoding, only the first page carries the total, and every page but the last is full.
     *
     * Edge cases:
     *
     * Ties on every sort key leave the order to the ID, and size and area tie for every asset. Walking one asset a page makes
     * every position an anchor, including the step from the dated assets into the undated ones and each tie among those.
     */
    val assets = fixture()

    for (field <- sortFields; direction <- SortDirection.values.toList; rpp <- List(5, 1)) {
      val sort = SearchSort(field, direction)
      withClue(s"$sort, $rpp a page: ") {
        traverseFlat(sort, rpp) shouldEqual assets.sorted(sortThenId(sort)).map(_.id)
      }
    }
  }

  test("A flat search under the Relevance sort continues by cursor in the order of the unpaged search") {

    /**
     * Setup:
     *
     * Assets named with "beach" on two days and one undated, two assets whose names do not match but whose album "Beach days"
     * does, and an unrelated asset, searched ungrouped for "beach" under the Relevance sort.
     *
     * Assertions:
     *
     * The unpaged search puts the album matches first and then the file-name matches, each newest capture first with the undated
     * one last. Walked two and one a page by cursor, the search returns the same assets in the same order.
     */
    val album = testApp.service.album.add("Beach days")
    val a1 = persistDated("2026-09-06T10:00:00", "beach-1.jpg").id
    val a2 = persistDated("2026-09-06T09:00:00", "two.jpg").id
    val a3 = persistDated("2026-09-06T11:00:00", "beach-3.jpg").id
    val b1 = persistDated("2026-09-05T08:00:00", "beach-4.jpg").id
    val b2 = persistDated("2026-09-05T07:00:00", "five.jpg").id
    val undated = persistUndated("beach-6.jpg").id
    persistDated("2026-09-06T12:00:00", "unrelated.jpg")
    testApp.service.album.addAssets(album.persistedId, Set(a2, b2))
    val beach = Some("beach")

    val unpaged = flatPage(SearchSort.Relevance, rpp = 50, text = beach)
    unpaged.records.map(_.persistedId) shouldEqual List(a2, b2, a3, a1, b1, undated)
    unpaged.nextCursor shouldBe None

    for (rpp <- List(2, 1)) withClue(s"$rpp a page: ") {
      traverseFlat(SearchSort.Relevance, rpp, beach) shouldEqual unpaged.records.map(_.persistedId)
    }
  }

  test("A flat cursor continues only the flat search it was issued for") {

    /**
     * Setup:
     *
     * The 16-asset fixture. A flat search sorted by file name gives a cursor from its first page of five; the same search grouped
     * by Date Taken gives another.
     *
     * Assertions:
     *
     * The flat cursor names the last asset of its page and continues the same search, at another page size too. It is refused
     * under another sort or direction, with Search text, and by the grouped search; the grouped search's cursor is refused by the
     * flat one.
     */
    val assets = fixture()
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val expected = assets.sorted(sortThenId(sort)).map(_.id)
    val grouping = SearchGrouping(GroupBy.DateTaken)

    val cursor = flatPage(sort, rpp = 5).nextCursor.get
    cursor.id shouldBe expected(4)
    flatPage(sort, rpp = 6, Some(cursor)).records.map(_.persistedId) shouldEqual expected.slice(5, 11)

    intercept[SearchCursorException](flatPage(SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC), 5, Some(cursor)))
    intercept[SearchCursorException](flatPage(SearchSort(FieldConst.CREATED_AT, SortDirection.ASC), 5, Some(cursor)))
    intercept[SearchCursorException](flatPage(sort, 5, Some(cursor), text = Some("beach")))
    intercept[SearchCursorException](continue(cursor, grouping, sort, rpp = 5))
    intercept[SearchCursorException](flatPage(sort, 5, firstPage(grouping, sort, rpp = 5).nextCursor))
  }

  test("The cursor points at the last returned image and ends with the results") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). It is grouped by Date Taken, sorted by file name and
     * read 5 a page, then 16 a page.
     *
     * Assertions:
     *
     * Each page picks up where the previous one stopped, the cursor names the last asset returned, only the first page carries
     * the total, and the last page has no cursor.
     *
     * Edge cases:
     *
     * A first page that holds exactly every result has no cursor either.
     */
    val assets = fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val expected = expectedOrder(assets, grouping, sort)

    val page1 = firstPage(grouping, sort, rpp = 5)
    ids(page1) shouldEqual expected.take(5)
    page1.nextCursor.get.id shouldBe expected(4)
    page1.total shouldBe Some(16)

    // A continuation skips the overall count; the first page set it
    val page2 = continue(page1.nextCursor.get, grouping, sort, 5)
    page2.total shouldBe None
    val page3 = continue(page2.nextCursor.get, grouping, sort, 5)
    ids(page3) shouldEqual expected.slice(10, 15)
    val page4 = continue(page3.nextCursor.get, grouping, sort, 5)
    ids(page4) shouldEqual expected.drop(15)
    page4.nextCursor shouldBe None

    // Exactly a full last page still needs one more request to learn that it was the last
    val exact = firstPage(grouping, sort, rpp = 16)
    ids(exact) shouldEqual expected
    exact.nextCursor shouldBe None
  }

  test("Deleting the anchor or inserting before it does not skip or repeat images") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). Its first page of 5, grouped by Date Taken and sorted
     * by file name, gives the cursor; then the anchor is recycled, and later an asset that sorts before the anchor is added.
     *
     * Assertions:
     *
     * After each change the continuation from the same cursor returns the same next five assets, while a fresh first page's total
     * follows the change.
     *
     * Edge cases:
     *
     * The anchor itself is no longer in the results when the search continues from it.
     */
    val assets = fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val expected = expectedOrder(assets, grouping, sort)

    val page1 = firstPage(grouping, sort, rpp = 5)
    val cursor = page1.nextCursor.get

    // The anchor leaves the result set
    testApp.service.library.recycleAssets(Set(cursor.id))
    val afterDelete = continue(cursor, grouping, sort, 5)
    ids(afterDelete) shouldEqual expected.slice(5, 10)
    firstPage(grouping, sort, rpp = 5).total shouldBe Some(15)

    // An image sorting before the anchor appears; the remaining pages are unchanged and the counts are live
    persistDated("2026-09-06T09:00:00", "img00.jpg")
    val afterInsert = continue(cursor, grouping, sort, 5)
    ids(afterInsert) shouldEqual expected.slice(5, 10)
    firstPage(grouping, sort, rpp = 5).total shouldBe Some(16)
    afterInsert.nextCursor.isDefined shouldBe true
  }

  test("A cursor is rejected for a different scope or ordering, and when malformed") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). The cursor comes from its first page of 5, grouped by
     * Date Taken and sorted by file name; a folder, a Location in Rome and a box around Rome serve as other scopes.
     *
     * Assertions:
     *
     * The cursor is refused when the text, the sort field or direction, the group direction or the grouping, the folder, the
     * Location or the bounding box differ, and decoding refuses an older version, garbage, an empty object and an empty string.
     * The same search still continues.
     *
     * Edge cases:
     *
     * A cursor with no day and a null sort value survives encoding, and a continuation may ask for another page size: it is not
     * part of the position.
     */
    fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val cursor = firstPage(grouping, sort, rpp = 5).nextCursor.get

    intercept[SearchCursorException](continue(cursor, grouping, sort, rpp = 5, text = Some("beach")))
    intercept[SearchCursorException](
      continue(cursor, grouping, SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC), rpp = 5))
    intercept[SearchCursorException](continue(cursor, grouping, SearchSort(FieldConst.CREATED_AT, SortDirection.ASC), rpp = 5))
    intercept[SearchCursorException](continue(cursor, SearchGrouping(GroupBy.DateTaken, SortDirection.ASC), sort, rpp = 5))

    // The page size is not part of the position: a continuation may ask for another one
    ids(continue(cursor, grouping, sort, rpp = 6)).length shouldBe 6

    val folder = testApp.service.folder.add("scoped")
    val location = testApp.service.location.addLocation("Rome", 41.9, 12.5, None)
    def scoped(folderIds: Set[String] = Set(), locationIds: Set[String] = Set(), bbox: Option[BoundingBox] = None) =
      searchGrouped(
        new SearchQuery(
          params = Map(FieldConst.Asset.IS_RECYCLED -> false),
          folderIds = folderIds,
          locationIds = locationIds,
          bbox = bbox,
          rpp = 5,
          searchSort = List(sort),
          grouping = Some(grouping),
          cursor = Some(cursor)
        ))
    intercept[SearchCursorException](scoped(folderIds = Set(folder.persistedId)))
    intercept[SearchCursorException](scoped(locationIds = Set(location.persistedId)))
    intercept[SearchCursorException](scoped(bbox = Some(BoundingBox(41.0, 12.0, 42.0, 13.0))))
    // A cursor from a day grouping does not continue a Location grouping of the same search
    intercept[SearchCursorException](continue(cursor, SearchGrouping(GroupBy.Location), sort, rpp = 5))

    val nullCursor = cursor.copy(key = None, sortValue = SortValue.Null)
    SearchCursor.decode(nullCursor.encode) shouldBe nullCursor
    val oldJson =
      ujson.read(new String(java.util.Base64.getUrlDecoder.decode(cursor.encode), java.nio.charset.StandardCharsets.UTF_8))
    oldJson("v") = 5
    val oldToken = java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(ujson.write(oldJson).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    intercept[SearchCursorException](SearchCursor.decode(oldToken)).getMessage shouldBe "Unsupported cursor version"

    intercept[SearchCursorException](SearchCursor.decode("not a cursor"))
    intercept[SearchCursorException](SearchCursor.decode("e30")) // {}
    intercept[SearchCursorException](SearchCursor.decode(""))

    // The same search continues
    ids(continue(cursor, grouping, sort, rpp = 5)).length shouldBe 5
  }

  if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
    test("A cursor continues correctly through a legacy null import time") {

      /**
       * Setup:
       *
       * SQLite only: the fixture without its undated assets, with one asset's import time cleared to NULL, as a legacy row may
       * have it. Only SQLite's legacy rows can hold a NULL import time, so the test is registered on SQLite only.
       *
       * Assertions:
       *
       * Grouped by capture day and sorted by import time in either direction, walking one and two assets a page keeps that asset
       * in its day, first or last in it as SQLite puts nulls for the direction. Ungrouped, the same walks put it first of all
       * ascending and last of all descending.
       */
      val assets = fixture(includeUndated = false)
      val undated = assets(2)
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.id)
      }
      val grouping = SearchGrouping(GroupBy.DateTaken)

      // Grouped by capture day, the image keeps its day; sorted by import time it sits where SQLite puts nulls
      List(SortDirection.ASC, SortDirection.DESC).foreach {
        direction =>
          val sort = SearchSort(FieldConst.CREATED_AT, direction)
          val dayOfUndated = assets.filter(_.taken.map(_.toLocalDate) == undated.taken.map(_.toLocalDate))
          val others = expectedOrder(dayOfUndated.filterNot(_.id == undated.id), grouping, sort)
          val expectedDay = if (direction == SortDirection.ASC) undated.id :: others else others :+ undated.id
          val rest =
            expectedOrder(assets.filterNot(a => a.taken.map(_.toLocalDate) == undated.taken.map(_.toLocalDate)), grouping, sort)

          val flat = assets.filterNot(_.id == undated.id).sorted(sortThenId(sort)).map(_.id)
          val expectedFlat = if (direction == SortDirection.ASC) undated.id :: flat else flat :+ undated.id

          withClue(s"$sort: ") {
            traverse(grouping, sort, rpp = 1) shouldEqual expectedDay ++ rest
            traverse(grouping, sort, rpp = 2) shouldEqual expectedDay ++ rest
            traverseFlat(sort, rpp = 1) shouldEqual expectedFlat
            traverseFlat(sort, rpp = 2) shouldEqual expectedFlat
          }
      }
    }
  }

  test("Cursor traversal by Location matches the complete order for every sort field and direction") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). It is spread over Rome and Alba in the Italy category
     * and an uncategorized Berlin, with overlapping memberships: Rome holds 6, Berlin 8 (three shared with Rome), Alba 2, and 3
     * assets are in no Location.
     *
     * Assertions:
     *
     * For every sort field and direction, walking 5, 1 and 6 assets a page reproduces the Locations in path order (Berlin, then
     * Italy's Alba and Rome) and then the assets in none, each block in sort order then by ID. The first page counts each asset
     * once although some repeat across Locations, and its cursor names the Location by its path key and ID, the path key of a
     * categorized one carrying its category.
     *
     * Edge cases:
     *
     * Walking one asset a page makes each group boundary an anchor, and at six a page Rome fills exactly one page. Deleting the
     * anchor's Location mid-walk moves on to the trailing group, which its members in no other Location have joined.
     */
    val assets = fixture()
    val italy = testApp.service.location.addCategory("Italy")
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, Some(italy.persistedId))
    val alba = testApp.service.location.addLocation("Alba", 44.7, 8.0, Some(italy.persistedId))
    val berlin = testApp.service.location.addLocation("Berlin", 52.5, 13.4, None)
    // Overlapping memberships, a Location the size of a page, and a trailing group with every kind of tie
    val inRome = assets.slice(0, 6)
    val inBerlin = assets.slice(3, 9) ++ assets.slice(12, 14)
    val inAlba = assets.slice(9, 11)
    val inNone = assets.slice(11, 12) ++ assets.slice(14, 16)
    testApp.service.location.addAssets(rome.persistedId, inRome.map(_.id).toSet)
    testApp.service.location.addAssets(berlin.persistedId, inBerlin.map(_.id).toSet)
    testApp.service.location.addAssets(alba.persistedId, inAlba.map(_.id).toSet)
    val blocks = List(inBerlin, inAlba, inRome, inNone)
    val grouping = SearchGrouping(GroupBy.Location)

    for (field <- sortFields; sortDirection <- SortDirection.values.toList) {
      val sort = SearchSort(field, sortDirection)
      val expected = expectedLocationOrder(blocks, sort)
      withClue(s"$sort: ") {
        traverse(grouping, sort, rpp = 5) shouldEqual expected
        // Every position becomes an anchor, including each group boundary
        traverse(grouping, sort, rpp = 1) shouldEqual expected
        traverse(grouping, sort, rpp = 6) shouldEqual expected
      }
    }

    // Within a group the rows are one asset each; across groups an asset repeats, and the first page counts assets once
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val page1 = firstPage(grouping, sort, rpp = 5)
    page1.total shouldBe Some(16)
    page1.groups.map(_.total) shouldEqual List(8)
    page1.nextCursor.map(cursor => (cursor.key, cursor.groupId)) shouldBe Some((Some("berlin"), Some(berlin.persistedId)))
    val cursorInRome = {
      var page = page1
      while (!page.groups.last.key.cursorGroupId.contains(rome.persistedId))
        page = continue(page.nextCursor.get, grouping, sort, 5)
      page.nextCursor.get
    }
    cursorInRome.key shouldBe Some("italy" + 1.toChar + "rome")

    // Deleting the anchor's Location moves on to the trailing group, which its now unlocated members have joined
    testApp.service.location.deleteById(rome.persistedId)
    val afterRome = expectedLocationOrder(List(inNone ++ assets.slice(0, 3)), sort)
    var cursor = Option(cursorInRome)
    var remaining = List.empty[String]
    while (cursor.isDefined) {
      val page = continue(cursor.get, grouping, sort, 5)
      remaining = remaining ++ ids(page)
      cursor = page.nextCursor
    }
    remaining shouldEqual afterRome
  }

  private val img = Some("img")

  /**
   * Makes the text "img" match the fixture in three sources: every file name, an album for a third of the assets and a person for
   * a quarter of them. Returns the Relevance of each asset: the score of the best source it is matched in.
   */
  private def rankByImg(assets: List[Dated]): Map[String, Int] = {
    val album = testApp.service.album.add("Img picks")
    val person = testApp.service.person.addPerson(Person(name = Some("Img Model")))
    val inAlbum = assets.zipWithIndex.collect { case (asset, index) if index % 3 == 0 => asset.id }.toSet
    val withPerson = assets.zipWithIndex.collect { case (asset, index) if index % 4 == 0 => asset.id }.toSet
    testApp.service.album.addAssets(album.persistedId, inAlbum)
    withPerson.foreach {
      id =>
        val asset: Asset = testApp.service.asset.getById(id)
        testContext.addTestFace(person, asset, Array.fill(FaceDetectionService.EMBEDDING_DIMENSIONS)(Random.nextFloat()))
    }
    assets
      .map(
        asset =>
          asset.id -> (if (withPerson(asset.id)) SearchSource.Person
                       else if (inAlbum(asset.id)) SearchSource.Album
                       else SearchSource.Document).relevance)
      .toMap
  }

  /** The order within a group under the Relevance sort: best match, then newest capture with the undated last, then the ID */
  private def relevanceThenId(relevance: Map[String, Int]): Ordering[Dated] =
    Ordering.by[Dated, (Int, Boolean, Long, String)](
      asset =>
        (-relevance(asset.id), asset.taken.isEmpty, -asset.taken.map(_.toEpochSecond(ZoneOffset.UTC)).getOrElse(0L), asset.id))

  test("Cursor traversal under the Relevance sort matches the complete order by day, in both directions") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). The text "img" matches every file name, an album holds
     * every third asset and a person is on every fourth, so an asset's Relevance is its best source: 5 for the person, 2 for the
     * album, 1 for the file name alone.
     *
     * Assertions:
     *
     * In both day directions, walking 1, 2 and 5 assets a page under the Relevance sort reproduces the order computed in Scala:
     * the day, the best Relevance, the newest capture with the undated last, then the ID.
     *
     * Edge cases:
     *
     * The fixture ties on Relevance and on capture time, and ranks an older capture above a newer one.
     */
    val assets = fixture()
    val relevance = rankByImg(assets)
    // The fixture has to tie on Relevance and on capture time, and to rank an older capture above a newer one
    relevance.values.toSet shouldBe Set(5, 2, 1)

    for (direction <- SortDirection.values.toList; rpp <- List(1, 2, 5)) {
      val grouping = SearchGrouping(GroupBy.DateTaken, direction)
      val dayOrdering = Ordering.by[Dated, Option[String]](_.taken.map(_.toLocalDate.toString))(nativeOrder(direction))
      withClue(s"$grouping, $rpp a page: ") {
        traverse(grouping, SearchSort.Relevance, rpp, img) shouldEqual
          assets.sorted(dayOrdering.orElse(relevanceThenId(relevance))).map(_.id)
      }
    }
  }

  test("Cursor traversal under the Relevance sort matches the complete order by Location") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). The text "img" matches every file name, an album holds
     * every third asset and a person is on every fourth, so an asset's Relevance is its best source: 5 for the person, 2 for the
     * album, 1 for the file name alone. The assets are spread over Rome and Berlin with overlapping memberships, both Locations
     * and the trailing group mixing dated and undated assets of every Relevance.
     *
     * Assertions:
     *
     * Walking 1, 2 and 5 assets a page reproduces Berlin, Rome and then the assets in none, each block by Relevance, the newest
     * capture with the undated last, then the ID.
     */
    val assets = fixture()
    val relevance = rankByImg(assets)
    val rome = testApp.service.location.addLocation("Rome", 41.9, 12.5, None)
    val berlin = testApp.service.location.addLocation("Berlin", 52.5, 13.4, None)
    // Both Locations and the trailing group mix dated and undated assets of every Relevance
    val inRome = assets.slice(0, 6) ++ assets.slice(12, 13)
    val inBerlin = assets.slice(3, 9) ++ assets.slice(13, 15)
    val inNone = assets.slice(9, 12) ++ assets.slice(15, 16)
    testApp.service.location.addAssets(rome.persistedId, inRome.map(_.id).toSet)
    testApp.service.location.addAssets(berlin.persistedId, inBerlin.map(_.id).toSet)
    val expected = List(inBerlin, inRome, inNone).flatMap(_.sorted(relevanceThenId(relevance)).map(_.id))

    for (rpp <- List(1, 2, 5)) withClue(s"$rpp a page: ") {
      traverse(SearchGrouping(GroupBy.Location), SearchSort.Relevance, rpp, img) shouldEqual expected
    }
  }

  test("A Relevance cursor carries the capture time beside the Relevance, and a column sort's cursor does not") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). The text "img" matches every file name, an album holds
     * every third asset and a person is on every fourth, so an asset's Relevance is its best source: 5 for the person, 2 for the
     * album, 1 for the file name alone. Grouped by Location with no Location defined, first pages of 1 and 15 are read under the
     * Relevance sort and of 1 under a file name sort.
     *
     * Assertions:
     *
     * A Relevance cursor names its asset with its Relevance and its capture time, survives encoding and continues the walk, while
     * a column sort's cursor carries no capture time.
     *
     * Edge cases:
     *
     * The cursor of an undated asset says so with an explicit null rather than omitting the capture time, and continues to the
     * last asset. A Relevance cursor without its capture time, or used under another sort, is refused.
     */
    val assets = fixture()
    val relevance = rankByImg(assets)
    val grouping = SearchGrouping(GroupBy.Location)
    val expected = assets.sorted(relevanceThenId(relevance))

    val cursor = firstPage(grouping, SearchSort.Relevance, 1, img).nextCursor.get
    cursor.id shouldBe expected.head.id
    cursor.sortValue shouldBe SortValue.Num(5)
    cursor.secondSortValue.isDefined shouldBe true
    SearchCursor.decode(cursor.encode) shouldBe cursor

    // The last asset has no capture time: its cursor says so, rather than saying nothing
    val last = firstPage(grouping, SearchSort.Relevance, 15, img).nextCursor.get
    last.id shouldBe expected(14).id
    last.secondSortValue shouldBe Some(SortValue.Null)
    SearchCursor.decode(last.encode) shouldBe last
    ids(continue(last, grouping, SearchSort.Relevance, 5, img)) shouldEqual List(expected(15).id)

    // Without its capture time a Relevance cursor is no position
    intercept[SearchCursorException](continue(cursor.copy(secondSortValue = None), grouping, SearchSort.Relevance, 1, img))
    // It belongs to its sort like any other cursor
    val byFilename = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    intercept[SearchCursorException](continue(cursor, grouping, byFilename, 1, img))

    val columnCursor = firstPage(grouping, byFilename, 1, img).nextCursor.get
    columnCursor.secondSortValue shouldBe None
    SearchCursor.decode(columnCursor.encode) shouldBe columnCursor
  }

  test("Text of exclusions alone is traversed under the Relevance sort by the tiebreakers") {

    /**
     * Setup:
     *
     * The 16-asset fixture (12 assets over four capture days, 4 undated). It is searched with `-nothing`, an exclusion that
     * matches no asset, three assets a page.
     *
     * Assertions:
     *
     * Every asset scores no Relevance, so grouped by Location and by Date Taken the walk follows the tiebreakers alone: the
     * newest capture with the undated last, then the ID.
     */
    val assets = fixture()
    val nothing = assets.map(_.id -> 0).toMap
    val excluded = Some("-nothing")

    traverse(SearchGrouping(GroupBy.Location), SearchSort.Relevance, rpp = 3, excluded) shouldEqual
      assets.sorted(relevanceThenId(nothing)).map(_.id)
    val byDay = SearchGrouping(GroupBy.DateTaken)
    val dayOrdering = Ordering.by[Dated, Option[String]](_.taken.map(_.toLocalDate.toString))(nativeOrder(byDay.direction))
    traverse(byDay, SearchSort.Relevance, rpp = 3, excluded) shouldEqual
      assets.sorted(dayOrdering.orElse(relevanceThenId(nothing))).map(_.id)
  }
}
