package altitude.core.integration

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.search.SqliteSearchDialect
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Location
import altitude.core.models.MapBounds
import altitude.core.models.MapCell
import altitude.core.models.MapCells
import altitude.core.models.MapLocation
import altitude.core.service.SearchService
import altitude.core.util.BoundingBox
import altitude.core.util.SearchQuery

/**
 * The map's data: viewport cells over the plotted points of a search (an asset at its own point, or at the pin of each Location
 * it is in when it has none), the Locations in the viewport with matching assets, and the bounds of every plotted point.
 */
@DoNotDiscover class SearchMapTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val paris = (48.8566, 2.3522)
  private val world = BoundingBox.parse("-90,-180,90,180")

  private def addLocation(name: String, pin: (Double, Double) = paris, categoryId: Option[String] = None): Location =
    testApp.service.location.addLocation(name, pin._1, pin._2, categoryId)

  private def persistAt(latitude: Double, longitude: Double, folder: Option[Folder] = None): Asset = {
    val asset = testContext.persistAsset(folder = folder)
    testContext.setAssetCoordinates(asset.persistedId, latitude, longitude)
    asset
  }

  private def setTaken(asset: Asset, taken: Option[String]): Unit =
    testContext.setAssetDates(
      asset.persistedId,
      taken.map(LocalDateTime.parse),
      OffsetDateTime.of(LocalDateTime.parse("2026-09-06T12:00:00"), ZoneOffset.UTC))

  private def searchQuery(
      params: Map[String, Any] = Map(FieldConst.Asset.IS_RECYCLED -> false),
      folderIds: Set[String] = Set(),
      locationIds: Set[String] = Set()): SearchQuery =
    new SearchQuery(params = params, folderIds = folderIds, locationIds = locationIds)

  private def cellsOf(bbox: BoundingBox = world, zoom: Int = 12, query: SearchQuery = searchQuery()): MapCells =
    testApp.service.library.mapCells(query, bbox, zoom)

  /** Cells as (count, representative asset), in a stable order for comparison */
  private def summary(cells: List[MapCell]): List[(Int, String)] = cells.map(cell => (cell.count, cell.assetId)).sorted

  private def locationSummary(locations: List[MapLocation]): List[(String, Option[String], Int)] =
    locations.map(location => (location.name, location.categoryName, location.count)).sorted

  private def bounds(query: SearchQuery = searchQuery()): Option[MapBounds] = testApp.service.library.mapBounds(query)

  private def searchDialect: SearchDialect =
    if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) PostgresSearchDialect else SqliteSearchDialect

  /** The engine's plan for `statement` as text: the lines of Postgres' EXPLAIN, or the details of SQLite's EXPLAIN QUERY PLAN */
  private def planOf(statement: SqlStr): String = {
    val engine = searchDialect
    import engine.dialect.*

    testApp.txManager.asReadOnly {
      if (testApp.dataSourceType == Const.DbEngineName.POSTGRES)
        Db.read(engine.dialect)(_.runSql[String](sql"EXPLAIN $statement")).mkString("\n")
      else
        Db.read(engine.dialect)(_.runSql[(Int, Int, Int, String)](sql"EXPLAIN QUERY PLAN $statement")).map(_._4).mkString("\n")
    }
  }

  test("Cells aggregate the plotted points: an asset at its own point, or at its Locations' pins without one") {
    val italy = testApp.service.location.addCategory("Italy")
    val rome = addLocation("Rome", (41.9028, 12.4964), Some(italy.persistedId))
    val sydney = addLocation("Sydney", (-33.8688, 151.2093))
    addLocation("Empty", (35.6762, 139.6503))

    val ownPoint = persistAt(paris._1, paris._2)
    val samePoint = persistAt(paris._1, paris._2)
    val pinnedOnce = testContext.persistAsset()
    val pinnedTwice = testContext.persistAsset()
    val pointAndPin = persistAt(51.5007, -0.1276)
    val nowhere = testContext.persistAsset()
    testApp.service.location
      .addAssets(rome.persistedId, Set(pinnedOnce.persistedId, pinnedTwice.persistedId, pointAndPin.persistedId))
    testApp.service.location.addAssets(sydney.persistedId, Set(pinnedTwice.persistedId, nowhere.persistedId))
    testApp.service.location.removeAssets(sydney.persistedId, Set(nowhere.persistedId))

    val result = cellsOf()

    // Two assets at one point are one cell; an asset without a point is plotted at each of its Locations' pins; an asset with
    // a point of its own is plotted there alone, never at a pin
    val cells = result.cells
    cells.map(_.count).sum shouldBe 6
    cells.length shouldBe 4
    def cellAt(latitude: Double, longitude: Double): MapCell =
      cells.find(cell => math.abs(cell.latitude - latitude) < 1e-6 && math.abs(cell.longitude - longitude) < 1e-6).value

    val parisCell = cellAt(paris._1, paris._2)
    parisCell.count shouldBe 2
    Set(ownPoint.persistedId, samePoint.persistedId) should contain(parisCell.assetId)
    val romeCell = cellAt(41.9028, 12.4964)
    romeCell.count shouldBe 2
    Set(pinnedOnce.persistedId, pinnedTwice.persistedId) should contain(romeCell.assetId)
    val sydneyCell = cellAt(-33.8688, 151.2093)
    sydneyCell.count shouldBe 1
    sydneyCell.assetId shouldBe pinnedTwice.persistedId
    val londonCell = cellAt(51.5007, -0.1276)
    londonCell.count shouldBe 1
    londonCell.assetId shouldBe pointAndPin.persistedId

    // A Location is listed with its matching assets, and not at all when it has none; the category names it
    locationSummary(result.locations) shouldEqual List(("Rome", Some("Italy"), 3), ("Sydney", None, 1))
  }

  test("The representative asset of a cell is its newest capture, then the lowest ID; a cell of one carries its asset") {
    val undated = persistAt(paris._1, paris._2)
    val older = persistAt(paris._1, paris._2)
    val newest = persistAt(paris._1, paris._2)
    setTaken(undated, None)
    setTaken(older, Some("2026-09-01T10:00:00"))
    setTaken(newest, Some("2026-09-05T10:00:00"))

    summary(cellsOf().cells) shouldEqual List((3, newest.persistedId))

    testApp.service.library.recycleAssets(Set(newest.persistedId))
    summary(cellsOf().cells) shouldEqual List((2, older.persistedId))

    // Without a capture time to prefer, the lowest ID is a stable choice across pans
    testApp.service.library.recycleAssets(Set(older.persistedId))
    val alsoUndated = persistAt(paris._1, paris._2)
    setTaken(alsoUndated, None)
    summary(cellsOf().cells) shouldEqual List((2, List(undated, alsoUndated).map(_.persistedId).min))
  }

  test("Cells merge by the zoom's cell size, and the zoom is clamped to 0..20") {
    val west = persistAt(48.5, 2.2)
    val east = persistAt(48.5, 3.2)

    // A zoom-0 cell is 90 degrees wide; at zoom 10 it is about 0.09 degrees, so a degree apart is two cells
    cellsOf(zoom = 0).cells.map(_.count) shouldEqual List(2)
    summary(cellsOf(zoom = 10).cells) shouldEqual List((1, east.persistedId), (1, west.persistedId)).sorted
    cellsOf(zoom = -5).cells.map(_.count) shouldEqual cellsOf(zoom = 0).cells.map(_.count)
    summary(cellsOf(zoom = 25).cells) shouldEqual summary(cellsOf(zoom = 20).cells)

    // The centroid of a merged cell is the mean of its points
    val merged = cellsOf(zoom = 0).cells.head
    merged.latitude shouldBe 48.5 +- 1e-6
    merged.longitude shouldBe 2.7 +- 1e-6
  }

  test("Cells and Locations are clipped to the viewport, across the antimeridian too") {
    val sydney = addLocation("Sydney", (-33.8688, 151.2093))
    val inParis = persistAt(paris._1, paris._2)
    val pinned = testContext.persistAsset()
    testApp.service.location.addAssets(sydney.persistedId, Set(pinned.persistedId))
    val east = persistAt(0.5, 179.5)
    val west = persistAt(-0.5, -179.5)

    summary(cellsOf(BoundingBox.parse("48,2,49,3")).cells) shouldEqual List((1, inParis.persistedId))
    cellsOf(BoundingBox.parse("48,2,49,3")).locations shouldBe Nil

    val sydneyView = cellsOf(BoundingBox.parse("-34,151,-33,152"))
    summary(sydneyView.cells) shouldEqual List((1, pinned.persistedId))
    locationSummary(sydneyView.locations) shouldEqual List(("Sydney", None, 1))

    summary(cellsOf(BoundingBox.parse("-1,179,1,-179"), zoom = 10).cells) shouldEqual
      List((1, east.persistedId), (1, west.persistedId)).sorted
    cellsOf(BoundingBox.parse("-1,-179,1,179")).cells shouldBe Nil
  }

  test("A cells query reads the geotagged assets through the asset_geo partial index") {
    val inParis = persistAt(paris._1, paris._2)
    persistAt(48.8606, 2.3376)
    testContext.persistAsset()

    val cells = SearchQueries.mapCells(
      searchDialect,
      searchQuery(),
      RequestContext.getRepository.persistedId,
      BoundingBox.parse("48,2,49,3"),
      SearchService.cellDegrees(12))

    // Postgres costs a plan by the table's statistics, and over a handful of rows every index on the repository costs the same,
    // so its choice would be a tie. At a library's scale - thousands of assets, most without a point, analyzed - it is not.
    // SQLite has no statistics before ANALYZE and prefers the index that constrains the most columns.
    val plan =
      if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) {
        // The library is only scaled up for the plan: the schema is shared by every suite that follows, so the copies and their
        // column statistics are rolled back once the plan is read in the same transaction. ANALYZE writes the table's row count
        // estimate in place, which a rollback keeps, so the table is analyzed again over the rows that are left.
        try
          testApp.txManager.withTransaction {
            update(
              """INSERT INTO asset
                |SELECT (jsonb_populate_record(a, jsonb_build_object(
                |  'id', lpad(g::text, 36, '0'), 'checksum', 1000000 + g,
                |  'latitude', CASE WHEN g % 10 = 0 THEN -80 + g % 160 END,
                |  'longitude', CASE WHEN g % 10 = 0 THEN -180 + g % 360 END))).*
                |  FROM asset a, generate_series(1, 5000) g
                | WHERE a.id = ?""".stripMargin,
              inParis.persistedId
            )
            update("ANALYZE asset")
            try planOf(cells)
            finally RequestContext.getConn.rollback()
          }
        finally testApp.txManager.withTransaction(update("ANALYZE asset"))
      } else planOf(cells)

    withClue(plan) {
      plan should include("asset_geo")
    }
  }

  test("Bounds cover both point sources, count plotted points, and are absent when nothing is plotted") {
    bounds() shouldBe None

    val nowhere = testContext.persistAsset()
    bounds() shouldBe None

    val sydney = addLocation("Sydney", (-33.8688, 151.2093))
    persistAt(paris._1, paris._2)
    persistAt(51.5007, -0.1276)
    val pinned = testContext.persistAsset()
    testApp.service.location.addAssets(sydney.persistedId, Set(pinned.persistedId, nowhere.persistedId))
    testApp.service.location.removeAssets(sydney.persistedId, Set(nowhere.persistedId))

    val box = bounds().value
    box.south shouldBe -33.8688 +- 1e-6
    box.north shouldBe 51.5007 +- 1e-6
    box.west shouldBe -0.1276 +- 1e-6
    box.east shouldBe 151.2093 +- 1e-6
    box.count shouldBe 3

    // The bounds follow the search: scoped to the Location, only the pinned asset is plotted
    val scoped = bounds(searchQuery(locationIds = Set(sydney.persistedId))).value
    scoped.count shouldBe 1
    scoped.south shouldBe -33.8688 +- 1e-6
    scoped.north shouldBe -33.8688 +- 1e-6
  }

  test("The map reads the same matching set as the grid: view flags, folder scope, and the repository") {
    val folder: Folder = testApp.service.folder.add("Trips")
    val inFolder = persistAt(paris._1, paris._2, folder = Some(folder))
    val elsewhere = persistAt(41.9028, 12.4964)
    val recycled = persistAt(35.6762, 139.6503)
    testApp.service.library.recycleAssets(Set(recycled.persistedId))
    val rome = addLocation("Rome", (41.9028, 12.4964))
    testApp.service.location.addAssets(rome.persistedId, Set(elsewhere.persistedId, inFolder.persistedId))

    // Another repository's geotagged asset is invisible here
    val otherUser = testContext.persistUser()
    val otherRepo = testContext.persistRepository(user = Some(otherUser))
    val foreign = testContext.persistAsset(repository = Some(otherRepo), user = Some(otherUser))
    testContext.setAssetCoordinates(foreign.persistedId, paris._1, paris._2)
    switchContextRepo(testContext.repositories.head)
    switchContextUser(testContext.users.head)

    summary(cellsOf().cells) shouldEqual List((1, elsewhere.persistedId), (1, inFolder.persistedId)).sorted
    bounds().value.count shouldBe 2

    // The trash bin plots the recycled asset alone
    val trash = searchQuery(params = Map(FieldConst.Asset.IS_RECYCLED -> true))
    summary(cellsOf(query = trash).cells) shouldEqual List((1, recycled.persistedId))
    cellsOf(query = trash).locations shouldBe Nil

    // A folder scope narrows the cells, the Location counts and the bounds alike
    val scoped = searchQuery(folderIds = Set(folder.persistedId))
    summary(cellsOf(query = scoped).cells) shouldEqual List((1, inFolder.persistedId))
    locationSummary(cellsOf(query = scoped).locations) shouldEqual List(("Rome", None, 1))
    bounds(scoped).value.count shouldBe 1

    // The root folder is the whole repository
    val root = searchQuery(folderIds = Set(testContext.repositories.head.rootFolderId))
    bounds(root).value.count shouldBe 2
  }
}
