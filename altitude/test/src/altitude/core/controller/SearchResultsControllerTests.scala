package altitude.core.controller

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.AssetType

/** The grouped HTML grid of the search results route, its continuation by cursor, and the ungrouped grid next to it */
@DoNotDiscover class SearchResultsControllerTests extends ControllerTestCore {

  private val jsonHeaders = Map("Accept" -> "application/json")

  private def persistDated(taken: String, filename: String): Asset = {
    val asset = testContext.persistAsset()
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    testContext.setAssetDates(asset.persistedId, Some(takenAt), OffsetDateTime.of(takenAt, ZoneOffset.UTC))
    asset
  }

  private def search(host: String, repoId: String, params: Map[String, String], headers: Map[String, String] = jsonHeaders) =
    requests.get(s"$host/htmx/search/r/$repoId", params = params, headers = headers, cookies = testContext.cookies, check = false)

  // Identity encoding because the client cannot read a compressed empty body (the 204)
  private def htmlSearch(host: String, repoId: String, params: Map[String, String]) =
    search(host, repoId, params, headers = Map("Accept-Encoding" -> "identity"))

  private def cell(asset: Asset): String = s"""id="asset-${asset.persistedId}""""

  private def persistUndated(filename: String): Asset = {
    val asset = persistDated("2026-09-06T10:00:00", filename)
    testContext.setAssetDates(asset.persistedId, None, OffsetDateTime.parse("2026-09-06T12:00:00Z"))
    asset
  }

  test("A cell names its media type, and a Video's cell wears a play badge and shows its duration") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an imported image and a 65-second MP4 Video added directly as a completed asset.
     *
     * Assertions:
     *
     * Each cell of the HTML grid names its media type, and only the Video's cell wears the play badge and shows its duration as
     * 1:05.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val image = testContext.persistAsset()
        val clip = testApp.service.asset.add(
          testContext
            .makeAsset(filename = "clip.mp4")
            .copy(assetType = AssetType("video", "mp4", "video/mp4"), durationMs = Some(65_000L)))
        testApp.service.asset.markAsCompleted(clip)

        val page = htmlSearch(host, repoId, Map("sort" -> "filename0")).text()
        // Each cell's markup runs from its id to the next cell's
        val cells = page.split("""(?=id="asset-)""").toList
        val imageCell = cells.find(_.startsWith(cell(image))).get
        val clipCell = cells.find(_.startsWith(cell(clip))).get

        imageCell should include("""data-media-type="image"""")
        (imageCell should not).include("play-badge")
        (imageCell should not).include("Duration:")
        clipCell should include("""data-media-type="video"""")
        clipCell should include("play-badge")
        clipCell should include("<span>Duration:</span> 1:05")
    }
  }

