package altitude.core.controller

import altitude.test.TestContext
import java.time.OffsetDateTime
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Person
import altitude.core.util.TriageSubset

/** The Triage routes: the Triage summary, the scope dialog of an explorer click in Triage, and Quick view */
@DoNotDiscover class TriageControllerTests extends ControllerTestCore {

  // Through the first repository, the one the login opens. Identity encoding because the client cannot read a compressed empty
  // body (the 204)
  private def get(host: String, path: String, params: Map[String, String] = Map.empty) =
    requests.get(
      s"$host/htmx/triage/r/${testContext.repositories.head.persistedId}/$path",
      params = params,
      headers = Map("Accept-Encoding" -> "identity"),
      cookies = testContext.cookies,
      check = false
    )

  private def summary(host: String) = get(host, "summary")
  private def scope(host: String, params: (String, String)*) = get(host, "dialogs/scope", params.toMap)
  private def quickView(host: String, folderId: String) = get(host, "modals/quick-view", Map("folderId" -> folderId))

  /** The text a reader sees: the markup dropped and the white space collapsed */
  private def plain(html: String): String =
    html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").replaceAll(" ([.,])", "$1").trim

  /** The text of the link carrying `attribute`, when there is one */
  private def linkText(html: String, attribute: String): Option[String] =
    s"""<a\\b[^>]*${java.util.regex.Pattern.quote(attribute)}[^>]*>([^<]*)</a>""".r
      .findFirstMatchIn(html)
      .map(_.group(1).trim)

  /** The assets whose Previews a page shows, in its order */
  private def previewsOf(html: String): List[String] =
    s"""<img\\b[^>]*src="/content/r/[^/"]+/${Const.DataStore.PREVIEW}/([^"]+)"""".r
      .findAllMatchIn(html)
      .map(_.group(1))
      .toList

  private def triagedWithFaceOf(person: Person): Asset = {
    val asset = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    testContext.addTestFace(person, asset, TestContext.unitVector(1))
    asset
  }

  private def triaged(): Asset = testContext.insertAsset(testContext.makeAsset(isTriaged = true))

  private def inFolder(folder: Folder): Asset = testContext.insertAsset(testContext.makeAsset(folder = Some(folder)))

  private val unknownPeopleSubset = s"""data-app-search-subset="${TriageSubset.UnknownPeople.apiValue}""""
  private val noLocationSubset = s"""data-app-search-subset="${TriageSubset.NoLocation.apiValue}""""
  private val allOfTriage = """data-app-search-person-id="""""

