package altitude.core.integration

import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ contain, should, shouldBe, shouldEqual, theSameElementsAs }

import altitude.core.{ Altitude, Const, FieldConst, SearchCursorException }
import altitude.core.util.*

/** Cursor continuation of a grouped search: every supported ordering, live changes around the anchor, and scope checks */
@DoNotDiscover class SearchCursorTests(override val testApp: Altitude) extends IntegrationTestCore {

  /** What a fixture asset was given, so the expected order can be computed independently of SQL */
  private case class Dated(id: String, taken: LocalDateTime, imported: OffsetDateTime, filename: String)

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
    testContext.setAssetDates(asset.persistedId, takenAt, importedAt)
    Dated(asset.persistedId, takenAt, importedAt, filename)
  }

  /** Four days, three images each, with ties on every sort key so the ID tiebreaker is exercised */
  private def fixture(): List[Dated] = List(
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
  )

  private def expectedOrder(assets: List[Dated], grouping: SearchGrouping, sort: SearchSort): List[String] = {
    def day(a: Dated): LocalDate = grouping.by match {
      case GroupBy.DateTaken => a.taken.toLocalDate
      case GroupBy.DateImported => a.imported.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate
    }
    // Every asset in the fixture has the same size and area, so those sorts fall through to the ID
    def sortKey(a: Dated): String = sort.field match {
      case FieldConst.Asset.ORIGINAL_CREATED_AT => a.taken.toString
      case FieldConst.CREATED_AT => a.imported.withOffsetSameInstant(ZoneOffset.UTC).toString
      case FieldConst.Asset.FILENAME => a.filename
      case _ => ""
    }
    val byDay: Ordering[Dated] = Ordering.by[Dated, String](a => day(a).toString)
    val bySort: Ordering[Dated] = Ordering.by[Dated, String](sortKey)
    val byId: Ordering[Dated] = Ordering.by[Dated, String](_.id)
    val dayOrdering = if (grouping.direction == SortDirection.DESC) byDay.reverse else byDay
    val sortOrdering = if (sort.direction == SortDirection.DESC) bySort.reverse else bySort
    assets.sorted(dayOrdering.orElse(sortOrdering).orElse(byId)).map(_.id)
  }

  private def firstPage(grouping: SearchGrouping, sort: SearchSort, rpp: Int, text: Option[String] = None): IdSearchResult =
    testApp.service.library.searchIds(
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
      text: Option[String] = None): IdSearchResult =
    testApp.service.library.searchIds(
      new SearchQuery(
        text = text,
        params = Map(FieldConst.Asset.IS_RECYCLED -> false),
        rpp = rpp,
        page = cursor.nextPage,
        searchSort = List(sort),
        grouping = Some(grouping),
        cursor = Some(cursor)
      ))

  /** Walks every page through encoded cursors, as a client would, and returns the IDs in order */
  private def traverse(grouping: SearchGrouping, sort: SearchSort, rpp: Int): List[String] = {
    var page = firstPage(grouping, sort, rpp)
    var ids = page.ids
    var pages = 1
    while (page.nextCursor.isDefined) {
      val cursor = SearchCursor.decode(page.nextCursor.get.encode)
      cursor shouldEqual page.nextCursor.get
      page = continue(cursor, grouping, sort, rpp)
      page.page shouldBe pages + 1
      ids = ids ++ page.ids
      pages += 1
    }
    ids
  }

  test("Cursor traversal matches the complete order for every grouping, sort field and direction") {
    val assets = fixture()

    for {
      by <- GroupBy.values.toList
      groupDirection <- SortDirection.values.toList
      field <- sortFields
      sortDirection <- SortDirection.values.toList
    } {
      val grouping = SearchGrouping(by, groupDirection)
      val sort = SearchSort(field, sortDirection)
      val expected = expectedOrder(assets, grouping, sort)
      withClue(s"$grouping $sort: ") {
        traverse(grouping, sort, rpp = 5) shouldEqual expected
      }
    }

    // A page size of one puts every image at an anchor position
    traverse(SearchGrouping(GroupBy.DateTaken), SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC), rpp = 1) shouldEqual
      expectedOrder(assets, SearchGrouping(GroupBy.DateTaken), SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC))
  }

  test("The cursor points at the last returned image and ends with the results") {
    val assets = fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val expected = expectedOrder(assets, grouping, sort)

    val page1 = firstPage(grouping, sort, rpp = 5)
    page1.ids shouldEqual expected.take(5)
    page1.nextCursor.get.id shouldBe expected(4)
    page1.nextCursor.get.nextPage shouldBe 2
    page1.nextCursor.get.rpp shouldBe 5

    val page3 = continue(continue(page1.nextCursor.get, grouping, sort, 5).nextCursor.get, grouping, sort, 5)
    page3.ids shouldEqual expected.drop(10)
    page3.nextCursor shouldBe None
    page3.total shouldBe 12
    page3.totalPages shouldBe 3

    // Exactly a full last page still needs one more request to learn that it was the last
    val exact = firstPage(grouping, sort, rpp = 12)
    exact.ids shouldEqual expected
    exact.nextCursor shouldBe None
  }

  test("Deleting the anchor or inserting before it does not skip or repeat images") {
    val assets = fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val expected = expectedOrder(assets, grouping, sort)

    val page1 = firstPage(grouping, sort, rpp = 5)
    val cursor = page1.nextCursor.get

    // The anchor leaves the result set
    testApp.service.library.recycleAssets(Set(cursor.id))
    val afterDelete = continue(cursor, grouping, sort, 5)
    afterDelete.ids shouldEqual expected.slice(5, 10)
    afterDelete.total shouldBe 11

    // An image sorting before the anchor appears; the remaining pages are unchanged and the totals are live
    persistDated("2026-09-06T09:00:00", "img00.jpg")
    val afterInsert = continue(cursor, grouping, sort, 5)
    afterInsert.ids shouldEqual expected.slice(5, 10)
    afterInsert.total shouldBe 12
    afterInsert.nextCursor.isDefined shouldBe true
  }

  test("A cursor is rejected for a different scope, ordering or page size, and when malformed") {
    fixture()
    val grouping = SearchGrouping(GroupBy.DateTaken)
    val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
    val cursor = firstPage(grouping, sort, rpp = 5).nextCursor.get

    intercept[SearchCursorException](continue(cursor, grouping, sort, rpp = 5, text = Some("beach")))
    intercept[SearchCursorException](
      continue(cursor, grouping, SearchSort(FieldConst.Asset.FILENAME, SortDirection.DESC), rpp = 5))
    intercept[SearchCursorException](continue(cursor, grouping, SearchSort(FieldConst.CREATED_AT, SortDirection.ASC), rpp = 5))
    intercept[SearchCursorException](continue(cursor, SearchGrouping(GroupBy.DateTaken, SortDirection.ASC), sort, rpp = 5))
    intercept[SearchCursorException](continue(cursor, SearchGrouping(GroupBy.DateImported), sort, rpp = 5))
    intercept[SearchCursorException](continue(cursor, grouping, sort, rpp = 6))

    val folder = testApp.service.folder.add("scoped")
    intercept[SearchCursorException] {
      testApp.service.library.searchIds(
        new SearchQuery(
          params = Map(FieldConst.Asset.IS_RECYCLED -> false),
          folderIds = Set(folder.persistedId),
          rpp = 5,
          page = cursor.nextPage,
          searchSort = List(sort),
          grouping = Some(grouping),
          cursor = Some(cursor)
        ))
    }

    intercept[SearchCursorException](SearchCursor.decode("not a cursor"))
    intercept[SearchCursorException](SearchCursor.decode("e30")) // {}
    intercept[SearchCursorException](SearchCursor.decode(""))

    // The same search continues
    continue(cursor, grouping, sort, rpp = 5).ids.length shouldBe 5
  }

  test("A cursor continues correctly through legacy null import times") {
    if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
      val assets = fixture()
      val undated = assets(2)
      testApp.txManager.withTransaction {
        update("UPDATE asset SET created_at = NULL WHERE id = ?", undated.id)
      }
      val grouping = SearchGrouping(GroupBy.DateTaken)

      // Grouped by capture day, the image keeps its day; sorted by import time it sits where SQLite puts nulls
      List(SortDirection.ASC, SortDirection.DESC).foreach {
        direction =>
          val sort = SearchSort(FieldConst.CREATED_AT, direction)
          val dayOfUndated = assets.filter(_.taken.toLocalDate == undated.taken.toLocalDate)
          val others = expectedOrder(dayOfUndated.filterNot(_.id == undated.id), grouping, sort)
          val expectedDay = if (direction == SortDirection.ASC) undated.id :: others else others :+ undated.id
          val rest = expectedOrder(assets.filterNot(a => a.taken.toLocalDate == undated.taken.toLocalDate), grouping, sort)

          withClue(s"$sort: ") {
            traverse(grouping, sort, rpp = 1) shouldEqual expectedDay ++ rest
            traverse(grouping, sort, rpp = 2) shouldEqual expectedDay ++ rest
          }
      }

      // Grouped by import day it has no day and is left out, consistently across pages
      val byImport = SearchGrouping(GroupBy.DateImported)
      val sort = SearchSort(FieldConst.Asset.FILENAME, SortDirection.ASC)
      val walked = traverse(byImport, sort, rpp = 4)
      walked should contain theSameElementsAs assets.filterNot(_.id == undated.id).map(_.id)
      firstPage(byImport, sort, rpp = 4).total shouldBe 11
    }
  }
}
