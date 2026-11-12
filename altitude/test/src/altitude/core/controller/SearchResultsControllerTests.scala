package altitude.core.controller

import altitude.test.TestContext
import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ empty, include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.AssetType
import altitude.core.models.Person
import altitude.core.util.TriageSubset
import altitude.core.util.Util

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

  /** A frame in triage, in the Sequences `inSequence` stamps it into */
  private def persistFrame(taken: String, filename: String): Asset = {
    val asset = testContext.persistAsset(isTriaged = true)
    testApp.service.asset.rename(asset.persistedId, filename)
    val takenAt = LocalDateTime.parse(taken)
    testContext.setAssetDates(asset.persistedId, Some(takenAt), OffsetDateTime.of(takenAt, ZoneOffset.UTC))
    asset
  }

  private def inSequence(frames: Asset*): String = {
    val sequenceId = BaseDao.genId
    testApp.txManager.withTransaction(testApp.DAO.asset.setSequence(frames.map(_.persistedId).toSet, sequenceId))
    sequenceId
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
     * The first page heads the group with its Category › Location path, reflects the grouping in the Group menu and the
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
        // The Group menu reflects the grouping, and the grid carries it for its continuations
        page.text() should include(
          """data-app-search-group-by="location" data-app-search-group-direction="" aria-checked="true"""")
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

  private def curateButton(sequenceId: String): String = s"""data-app-sequence-id="$sequenceId""""

  private val sequenceOption = """data-app-search-group-by="sequence""""

  test("Group by Sequence heads a group with its first capture and offers Curate from two frames") {

    /**
     * Setup:
     *
     * In triage: a Sequence of two frames, a Sequence of one, a frame in none, read grouped by Sequence one and then all to a
     * page.
     *
     * Assertions:
     *
     * Each header is the group's first capture's day and time beside its count, with No sequence last. The Sequence of two has a
     * Curate button naming it and carrying `Const.Curation.MIN_FRAMES`, which the Sequence of one and No sequence do not have. A
     * frame's cell is its plain ID. The Group menu marks the option and the grid carries the grouping for its continuations, with
     * no direction.
     *
     * Edge cases:
     *
     * A continuation that finishes a group repeats neither its header nor its button.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val a = persistFrame("2026-09-06T10:00:05", "a.jpg")
        val b = persistFrame("2026-09-06T10:00:07", "b.jpg")
        val lone = persistFrame("2026-09-06T14:30:00", "c.jpg")
        val loose = persistFrame("2026-09-05T08:00:00", "d.jpg")
        val burst = inSequence(a, b)
        val single = inSequence(lone)
        val params = Map(Api.Field.Search.VIEW -> "triage", Api.Field.Search.GROUP_BY -> "sequence", "sort" -> "filename0")

        val page = htmlSearch(host, repoId, params)
        page.statusCode shouldBe 200
        val html = page.text()
        html should include("""<time datetime="2026-09-06T10:00:05">Sunday, September 6, 2026, 10:00:05 AM</time>""")
        html should include("""<time datetime="2026-09-06T14:30">Sunday, September 6, 2026, 2:30:00 PM</time>""")
        ordered(html, header(burst), header(single))
        ordered(html, header(single), "No sequence")
        ordered(html, "(2 items)", curateButton(burst))
        html should include(
          s"""${curateButton(burst)} data-min-frames="${Const.Curation.MIN_FRAMES}" @click="$$dispatch(window.Const.events.curateRequested, { sequenceId: $$el.dataset.appSequenceId })">Curate</button>""")
        (html should not).include(curateButton(single))
        "data-app-sequence-id=".r.findAllIn(html).size shouldBe 1
        html should include(cell(a))
        html should include(cell(loose))
        (html should not).include(s"""id="asset-${a.persistedId}-in-""")
        html should include(s"""$sequenceOption data-app-search-group-direction="" aria-checked="true"""")
        html should include("""data-results-group-by="sequence"""")
        html should include("""data-results-group-direction=""""")

        // The page ends inside the burst: the next finishes it under the same header and opens the next group
        val first = htmlSearch(host, repoId, params + (Api.Field.Search.RESULTS_PER_PAGE -> "1")).text()
        first should include(curateButton(burst))
        val next = htmlSearch(
          host,
          repoId,
          params ++ Map(
            Api.Field.Search.AFTER -> cursorOf(first).head,
            Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true",
            Api.Field.Search.RESULTS_PER_PAGE -> "3"
          )
        ).text()
        (next should not).include(header(burst))
        (next should not).include(curateButton(burst))
        ordered(next, cell(b), header(single))
        ordered(next, header(single), "No sequence")
        ordered(next, "No sequence", cell(loose))
    }
  }

  test(
    "A Sequence whose frames fall under two in the search has no Curate, and one at two has it though the Sequence is larger") {

    /**
     * Setup:
     *
     * A Sequence of three frames, two of them in an album and one of them in another, read in triage over each album.
     *
     * Assertions:
     *
     * The button follows the count of the group's matching frames: two shown have it and the count reads two; one shown does not.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val a = persistFrame("2026-09-06T10:00:00", "a.jpg")
        val b = persistFrame("2026-09-06T10:00:01", "b.jpg")
        val c = persistFrame("2026-09-06T10:00:02", "c.jpg")
        val burst = inSequence(a, b, c)
        val two = testApp.service.album.add("Two")
        val one = testApp.service.album.add("One")
        testApp.service.album.addAssets(two.persistedId, Set(a.persistedId, b.persistedId))
        testApp.service.album.addAssets(one.persistedId, Set(c.persistedId))
        val params = Map(Api.Field.Search.VIEW -> "triage", Api.Field.Search.GROUP_BY -> "sequence")

        val overTwo = htmlSearch(host, repoId, params + (Api.Field.Search.ALBUM_ID -> two.persistedId)).text()
        overTwo should include(curateButton(burst))
        overTwo should include("(2 items)")
        val overOne = htmlSearch(host, repoId, params + (Api.Field.Search.ALBUM_ID -> one.persistedId)).text()
        overOne should include(header(burst))
        (overOne should not).include(curateButton(burst))
    }
  }

  test("Under Curate own, a Sequence's Curate counts the frames the account owns alone") {

    /**
     * Setup:
     *
     * A User holding Curate own alone, logged in; in triage, a Sequence of two of its frames, and a Sequence of one of its frames
     * and two another account owns; read grouped by Sequence.
     *
     * Assertions:
     *
     * The first Sequence has its Curate button; the second, whose three frames shown leave one the User may curate, has none, as
     * Curation would show it too few frames.
     *
     * Edge cases:
     *
     * A User holding Curate all is offered Curate on both.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    testContext.grant(
      testContext.user,
      testContext.repository,
      altitude.core.models.Permissions.of(altitude.core.models.Permission.CurateOwn))
    login()

    withServer(App) {
      host =>
        val mine = inSequence(persistFrame("2026-09-06T10:00:00", "a.jpg"), persistFrame("2026-09-06T10:00:01", "b.jpg"))
        val ownInMixed = persistFrame("2026-09-07T10:00:00", "c.jpg")
        val theirs = List(persistFrame("2026-09-07T10:00:01", "d.jpg"), persistFrame("2026-09-07T10:00:02", "e.jpg"))
        // Persisted once the frames are, which the test context's one account persists
        val other = testContext.persistUser()
        testApp.txManager.withTransaction {
          for (frame <- theirs)
            testApp.DAO.asset
              .updateByBySql("UPDATE asset SET owner_user_id = ? WHERE id = ?", List(other.persistedId, frame.persistedId))
        }
        val mixed = inSequence(ownInMixed :: theirs*)
        val params = Map(Api.Field.Search.VIEW -> "triage", Api.Field.Search.GROUP_BY -> "sequence")

        val html = htmlSearch(host, repoId, params).text()
        html should include(curateButton(mine))
        html should include(header(mixed))
        (html should not).include(curateButton(mixed))

        val curator = testContext.persistUser()
        testContext.grant(
          curator,
          testApp.service.repository.getById(repoId),
          altitude.core.models.Permissions.of(altitude.core.models.Permission.CurateAll))
        val all = requests
          .get(
            s"$host/htmx/search/r/$repoId",
            params = params,
            headers = Map("Accept-Encoding" -> "identity"),
            cookies = cookiesOf(host, curator))
          .text()
        all should include(curateButton(mine))
        all should include(curateButton(mixed))
    }
  }

  test("The Group menu offers Sequence in Triage alone, and a Sequence grouping elsewhere is no grouping") {

    /**
     * Setup:
     *
     * Two frames of one Sequence in triage and an asset in the library, read in each view with and without `groupBy=sequence`.
     *
     * Assertions:
     *
     * Triage's Group menu offers Sequence beside its other options; the other views' offer it nowhere. Outside Triage the
     * grouping is no grouping and not a 400: no header, "No grouping" checked, the grid carrying no grouping for its
     * continuations, while the URL keeps what was asked for, so a reload still knows it.
     *
     * Edge cases:
     *
     * A direction is refused with the grouping wherever it was asked for, as with a Location grouping. A page continued outside
     * Triage under the asked-for grouping reads as the flat page it is.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val a = persistFrame("2026-09-06T10:00:00", "a.jpg")
        val b = persistFrame("2026-09-06T10:00:01", "b.jpg")
        inSequence(a, b)
        persistDated("2026-09-06T11:00:00", "c.jpg")
        persistDated("2026-09-06T12:00:00", "d.jpg")
        val locationOption = """data-app-search-group-by="location""""

        val triage = htmlSearch(host, repoId, Map(Api.Field.Search.VIEW -> "triage")).text()
        triage should include(sequenceOption)
        triage should include(locationOption)

        for (view <- List("repository", "trashbin")) withClue(view) {
          val plain = htmlSearch(host, repoId, Map(Api.Field.Search.VIEW -> view)).text()
          (plain should not).include(sequenceOption)
          plain should include(locationOption)

          val asked = htmlSearch(
            host,
            repoId,
            Map(Api.Field.Search.VIEW -> view, Api.Field.Search.GROUP_BY -> "sequence", Api.Field.Search.RESULTS_PER_PAGE -> "1"))
          asked.statusCode shouldBe 200
          (asked.text() should not).include("result-group")
          (asked.text() should not).include(sequenceOption)
          asked.text() should include(
            """data-app-search-group-by="" data-app-search-group-direction="" aria-checked="true">No grouping""")
          asked.text() should include("""data-results-group-by=""""")
          asked.headers("hx-replace-url").head should include("groupBy=sequence")
        }

        val elsewhere = Map(
          Api.Field.Search.VIEW -> "repository",
          Api.Field.Search.GROUP_BY -> "sequence",
          Api.Field.Search.RESULTS_PER_PAGE -> "1")
        val next = htmlSearch(
          host,
          repoId,
          elsewhere ++ Map(
            Api.Field.Search.AFTER -> cursorOf(htmlSearch(host, repoId, elsewhere).text()).head,
            Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")
        )
        next.statusCode shouldBe 200
        (next.text() should not).include("result-group")

        // The order is fixed: a direction is refused, in Triage and wherever else the grouping is asked for
        for (view <- List("triage", "repository")) withClue(view) {
          val refused = htmlSearch(
            host,
            repoId,
            Map(
              Api.Field.Search.VIEW -> view,
              Api.Field.Search.GROUP_BY -> "sequence",
              Api.Field.Search.GROUP_DIRECTION -> "asc"))
          refused.statusCode shouldBe 400
          refused.text() should include("groupDirection")
        }
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
        "(?s)<div id=\"groupMenuHost\".*?>".r.findFirstIn(page).get should include("data-choice-menu-disabled")
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
     * overall total, cells in sort order and the grouping reflected in the Group menu; only its last cell carries the cursor, and
     * no cell a page number. The continuation completes the day without repeating its header, opens the next one and, as the last
     * page, carries no cursor.
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

        // The Group menu reflects the grouping
        page1 should include(
          """data-app-search-group-by="dateTaken" data-app-search-group-direction="desc" aria-checked="true"""")
        page1 should include("""data-results-group-by="dateTaken"""")
        page1 should include("""data-results-group-direction="desc"""")

        // The last cell, and only it, carries the cursor
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
        trashPage should include(
          """data-app-search-group-by="dateTaken" data-app-search-group-direction="desc" aria-checked="true"""")

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
     * Every malformed or contradictory grouping, direction, cursor, page size or sort is a plain-text 400 naming the parameter at
     * fault. A cursor is refused for a different search but accepted with another page size.
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
        rejected(Map(Api.Field.Search.AFTER -> "abc")) should include("cursor")
        rejected(grouped + (Api.Field.Search.AFTER -> "abc")) should include("cursor")
        rejected(grouped + (Api.Field.Search.AFTER -> "e30")) should include("cursor")
        rejected(grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "0")) should include("rpp")
        rejected(grouped + (Api.Field.Search.RESULTS_PER_PAGE -> "501")) should include("rpp")
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

  test("An ungrouped page is bounded: rpp out of range is a plain-text 400 error") {

    /**
     * Setup:
     *
     * Two dated assets, and the cursor of a one-asset first page.
     *
     * Assertions:
     *
     * Page sizes outside 1..500 are plain-text 400s stating the bounds, on first pages and continuations alike, and a page size
     * of 1 or 500 returns one asset or both.
     *
     * Edge cases:
     *
     * The page sizes 0, -1 and 501.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val older = persistDated("2026-09-06T10:00:00", "a1.jpg")
        val newer = persistDated("2026-09-07T10:00:00", "a2.jpg")

        val one = htmlSearch(host, repoId, Map(Api.Field.Search.RESULTS_PER_PAGE -> "1")).text()
        List(older, newer).count(asset => one.contains(cell(asset))) shouldBe 1
        val continuation = Map(Api.Field.Search.AFTER -> cursorOf(one).head, Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")

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
        val relevanceOption = """data-app-search-sort="relevance""""
        val text = Map(Api.Field.Search.QUERY_TEXT -> "beach")

        for (shape <- List(Map.empty[String, String], Map("groupBy" -> "dateTaken"), Map(Api.Field.Search.LAYOUT -> "map")))
          withClue(s"$shape: ") {
            val withText = htmlSearch(host, repoId, text ++ shape).text()
            withText should include("""data-results-q="beach"""")
            withText should include(s"$relevanceOption aria-checked=\"true\"")

            // A sort the request names leaves Relevance on offer, not selected
            val byName = htmlSearch(host, repoId, text ++ shape + (Api.Field.Search.SORT -> "filename0")).text()
            byName should include(relevanceOption)
            byName.contains(s"$relevanceOption aria-checked=\"true\"") shouldBe false

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

  test("The repository view leaves triaged assets out unless asked, and the triage view refuses triaged=false") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a sorted asset and a triaged asset.
     *
     * Assertions:
     *
     * The repository view renders the sorted cell alone. With `triaged=true` it renders both cells and the bookmarkable URL
     * carries the parameter. The triage view with `triaged=false` is a plain-text 400 naming the view, as the map layout is.
     *
     * Edge cases:
     *
     * A `triaged` that is not a boolean is a 400.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val sorted = testContext.persistAsset()
        val triaged = testContext.persistAsset(isTriaged = true)

        val byDefault = htmlSearch(host, repoId, Map.empty).text()
        byDefault should include(cell(sorted))
        (byDefault should not).include(cell(triaged))

        val shown = htmlSearch(host, repoId, Map(Api.Field.Search.TRIAGED -> "true"))
        shown.statusCode shouldBe 200
        shown.text() should include(cell(sorted))
        shown.text() should include(cell(triaged))
        shown.headers("hx-replace-url").head should include("triaged=true")

        val contradiction = Map(Api.Field.Search.VIEW -> "triage", Api.Field.Search.TRIAGED -> "false")
        for (shape <- List(Map.empty[String, String], Map(Api.Field.Search.LAYOUT -> "map")))
          withClue(s"$shape: ") {
            val response = htmlSearch(host, repoId, contradiction ++ shape)
            response.statusCode shouldBe 400
            response.headers("content-type").head should include("text/plain")
            response.text() should include("triage")
          }

        htmlSearch(host, repoId, Map(Api.Field.Search.TRIAGED -> "maybe")).statusCode shouldBe 400
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
    val strangersRepo = testContext.persistRepository()
    testContext.grant(stranger, strangersRepo)
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
    "An ungrouped page carries the cursor of the next until the last page; the first page carries the total and whether it is capped") {

    /**
     * Setup:
     *
     * Three assets taken at the same moment, two to a page in filename order.
     *
     * Assertions:
     *
     * The first page carries the total, whether it is capped, and the cursor of the next page on its last cell alone; the second
     * page, requested with that cursor, holds the remaining asset with neither a cursor nor a total. A cursor without
     * isContinuousScroll, a malformed one and one sent with another sort are plain-text 400s.
     *
     * Edge cases:
     *
     * A continuation whose remaining assets have left the results is a 204 with nothing to append.
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
        val cursors = cursorOf(page1)
        cursors.length shouldBe 1
        ordered(page1, cell(a2), "data-app-search-after")
        page1.contains(cell(a3)) shouldBe false

        val scroll = params + (Api.Field.Search.AFTER -> cursors.head) + (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")
        val page2 = htmlSearch(host, repoId, scroll)
        page2.statusCode shouldBe 200
        page2.text() should include(cell(a3))
        page2.text().contains(cell(a2)) shouldBe false
        page2.text().contains("data-app-search-after") shouldBe false
        page2.text().contains("data-results-total") shouldBe false

        // A cursor continues the results of the search it was issued for
        for (
          refused <- List(
            params + (Api.Field.Search.AFTER -> cursors.head),
            scroll + (Api.Field.Search.AFTER -> "abc"),
            scroll + (Api.Field.Search.SORT -> "filename1"))
        ) {
          val response = htmlSearch(host, repoId, refused)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
        }

        // A continuation that finds no rows has nothing to append
        testApp.service.library.recycleAssets(Set(a3.persistedId))
        htmlSearch(host, repoId, scroll).statusCode shouldBe 204
    }
  }

  test("A first page's thumbnail carries its URL in src and data-src, a continuation's in data-src alone") {

    /**
     * Setup:
     *
     * Two assets taken at the same moment, one to a page in filename order.
     *
     * Assertions:
     *
     * The first page's thumbnail names its preview URL in both src, so it paints before any script runs, and data-src, so the
     * lazy-image observer can release and reload it like any other; the continuation's thumbnail names it in data-src alone.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val List(a1, a2) = List("a1.jpg", "a2.jpg").map(persistDated("2026-09-06T10:00:00", _)): @unchecked
        def previewUrl(asset: Asset) = s"/content/r/$repoId/preview/${asset.persistedId}"
        val params = Map(Api.Field.Search.RESULTS_PER_PAGE -> "1", Api.Field.Search.SORT -> "filename0")

        val page1 = htmlSearch(host, repoId, params).text()
        thumbnailSources(page1, a1) shouldBe Map("src" -> previewUrl(a1), "data-src" -> previewUrl(a1))

        val page2 = htmlSearch(
          host,
          repoId,
          params + (Api.Field.Search.AFTER -> cursorOf(page1).head) + (Api.Field.Search.IS_CONTINUOUS_SCROLL -> "true")).text()
        thumbnailSources(page2, a2) shouldBe Map("data-src" -> previewUrl(a2))
    }
  }

  /** The `src` and `data-src` attributes of the thumbnail in `asset`'s cell, by name */
  private def thumbnailSources(html: String, asset: Asset): Map[String, String] = {
    val img = "(?s)<img.*?>".r.findFirstIn(html.substring(html.indexOf(cell(asset)))).get
    """(data-src|(?<![-\w])src)="([^"]*)"""".r.findAllMatchIn(img).map(m => m.group(1) -> m.group(2)).toMap
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
     * bounding-box scope. Asking for JSON, by the Accept header or by the legacy Content-Type, is a 400 with a JSON error
     * pointing to HTML.
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
        page should include("""data-app-search-group-by="" data-app-search-group-direction="" aria-checked="true"""")
        page should include("""data-results-layout="grid"""")
        page should include("""data-results-group-by=""""")
        pressedLayout(page) shouldBe "grid"
        page.contains("""id="bboxScope"""") shouldBe false
        page.contains("""class="result-group"""") shouldBe false // the style block names it; no header is rendered

        // Nothing asks for results as JSON any more: the detail modal walks the grid
        for (params <- Seq(Map[String, String](), Map(Api.Field.Search.GROUP_BY -> "dateTaken"))) {
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

  test("Results open with a title block for their scope, and with the toolbar's total without one") {

    /**
     * Setup:
     *
     * A folder Iceland under Trips, an album, a Location Reykjavik under the category Countries, and one asset named beach.jpg.
     *
     * Assertions:
     *
     * Unscoped results render no title block. A folder scope renders a title block with the folder icon, the ancestors as folder
     * search triggers, the folder as the leaf and the way back to the whole result set (a trigger clearing the person scope,
     * which the store's rules widen to every scope); the toolbar keeps "Total:" with and without a scope. An album scope shows
     * the album's icon, name and creation day; a Location scope its icon, name and category; Search text its icon, the text and
     * "in this library".
     *
     * Edge cases:
     *
     * The root folder is the whole repository and renders no title block.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        persistDated("2026-09-06T10:00:00", "beach.jpg")
        val trips = testApp.service.folder.add("Trips")
        val iceland = testApp.service.folder.add("Iceland", parentId = trips.id)
        val album = testApp.service.album.add("Best of Iceland")
        val countries = testApp.service.location.addCategory("Countries")
        val reykjavik = testApp.service.location.addLocation("Reykjavik", 64.1466, -21.9426, categoryId = countries.id)
        val total = "<strong>Total:</strong>"
        val titleBlock = """class="results-title"""

        val unscoped = htmlSearch(host, repoId, Map.empty).text()
        unscoped should include(total)
        unscoped.contains(titleBlock) shouldBe false

        val folderPage = htmlSearch(host, repoId, Map(Api.Field.Search.FOLDER_ID -> iceland.persistedId)).text()
        folderPage should include(titleBlock)
        folderPage should include("fa-folder")
        folderPage should include(s"""data-app-search-folder-id="${trips.persistedId}"""")
        folderPage should include("""<span class="leaf">Iceland</span>""")
        folderPage should include("""data-app-search-person-id=""""")
        folderPage should include(total)

        val rootPage = htmlSearch(host, repoId, Map(Api.Field.Search.FOLDER_ID -> testContext.repository.rootFolderId)).text()
        rootPage should include(total)
        rootPage.contains(titleBlock) shouldBe false

        val albumPage = htmlSearch(host, repoId, Map(Api.Field.Search.ALBUM_ID -> album.persistedId)).text()
        albumPage should include("fa-images")
        albumPage should include("Best of Iceland")
        val createdAt = testApp.service.album.getById(album.persistedId).createdAt.get
        albumPage should include(s"Created ${Util.humanReadableDate(createdAt.toLocalDate)}")

        val locationPage = htmlSearch(host, repoId, Map(Api.Field.Search.LOCATION_ID -> reykjavik.persistedId)).text()
        locationPage should include("fa-map-marker-alt")
        locationPage should include("Reykjavik")
        locationPage should include("""<div class="subtitle">Countries</div>""")

        val textPage = htmlSearch(host, repoId, Map(Api.Field.Search.QUERY_TEXT -> "beach")).text()
        textPage should include("fa-search")
        textPage should include("""<span class="query">beach</span>""")
        textPage should include("""<div class="subtitle">in this library</div>""")
    }
  }

  test("A subset narrows the results in Triage and Browse, titles them and stays in the replaced URL") {

    /**
     * Setup:
     *
     * A logged-in user's repository with three triaged assets, one with a Face of an unnamed person, one with a Face of a named
     * person, one in the Location "Beach", and a sorted asset with a Face of the unnamed person.
     *
     * Assertions:
     *
     * In Triage, `subset=unknownPeople` renders the cell of the asset with the unknown person alone, under the Results title
     * "Unknown people" with its icon and the way back, and `subset=noLocation` the two assets no Location points at under "No
     * Location"; both grid shapes and the map layout render the title, and the replaced URL carries the subset. In Browse,
     * `subset=unknownPeople` renders the sorted asset with the unknown person.
     *
     * Edge cases:
     *
     * A subset that is not one of the two, or empty, is a plain-text 400, in the grid and in the map layout.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val unknown = testApp.service.person.addPerson(Person())
        val named = testApp.service.person.addPerson(Person(name = Some("Alice")))
        def withFaceOf(person: Person, isTriaged: Boolean): Asset = {
          val asset = testContext.persistAsset(isTriaged = isTriaged)
          testContext.addTestFace(person, asset, TestContext.unitVector(1))
          asset
        }
        val withUnknown = withFaceOf(unknown, isTriaged = true)
        val withNamed = withFaceOf(named, isTriaged = true)
        val inBeach = testContext.persistAsset(isTriaged = true)
        val beach = testApp.service.location.addLocation("Beach", 1, 2)
        testApp.service.location.addAssets(beach.persistedId, Set(inBeach.persistedId))
        val sortedWithUnknown = withFaceOf(unknown, isTriaged = false)
        val triage = Map(Api.Field.Search.VIEW -> Const.Search.View.TRIAGE)
        val unknownPeople = Map(Api.Field.Search.SUBSET -> TriageSubset.UnknownPeople.apiValue)
        val noLocation = Map(Api.Field.Search.SUBSET -> TriageSubset.NoLocation.apiValue)

        for (shape <- List(Map.empty[String, String], Map("groupBy" -> "dateTaken"))) withClue(s"$shape: ") {
          val response = htmlSearch(host, repoId, triage ++ unknownPeople ++ shape)
          response.statusCode shouldBe 200
          val page = response.text()
          page should include(cell(withUnknown))
          (page should not).include(cell(withNamed))
          (page should not).include(cell(inBeach))
          page should include("""class="results-title"""")
          page should include("fa-user-secret")
          page should include("""<span class="name">Unknown people</span>""")
          page should include("""data-app-search-person-id=""""")
          java.net.URLDecoder.decode(response.headers("hx-replace-url").head, "UTF-8") should include(
            s"subset=${TriageSubset.UnknownPeople.apiValue}")
        }

        val noLocationResponse = htmlSearch(host, repoId, triage ++ noLocation)
        val noLocationPage = noLocationResponse.text()
        noLocationPage should include(cell(withUnknown))
        noLocationPage should include(cell(withNamed))
        (noLocationPage should not).include(cell(inBeach))
        noLocationPage should include("fa-map-marker-alt slashed")
        noLocationPage should include("""<span class="name">No Location</span>""")
        noLocationResponse.headers("hx-replace-url").head should include(s"subset=${TriageSubset.NoLocation.apiValue}")

        val map = htmlSearch(host, repoId, triage ++ noLocation + (Api.Field.Search.LAYOUT -> Const.Search.Layout.MAP))
        map.statusCode shouldBe 200
        map.text() should include("""data-results-total="2"""")
        map.text() should include("""<span class="name">No Location</span>""")
        map.headers("hx-replace-url").head should include(s"subset=${TriageSubset.NoLocation.apiValue}")

        val browse = htmlSearch(host, repoId, unknownPeople).text()
        browse should include(cell(sortedWithUnknown))
        (browse should not).include(cell(withUnknown))

        for (
          subset <- List("everyone", "");
          shape <- List(Map.empty[String, String], Map(Api.Field.Search.LAYOUT -> Const.Search.Layout.MAP))
        ) withClue(s"'$subset' $shape: ") {
          val response = htmlSearch(host, repoId, triage ++ shape + (Api.Field.Search.SUBSET -> subset))
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
        }
    }
  }

  test("A search scoped to an unknown or another repository's Person, folder, album or Location is a 404") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an asset, and a second repository of theirs with the Person "Eve", the folder "Eve's
     * folder", the album "Eve's album" and the Location "Eve's place".
     *
     * Assertions:
     *
     * Through the first repository, a search scoped to each of the four, or to an ID of each kind that does not exist, answers a
     * plain-text 404 that names none of them, in the grid layout and in the map layout.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        testContext.insertAsset(testContext.makeAsset(repository = Some(repo)))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val scopes = List(
          Api.Field.Search.PERSON_ID -> testApp.service.person.addPerson(Person(name = Some("Eve"))).persistedId,
          Api.Field.Search.FOLDER_ID -> testApp.service.folder.add("Eve's folder").persistedId,
          Api.Field.Search.ALBUM_ID -> testApp.service.album.add("Eve's album").persistedId,
          Api.Field.Search.LOCATION_ID -> testApp.service.location.addLocation("Eve's place", 1, 2).persistedId
        )
        testApp.service.repository.switchContextToRepository(repo)

        for (
          (param, foreignId) <- scopes;
          id <- List(java.util.UUID.randomUUID.toString, foreignId);
          layout <- List(Const.Search.Layout.GRID, Const.Search.Layout.MAP)
        ) withClue(s"$param=$id $layout: ") {
          val response = htmlSearch(host, repoId, Map(param -> id, Api.Field.Search.LAYOUT -> layout))
          response.statusCode shouldBe 404
          response.headers("content-type") shouldBe Seq("text/plain")
          (response.text() should not).include("Eve")
        }
    }
  }

  /** The URL a response replaces the browser's with, decoded */
  private def replacedUrl(response: requests.Response): String =
    java.net.URLDecoder.decode(response.headers("hx-replace-url").head, "UTF-8")

  /** The grid ungrouped and grouped by day: an advanced search's filters hold in both */
  private val gridShapes = List(Map.empty[String, String], Map("groupBy" -> "dateTaken"))

  private val mapLayout = Map(Api.Field.Search.LAYOUT -> Const.Search.Layout.MAP)

  test("Several people match the assets of all of them, or of any of them with peopleMatch=any") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the people Alice and Bob and four assets: one with a Face of each, one with Alice alone,
     * one with Bob alone and one with nobody.
     *
     * Assertions:
     *
     * `personId` naming both, comma-separated, renders the asset with both alone, as `peopleMatch=all` does, and
     * `peopleMatch=any` the three assets with either, in both grid shapes; the map layout counts the same matches. The replaced
     * URL carries the people as given and the people match. A page of the search is continued by its cursor.
     *
     * Edge cases:
     *
     * An ID that names nobody, beside Alice's, is no 404: all of the two match nothing, and any of them match Alice's assets. A
     * cursor of the search matching any does not continue the one matching all.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        def withFacesOf(filename: String, people: Person*): Asset = {
          val asset = persistDated("2026-09-06T10:00:00", filename)
          people.zipWithIndex.foreach(
            (person, index) => testContext.addTestFace(person, asset, TestContext.unitVector(index + 1)))
          asset
        }
        val both = withFacesOf("a.jpg", alice, bob)
        val aliceOnly = withFacesOf("b.jpg", alice)
        val bobOnly = withFacesOf("c.jpg", bob)
        val neither = withFacesOf("d.jpg")
        val people = Map(Api.Field.Search.PERSON_ID -> s"${alice.persistedId},${bob.persistedId}", "sort" -> "filename0")
        val any = people + (Api.Field.Search.PEOPLE_MATCH -> "any")

        for (shape <- gridShapes; all <- List(people, people + (Api.Field.Search.PEOPLE_MATCH -> "all")))
          withClue(s"$shape $all: ") {
            val response = htmlSearch(host, repoId, all ++ shape)
            response.statusCode shouldBe 200
            val page = response.text()
            page should include(cell(both))
            for (other <- List(aliceOnly, bobOnly, neither)) (page should not).include(cell(other))
            replacedUrl(response) should include(s"personId=${alice.persistedId},${bob.persistedId}")
          }

        for (shape <- gridShapes) withClue(s"$shape any: ") {
          val response = htmlSearch(host, repoId, any ++ shape)
          val page = response.text()
          for (matching <- List(both, aliceOnly, bobOnly)) page should include(cell(matching))
          (page should not).include(cell(neither))
          replacedUrl(response) should include("peopleMatch=any")
        }

        htmlSearch(host, repoId, people ++ mapLayout).text() should include("""data-results-total="1"""")
        htmlSearch(host, repoId, any ++ mapLayout).text() should include("""data-results-total="3"""")

        // The cursor carries the people match: the second page of any follows the first, and is refused for all
        val firstPage = htmlSearch(host, repoId, any + ("rpp" -> "2")).text()
        firstPage should include(cell(both))
        firstPage should include(cell(aliceOnly))
        (firstPage should not).include(cell(bobOnly))
        val continuation = Map("after" -> cursorOf(firstPage).head, "isContinuousScroll" -> "true", "rpp" -> "2")
        htmlSearch(host, repoId, any ++ continuation).text() should include(cell(bobOnly))
        htmlSearch(host, repoId, people ++ continuation).statusCode shouldBe 400

        val withNobody = Map(Api.Field.Search.PERSON_ID -> s"${alice.persistedId},${java.util.UUID.randomUUID}")
        val allOfThem = htmlSearch(host, repoId, withNobody)
        allOfThem.statusCode shouldBe 200
        allOfThem.text() should include("""data-results-total="0"""")
        val anyOfThem = htmlSearch(host, repoId, withNobody + (Api.Field.Search.PEOPLE_MATCH -> "any")).text()
        anyOfThem should include(cell(both))
        anyOfThem should include(cell(aliceOnly))
        (anyOfThem should not).include(cell(bobOnly))
    }
  }

  test("Several Locations match the assets in any of them") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the Locations Lisbon, Sintra and Porto, an asset in each, and an asset in none.
     *
     * Assertions:
     *
     * `locationId` naming Lisbon and Sintra, comma-separated, renders the assets of the two, in both grid shapes, and the map
     * layout counts them; the replaced URL carries the Locations as given.
     *
     * Edge cases:
     *
     * An ID that names no Location, beside Lisbon's, is no 404 and adds nothing to Lisbon's assets.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def located(name: String, filename: String) = {
          val location = testApp.service.location.addLocation(name, 1, 2)
          val asset = persistDated("2026-09-06T10:00:00", filename)
          testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))
          (location, asset)
        }
        val (lisbon, inLisbon) = located("Lisbon", "a.jpg")
        val (sintra, inSintra) = located("Sintra", "b.jpg")
        val (_, inPorto) = located("Porto", "c.jpg")
        val unlocated = persistDated("2026-09-06T10:00:00", "d.jpg")
        val locations = Map(Api.Field.Search.LOCATION_ID -> s"${lisbon.persistedId},${sintra.persistedId}")

        for (shape <- gridShapes) withClue(s"$shape: ") {
          val response = htmlSearch(host, repoId, locations ++ shape)
          response.statusCode shouldBe 200
          val page = response.text()
          page should include(cell(inLisbon))
          page should include(cell(inSintra))
          for (other <- List(inPorto, unlocated)) (page should not).include(cell(other))
          replacedUrl(response) should include(s"locationId=${lisbon.persistedId},${sintra.persistedId}")
        }
        htmlSearch(host, repoId, locations ++ mapLayout).text() should include("""data-results-total="2"""")

        val withNowhere =
          htmlSearch(host, repoId, Map(Api.Field.Search.LOCATION_ID -> s"${lisbon.persistedId},${java.util.UUID.randomUUID}"))
        withNowhere.statusCode shouldBe 200
        withNowhere.text() should include(cell(inLisbon))
        withNowhere.text() should include("""data-results-total="1"""")
    }
  }

  test("A Date Taken range and a Date Imported range narrow the results to whole days, both ends included") {

    /**
     * Setup:
     *
     * A logged-in user's repository with assets taken at the first moment of June 1, on June 15, in the last second of June 30
     * and at the first moment of July 1, 2024, imported on four consecutive days at noon UTC, an asset with no capture time
     * imported on the fifth, and a triaged asset taken on June 15.
     *
     * Assertions:
     *
     * `takenFrom` and `takenTo` of June 1 and June 30 render the three assets of June, in both grid shapes, and the map layout
     * counts them; `takenFrom` alone renders the assets from that day on and `takenTo` alone those up to it. `importedFrom` and
     * `importedTo` render the assets imported on those days of the server's time zone and between them. Both ranges together
     * render the assets in both. The replaced URL carries the four dates.
     *
     * Edge cases:
     *
     * The asset with no capture time is in no Date Taken range, not even one open at an end. `triaged=true` adds the triaged
     * asset of June 15 to the range's results, which leave it out otherwise.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def imported(day: Int) = OffsetDateTime.of(2026, 9, day, 12, 0, 0, 0, ZoneOffset.UTC)
        // The day of the server's time zone an import of noon UTC falls on
        def importDay(day: Int): String = imported(day).atZoneSameInstant(java.time.ZoneId.systemDefault).toLocalDate.toString
        def dated(taken: String, filename: String, importedOn: Int): Asset = {
          val asset = persistDated(taken, filename)
          testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(taken)), imported(importedOn))
          asset
        }
        val juneFirst = dated("2024-06-01T00:00:00", "a.jpg", 1)
        val juneMid = dated("2024-06-15T12:00:00", "b.jpg", 2)
        val juneLast = dated("2024-06-30T23:59:59", "c.jpg", 3)
        val julyFirst = dated("2024-07-01T00:00:00", "d.jpg", 4)
        val undated = persistUndated("e.jpg")
        testContext.setAssetDates(undated.persistedId, None, imported(5))
        val triaged = persistFrame("2024-06-15T13:00:00", "f.jpg")
        val everyone = List(juneFirst, juneMid, juneLast, julyFirst, undated, triaged)

        def shows(page: String, expected: Asset*): Unit = {
          for (asset <- expected) page should include(cell(asset))
          for (asset <- everyone.diff(expected)) (page should not).include(cell(asset))
        }

        val june = Map(Api.Field.Search.TAKEN_FROM -> "2024-06-01", Api.Field.Search.TAKEN_TO -> "2024-06-30")
        for (shape <- gridShapes) withClue(s"$shape: ") {
          val response = htmlSearch(host, repoId, june ++ shape)
          response.statusCode shouldBe 200
          shows(response.text(), juneFirst, juneMid, juneLast)
          replacedUrl(response) should include("takenFrom=2024-06-01")
          replacedUrl(response) should include("takenTo=2024-06-30")
        }
        htmlSearch(host, repoId, june ++ mapLayout).text() should include("""data-results-total="3"""")

        shows(htmlSearch(host, repoId, Map(Api.Field.Search.TAKEN_FROM -> "2024-06-30")).text(), juneLast, julyFirst)
        shows(htmlSearch(host, repoId, Map(Api.Field.Search.TAKEN_TO -> "2024-06-01")).text(), juneFirst)
        shows(htmlSearch(host, repoId, june + (Api.Field.Search.TRIAGED -> "true")).text(), juneFirst, juneMid, juneLast, triaged)

        val importedRange =
          Map(Api.Field.Search.IMPORTED_FROM -> importDay(2), Api.Field.Search.IMPORTED_TO -> importDay(4))
        for (shape <- gridShapes) withClue(s"$shape imported: ") {
          val response = htmlSearch(host, repoId, importedRange ++ shape)
          shows(response.text(), juneMid, juneLast, julyFirst)
          replacedUrl(response) should include(s"importedFrom=${importDay(2)}")
          replacedUrl(response) should include(s"importedTo=${importDay(4)}")
        }
        shows(htmlSearch(host, repoId, Map(Api.Field.Search.IMPORTED_FROM -> importDay(5))).text(), undated)

        shows(htmlSearch(host, repoId, june ++ importedRange).text(), juneMid, juneLast)
    }
  }

  test("Text, a folder, people, Locations and date ranges narrow one search together") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Trips, the people Alice and Bob, and the Locations Lisbon and Sintra. One
     * asset, beach.jpg in Trips, taken on June 15, 2024, has a Face of each and is in Lisbon; five more each differ from it in
     * one way: named forest.jpg, in no folder of Trips, without Bob, in Porto, and taken on July 15.
     *
     * Assertions:
     *
     * A search for the text "beach" in Trips, with both people, in Lisbon or Sintra, taken in June 2024 and imported on the day
     * the assets were, renders the one asset that passes every filter, in both grid shapes, and the map layout counts it.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trips = testApp.service.folder.add("Trips")
        val elsewhere = testApp.service.folder.add("Elsewhere")
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val lisbon = testApp.service.location.addLocation("Lisbon", 1, 2)
        val sintra = testApp.service.location.addLocation("Sintra", 1, 2)
        val porto = testApp.service.location.addLocation("Porto", 1, 2)
        val importedAt = OffsetDateTime.of(2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC)
        val importDay = importedAt.atZoneSameInstant(java.time.ZoneId.systemDefault).toLocalDate.toString

        def asset(
            filename: String = "beach.jpg",
            folderId: String = trips.persistedId,
            people: List[Person] = List(alice, bob),
            locationId: String = lisbon.persistedId,
            taken: String = "2024-06-15T10:00:00"): Asset = {
          val asset = testContext.persistAsset()
          testApp.service.asset.rename(asset.persistedId, filename)
          testApp.service.library.moveAssetsToFolder(Set(asset.persistedId), folderId)
          people.zipWithIndex.foreach(
            (person, index) => testContext.addTestFace(person, asset, TestContext.unitVector(index + 1)))
          testApp.service.location.addAssets(locationId, Set(asset.persistedId))
          testContext.setAssetDates(asset.persistedId, Some(LocalDateTime.parse(taken)), importedAt)
          asset
        }
        val matching = asset()
        val others = List(
          asset(filename = "forest.jpg"),
          asset(folderId = elsewhere.persistedId),
          asset(people = List(alice)),
          asset(locationId = porto.persistedId),
          asset(taken = "2024-07-15T10:00:00")
        )

        val filters = Map(
          Api.Field.Search.QUERY_TEXT -> "beach",
          Api.Field.Search.FOLDER_ID -> trips.persistedId,
          Api.Field.Search.PERSON_ID -> s"${alice.persistedId},${bob.persistedId}",
          Api.Field.Search.LOCATION_ID -> s"${lisbon.persistedId},${sintra.persistedId}",
          Api.Field.Search.TAKEN_FROM -> "2024-06-01",
          Api.Field.Search.TAKEN_TO -> "2024-06-30",
          Api.Field.Search.IMPORTED_FROM -> importDay,
          Api.Field.Search.IMPORTED_TO -> importDay
        )

        for (shape <- gridShapes) withClue(s"$shape: ") {
          val response = htmlSearch(host, repoId, filters ++ shape)
          response.statusCode shouldBe 200
          response.text() should include(cell(matching))
          for (other <- others) (response.text() should not).include(cell(other))
        }
        htmlSearch(host, repoId, filters ++ mapLayout).text() should include("""data-results-total="1"""")
    }
  }

  test("An advanced search's malformed parameters, and its filters in the trash, are plain-text 400s") {

    /**
     * Setup:
     *
     * An empty repository, searched ungrouped, grouped by day and in map layout.
     *
     * Assertions:
     *
     * A date that is not an ISO day, a range whose start is after its end, a `peopleMatch` that is neither `all` nor `any`, and
     * eleven people or eleven Locations in one parameter are each a plain-text 400. So are a date range, two people and two
     * Locations with the trash view, naming the trash.
     *
     * Edge cases:
     *
     * Ten people in one parameter, and one person and one Location with the trash view, are served.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def ids(count: Int): String = List.fill(count)(java.util.UUID.randomUUID.toString).mkString(",")
        val trash = Api.Field.Search.VIEW -> Const.Search.View.TRASHBIN
        val shapes = gridShapes :+ mapLayout

        val malformed = List(
          Map(Api.Field.Search.TAKEN_FROM -> "june"),
          Map(Api.Field.Search.IMPORTED_TO -> "2024-02-30"),
          Map(Api.Field.Search.TAKEN_FROM -> "2024-06-02", Api.Field.Search.TAKEN_TO -> "2024-06-01"),
          Map(Api.Field.Search.IMPORTED_FROM -> "2024-06-02", Api.Field.Search.IMPORTED_TO -> "2024-06-01"),
          Map(Api.Field.Search.PEOPLE_MATCH -> "some"),
          Map(Api.Field.Search.PERSON_ID -> ids(11)),
          Map(Api.Field.Search.LOCATION_ID -> ids(11))
        )
        for (params <- malformed; shape <- shapes) withClue(s"$params $shape: ") {
          val response = htmlSearch(host, repoId, params ++ shape)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
        }

        val inTrash = List(
          Map(Api.Field.Search.TAKEN_FROM -> "2024-06-01"),
          Map(Api.Field.Search.IMPORTED_TO -> "2024-06-01"),
          Map(Api.Field.Search.PERSON_ID -> ids(2)),
          Map(Api.Field.Search.LOCATION_ID -> ids(2))
        )
        for (params <- inTrash; shape <- shapes) withClue(s"trash $params $shape: ") {
          val response = htmlSearch(host, repoId, params ++ shape + trash)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("text/plain")
          response.text() should include("trash")
        }

        htmlSearch(host, repoId, Map(Api.Field.Search.PERSON_ID -> ids(10))).statusCode shouldBe 200
    }
  }

  /**
   * A chip of a filtered search's results title: the search parameters its × leaves out of the search, each with the value its
   * remove button carries (`data-app-filter-<param>`, the parameter in kebab case), and the text it shows
   */
  private case class FilterChip(filter: Map[String, String], text: String)

  /** The chips of the results title in `html`, in document order */
  private def filterChipsIn(html: String): List[FilterChip] =
    """(?s)<li\b[^>]*>(?:(?!</li>).)*\bdata-app-filter-.*?</li>""".r.findAllIn(html).toList.map {
      element =>
        FilterChip(
          filter = """\bdata-app-filter-([a-z-]+)="([^"]*)"""".r.findAllMatchIn(element).map(m => m.group(1) -> m.group(2)).toMap,
          text = element.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim
        )
    }

  /** The title of a filtered search, which opens its block */
  private val filteredTitle = "Search results"

  /** The control of a filtered search's title that opens the advanced search panel */
  private val editFilters = "data-app-edit-filters"

  test("A filtered search is titled Search results over a chip per filter, in the order of the panel's fields") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Lisbon under 2024 under Trips, the people Alice, with a Face and so a cover,
     * and Bob, with none, the Location Lisbon in the Category Portugal and the Location Sintra.
     *
     * Assertions:
     *
     * A search by the text "beach", the folder, Bob and Alice matching any of them, Sintra and Lisbon, a Date Taken range and a
     * Date Imported range is titled "Search results" with the Edit control, over one chip per filter, in the order of the panel's
     * fields: the text, the folder by its path, each person by name in the order given, each Location with its Category in the
     * order given, then the two ranges as "Taken" and "Imported" days. Each chip carries the parameters its × leaves out: the
     * text, the folder, its own person or Location, or both days of its range. Alice's chip leads with her cover Face; the people
     * are announced as "any of" them, and as "all of" them when the match is left out.
     *
     * Edge cases:
     *
     * The title of one scope is not rendered: no person block, no folder crumbs, no quoted text.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trips = testApp.service.folder.add("Trips")
        val year = testApp.service.folder.add("2024", parentId = trips.id)
        val folder = testApp.service.folder.add("Lisbon", parentId = year.id)
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        testContext.addTestFace(alice, testContext.persistAsset(), TestContext.unitVector(1))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val portugal = testApp.service.location.addCategory("Portugal")
        val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14, Some(portugal.persistedId))
        val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)
        val filters = Map(
          Api.Field.Search.QUERY_TEXT -> "beach",
          Api.Field.Search.FOLDER_ID -> folder.persistedId,
          Api.Field.Search.PERSON_ID -> s"${bob.persistedId},${alice.persistedId}",
          Api.Field.Search.PEOPLE_MATCH -> "any",
          Api.Field.Search.LOCATION_ID -> s"${sintra.persistedId},${lisbon.persistedId}",
          Api.Field.Search.TAKEN_FROM -> "2024-06-01",
          Api.Field.Search.TAKEN_TO -> "2024-06-30",
          Api.Field.Search.IMPORTED_FROM -> "2025-01-08",
          Api.Field.Search.IMPORTED_TO -> "2025-01-08"
        )

        val response = htmlSearch(host, repoId, filters)
        response.statusCode shouldBe 200
        val page = response.text()
        page should include(filteredTitle)
        page should include(editFilters)
        (page should not).include("""id="person"""")
        (page should not).include("""class="crumbs"""")
        (page should not).include("""class="query"""")

        filterChipsIn(page) shouldBe List(
          FilterChip(Map("q" -> "beach"), "beach"),
          FilterChip(Map("folder-id" -> folder.persistedId), "Trips › 2024 › Lisbon"),
          FilterChip(Map("person-id" -> bob.persistedId), "Bob"),
          FilterChip(Map("person-id" -> alice.persistedId), "Alice"),
          FilterChip(Map("location-id" -> sintra.persistedId), "Sintra"),
          FilterChip(Map("location-id" -> lisbon.persistedId), "Portugal › Lisbon"),
          FilterChip(Map("taken-from" -> "2024-06-01", "taken-to" -> "2024-06-30"), "Taken 1 Jun – 30 Jun 2024"),
          FilterChip(Map("imported-from" -> "2025-01-08", "imported-to" -> "2025-01-08"), "Imported 8 Jan 2025")
        )
        val cover = testApp.service.person.getById(alice.persistedId).coverFaceId.get
        page should include(Util.contentUrl(Const.DataStore.FACE, cover))
        ordered(page, "any of", "Bob")

        val allOf = htmlSearch(host, repoId, filters - Api.Field.Search.PEOPLE_MATCH).text()
        ordered(allOf, "all of", "Bob")
        (allOf should not).include("any of")
    }
  }

  test("A filtered search's chip reads a date range as its days, an open end as from or until") {

    /**
     * Setup:
     *
     * An empty repository.
     *
     * Assertions:
     *
     * A search by a date range alone is a filtered search: its one chip reads a range within a year with the year once ("Taken 1
     * Jun – 30 Jun 2024"), a range across two years with both ("Imported 28 Dec 2023 – 3 Jan 2024"), a range of one day as that
     * day ("Taken 8 Jun 2024"), a range with no last day as "from" its first ("Taken from 8 Jun 2024") and one with no first day
     * as "until" its last ("Imported until 21 Jun 2024"), and carries the days the request gave, an open end none.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val ranges = List(
          Map("takenFrom" -> "2024-06-01", "takenTo" -> "2024-06-30") -> "Taken 1 Jun – 30 Jun 2024",
          Map("importedFrom" -> "2023-12-28", "importedTo" -> "2024-01-03") -> "Imported 28 Dec 2023 – 3 Jan 2024",
          Map("takenFrom" -> "2024-06-08", "takenTo" -> "2024-06-08") -> "Taken 8 Jun 2024",
          Map("takenFrom" -> "2024-06-08") -> "Taken from 8 Jun 2024",
          Map("importedTo" -> "2024-06-21") -> "Imported until 21 Jun 2024"
        )
        def kebab(param: String): String = param.replaceAll("([A-Z])", "-$1").toLowerCase

        for ((range, label) <- ranges) withClue(s"$range: ") {
          val page = htmlSearch(host, repoId, range).text()
          page should include(filteredTitle)
          filterChipsIn(page) shouldBe List(FilterChip(range.map((param, day) => kebab(param) -> day), label))
        }
    }
  }

  test("Only a filtered search is titled by its filters; any other keeps the title of its one scope") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Trips, the people Alice and Bob, Alice with Faces on two assets, and the
     * Locations Lisbon and Sintra.
     *
     * Assertions:
     *
     * Two people alone, two Locations alone, the text with a folder, the text with a person, a folder with a Location, and a
     * person with a Location are each a filtered search, titled "Search results" over their chips. One person alone keeps the
     * person's block with the Hide link, one folder alone its crumbs, one Location alone its name, and the text alone its quoted
     * text, none with a chip or the Edit control.
     *
     * Edge cases:
     *
     * The root folder is no filter: the text in the root folder keeps the text's title.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        val trips = testApp.service.folder.add("Trips")
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        testContext.addTestFace(alice, testContext.persistAsset(), TestContext.unitVector(1))
        testContext.addTestFace(alice, testContext.persistAsset(), TestContext.unitVector(2))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val lisbon = testApp.service.location.addLocation("Lisbon", 38.72, -9.14)
        val sintra = testApp.service.location.addLocation("Sintra", 38.8, -9.39)
        val fields = Api.Field.Search
        val text = fields.QUERY_TEXT -> "beach"
        val folder = fields.FOLDER_ID -> trips.persistedId
        val person = fields.PERSON_ID -> alice.persistedId
        val location = fields.LOCATION_ID -> lisbon.persistedId

        val filtered = List(
          Map(fields.PERSON_ID -> s"${alice.persistedId},${bob.persistedId}"),
          Map(fields.LOCATION_ID -> s"${lisbon.persistedId},${sintra.persistedId}"),
          Map(text, folder),
          Map(text, person),
          Map(folder, location),
          Map(person, location)
        )
        for (params <- filtered) withClue(s"$params: ") {
          val response = htmlSearch(host, repoId, params)
          response.statusCode shouldBe 200
          val page = response.text()
          page should include(filteredTitle)
          page should include(editFilters)
          filterChipsIn(page).size shouldBe params.values.map(_.split(",").length).sum
        }

        def unfiltered(params: Map[String, String]): String = {
          val page = htmlSearch(host, repoId, params).text()
          (page should not).include(filteredTitle)
          (page should not).include(editFilters)
          filterChipsIn(page) shouldBe empty
          page
        }
        val personPage = unfiltered(Map(person))
        personPage should include(s"""id="person" class="results-title dropzone" x-data data-person-id="${alice.persistedId}"""")
        personPage should include("""class="mark-hidden"""")
        unfiltered(Map(folder)) should include("""<span class="leaf">Trips</span>""")
        unfiltered(Map(location)) should include("""<span class="name">Lisbon</span>""")
        unfiltered(Map(text)) should include("""<span class="query">beach</span>""")
        unfiltered(Map(text, fields.FOLDER_ID -> testContext.repository.rootFolderId)) should include(
          """<span class="query">beach</span>""")
    }
  }

  test("In a filtered search an ID that names nothing in the repository has no chip and matches nothing") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Alice, who has a Face on an asset taken on June 15, 2024, in the folder Trips and the
     * Location Lisbon, and a second repository of theirs with the person Eve, the folder "Eve's folder" and the Location "Eve's
     * place".
     *
     * Assertions:
     *
     * A search of June 2024 scoped to a person, a folder or a Location that is the other repository's, or that does not exist,
     * answers 200 with no result and no chip but the range's, naming nothing of the other repository, in the grid and the map
     * layouts. Beside Alice, an unknown person has no chip while Alice keeps hers, and the people matching all of the two match
     * nothing.
     *
     * Edge cases:
     *
     * The same IDs beside June alone are matched as the scope: June 2024 alone finds the asset.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()

    withServer(App) {
      host =>
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val asset = persistDated("2024-06-15T10:00:00", "beach.jpg")
        testContext.addTestFace(alice, asset, TestContext.unitVector(1))
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val scopes = List(
          Api.Field.Search.PERSON_ID -> testApp.service.person.addPerson(Person(name = Some("Eve"))).persistedId,
          Api.Field.Search.FOLDER_ID -> testApp.service.folder.add("Eve's folder").persistedId,
          Api.Field.Search.LOCATION_ID -> testApp.service.location.addLocation("Eve's place", 1, 2).persistedId
        )
        testApp.service.repository.switchContextToRepository(repo)
        val june = Map(Api.Field.Search.TAKEN_FROM -> "2024-06-01", Api.Field.Search.TAKEN_TO -> "2024-06-30")
        val juneChip = FilterChip(Map("taken-from" -> "2024-06-01", "taken-to" -> "2024-06-30"), "Taken 1 Jun – 30 Jun 2024")

        htmlSearch(host, repoId, june).text() should include(cell(asset))

        for (
          (param, foreignId) <- scopes;
          id <- List(java.util.UUID.randomUUID.toString, foreignId);
          layout <- List(Const.Search.Layout.GRID, Const.Search.Layout.MAP)
        ) withClue(s"$param=$id $layout: ") {
          val response = htmlSearch(host, repoId, june + (param -> id) + (Api.Field.Search.LAYOUT -> layout))
          response.statusCode shouldBe 200
          val page = response.text()
          page should include("""data-results-total="0"""")
          filterChipsIn(page) shouldBe List(juneChip)
          (page should not).include("Eve")
        }

        val withNobody = Map(Api.Field.Search.PERSON_ID -> s"${alice.persistedId},${java.util.UUID.randomUUID}")
        val response = htmlSearch(host, repoId, withNobody)
        response.statusCode shouldBe 200
        response.text() should include("""data-results-total="0"""")
        filterChipsIn(response.text()) shouldBe List(FilterChip(Map("person-id" -> alice.persistedId), "Alice"))
    }
  }
}
