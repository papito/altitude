package altitude.core.controller

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ empty, include, should, shouldBe, shouldEqual }

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Asset

/** The grouped JSON contract of the search results route, and the untouched ungrouped HTML/JSON behavior next to it */
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

  private def group(json: ujson.Value): (String, Int, Int, Int) =
    (json("date").str, json("startIndex").num.toInt, json("length").num.toInt, json("total").num.toInt)

  test("Grouped JSON pages carry IDs, date groups with full-day totals, and a cursor that continues to the end") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val a1 = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val a2 = persistDated("2026-09-06T09:00:00", "a2.jpg")
        val a3 = persistDated("2026-09-06T08:00:00", "a3.jpg")
        val b1 = persistDated("2026-09-05T08:00:00", "b1.jpg")
        val b2 = persistDated("2026-09-05T07:00:00", "b2.jpg")

        val params = Map(
          Api.Field.Search.GROUP_BY -> "dateTaken",
          Api.Field.Search.RESULTS_PER_PAGE -> "2",
          Api.Field.Search.SORT -> "filename0")

        val response1 = search(host, repoId, params)
        response1.statusCode shouldBe 200
        response1.headers("content-type").head should include("application/json")
        val page1 = ujson.read(response1.text())
        page1("ids").arr.map(_.str).toList shouldEqual List(a1.persistedId, a2.persistedId)
        page1("page").num shouldBe 1
        page1("total").num shouldBe 5
        page1("totalPages").num shouldBe 3
        page1("groupBy").str shouldBe "dateTaken"
        page1("groupDirection").str shouldBe "desc"
        page1("groups").arr.map(group).toList shouldEqual List(("2026-09-06", 0, 2, 3))
        val cursor1 = page1("nextCursor").str

        val response2 = search(host, repoId, params + (Api.Field.Search.AFTER -> cursor1))
        response2.statusCode shouldBe 200
        val page2 = ujson.read(response2.text())
        page2("ids").arr.map(_.str).toList shouldEqual List(a3.persistedId, b1.persistedId)
        page2("page").num shouldBe 2
        page2("groups").arr.map(group).toList shouldEqual List(("2026-09-06", 0, 1, 3), ("2026-09-05", 1, 1, 2))

        val page3 = ujson.read(search(host, repoId, params + (Api.Field.Search.AFTER -> page2("nextCursor").str)).text())
        page3("ids").arr.map(_.str).toList shouldEqual List(b2.persistedId)
        page3("groups").arr.map(group).toList shouldEqual List(("2026-09-05", 0, 1, 2))
        page3("nextCursor") shouldBe ujson.Null

        // Direct page access by number, ascending groups, and a valid empty page
        val page2ByNumber = ujson.read(search(host, repoId, params + (Api.Field.Search.PAGE -> "2")).text())
        page2ByNumber("ids") shouldEqual page2("ids")

        val ascending = ujson.read(search(host, repoId, params + (Api.Field.Search.GROUP_DIRECTION -> "asc")).text())
        ascending("ids").arr.map(_.str).toList shouldEqual List(b1.persistedId, b2.persistedId)
        ascending("groupDirection").str shouldBe "asc"

        val emptyResponse = search(host, repoId, params + (Api.Field.Search.PAGE -> "9"))
        emptyResponse.statusCode shouldBe 200
        val emptyPage = ujson.read(emptyResponse.text())
        emptyPage("ids").arr shouldBe empty
        emptyPage("groups").arr shouldBe empty
        emptyPage("total").num shouldBe 5
        emptyPage("nextCursor") shouldBe ujson.Null
    }
  }

  test("Grouped JSON honors the view and the legacy Content-Type negotiation") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val kept = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val recycled = persistDated("2026-09-06T11:00:00", "a2.jpg")
        testApp.service.library.recycleAssets(Set(recycled.persistedId))

        val trash = search(
          host,
          repoId,
          Map(Api.Field.Search.GROUP_BY -> "dateImported", Api.Field.Search.VIEW -> "trashbin"),
          headers = Map("Content-Type" -> "application/json"))
        trash.statusCode shouldBe 200
        val json = ujson.read(trash.text())
        json("ids").arr.map(_.str).toList shouldEqual List(recycled.persistedId)
        json("groupBy").str shouldBe "dateImported"
        json("groups").arr.map(group).toList shouldEqual List(("2026-09-06", 0, 1, 1))

        val library = ujson.read(search(host, repoId, Map(Api.Field.Search.GROUP_BY -> "dateImported")).text())
        library("ids").arr.map(_.str).toList shouldEqual List(kept.persistedId)
    }
  }

  test("Invalid grouped requests are 400 JSON errors") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        persistDated("2026-09-06T10:00:00", "a1.jpg")
        persistDated("2026-09-06T11:00:00", "a2.jpg")
        val grouped = Map(Api.Field.Search.GROUP_BY -> "dateTaken")
        val cursor = ujson.read(search(host, repoId, grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "1")).text())("nextCursor")

        def rejected(params: Map[String, String], headers: Map[String, String] = jsonHeaders): String = {
          val response = search(host, repoId, params, headers)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("application/json")
          ujson.read(response.text())("error").str
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
        rejected(grouped + (Api.Field.Search.PAGE -> "0")) should include("p ")
        rejected(grouped + (Api.Field.Search.SORT -> "checksum0")) should include("sort")
        rejected(grouped + (Api.Field.Search.SORT -> "filename")) should include("sort")
        rejected(grouped, headers = Map("Accept" -> "text/html")) should include("JSON")

        // A cursor belongs to one search
        cursor.strOpt.isDefined shouldBe true
        rejected(grouped + (Api.Field.Search.AFTER -> cursor.str) + (Api.Field.Search.PAGE -> "2")) should include("after")
        rejected(grouped + (Api.Field.Search.AFTER -> cursor.str)) should include("page size")
        rejected(
          Map(
            Api.Field.Search.GROUP_BY -> "dateImported",
            Api.Field.Search.RESULTS_PER_PAGE -> "1",
            Api.Field.Search.AFTER -> cursor.str)) should include("cursor")
    }
  }

  test("Grouped requests require authentication and a repository the user can see") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/htmx/search/r/$repoId",
          params = Map(Api.Field.Search.GROUP_BY -> "dateTaken"),
          headers = jsonHeaders,
          check = false)
        response.statusCode shouldBe 401
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
        html.text() should include(s"""id="asset-${asset.persistedId}"""")

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
