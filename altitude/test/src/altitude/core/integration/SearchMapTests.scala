package altitude.core.integration

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.search.SearchQueries
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
@DoNotDiscover class SearchMapTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {

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
      locationIds: Set[String] = Set(),
      text: Option[String] = None): SearchQuery =
    new SearchQuery(text = text, params = params, folderIds = folderIds, locationIds = locationIds)

  private def cellsOf(bbox: BoundingBox = world, zoom: Int = 12, query: SearchQuery = searchQuery()): MapCells =
    testApp.service.library.mapCells(query, bbox, zoom)

  /** Cells as (count, representative asset), in a stable order for comparison */
  private def summary(cells: List[MapCell]): List[(Int, String)] = cells.map(cell => (cell.count, cell.assetId)).sorted

  private def locationSummary(locations: List[MapLocation]): List[(String, Option[String], Int)] =
    locations.map(location => (location.name, location.categoryName, location.count)).sorted

  private def bounds(query: SearchQuery = searchQuery()): Option[MapBounds] = testApp.service.library.mapBounds(query)

  test("Cells aggregate the plotted points: an asset at its own point, or at its Locations' pins without one") {

    /**
     * Setup:
     *
     * Rome under the Italy category, Sydney and an empty Location; two assets at the same Paris point, an asset without a point
     * in Rome, one without a point in Rome and Sydney, an asset with its own point in London that is also in Rome, and an asset
     * with neither, added to Sydney and removed again.
     *
     * Assertions:
     *
     * The world view gathers the plotted points into one cell per position, with counts that add up to the plotted points and
     * each cell represented by one of its own assets. The Locations in view list their matching members' count and their
     * category's name.
     *
     * Edge cases:
     *
     * Two assets on one point share a cell, an asset in two Locations is plotted at both pins, an asset with its own point is
     * plotted there and never at its Location's pin (though it still counts in the Location), and neither an empty Location nor
     * an asset whose membership was removed shows up.
     */
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

    /**
     * Setup:
     *
     * Three assets at one Paris point, one undated, one taken on 2026-09-01 and one on 2026-09-05; the dated ones are recycled in
     * turn and a second undated asset is added. Last, a lone asset at a Tokyo point.
     *
     * Assertions:
     *
     * A cell is represented by its newest capture and, when none of its assets has a capture time, by the lowest asset ID. A cell
     * of one is represented by its only asset.
     *
     * Edge cases:
     *
     * Undated assets rank after dated ones, and among themselves by ID, so the choice is stable across pans.
     */
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
    val lowestUndated = List(undated, alsoUndated).map(_.persistedId).min
    summary(cellsOf().cells) shouldEqual List((2, lowestUndated))

    val alone = persistAt(35.6762, 139.6503)
    summary(cellsOf().cells) shouldEqual List((1, alone.persistedId), (2, lowestUndated))
  }

  test("Cells merge by the zoom's cell size, and the zoom is clamped to 0..20") {

    /**
     * Setup:
     *
     * Two assets a degree of longitude apart at latitude 48.5.
     *
     * Assertions:
     *
     * Zoom 0 merges them into one cell placed at their centroid, zoom 10 keeps them apart, and zooms outside 0..20 behave as the
     * nearest bound.
     *
     * Edge cases:
     *
     * The out-of-range zooms -5 and 25.
     */
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

    /**
     * Setup:
     *
     * A Sydney Location with one member that has no point, an asset in Paris, and assets on either side of the antimeridian.
     *
     * Assertions:
     *
     * Each viewport returns only the cells and the Locations inside it.
     *
     * Edge cases:
     *
     * A viewport across the antimeridian gathers both sides, while the same edges the other way round return nothing.
     */
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

  /** A library for the planner: 5,000 copies of the asset, one in ten of them geotagged somewhere on the globe */
  private def seedGeotagged(template: Asset): Unit = {
    seedCopies(template.persistedId, copies = 5000, folders = 500)
    update("""UPDATE asset SET latitude = -80 + (checksum % 160), longitude = -180 + (checksum % 360)
             | WHERE checksum >= 1000000 AND checksum % 10 = 0""".stripMargin)
  }

  test("The viewport statements read the geotagged assets through the asset_geo partial index alone") {

    /**
     * Setup:
     *
     * Two assets in Paris and one without a point, the last seeded into 5,000 copies of which one in ten is geotagged, analyzed
     * and rolled back once the plans are read. The cells statement over a Paris viewport, and the capped count of the grid scoped
     * to the same box, which is what the crowded-pin panel shows.
     *
     * Assertions:
     *
     * The cells statement reads its assets' own points from `asset_geo` and nothing else of the asset: the index carries the ID
     * and the capture time a cell is ranked by, so the read is index-only on PostgreSQL and covering on SQLite. The grid scoped
     * to the box bounds its assets by `asset_geo` as well.
     */
    persistAt(paris._1, paris._2)
    persistAt(48.8606, 2.3376)
    val template = testContext.persistAsset()
    val box = BoundingBox.parse("48,2,49,3")
    val repositoryId = RequestContext.getRepository.persistedId

    val cells = SearchQueries.mapCells(searchDialect, searchQuery(), repositoryId, box, SearchService.cellDegrees(12))
    val inBox = new SearchQuery(params = Map(FieldConst.Asset.IS_RECYCLED -> false), bbox = Some(box))
    val grid = SearchQueries.cappedCount(searchDialect, inBox, repositoryId)

    val (cellsPlan, gridPlan) = atScale(seedGeotagged(template)) {
      (indexOnlyPlanOf(cells, "asset"), planOf(grid))
    }

    withClue(cellsPlan) {
      if (isPostgres) cellsPlan should include("Index Only Scan using asset_geo")
      else cellsPlan should include("USING COVERING INDEX asset_geo")
    }
    withClue(gridPlan)(gridPlan should include("asset_geo"))
  }

  test("The Locations of a viewport are counted from their own members, not from every match") {

    /**
     * Setup:
     *
     * A Location pinned in Paris holding one asset, in a library of 5,000 other assets, analyzed and rolled back once the plan is
     * read, and the statement that counts the matching assets of the Locations in a Paris viewport.
     *
     * Assertions:
     *
     * The plan finds the matching assets by their ID, from the memberships of the Locations in the box, and never passes over an
     * index of the repository's assets.
     */
    val location = addLocation("Paris")
    val member = testContext.persistAsset()
    testApp.service.location.addAssets(location.persistedId, Set(member.persistedId))
    val template = testContext.persistAsset()

    val locations =
      SearchQueries.mapLocations(
        searchDialect,
        searchQuery(),
        RequestContext.getRepository.persistedId,
        BoundingBox.parse("48,2,49,3"))

    val plan = atScale(seedCopies(template.persistedId, copies = 5000, folders = 500))(planOf(locations))

    withClue(plan) {
      plan should include(if (isPostgres) "asset_pkey" else "sqlite_autoindex_asset_1")
      (plan should not).include("asset_search_")
      (plan should not).include(if (isPostgres) "Seq Scan on asset" else "SCAN asset")
    }
  }

  test("Bounds cover both point sources, count plotted points, and are absent when nothing is plotted") {

    /**
     * Setup:
     *
     * First an empty library, then an asset with neither a point nor a Location; then a Sydney Location with a member that has no
     * point, assets with their own points in Paris and London, and the first asset added to Sydney and removed again.
     *
     * Assertions:
     *
     * Bounds are absent while nothing is plotted; then they span both own points and Location pins with the count of plotted
     * points, and follow the search when it is scoped to a Location.
     */
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

    /**
     * Setup:
     *
     * A Trips folder with an asset in Paris, an asset in Rome outside it, a recycled asset in Tokyo, a Rome Location holding both
     * live assets, and another repository's asset in Paris.
     *
     * Assertions:
     *
     * Cells, Location counts and bounds read the same matching set as the grid: the trash view plots only the recycled asset, a
     * folder scope narrows all three, the root folder is the whole repository, and another repository's assets never appear.
     */
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

  test("The map follows the Search text: cells, Location counts and bounds are those of the assets the names find") {

    /**
     * Setup:
     *
     * A Trips folder with an asset in Paris that is also in Rome, an unrelated asset in Tokyo, and an asset without a point in
     * Rome.
     *
     * Assertions:
     *
     * Searching a folder's or a Location's name plots only the assets the name finds, at their own points or their Locations'
     * pins, with Location counts and bounds to match; an excluded term narrows the bounds, and text that finds nothing has none.
     */
    val trips: Folder = testApp.service.folder.add("Trips")
    val inFolder = persistAt(paris._1, paris._2, folder = Some(trips))
    persistAt(35.6762, 139.6503)
    val rome = addLocation("Rome", (41.9028, 12.4964))
    val pinned = testContext.persistAsset()
    testApp.service.location.addAssets(rome.persistedId, Set(pinned.persistedId, inFolder.persistedId))

    bounds().value.count shouldBe 3

    val byFolder = searchQuery(text = Some("trips"))
    summary(cellsOf(query = byFolder).cells) shouldEqual List((1, inFolder.persistedId))
    locationSummary(cellsOf(query = byFolder).locations) shouldEqual List(("Rome", None, 1))
    bounds(byFolder).value shouldBe MapBounds(south = paris._1, west = paris._2, north = paris._1, east = paris._2, count = 1)

    val byLocation = searchQuery(text = Some("rome"))
    summary(cellsOf(query = byLocation).cells) shouldEqual List((1, inFolder.persistedId), (1, pinned.persistedId)).sorted
    locationSummary(cellsOf(query = byLocation).locations) shouldEqual List(("Rome", None, 2))
    bounds(byLocation).value.count shouldBe 2
    bounds(byLocation).value.south shouldBe 41.9028 +- 1e-6

    bounds(searchQuery(text = Some("rome -trips"))).value.count shouldBe 1
    bounds(searchQuery(text = Some("nowhere"))) shouldBe None
  }
}
