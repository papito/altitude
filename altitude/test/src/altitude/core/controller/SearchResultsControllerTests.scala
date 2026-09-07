package altitude.core.controller

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe, shouldEqual }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Asset

/** The grouped HTML grid of the search results route, its continuation by cursor, and the untouched ungrouped behavior next to it */
@DoNotDiscover class SearchResultsControllerTests extends ControllerTestCore {

  private val jsonHeaders = Map("Accept" -> "application/json")

  private def persistDated(taken: String, filename: String): Asset = {
    val asset = testContext.persistAsset()
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    testContext.setAssetDates(asset.persistedId, takenAt, OffsetDateTime.of(takenAt, ZoneOffset.UTC))
    asset
  }

  private def search(host: String, repoId: String, params: Map[String, String], headers: Map[String, String] = jsonHeaders) =
    requests.get(s"$host/htmx/search/r/$repoId", params = params, headers = headers, cookies = testContext.cookies, check = false)

  // Identity encoding because the client cannot read a compressed empty body (the 204)
  private def htmlSearch(host: String, repoId: String, params: Map[String, String]) =
    search(host, repoId, params, headers = Map("Accept-Encoding" -> "identity"))

  private def cell(asset: Asset): String = s"""id="asset-${asset.persistedId}""""

  private def header(day: String): String = s"""data-group-date="$day""""

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
        page1 should include("""<span class="count">3</span>""")
        page1 should include("""data-results-total="4"""")
        page1.contains(header("2026-09-05")) shouldBe false
        ordered(page1, header("2026-09-06"), cell(a1))
        ordered(page1, cell(a1), cell(a2))
        page1.contains(cell(a3)) shouldBe false

        // The Group dropdown reflects the grouping
        page1 should include("""data-app-search-group-by="dateTaken" data-app-search-group-direction="desc" selected""")

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

  test("Grouped HTML honors the view and the import day") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val kept = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val recycled = persistDated("2026-09-06T11:00:00", "a2.jpg")
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        val trash =
          htmlSearch(host, repoId, Map(Api.Field.Search.GROUP_BY -> "dateImported", Api.Field.Search.VIEW -> "trashbin"))
        trash.statusCode shouldBe 200
        val trashPage = trash.text()
        ordered(trashPage, header("2026-09-06"), cell(recycled))
        trashPage.contains(cell(kept)) shouldBe false
        trashPage should include("""data-app-search-group-by="dateImported" data-app-search-group-direction="desc" selected""")

        val library = htmlSearch(host, repoId, Map(Api.Field.Search.GROUP_BY -> "dateImported")).text()
        ordered(library, header("2026-09-06"), cell(kept))
        library.contains(cell(recycled)) shouldBe false
    }
  }

  test("Invalid grouped requests are 400 errors in the format of the request") {
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
        rejected(Map(Api.Field.Search.GROUP_BY -> "")) should include("groupBy")
        rejected(Map(Api.Field.Search.GROUP_DIRECTION -> "asc")) should include("groupBy")
        rejected(grouped + (Api.Field.Search.GROUP_DIRECTION -> "up")) should include("groupDirection")
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
          Map(
            Api.Field.Search.GROUP_BY -> "dateImported",
            Api.Field.Search.AFTER -> cursor,
            Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")) should include("cursor")
        val otherPageSize = htmlSearch(
          host,
          repoId,
          grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "5") + (Api.Field.Search.AFTER -> cursor) +
            (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"))
        otherPageSize.statusCode shouldBe 200

        // Grouped results are the HTML grid only
        val json = search(host, repoId, grouped)
        json.statusCode shouldBe 400
        json.headers("content-type").head should include("application/json")
        ujson.read(json.text())("error").str should include("HTML")
    }
  }

  test("Grouped requests require authentication and a repository the user can see") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

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
    }
  }

  test("Ungrouped HTML and JSON results are unchanged") {
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
        page.contains("""class="date-group"""") shouldBe false // the style block names it; no header is rendered

        val json = search(host, repoId, Map(Api.Field.Search.PAGE -> "1"))
        json.statusCode shouldBe 200
        val payload = ujson.read(json.text())
        payload("ids").arr.map(_.str).toList shouldEqual List(asset.persistedId)
        payload("page").num shouldBe 1
        payload("totalPages").num shouldBe 1
        payload.obj.contains("groups") shouldBe false

        // Past the last page; identity encoding because the client cannot read a compressed empty body
        val scroll = search(
          host,
          repoId,
          Map(Api.Field.Search.PAGE -> "2", Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true"),
          headers = Map("Accept-Encoding" -> "identity"))
        scroll.statusCode shouldBe 204
    }
  }
}
