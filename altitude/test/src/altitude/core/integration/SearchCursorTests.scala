package altitude.core.integration

import java.time.{ LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, should, shouldBe, shouldEqual }

import altitude.core.{ Altitude, Const, FieldConst, SearchCursorException }
import altitude.core.util.*

/** Cursor continuation of a grouped search: every supported ordering, live changes around the anchor, and scope checks */
@DoNotDiscover class SearchCursorTests(override val testApp: Altitude) extends IntegrationTestCore {

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

  private def expectedOrder(assets: List[Dated], grouping: SearchGrouping, sort: SearchSort): List[String] = {
    def day(a: Dated): Option[String] = a.taken.map(_.toLocalDate.toString)
    // Equal size and area leave those sorts to the ID. Nulls are ranked independently at each ordering level.
    def sortKey(a: Dated): Option[String] = sort.field match {
      case FieldConst.Asset.ORIGINAL_CREATED_AT => a.taken.map(_.toString)
      case FieldConst.CREATED_AT => Some(a.imported.withOffsetSameInstant(ZoneOffset.UTC).toString)
      case FieldConst.Asset.FILENAME => Some(a.filename)
      case _ => Some("")
    }
    def nativeOrder(direction: SortDirection): Ordering[Option[String]] = new Ordering[Option[String]] {
      override def compare(a: Option[String], b: Option[String]): Int = (a, b) match {
        case (None, None) => 0
        case (None, _) => if (nullsFirst(direction)) -1 else 1
        case (_, None) => if (nullsFirst(direction)) 1 else -1
        case (Some(x), Some(y)) => if (direction == SortDirection.ASC) x.compareTo(y) else y.compareTo(x)
      }
    }
    val dayOrdering = Ordering.by[Dated, Option[String]](day)(nativeOrder(grouping.direction))
    val sortOrdering = Ordering.by[Dated, Option[String]](sortKey)(nativeOrder(sort.direction))
    val byId = Ordering.by[Dated, String](_.id)
    assets.sorted(dayOrdering.orElse(sortOrdering).orElse(byId)).map(_.id)
  }

  private def firstPage(grouping: SearchGrouping, sort: SearchSort, rpp: Int, text: Option[String] = None): GroupedSearchResult =
    testApp.service.library.searchGrouped(
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
    testApp.service.library.searchGrouped(
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
  private def traverse(grouping: SearchGrouping, sort: SearchSort, rpp: Int): List[String] = {
    var page = firstPage(grouping, sort, rpp)
    var walked = ids(page)
    while (page.nextCursor.isDefined) {
      val cursor = SearchCursor.decode(page.nextCursor.get.encode)
      cursor shouldEqual page.nextCursor.get
      page = continue(cursor, grouping, sort, rpp)
      page.continuesGroup shouldBe page.groups.headOption.exists(_.date == cursor.day)
      page.assets.nonEmpty shouldBe true
      walked.size should be < 100
      walked = walked ++ ids(page)
    }
    walked
  }

  test("Cursor traversal matches the complete order for every grouping, sort field and direction") {
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

  test("The cursor points at the last returned image and ends with the results") {
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
    intercept[SearchCursorException] {
      testApp.service.library.searchGrouped(
        new SearchQuery(
          params = Map(FieldConst.Asset.IS_RECYCLED -> false),
          folderIds = Set(folder.persistedId),
          rpp = 5,
          searchSort = List(sort),
          grouping = Some(grouping),
          cursor = Some(cursor)
        ))
    }

    val nullCursor = cursor.copy(day = None, sortValue = SortValue.Null)
    SearchCursor.decode(nullCursor.encode) shouldBe nullCursor
    val oldJson =
      ujson.read(new String(java.util.Base64.getUrlDecoder.decode(cursor.encode), java.nio.charset.StandardCharsets.UTF_8))
    oldJson("v") = 2
    val oldToken = java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString(ujson.write(oldJson).getBytes(java.nio.charset.StandardCharsets.UTF_8))
    intercept[SearchCursorException](SearchCursor.decode(oldToken)).getMessage shouldBe "Unsupported cursor version"

    intercept[SearchCursorException](SearchCursor.decode("not a cursor"))
    intercept[SearchCursorException](SearchCursor.decode("e30")) // {}
    intercept[SearchCursorException](SearchCursor.decode(""))

    // The same search continues
    ids(continue(cursor, grouping, sort, rpp = 5)).length shouldBe 5
  }

  test("A cursor continues correctly through a legacy null import time") {
    if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
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

          withClue(s"$sort: ") {
            traverse(grouping, sort, rpp = 1) shouldEqual expectedDay ++ rest
            traverse(grouping, sort, rpp = 2) shouldEqual expectedDay ++ rest
          }
      }
    }
  }
}