  test("Location grouping renders paths and continues by cursor to No location") {

    /**
     * Setup:
     *
     * A Beach Location under the Italy category holding two undated assets, and a third asset in no Location, grouped by Location
     * one asset to a page.
     *
     * Assertions:
     *
     * The first page heads the group with its Category › Location path, reflects the grouping in the Group dropdown and the
     * fragment's data attributes, and gives an asset in a Location a cell ID qualified by it. The continuation finishes Beach
     * without repeating its header and opens No location, whose cell keeps the plain asset ID.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val category = testApp.service.location.addCategory("Italy")
        val location = testApp.service.location.addLocation("Beach", 1, 2, Some(category.persistedId))
        val first = persistUndated("a.jpg")
        val second = persistUndated("b.jpg")
        val unlocated = persistUndated("c.jpg")
        testApp.service.location.addAssets(location.persistedId, Set(first.persistedId, second.persistedId))
        val params = Map("groupBy" -> "location", "rpp" -> "1", "sort" -> "filename0")
        val page = htmlSearch(host, repoId, params)
        page.statusCode shouldBe 200
        page.text() should include("<span class=\"category\">Italy</span> › Beach")
        page.text() should include(header(location.persistedId))
        // The Group dropdown reflects the grouping, and the grid carries it for its continuations
        page.text() should include("""data-app-search-group-by="location" data-app-search-group-direction="" selected""")
        page.text() should include("""data-results-group-by="location"""")
        page.text() should include("""data-results-group-direction=""""")
        // An asset in a Location is a cell of its own under it, distinct from the same asset's cell elsewhere
        page.text() should include(s"""id="asset-${first.persistedId}-in-${location.persistedId}"""")
        val next = htmlSearch(
          host,
          repoId,
          params ++ Map("after" -> cursorOf(page.text()).head, "isContinuousScroll" -> "true", "rpp" -> "2"))
        next.statusCode shouldBe 200
        next.text().contains(header(location.persistedId)) shouldBe false
        ordered(next.text(), s"""id="asset-${second.persistedId}-in-${location.persistedId}"""", "No location")
        // The trailing group is the asset's only cell, so it keeps the plain ID
        ordered(next.text(), "No location", cell(unlocated))
    }
  }

  test("A Location page marks only its last cell for continuation, though that asset has a cell under an earlier Location") {

    /**
     * Setup:
     *
     * Beach and Hills Locations, an asset in both, one in Beach only and one in neither, read as a Location-grouped page of
     * three.
     *
     * Assertions:
     *
     * The page holds the shared asset under both Locations, and exactly one cell - the last by position, under Hills - is marked
     * as the last and carries the cursor.
     *
     * Edge cases:
     *
     * The page's last asset also has a cell earlier on the same page.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val beach = testApp.service.location.addLocation("Beach", 1, 2)
        val hills = testApp.service.location.addLocation("Hills", 3, 4)
        val both = persistUndated("a.jpg")
        val beachOnly = persistUndated("b.jpg")
        persistUndated("c.jpg")
        testApp.service.location.addAssets(beach.persistedId, Set(both.persistedId, beachOnly.persistedId))
        testApp.service.location.addAssets(hills.persistedId, Set(both.persistedId))

        // The page is Beach: a, b; Hills: a - and "No location" is still to come, so the page continues
        val page = htmlSearch(host, repoId, Map("groupBy" -> "location", "rpp" -> "3", "sort" -> "filename0")).text()
        ordered(
          page,
          s"""id="asset-${both.persistedId}-in-${beach.persistedId}"""",
          s"""id="asset-${both.persistedId}-in-${hills.persistedId}"""")
        cursorOf(page).size shouldBe 1
        "class=\"cell last-cell\"".r.findAllMatchIn(page).size shouldBe 1
        s"""(?s)id="asset-${both.persistedId}-in-${hills.persistedId}"\\s+class="cell last-cell".*?data-app-search-after=""".r
          .findFirstIn(page)
          .isDefined shouldBe true
    }
  }

  test("Location and bbox filter both grid shapes, and map layout ignores grouping and paging") {

    /**
     * Setup:
     *
     * A Beach Location pinned at 1, 2 with one undated member, and an undated asset outside it.
     *
     * Assertions:
     *
     * The Location and bounding-box filters narrow the ungrouped, Location and day grids to the member, and the fragment carries
     * the Location scope. The map layout ignores the grid-only parameters, plotting and totalling the whole search with the Group
     * control disabled and keeping layout, Location and box in the replaced URL. Malformed box and layout values are plain-text
     * 400s.
     *
     * Edge cases:
     *
     * Invalid grouping, cursor and paging parameters, which the map layout ignores rather than rejects, and a box with a latitude
     * out of range.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val location = testApp.service.location.addLocation("Beach", 1, 2)
        val member = persistUndated("a.jpg")
        val outside = persistUndated("b.jpg")
        testApp.service.location.addAssets(location.persistedId, Set(member.persistedId))
        val scope = Map("locationId" -> location.persistedId, "bbox" -> "0,0,10,10")
        for (group <- List(Map.empty[String, String], Map("groupBy" -> "location"), Map("groupBy" -> "dateTaken"))) {
          withClue(s"$group: ") {
            val grid = htmlSearch(host, repoId, scope ++ group)
            grid.statusCode shouldBe 200
            // In the Location grid the cell's ID carries the Location it is under
            grid.text() should include(
              if (group.get("groupBy").contains("location")) s"""id="asset-${member.persistedId}-in-${location.persistedId}""""
              else cell(member))
            grid.text().contains(s"asset-${outside.persistedId}") shouldBe false
            grid.text() should include(s"""data-results-location-id="${location.persistedId}"""")
          }
        }
        // In map layout the bbox is the panel's scope: the map's total and bounds cover the whole search, the URL keeps it
        val response = htmlSearch(
          host,
          repoId,
          scope ++ Map(
            "bbox" -> "50,50,60,60",
            "layout" -> "map",
            "groupBy" -> "bad",
            "groupDirection" -> "up",
            "after" -> "bad",
            "p" -> "99",
            "rpp" -> "0",
            "isContinuousScroll" -> "true")
        )
        response.statusCode shouldBe 200
        val page = response.text()
        page should include("""id="map"""")
        page should include("""data-map-bounds="1.0,2.0,1.0,2.0"""")
        page should include("""data-map-count="1"""")
        page should include("""data-results-total="1"""")
        page should include("""data-results-total-capped="false"""")
        page.contains("""id="assets"""") shouldBe false
        "(?s)<select id=\"groupOptions\".*?>".r.findFirstIn(page).get should include("disabled")
        page should include("""id="mapPanel" hidden""")
        page should include("""id="bboxScope"""")
        page should include("""data-results-layout="map"""")
        pressedLayout(page) shouldBe "map"
        val url = java.net.URLDecoder.decode(response.headers("hx-replace-url").head, "UTF-8")
        url should include("layout=map")
        url should include(s"locationId=${location.persistedId}")
        url should include("bbox=50,50,60,60")
        for (params <- List(Map("bbox" -> "bad"), Map("bbox" -> "-91,0,0,0"), Map("layout" -> "other"))) {
          withClue(s"$params: ") {
            val invalid = htmlSearch(host, repoId, params)
            invalid.statusCode shouldBe 400
            invalid.headers("content-type").head should include("text/plain")
          }
        }
    }
  }

  test("The No date header crosses a page boundary and is not repeated on continuation") {

    /**
     * Setup:
     *
     * Assets taken on 2026-09-05 and 2026-09-06 and two undated ones, grouped by capture day three to a page.
     *
     * Assertions:
     *
     * Descending, the dated days come first and the No date group trails with its full count and no date element; the
     * continuation adds the remaining undated asset without another header and ends the cursor. Ascending, No date leads.
     *
     * Edge cases:
     *
     * SQLite's null placement, which the controller tests run on: null days trail descending and lead ascending.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val older = persistDated("2026-09-05T10:00:00", "a.jpg")
        val newer = persistDated("2026-09-06T10:00:00", "b.jpg")
        val first = persistUndated("c.jpg")
        val second = persistUndated("d.jpg")
        // Controller tests use SQLite: null days trail DESC and lead ASC.
        val params = Map("groupBy" -> "dateTaken", "groupDirection" -> "desc", "rpp" -> "3", "sort" -> "filename0")
        val page = htmlSearch(host, repoId, params).text()
        ordered(page, cell(newer), cell(older))
        ordered(page, cell(older), header(""))
        ordered(page, header(""), cell(first))
        page should include("<span>No date</span>")
        page should include("""<span class="count" data-count="2">(2 items)</span>""")
        page should include("""class="result-group"""")
        page.contains("""<time datetime="">""") shouldBe false
        val next = htmlSearch(host, repoId, params ++ Map("after" -> cursorOf(page).head, "isContinuousScroll" -> "true")).text()
        next should include(cell(second))
        next.contains("""class="result-group"""") shouldBe false
        cursorOf(next) shouldBe Nil
        val ascending = htmlSearch(host, repoId, params + ("groupDirection" -> "asc")).text()
        ordered(ascending, header(""), cell(first))
        ordered(ascending, cell(second), cell(older))
    }
  }

  test("The no date badge follows the effective capture sort on grouped and ungrouped pages") {

    /**
     * Setup:
     *
     * One dated and one undated asset, searched ungrouped and grouped by capture day, under the capture time, import time and
     * filename sorts.
     *
     * Assertions:
     *
     * The no date badge and the cell's no-date marker appear on the undated asset's cell only, and only when the results are
     * sorted by capture time.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val dated = persistDated("2026-09-06T10:00:00", "a.jpg")
        val undated = persistUndated("b.jpg")
        for {
          grouping <- List(Map.empty[String, String], Map("groupBy" -> "dateTaken"))
          sort <- List("original_created_at1", "created_at1", "filename0")
        } {
          val page = htmlSearch(host, repoId, grouping + ("sort" -> sort)).text()
          // Match rendered elements; class names also occur inside the response's style block.
          val badge = """<div class="no-date-marker">no date</div>"""
          page.contains(badge) shouldBe (sort == "original_created_at1")
          page.contains("""alt-has-no-date="true"""") shouldBe (sort == "original_created_at1")
          val datedCell = page.substring(page.indexOf(cell(dated))).takeWhile(_ != '<')
          datedCell.contains("alt-has-no-date") shouldBe false
          if (sort == "original_created_at1") {
            val undatedCell = page.substring(page.indexOf(cell(undated)))
            undatedCell.takeWhile(_ != '<') should include("""alt-has-no-date="true"""")
            undatedCell should include(badge)
          }
        }
    }
  }

  private def header(day: String): String = s"""data-group-key="$day""""

  /** The layout whose toggle button is pressed */
  private def pressedLayout(html: String): String =
    "(?s)aria-pressed=\"true\"\\s+data-app-search=\"click\" data-app-search-layout=\"([a-z]+)\"".r
      .findFirstMatchIn(html)
      .get
      .group(1)

  /** The cursor the page's last cell carries, if any */
  private def cursorOf(html: String): List[String] =
    "data-app-search-after=\"([^\"]+)\"".r.findAllMatchIn(html).map(_.group(1)).toList

  /** `first` comes before `second` in `html`, both present */
  private def ordered(html: String, first: String, second: String): Unit = {
    html should include(first)
    html should include(second)
    (html.indexOf(first) < html.indexOf(second)) shouldBe true
  }

  test("Grouped HTML pages open each day with a header and carry the cursor on the last cell") {

    /**
     * Setup:
     *
     * Three assets taken on 2026-09-06 and one on 2026-09-05, grouped by capture day two to a page in filename order.
     *
     * Assertions:
     *
     * The first page is an HTML fragment whose replaced URL carries the grouping, with a header giving the whole day's count, the
     * overall total, cells in sort order and the grouping reflected in the Group dropdown; only its last cell carries the cursor,
     * and no cell a page number. The continuation completes the day without repeating its header, opens the next one and, as the
     * last page, carries no cursor.
     *
     * Edge cases:
     *
     * Continuing from the first cursor after the remaining assets were recycled is a 204 with nothing to append.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
        val a3 = persistDated("2026-09-06T08:00:00", "a3.jpg")
        val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")

        val params = Map(
          Api.Field.Search.GROUP_BY -> "dateTaken",
          Api.Field.Search.GROUP_DIRECTION -> "desc",
          Api.Field.Search.RESULTS_PER_PAGE -> "2",
          Api.Field.Search.SORT -> "filename0"
        )

        val response = htmlSearch(host, repoId, params)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("text/html")
        response.headers("hx-replace-url").head should include("sort=filename0&groupBy=dateTaken&groupDirection=desc")
        val page1 = response.text()

        // The one day on the page: its header with the whole day's count, then its cells in sort order; the footer total
        page1 should include("""<time datetime="2026-09-06">Sunday, September 6, 2026</time>""")
        page1 should include("""<span class="count" data-count="3">(3 items)</span>""")
        page1 should include("""data-results-total="4"""")
        page1 should include("""data-results-total-capped="false"""")
        page1.contains(header("2026-09-05")) shouldBe false
        ordered(page1, header("2026-09-06"), cell(a1))
        ordered(page1, cell(a1), cell(a2))
        page1.contains(cell(a3)) shouldBe false

        // The Group dropdown reflects the grouping
        page1 should include("""data-app-search-group-by="dateTaken" data-app-search-group-direction="desc" selected""")
        page1 should include("""data-results-group-by="dateTaken"""")
        page1 should include("""data-results-group-direction="desc"""")

        // The last cell, and only it, carries the cursor; no cell carries a page number
        page1.contains("data-app-search-next-page") shouldBe false
        val cursors = cursorOf(page1)
        cursors.length shouldBe 1
        ordered(page1, cell(a2), "data-app-search-after")

        // The continuation completes the day without repeating its header, then opens the next day; it is the last page
        val continued = htmlSearch(
          host,
          repoId,
          params + (Api.Field.Search.AFTER -> cursors.head) + (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        continued.statusCode shouldBe 200
        val page2 = continued.text()
        page2.contains("searchControl") shouldBe false
        page2.contains(header("2026-09-06")) shouldBe false
        ordered(page2, cell(a3), header("2026-09-05"))
        page2 should include("""<time datetime="2026-09-05">Saturday, September 5, 2026</time>""")
        ordered(page2, header("2026-09-05"), cell(b1))
        page2.contains("data-app-search-after") shouldBe false
        page2.contains("data-app-search-next-page") shouldBe false

        // Continuing past the end, after the remaining images left the results, has nothing to append
        testApp.service.library.recycleAssets(Set(a3.persistedId, b1.persistedId))
        val past = htmlSearch(
          host,
          repoId,
          params + (Api.Field.Search.AFTER -> cursors.head) + (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        past.statusCode shouldBe 204
    }
  }

  test("Grouped HTML honors the view") {

    /**
     * Setup:
     *
     * Two assets taken on the same day, one of them recycled.
     *
     * Assertions:
     *
     * The date-grouped trash view shows only the recycled asset under its day, and the library view only the kept one.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val kept = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val recycled = persistDated("2026-09-06T11:00:00", "a2.jpg")
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        val trash =
          htmlSearch(host, repoId, Map(Api.Field.Search.GROUP_BY -> "dateTaken", Api.Field.Search.VIEW -> "trashbin"))
        trash.statusCode shouldBe 200
        val trashPage = trash.text()
        ordered(trashPage, header("2026-09-06"), cell(recycled))
        trashPage.contains(cell(kept)) shouldBe false
        trashPage should include("""data-app-search-group-by="dateTaken" data-app-search-group-direction="desc" selected""")

        val library = htmlSearch(host, repoId, Map(Api.Field.Search.GROUP_BY -> "dateTaken")).text()
        ordered(library, header("2026-09-06"), cell(kept))
        library.contains(cell(recycled)) shouldBe false
    }
  }

  test("Invalid grouped requests are plain-text 400 errors") {

    /**
     * Setup:
     *
     * Two dated assets and a real cursor taken from a one-asset grouped page.
     *
     * Assertions:
     *
     * Every malformed or contradictory grouping, direction, cursor, page size, page number or sort is a plain-text 400 naming the
     * parameter at fault. A cursor is refused for a different search but accepted with another page size.
     *
     * Edge cases:
     *
     * An empty groupBy, the removed dateImported grouping, a direction with the Location grouping, a cursor that decodes to an
     * empty JSON object, the page sizes 0 and 501, and a valid cursor sent without isContinuousScroll.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        persistDated("2026-09-06T10:00:00", "a1.jpg")
        persistDated("2026-09-06T11:00:00", "a2.jpg")
        val grouped = Map(Api.Field.Search.GROUP_BY -> "dateTaken")
        val cursor = cursorOf(htmlSearch(host, repoId, grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "1")).text()).head

        def rejected(params: Map[String, String]): String = {
          val response = htmlSearch(host, repoId, params)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
          response.text()
        }

        rejected(Map(Api.Field.Search.GROUP_BY -> "date")) should include("groupBy")
        rejected(Map(Api.Field.Search.GROUP_BY -> "dateImported")) should include("groupBy")
        rejected(Map(Api.Field.Search.GROUP_BY -> "")) should include("groupBy")
        rejected(Map(Api.Field.Search.GROUP_DIRECTION -> "asc")) should include("groupBy")
        rejected(grouped + (Api.Field.Search.GROUP_DIRECTION -> "up")) should include("groupDirection")
        // A Location grouping has a fixed order
        rejected(Map(Api.Field.Search.GROUP_BY -> "location", Api.Field.Search.GROUP_DIRECTION -> "asc")) should
          include("groupDirection")
        rejected(Map(Api.Field.Search.AFTER -> "abc")) should include("groupBy")
        rejected(grouped + (Api.Field.Search.AFTER -> "abc")) should include("cursor")
        rejected(grouped + (Api.Field.Search.AFTER -> "e30")) should include("cursor")
        rejected(grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "0")) should include("rpp")
        rejected(grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "501")) should include("rpp")
        rejected(grouped + (Api.Field.Search.PAGE -> "2")) should include("p is not used")
        rejected(grouped + (Api.Field.Search.SORT -> "checksum0")) should include("sort")
        rejected(grouped + (Api.Field.Search.SORT -> "filename")) should include("sort")
        rejected(grouped + (Api.Field.Search.AFTER -> cursor)) should include("isContinuousScroll")

        // A cursor belongs to one search; the page size is not part of it
        rejected(
          grouped + (Api.Field.Search.GROUP_DIRECTION -> "asc") + (Api.Field.Search.AFTER -> cursor) +
            (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")) should include("cursor")
        val otherPageSize = htmlSearch(
          host,
          repoId,
          grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "5") + (Api.Field.Search.AFTER -> cursor) +
            (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        otherPageSize.statusCode shouldBe 200

    }
  }

  test("An ungrouped page is bounded: rpp and p out of range are plain-text 400 errors") {

    /**
     * Setup:
     *
     * Two dated assets.
     *
     * Assertions:
     *
     * Page sizes outside 1..500 and page numbers outside 1..42949672 (the last page whose rows an Int offset can number at the
     * default 50) are plain-text 400s stating the bounds, on first pages and continuations alike; pages within the bounds are
     * served, and a page size of 1 or 500 returns one asset or both.
     *
     * Edge cases:
     *
     * The page sizes 0, -1 and 501; the pages 0, -1, one past the bound and Int.MaxValue; and Int.MaxValue accepted at one asset
     * a page.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val older = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val newer = persistDated("2026-09-07T10:00:00", "a2.jpg")
        val continuation = Map(Api.Field.Search.PAGE -> "2", Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")

        def rejected(params: Map[String, String]): String = {
          val response = htmlSearch(host, repoId, params)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
          response.text()
        }

        // No page size reads every match, so it is refused like one past the bound
        for (rpp <- List("0", "-1", "501")) withClue(s"rpp=$rpp: ") {
          rejected(Map(Api.Field.Search.RESULTS_PER_PAGE -> rpp)) shouldBe "rpp must be between 1 and 500"
          rejected(continuation + (Api.Field.Search.RESULTS_PER_PAGE -> rpp)) shouldBe "rpp must be between 1 and 500"
        }
        // The last page is the last one whose every row an Int can number: 42949672 at the default 50
        for (page <- List("0", "-1", "42949673", Int.MaxValue.toString)) withClue(s"p=$page: ") {
          rejected(Map(Api.Field.Search.PAGE -> page)) shouldBe "p must be between 1 and 42949672"
          rejected(Map(Api.Field.Search.PAGE -> page, Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")) shouldBe
            "p must be between 1 and 42949672"
        }
        htmlSearch(host, repoId, Map(Api.Field.Search.PAGE -> "42949672")).statusCode shouldBe 200
        // One asset a page reaches the largest page an Int numbers
        val last = Map(Api.Field.Search.RESULTS_PER_PAGE -> "1", Api.Field.Search.PAGE -> Int.MaxValue.toString)
        htmlSearch(host, repoId, last).statusCode shouldBe 200

        val one = htmlSearch(host, repoId, Map(Api.Field.Search.RESULTS_PER_PAGE -> "1")).text()
        List(older, newer).count(asset => one.contains(cell(asset))) shouldBe 1
        val largest = htmlSearch(host, repoId, Map(Api.Field.Search.RESULTS_PER_PAGE -> "500"))
        largest.statusCode shouldBe 200
        List(older, newer).foreach(asset => largest.text() should include(cell(asset)))
    }
  }

  test("Text is sorted by Relevance unless the request says otherwise, and Relevance needs text") {

    /**
     * Setup:
     *
     * beach.jpg taken on 2026-09-06 and sand.jpg taken on 2026-09-05 in the "Beach days" album, searched for "beach".
     *
     * Assertions:
     *
     * Search text defaults to the Relevance sort on every grid shape and the map layout, ranking the album match above the
     * file-name match, while a sort the request names wins and no usable text defaults to the newest import. A Relevance page
     * continues by cursor, and the Relevance sort without usable text, or with a direction appended, is a plain-text 400.
     *
     * Edge cases:
     *
     * Text without a usable term ("- OR") is no text; the day grouping puts the two assets on different days, so their order is
     * not compared there.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val album = testApp.service.album.add("Beach days")
        val byName = persistDated("2026-09-06T10:00:00", "beach.jpg")
        val inAlbum = persistDated("2026-09-05T10:00:00", "sand.jpg")
        testApp.service.album.addAssets(album.persistedId, Set(inAlbum.persistedId))
        val text = Map(Api.Field.Search.QUERY_TEXT -> "beach")
        def effectiveSort(params: Map[String, String]): String = {
          val response = htmlSearch(host, repoId, params)
          response.statusCode shouldBe 200
          "sort=([^&#]+)".r.findFirstMatchIn(response.headers("hx-replace-url").head).get.group(1)
        }

        // The album outranks the file name, on a flat page and within a day or a Location
        for (group <- List(Map.empty[String, String], Map("groupBy" -> "location"), Map("groupBy" -> "dateTaken")))
          withClue(s"$group: ") {
            effectiveSort(text ++ group) shouldBe "relevance"
            if (!group.get("groupBy").contains("dateTaken"))
              ordered(htmlSearch(host, repoId, text ++ group).text(), cell(inAlbum), cell(byName))
          }
        effectiveSort(text + (Api.Field.Search.LAYOUT -> "map")) shouldBe "relevance"
        // A sort the request names wins
        effectiveSort(text + (Api.Field.Search.SORT -> "filename0")) shouldBe "filename0"
        ordered(htmlSearch(host, repoId, text + (Api.Field.Search.SORT -> "filename0")).text(), cell(byName), cell(inAlbum))
        // No text, or text without a usable term, reads newest import first
        effectiveSort(Map()) shouldBe "created_at1"
        effectiveSort(Map(Api.Field.Search.QUERY_TEXT -> "- OR")) shouldBe "created_at1"

        // A Relevance page is continued by its cursor
        val grouped = text ++ Map("groupBy" -> "location", Api.Field.Search.RESULTS_PER_PAGE -> "1")
        val first = htmlSearch(host, repoId, grouped).text()
        first should include(cell(inAlbum))
        first.contains(cell(byName)) shouldBe false
        val next = htmlSearch(
          host,
          repoId,
          grouped ++ Map(Api.Field.Search.AFTER -> cursorOf(first).head, Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        next.statusCode shouldBe 200
        next.text() should include(cell(byName))

        def rejected(params: Map[String, String]): String = {
          val response = htmlSearch(host, repoId, params)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
          response.text()
        }

        val byRelevance = Map(Api.Field.Search.SORT -> "relevance")
        rejected(byRelevance) should include("Search text")
        rejected(byRelevance + (Api.Field.Search.QUERY_TEXT -> "")) should include("Search text")
        rejected(byRelevance + (Api.Field.Search.QUERY_TEXT -> "- OR")) should include("Search text")
        rejected(byRelevance + ("groupBy" -> "dateTaken")) should include("Search text")
        rejected(byRelevance + (Api.Field.Search.LAYOUT -> "map")) should include("Search text")
        // Relevance is the whole value: it has no direction to append
        rejected(text + (Api.Field.Search.SORT -> "relevance0")) should include("sort")
    }
  }

  test("Results carry their Search text and offer the Relevance sort only with it") {

    /**
     * Setup:
     *
     * One asset named beach.jpg, searched ungrouped, grouped by day and in map layout.
     *
     * Assertions:
     *
     * With Search text the fragment carries it in data-results-q and the Relevance option is selected, or offered unselected when
     * the request names another sort; without usable text data-results-q is empty and Relevance is not offered.
     *
     * Edge cases:
     *
     * Text without a usable term ("- OR").
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        persistDated("2026-09-06T10:00:00", "beach.jpg")
        val relevanceOption = """<option value="relevance""""
        val text = Map(Api.Field.Search.QUERY_TEXT -> "beach")

        for (shape <- List(Map.empty[String, String], Map("groupBy" -> "dateTaken"), Map(Api.Field.Search.LAYOUT -> "map")))
          withClue(s"$shape: ") {
            val withText = htmlSearch(host, repoId, text ++ shape).text()
            withText should include("""data-results-q="beach"""")
            withText should include(s"$relevanceOption selected")

            // A sort the request names leaves Relevance on offer, not selected
            val byName = htmlSearch(host, repoId, text ++ shape + (Api.Field.Search.SORT -> "filename0")).text()
            byName should include(relevanceOption)
            byName.contains(s"$relevanceOption selected") shouldBe false

            // No text, or text without a usable term, is no text: the server would refuse the Relevance sort
            for (noText <- List(Map.empty[String, String], Map(Api.Field.Search.QUERY_TEXT -> "- OR"))) {
              val page = htmlSearch(host, repoId, noText ++ shape).text()
              page should include("""data-results-q=""""")
              page.contains(relevanceOption) shouldBe false
            }
          }
    }
  }

  test("Search text with the trash view is a plain-text 400") {

    /**
     * Setup:
     *
     * An empty repository, searched in the trash view ungrouped, grouped by day and in map layout.
     *
     * Assertions:
     *
     * Search text in the trash is a plain-text 400 naming the trash, while the trash alone, the trash with text that has no
     * usable term, and text in another view are served.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trash = Map(Api.Field.Search.VIEW -> "trashbin")
        val text = trash + (Api.Field.Search.QUERY_TEXT -> "beach")
        for (shape <- List(Map.empty[String, String], Map("groupBy" -> "dateTaken"), Map(Api.Field.Search.LAYOUT -> "map")))
          withClue(s"$shape: ") {
            val response = htmlSearch(host, repoId, text ++ shape)
            response.statusCode shouldBe 400
            response.headers("content-type").head should include("text/plain")
            response.text() should include("trash")
          }

        // The trash itself still opens, and text without a usable term is no text
        htmlSearch(host, repoId, trash).statusCode shouldBe 200
        htmlSearch(host, repoId, trash + (Api.Field.Search.QUERY_TEXT -> "- OR")).statusCode shouldBe 200
        // Every other view takes text
        htmlSearch(
          host,
          repoId,
          Map(Api.Field.Search.VIEW -> "triage", Api.Field.Search.QUERY_TEXT -> "beach")).statusCode shouldBe 200
    }
  }

  test("Grouped requests require authentication and a repository the user can see") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second user's repository holding one asset.
     *
     * Assertions:
     *
     * An unauthenticated grouped request is redirected when it asks for HTML and refused with a 401 when it asks for JSON. A
     * logged-in user asking for the other user's repository is a 404 that shows none of its assets, as for any foreign entity,
     * and a repository that does not exist is the same 404.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    val stranger = testContext.persistUser()
    val strangersRepo = testContext.persistRepository(user = Some(stranger))
    testApp.service.repository.switchContextToRepository(strangersRepo)
    testApp.service.user.switchContextToUser(stranger)
    val foreign = testContext.persistAsset(repository = Some(strangersRepo), user = Some(stranger))

    withServer(App) {
      host =>
        // An HTML request is sent to the login page, a JSON one is told so
        val html = requests.get(
          s"$host/htmx/search/r/$repoId",
          params = Map(Api.Field.Search.GROUP_BY -> "dateTaken"),
          maxRedirects = 0,
          check = false)
        html.statusCode shouldBe 302

        val json = requests.get(
          s"$host/htmx/search/r/$repoId",
          params = Map(Api.Field.Search.GROUP_BY -> "dateTaken"),
          headers = jsonHeaders,
          check = false)
        json.statusCode shouldBe 401

        // A logged-in user cannot read a repository that is not theirs
        val foreignPage = htmlSearch(host, strangersRepo.persistedId, Map(Api.Field.Search.GROUP_BY -> "dateTaken"))
        foreignPage.statusCode shouldBe 404
        foreignPage.text().contains(cell(foreign)) shouldBe false

        // A repository that does not exist is answered the same way, so the two cannot be told apart
        htmlSearch(host, BaseDao.genId, Map(Api.Field.Search.GROUP_BY -> "dateTaken")).statusCode shouldBe 404
    }
  }

  test(
    "An ungrouped page carries the next page number until the last page; the first page carries the total and whether it is capped") {

    /**
     * Setup:
     *
     * Three assets taken at the same moment, two to a page in filename order.
     *
     * Assertions:
     *
     * The first page carries the total, whether it is capped, and the next page number on its last cell; the second page holds
     * the remaining asset with neither a next page nor a total.
     *
     * Edge cases:
     *
     * A continuation past the last page is a 204 with nothing to append.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val List(a1, a2, a3) = List("a1.jpg", "a2.jpg", "a3.jpg").map(persistDated("2026-09-06T10:00:00", _)): @unchecked
        val params = Map(Api.Field.Search.RESULTS_PER_PAGE -> "2", Api.Field.Search.SORT -> "filename0")

        val page1 = htmlSearch(host, repoId, params).text()
        page1 should include("""data-results-total="3"""")
        page1 should include("""data-results-total-capped="false"""")
        ordered(page1, cell(a1), cell(a2))
        ordered(page1, cell(a2), """data-app-search-next-page="2"""")
        page1.contains(cell(a3)) shouldBe false

        val scroll = params + (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")
        val page2 = htmlSearch(host, repoId, scroll + (Api.Field.Search.PAGE -> "2"))
        page2.statusCode shouldBe 200
        page2.text() should include(cell(a3))
        page2.text().contains("data-app-search-next-page") shouldBe false
        page2.text().contains("data-results-total") shouldBe false

        // A continuation that finds no rows has nothing to append
        htmlSearch(host, repoId, scroll + (Api.Field.Search.PAGE -> "3")).statusCode shouldBe 204
    }
  }

  test("Ungrouped results are an HTML grid, and a JSON request is a JSON 400") {

    /**
     * Setup:
     *
     * One dated asset.
     *
     * Assertions:
     *
     * Without an Accept header the ungrouped search is an HTML grid in grid layout, with no grouping, no group headers and no
     * bounding-box scope, and a continuation past the last page is a 204. Asking for JSON, by the Accept header or by the legacy
     * Content-Type, is a 400 with a JSON error pointing to HTML.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val asset = persistDated("2026-09-06T10:00:00", "a1.jpg")

        val html = search(host, repoId, Map(), headers = Map())
        html.statusCode shouldBe 200
        html.headers("content-type").head should include("text/html")
        val page = html.text()
        page should include(cell(asset))
        page should include("""data-app-search-group-by="" data-app-search-group-direction="" selected""")
        page should include("""data-results-layout="grid"""")
        page should include("""data-results-group-by=""""")
        pressedLayout(page) shouldBe "grid"
        page.contains("""id="bboxScope"""") shouldBe false
        page.contains("""class="result-group"""") shouldBe false // the style block names it; no header is rendered

        // Past the last page
        val scroll = htmlSearch(host, repoId, Map(Api.Field.Search.PAGE -> "2", Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        scroll.statusCode shouldBe 204

        // Nothing asks for results as JSON any more: the detail modal walks the grid
        for (params <- Seq(Map(Api.Field.Search.PAGE -> "1"), Map(Api.Field.Search.GROUP_BY -> "dateTaken"))) {
          val json = search(host, repoId, params)
          json.statusCode shouldBe 400
          json.headers("content-type").head should include("application/json")
          ujson.read(json.text())("error").str should include("HTML")
        }

        val legacy = search(host, repoId, Map(), headers = Map("Content-Type" -> "application/json"))
        legacy.statusCode shouldBe 400
        legacy.headers("content-type").head should include("application/json")
        ujson.read(legacy.text())("error").str should include("HTML")
    }
  }
}