  test("The Triage summary counts the items in triage, those with Unknown people and those in no Location") {

    /**
     * Setup:
     *
     * A logged-in user's repository with three triaged assets: one with a Face of an unnamed person, one with a Face of that
     * person in the Location "Beach", and one with neither. Outside triage, a sorted asset with a Face of the unnamed person, and
     * a triaged asset with one recycled.
     *
     * Assertions:
     *
     * The summary reads "There are 3 items in triage. 2 have unknown people. 2 have no Locations." Each number is a search
     * trigger: 3 clears the scope back to all of Triage, 2 with unknown people searches the `unknownPeople` subset, and 2 with no
     * Locations the `noLocation` subset; each link has a name that says what it shows.
     *
     * Edge cases:
     *
     * The sorted asset and the recycled triaged one are counted nowhere.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val unknown = testApp.service.person.addPerson(Person())
        triagedWithFaceOf(unknown)
        val inBeach = triagedWithFaceOf(unknown)
        val beach = testApp.service.location.addLocation("Beach", 1, 2)
        testApp.service.location.addAssets(beach.persistedId, Set(inBeach.persistedId))
        triaged()
        val sorted = testContext.insertAsset()
        testContext.addTestFace(unknown, sorted, TestContext.unitVector(1))
        testApp.service.library.recycleAssets(Set(triagedWithFaceOf(unknown).persistedId))

        val response = summary(host)
        response.statusCode shouldBe 200
        val page = response.text()
        plain(page) shouldBe "There are 3 items in triage. 2 have unknown people. 2 have no Locations."
        page should include("""class="notice triage-summary"""")
        linkText(page, allOfTriage) shouldBe Some("3")
        linkText(page, unknownPeopleSubset) shouldBe Some("2")
        linkText(page, noLocationSubset) shouldBe Some("2")
        page.split("<a ").drop(1).foreach {
          link =>
            link should include("""data-app-search="click"""")
            link should include("aria-label=")
        }
        page should include("""aria-label="Show all 3 items in triage"""")
        page should include("""aria-label="Show the 2 items in triage with unknown people"""")
        page should include("""aria-label="Show the 2 items in triage with no Location"""")
    }
  }

  test("The Triage summary of one item is in the singular") {

    /**
     * Setup:
     *
     * A logged-in user's repository with one triaged asset, with a Face of an unnamed person and in no Location.
     *
     * Assertions:
     *
     * The summary reads "There is 1 item in triage. 1 has unknown people. 1 has no Location.", each number a search trigger.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        triagedWithFaceOf(testApp.service.person.addPerson(Person()))

        val page = summary(host).text()
        plain(page) shouldBe "There is 1 item in triage. 1 has unknown people. 1 has no Location."
        linkText(page, allOfTriage) shouldBe Some("1")
        linkText(page, unknownPeopleSubset) shouldBe Some("1")
        linkText(page, noLocationSubset) shouldBe Some("1")
        page should include("""aria-label="Show the 1 item in triage"""")
        page should include("""aria-label="Show the 1 item in triage with unknown people"""")
        page should include("""aria-label="Show the 1 item in triage with no Location"""")
    }
  }

  test("A count of zero in the Triage summary is plain text") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two triaged assets, each with a Face of a named person, both in the Location "Beach".
     *
     * Assertions:
     *
     * The summary reads "There are 2 items in triage. None have unknown people. All have a Location." The 2 is a search trigger,
     * and nothing searches a subset.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val named = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val beach = testApp.service.location.addLocation("Beach", 1, 2)
        testApp.service.location
          .addAssets(beach.persistedId, Set(triagedWithFaceOf(named).persistedId, triagedWithFaceOf(named).persistedId))

        val page = summary(host).text()
        plain(page) shouldBe "There are 2 items in triage. None have unknown people. All have a Location."
        linkText(page, allOfTriage) shouldBe Some("2")
        (page should not).include("data-app-search-subset")
    }
  }

  test("An empty triage is one sentence with no link") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a sorted asset with a Face of an unnamed person, in no Location, and nothing in triage.
     *
     * Assertions:
     *
     * The summary reads "There are no items in triage." and nothing else, with no link.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val sorted = testContext.insertAsset()
        testContext.addTestFace(testApp.service.person.addPerson(Person()), sorted, TestContext.unitVector(1))

        val page = summary(host).text()
        plain(page) shouldBe "There are no items in triage."
        (page should not).include("<a ")
    }
  }

  test("A Person's scope dialog is a 204 with triaged assets, and otherwise offers the Person in Browse") {

    /**
     * Setup:
     *
     * A logged-in user's repository with Bob, who has a Face on a triaged asset, and Alice, who has a Face on a sorted asset
     * only.
     *
     * Assertions:
     *
     * Bob's scope dialog is a 204 with no body: the click searches Triage. Alice's is a popover dialog fragment titled with her
     * name, reading "This person has no assets assigned in Triage", with "Show person in new tab", a link opening Browse scoped
     * to her in a new tab, and Close.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val repoId = testContext.repository.persistedId
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        triagedWithFaceOf(bob)
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        testContext.addTestFace(alice, testContext.insertAsset(), TestContext.unitVector(2))

        val searches = scope(host, Api.Field.Search.PERSON_ID -> bob.persistedId)
        searches.statusCode shouldBe 204
        searches.text() shouldBe ""

        val response = scope(host, Api.Field.Search.PERSON_ID -> alice.persistedId)
        response.statusCode shouldBe 200
        val page = response.text()
        page should include("""data-app-fragment="modal"""")
        page should include("""data-app-modal-title="Alice"""")
        plain(page) should include("This person has no assets assigned in Triage")
        page should include(s"""href="/r/$repoId?personId=${alice.persistedId}" target="_blank" rel="noopener"""")
        linkText(page, "target=\"_blank\"") shouldBe Some("Show person in new tab")
        plain(page) should include("Close")
        (plain(page) should not).include("Quick view")
    }
  }

  test("A Location's scope dialog is a 204 with triaged assets, offers Browse with sorted ones, and Close alone without") {

    /**
     * Setup:
     *
     * A logged-in user's repository with three Locations: Beach holding a triaged asset, Park holding a sorted asset, and Lake
     * holding nothing.
     *
     * Assertions:
     *
     * Beach's scope dialog is a 204 with no body. Park's reads "This Location has no assets in Triage", with "Show Location in
     * new tab", a link opening Browse scoped to it in a new tab, and Close.
     *
     * Edge cases:
     *
     * Lake, empty everywhere, reads "This Location has no assets" and offers Close alone, with no link. A Category, which holds
     * no assets, is a 400.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val repoId = testContext.repository.persistedId
        val locations = testApp.service.location
        val beach = locations.addLocation("Beach", 1, 2)
        locations.addAssets(beach.persistedId, Set(triaged().persistedId))
        val park = locations.addLocation("Park", 3, 4)
        locations.addAssets(park.persistedId, Set(testContext.insertAsset().persistedId))
        val lake = locations.addLocation("Lake", 5, 6)

        val searches = scope(host, Api.Field.Search.LOCATION_ID -> beach.persistedId)
        searches.statusCode shouldBe 204
        searches.text() shouldBe ""

        val parkPage = scope(host, Api.Field.Search.LOCATION_ID -> park.persistedId).text()
        parkPage should include("""data-app-modal-title="Park"""")
        plain(parkPage) should include("This Location has no assets in Triage")
        parkPage should include(s"""href="/r/$repoId?locationId=${park.persistedId}" target="_blank" rel="noopener"""")
        linkText(parkPage, "target=\"_blank\"") shouldBe Some("Show Location in new tab")
        plain(parkPage) should include("Close")

        val lakeResponse = scope(host, Api.Field.Search.LOCATION_ID -> lake.persistedId)
        lakeResponse.statusCode shouldBe 200
        val lakePage = lakeResponse.text()
        plain(lakePage) should include("This Location has no assets")
        (plain(lakePage) should not).include("in Triage")
        (lakePage should not).include("<a ")
        plain(lakePage) should include("Close")

        val italy = locations.addCategory("Italy")
        scope(host, Api.Field.Search.LOCATION_ID -> italy.persistedId).statusCode shouldBe 400
    }
  }

  test("A folder's scope dialog counts its sorted assets and those of its subfolders, and offers Browse and Quick view") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Trips and its subfolder Italy: two sorted assets in Trips and one in Italy, a
     * recycled asset in Trips, and a triaged asset the fixture puts in Trips (the import never does). An empty folder, Empty.
     *
     * Assertions:
     *
     * Trips' scope dialog is a popover dialog fragment titled "Trips", reading "This folder has 3 results in the repository",
     * with "Show in new tab", a link opening Browse scoped to the folder in a new tab, "Quick view", a button that loads Quick
     * view into the general modal host, and Close.
     *
     * Edge cases:
     *
     * The recycled and the triaged assets are not counted. Empty reads "This folder has no assets" and offers Close alone. The
     * root folder is a 400, and so is a request naming no scope or two.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val repoId = testContext.repository.persistedId
        val trips = testApp.service.folder.add("Trips")
        val italy = testApp.service.folder.add("Italy", Some(trips.persistedId))
        val empty = testApp.service.folder.add("Empty")
        inFolder(trips)
        inFolder(trips)
        inFolder(italy)
        testApp.service.library.recycleAssets(Set(inFolder(trips).persistedId))
        testContext.insertAsset(testContext.makeAsset(folder = Some(trips), isTriaged = true))

        val response = scope(host, Api.Field.Search.FOLDER_ID -> trips.persistedId)
        response.statusCode shouldBe 200
        val page = response.text()
        page should include("""data-app-fragment="modal"""")
        page should include("""data-app-modal-title="Trips"""")
        plain(page) should include("This folder has 3 results in the repository")
        page should include(s"""href="/r/$repoId?folderId=${trips.persistedId}" target="_blank" rel="noopener"""")
        linkText(page, "target=\"_blank\"") shouldBe Some("Show in new tab")
        page should include(s"""hx-get="/htmx/triage/r/$repoId/modals/quick-view"""")
        page should include(s"""hx-vals='{"folderId": "${trips.persistedId}"}'""")
        page should include("""hx-target="#modalContent"""")
        plain(page) should include("Quick view")
        plain(page) should include("Close")

        val emptyPage = scope(host, Api.Field.Search.FOLDER_ID -> empty.persistedId).text()
        plain(emptyPage) should include("This folder has no assets")
        (emptyPage should not).include("<a ")
        (emptyPage should not).include("hx-get")
        plain(emptyPage) should include("Close")

        scope(host, Api.Field.Search.FOLDER_ID -> testContext.repository.rootFolderId).statusCode shouldBe 400
        scope(host).statusCode shouldBe 400
        scope(
          host,
          Api.Field.Search.FOLDER_ID -> trips.persistedId,
          Api.Field.Search.LOCATION_ID -> testApp.service.location.addLocation("Lake", 5, 6).persistedId).statusCode shouldBe 400
    }
  }

  test("Quick view shows a folder's first 100 assets by newest import under its path, read-only") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the folder Trips, its subfolder Italy and Italy's subfolder Rome: 100 sorted assets in
     * Italy, the oldest import of them dated 2020, and one in Rome imported last, in 2030, so Italy holds 101. Rome is also asked
     * for alone.
     *
     * Assertions:
     *
     * Italy's Quick view is a general modal fragment titled "Quick view", headed by the path Trips › Italy as plain crumbs, with
     * the Previews of 100 assets, newest import first: Rome's first, the 2020 one left out. The footer reads "Showing 100 of 101"
     * with "Show in new tab", a link opening Browse scoped to Italy in a new tab.
     *
     * Edge cases:
     *
     * Nothing in it is a search trigger, opens Asset detail, or carries the hooks of the grid's selection and drag. Rome's Quick
     * view of one asset has no footer. The root folder is a 400.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val repoId = testContext.repository.persistedId
        val trips = testApp.service.folder.add("Trips")
        val italy = testApp.service.folder.add("Italy", Some(trips.persistedId))
        val rome = testApp.service.folder.add("Rome", Some(italy.persistedId))
        val inItaly = (1 to 100).map(_ => inFolder(italy))
        val oldest = inItaly.head
        testContext.setAssetDates(oldest.persistedId, None, OffsetDateTime.parse("2020-01-01T00:00:00Z"))
        val newest = inFolder(rome)
        testContext.setAssetDates(newest.persistedId, None, OffsetDateTime.parse("2030-01-01T00:00:00Z"))

        val response = quickView(host, italy.persistedId)
        response.statusCode shouldBe 200
        val page = response.text()
        page should include("""data-app-fragment="modal"""")
        page should include("""data-app-modal-title="Quick view"""")
        page should include(
          """<span class="crumb">Trips</span><i class="fas fa-chevron-right sep" aria-hidden="true"></i><span class="leaf">Italy</span>""")
        val shown = previewsOf(page)
        shown.size shouldBe 100
        shown.head shouldBe newest.persistedId
        shown.toSet shouldBe (inItaly.tail.map(_.persistedId).toSet + newest.persistedId)
        (page should not).include(oldest.persistedId)
        plain(page) should include("Showing 100 of 101")
        page should include(s"""href="/r/$repoId?folderId=${italy.persistedId}" target="_blank" rel="noopener"""")
        linkText(page, "target=\"_blank\"") shouldBe Some("Show in new tab")
        for (hook <- List("data-app-search", "hx-get", "drag-drop", "data-asset-id", "checkmark", "class=\"cell"))
          withClue(s"$hook: ")((page should not).include(hook))

        val romePage = quickView(host, rome.persistedId).text()
        previewsOf(romePage) shouldBe List(newest.persistedId)
        (plain(romePage) should not).include("Showing")
        (romePage should not).include("<a ")

        quickView(host, testContext.repository.rootFolderId).statusCode shouldBe 400
    }
  }

  test("Every Triage route is a 404 for an unknown ID and for another repository's") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and a second repository of theirs with the folder "Eve's folder", the Person Eve and the
     * Location "Eve's place".
     *
     * Assertions:
     *
     * Asked through the first repository, the scope dialog for a folder, a Person or a Location, and Quick view, answer 404 for
     * an ID that does not exist and for each of the second repository's, and no answer carries the other repository's names.
     */
    val repo = testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val folder = testApp.service.folder.add("Eve's folder")
        val eve = testApp.service.person.addPerson(Person(name = Some("Eve")))
        val place = testApp.service.location.addLocation("Eve's place", 1, 2)
        testApp.service.repository.switchContextToRepository(repo)
        val unknownId = java.util.UUID.randomUUID.toString

        for (
          (param, foreignId) <- List(
            Api.Field.Search.FOLDER_ID -> folder.persistedId,
            Api.Field.Search.PERSON_ID -> eve.persistedId,
            Api.Field.Search.LOCATION_ID -> place.persistedId)
        ) withClue(s"$param: ") {
          scope(host, param -> unknownId).statusCode shouldBe 404
          val foreign = scope(host, param -> foreignId)
          foreign.statusCode shouldBe 404
          (foreign.text() should not).include("Eve")
        }

        quickView(host, unknownId).statusCode shouldBe 404
        val foreign = quickView(host, folder.persistedId)
        foreign.statusCode shouldBe 404
        (foreign.text() should not).include("Eve")
    }
  }

  test("A Person's scope dialog is a 404 for a Person merged away and for a bad match") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a Person named "Bob" merged into a Person named "Tessa", and a Person named "Carl"
     * marked as a bad match, each of the three with a Face on a sorted asset.
     *
     * Assertions:
     *
     * The scope dialog asked for with Bob's ID, and with Carl's, answers 404 and no dialog naming them, while Tessa's is her
     * dialog.
     */
    testContext.persistRepository()
    login()
    withServer(App) {
      host =>
        val tessa = testApp.service.person.addPerson(Person(name = Some("Tessa")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val carl = testApp.service.person.addPerson(Person(name = Some("Carl")))
        List(tessa, bob, carl).foreach {
          person => testContext.addTestFace(person, testContext.insertAsset(), TestContext.unitVector(1))
        }
        testApp.service.person.merge(dest = tessa, source = bob)
        testApp.service.person.markAsBadMatch(carl)

        for ((person, name) <- List(bob -> "Bob", carl -> "Carl")) withClue(s"$name: ") {
          val response = scope(host, Api.Field.Search.PERSON_ID -> person.persistedId)
          response.statusCode shouldBe 404
          (response.text() should not).include(name)
        }

        val live = scope(host, Api.Field.Search.PERSON_ID -> tessa.persistedId)
        live.statusCode shouldBe 200
        live.text() should include("Tessa")
    }
  }
}
