package altitude.core.controller

import altitude.test.TestContext
import java.time.{ LocalDateTime, OffsetDateTime, ZoneId, ZoneOffset }
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.models.EditRecipe
import altitude.core.models.Person

@DoNotDiscover class MapControllerTests extends ControllerTestCore {
  test("Map cells and bounds share the grid scope and count plotted points") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the Location "Beach", in "Category" and pinned at 10, 20, holding two imported assets:
     * one with its own point at 11, 21, and one with no point, which the map plots at the Location's pin. A third asset, in no
     * Location, has its own point at -40, -50.
     *
     * Assertions:
     *
     * Scoped to the Location as the grid is, the bounds and the cells agree on the two assets, the cells counting plotted points
     * and listing the Location's pin with its member count. Panning clips cells and pins to the viewport, while a visible
     * Location still counts all of its members. The map follows the grid's scope - search text, recycling, the trash view - and
     * ignores the grid-only parameters the client sends with it.
     *
     * Edge cases:
     *
     * The panel's bbox filter, the sort, layout, grouping and paging parameters change nothing; search text that matches nothing
     * is a count with no bounds; a recycled asset leaves the library's bounds and is the trash view's only point.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val category = testApp.service.location.addCategory("Category")
        val location = testApp.service.location.addLocation("Beach", 10, 20, Some(category.persistedId))
        val ownPoint = testContext.persistAsset()
        testContext.setAssetCoordinates(ownPoint.persistedId, 11, 21)
        val fallback = testContext.persistAsset()
        testApp.service.location.addAssets(location.persistedId, Set(ownPoint.persistedId, fallback.persistedId))
        val outside = testContext.persistAsset()
        testContext.setAssetCoordinates(outside.persistedId, -40, -50)
        def get(endpoint: String, params: Map[String, String] = Map.empty) = {
          val response =
            requests.get(s"$host/api/map/r/$repoId/$endpoint", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.headers("content-type").head should include("application/json")
          ujson.read(response.text())
        }
        val scope = Map("locationId" -> location.persistedId, "sort" -> "filename0")
        val bounds = get("bounds", scope)
        bounds shouldBe ujson.Obj("south" -> 10, "west" -> 20, "north" -> 11, "east" -> 21, "count" -> 2)
        val world = Map("viewport" -> "-90,-180,90,180", "zoom" -> "10")
        val cells = get("cells", scope ++ world)
        cells("countsPlottedPoints").bool shouldBe true
        cells("cells").arr.map(_("assetId").str).toSet shouldBe Set(ownPoint.persistedId, fallback.persistedId)
        cells("cells").arr.map(_("count").num).sum shouldBe 2
        cells("locations").arr.head shouldBe ujson.Obj(
          "id" -> location.persistedId,
          "name" -> "Beach",
          "categoryName" -> "Category",
          "latitude" -> 10,
          "longitude" -> 20,
          "count" -> 2)
        get("cells", scope ++ Map("viewport" -> "10.5,20.5,12,22", "zoom" -> "10"))("cells").arr
          .map(_("assetId").str)
          .toList shouldBe List(ownPoint.persistedId)
        // Panning clips plotted cells and Location pins, but a visible Location still counts every matching member.
        val pinViewport = get("cells", scope ++ Map("viewport" -> "9,19,10.5,20.5", "zoom" -> "10"))
        pinViewport("cells").arr.map(_("assetId").str).toList shouldBe List(fallback.persistedId)
        pinViewport("locations").arr.head("count").num shouldBe 2
        // The client sends its search parameters verbatim: grid-only ones are ignored, and so is the panel's bbox filter
        val gridOnly = Map(
          "bbox" -> "-1,-1,1,1",
          "sort" -> "filename1",
          "layout" -> "map",
          "groupBy" -> "location",
          "groupDirection" -> "up",
          "rpp" -> "0",
          "p" -> "9",
          "after" -> "x",
          "isContinuousScroll" -> "true"
        )
        get("cells", scope ++ world ++ gridOnly) shouldBe cells
        get("bounds", scope ++ gridOnly) shouldBe bounds
        get("bounds", scope + ("q" -> "no-such-photo")) shouldBe ujson.Obj("count" -> 0)
        testApp.service.library.recycleAssets(Set(outside.persistedId))
        get("bounds")("count").num shouldBe 2
        get("bounds", Map("view" -> "trashbin")) shouldBe ujson.Obj(
          "south" -> -40,
          "west" -> -50,
          "north" -> -40,
          "east" -> -50,
          "count" -> 1)
    }
  }

  test("A map cell carries its asset's rendition revision, for the pin's Preview URL") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two imported assets far apart, each with a point: one with a step of its Edit, rendered
     * as revision 1.
     *
     * Assertions:
     *
     * The edited asset's cell carries `editRev` 1, the other's 0.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    val edited = testContext.persistAsset()
    testContext.setAssetCoordinates(edited.persistedId, 10, 20)
    val plain = testContext.persistAsset()
    testContext.setAssetCoordinates(plain.persistedId, -40, -50)
    testApp.service.assetEdit.addStep(edited.persistedId, 0, EditRecipe.Identity.copy(exposure = 1), testContext.user)
    testApp.service.editRender.render(edited.persistedId)

    withServer(App) {
      host =>
        val response = requests.get(
          s"$host/api/map/r/$repoId/cells",
          params = Map("viewport" -> "-90,-180,90,180", "zoom" -> "10"),
          cookies = testContext.cookies)
        ujson.read(response.text())("cells").arr.map(cell => cell("assetId").str -> cell("editRev").num.toInt).toMap shouldBe
          Map(edited.persistedId -> 1, plain.persistedId -> 0)
    }
  }

  test("The map follows the triaged filter and refuses its contradiction with the triage view") {

    /**
     * Setup:
     *
     * A logged-in user's repository with a sorted asset and a triaged asset, each plotted at a point of its own.
     *
     * Assertions:
     *
     * The bounds and the cells hold the sorted asset's point alone without the parameter and both points with `triaged=true`; the
     * triage view with `triaged=false` is a JSON 400 naming the view on both routes.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val sorted = testContext.persistAsset()
        testContext.setAssetCoordinates(sorted.persistedId, 10, 20)
        val triaged = testContext.persistAsset(isTriaged = true)
        testContext.setAssetCoordinates(triaged.persistedId, 30, 40)
        def get(endpoint: String, params: Map[String, String]) =
          requests.get(s"$host/api/map/r/$repoId/$endpoint", params = params, cookies = testContext.cookies, check = false)
        val world = Map("viewport" -> "-90,-180,90,180", "zoom" -> "10")

        ujson.read(get("bounds", Map.empty).text()) shouldBe ujson.Obj(
          "south" -> 10,
          "west" -> 20,
          "north" -> 10,
          "east" -> 20,
          "count" -> 1)
        ujson.read(get("cells", world).text())("cells").arr.map(_("assetId").str).toList shouldBe List(sorted.persistedId)

        ujson.read(get("bounds", Map("triaged" -> "true")).text())("count").num shouldBe 2
        ujson.read(get("cells", world + ("triaged" -> "true")).text())("cells").arr.map(_("assetId").str).toSet shouldBe
          Set(sorted.persistedId, triaged.persistedId)

        for (endpoint <- List("bounds", "cells")) withClue(s"$endpoint: ") {
          val response = get(endpoint, world ++ Map("view" -> "triage", "triaged" -> "false"))
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("application/json")
          ujson.read(response.text())("error").str should include("triage")
        }
    }
  }

  test("The map follows the subset, and an unknown subset is a JSON 400") {

    /**
     * Setup:
     *
     * A logged-in user's repository with two triaged assets, each plotted at a point of its own: one in the Location "Beach" and
     * one with a Face of an unnamed person.
     *
     * Assertions:
     *
     * In the triage view the bounds and the cells hold both points without a subset, the asset in no Location alone with
     * `subset=noLocation`, and the asset with the unknown person alone with `subset=unknownPeople`.
     *
     * Edge cases:
     *
     * A subset that is not one of the two is a JSON 400 on both routes.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val located = testContext.persistAsset(isTriaged = true)
        testContext.setAssetCoordinates(located.persistedId, 10, 20)
        val beach = testApp.service.location.addLocation("Beach", 10, 20)
        testApp.service.location.addAssets(beach.persistedId, Set(located.persistedId))
        val withUnknown = testContext.persistAsset(isTriaged = true)
        testContext.setAssetCoordinates(withUnknown.persistedId, 30, 40)
        testContext.addTestFace(testApp.service.person.addPerson(Person()), withUnknown, TestContext.unitVector(1))
        def get(endpoint: String, params: Map[String, String]) =
          requests.get(s"$host/api/map/r/$repoId/$endpoint", params = params, cookies = testContext.cookies, check = false)
        val triage = Map("view" -> "triage", "viewport" -> "-90,-180,90,180", "zoom" -> "10")
        def plotted(params: Map[String, String]): Set[String] =
          ujson.read(get("cells", triage ++ params).text())("cells").arr.map(_("assetId").str).toSet
        def count(params: Map[String, String]): Double = ujson.read(get("bounds", triage ++ params).text())("count").num

        plotted(Map.empty) shouldBe Set(located.persistedId, withUnknown.persistedId)
        count(Map.empty) shouldBe 2
        plotted(Map("subset" -> "noLocation")) shouldBe Set(withUnknown.persistedId)
        count(Map("subset" -> "noLocation")) shouldBe 1
        plotted(Map("subset" -> "unknownPeople")) shouldBe Set(withUnknown.persistedId)
        count(Map("subset" -> "unknownPeople")) shouldBe 1

        for (endpoint <- List("bounds", "cells")) withClue(s"$endpoint: ") {
          val response = get(endpoint, triage + ("subset" -> "everyone"))
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("application/json")
        }
    }
  }

  test("The map follows several people, several Locations and the date ranges, and refuses their malformed values") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the people Alice and Bob, the Locations Lisbon and Sintra, and four assets, each plotted
     * at a point of its own: one taken on June 1, 2024 with a Face of each person, in Lisbon; one taken on June 15 with Alice
     * alone, in Sintra; one taken on July 1 with Bob alone; and one with no capture time and nobody. All four are imported at one
     * moment.
     *
     * Assertions:
     *
     * The bounds count and the cells plot the asset with both people for `personId` naming the two, the three assets with either
     * with `peopleMatch=any`, the two assets in either Location for `locationId` naming the two, the two assets taken in June for
     * `takenFrom` and `takenTo`, and all four for the Date Imported range of the day they were imported, none for the day after.
     *
     * Edge cases:
     *
     * A malformed date, a range whose start is after its end, an unknown `peopleMatch`, eleven people in one parameter and a date
     * range with the trash view are JSON 400s on both routes.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val alice = testApp.service.person.addPerson(Person(name = Some("Alice")))
        val bob = testApp.service.person.addPerson(Person(name = Some("Bob")))
        val lisbon = testApp.service.location.addLocation("Lisbon", 1, 2)
        val sintra = testApp.service.location.addLocation("Sintra", 3, 4)
        val importedAt = OffsetDateTime.of(2026, 9, 6, 12, 0, 0, 0, ZoneOffset.UTC)
        val importDay = importedAt.atZoneSameInstant(ZoneId.systemDefault).toLocalDate
        def plottedAsset(latitude: Double, taken: Option[String], people: Person*): String = {
          val asset = testContext.persistAsset()
          testContext.setAssetCoordinates(asset.persistedId, latitude, 20)
          testContext.setAssetDates(asset.persistedId, taken.map(LocalDateTime.parse), importedAt)
          people.zipWithIndex.foreach(
            (person, index) => testContext.addTestFace(person, asset, TestContext.unitVector(index + 1)))
          asset.persistedId
        }
        val both = plottedAsset(10, Some("2024-06-01T10:00:00"), alice, bob)
        val aliceOnly = plottedAsset(11, Some("2024-06-15T10:00:00"), alice)
        val bobOnly = plottedAsset(12, Some("2024-07-01T10:00:00"), bob)
        val nobody = plottedAsset(13, None)
        testApp.service.location.addAssets(lisbon.persistedId, Set(both))
        testApp.service.location.addAssets(sintra.persistedId, Set(aliceOnly))

        def get(endpoint: String, params: Map[String, String]) =
          requests.get(s"$host/api/map/r/$repoId/$endpoint", params = params, cookies = testContext.cookies, check = false)
        val world = Map("viewport" -> "-90,-180,90,180", "zoom" -> "10")
        // What the cells plot, checked against what the bounds count
        def plotted(params: Map[String, String]): Set[String] = {
          val assets = ujson.read(get("cells", world ++ params).text())("cells").arr.map(_("assetId").str).toSet
          ujson.read(get("bounds", params).text())("count").num shouldBe assets.size
          assets
        }

        val people = Map("personId" -> s"${alice.persistedId},${bob.persistedId}")
        plotted(people) shouldBe Set(both)
        plotted(people + ("peopleMatch" -> "any")) shouldBe Set(both, aliceOnly, bobOnly)
        plotted(Map("locationId" -> s"${lisbon.persistedId},${sintra.persistedId}")) shouldBe Set(both, aliceOnly)
        plotted(Map("takenFrom" -> "2024-06-01", "takenTo" -> "2024-06-30")) shouldBe Set(both, aliceOnly)
        plotted(Map("importedFrom" -> importDay.toString, "importedTo" -> importDay.toString)) shouldBe
          Set(both, aliceOnly, bobOnly, nobody)
        plotted(Map("importedFrom" -> importDay.plusDays(1).toString)) shouldBe empty

        val refused = List(
          Map("takenFrom" -> "june"),
          Map("importedFrom" -> "2024-06-02", "importedTo" -> "2024-06-01"),
          Map("peopleMatch" -> "some"),
          Map("personId" -> List.fill(11)(UUID.randomUUID.toString).mkString(",")),
          Map("view" -> "trashbin", "takenTo" -> "2024-06-30")
        )
        for (params <- refused; endpoint <- List("bounds", "cells")) withClue(s"$endpoint $params: ") {
          val response = get(endpoint, world ++ params)
          response.statusCode shouldBe 400
          response.headers("content-type").head should include("application/json")
          ujson.read(response.text())("error").str should not be empty
        }
    }
  }

  test("Map input errors are JSON 400s and a disabled geocoder is a JSON 404") {

    /**
     * Setup:
     *
     * A logged-in user's repository with no assets, and the geocoder disabled, as the test configuration has it.
     *
     * Assertions:
     *
     * A malformed viewport or zoom on the cells route is a JSON 400 naming the bad parameter, search text in the trash is refused
     * on both map routes, and the disabled geocoder is a JSON 404.
     *
     * Edge cases:
     *
     * A missing parameter, a viewport whose south is above its north, NaN in the viewport and a fractional zoom; text with no
     * usable term, which counts as no text and is allowed in the trash; a malformed bbox search filter, which is ignored rather
     * than refused.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val valid = Map("viewport" -> "-90,-180,90,180", "zoom" -> "5")
        val invalid = List(
          valid - "viewport",
          valid - "zoom",
          valid + ("viewport" -> "x"),
          valid + ("viewport" -> "20,0,10,1"),
          valid + ("viewport" -> "NaN,0,10,1"),
          valid + ("zoom" -> "five"),
          valid + ("zoom" -> "1.5")
        )
        invalid.foreach {
          params =>
            withClue(s"$params: ") {
              val response =
                requests.get(s"$host/api/map/r/$repoId/cells", params = params, cookies = testContext.cookies, check = false)
              response.statusCode shouldBe 400
              response.headers("content-type").head should include("application/json")
              val error = ujson.read(response.text())("error").str
              if params.contains("zoom") && params.get("zoom") == valid.get("zoom") then error should include("viewport")
              else error should include("zoom")
            }
        }
        // Search text does not reach the trash, on either endpoint; text without a usable term is no text
        for (endpoint <- List("cells", "bounds")) withClue(s"$endpoint: ") {
          def inTrash(q: String) = requests.get(
            s"$host/api/map/r/$repoId/$endpoint",
            params = valid ++ Map("view" -> "trashbin", "q" -> q),
            cookies = testContext.cookies,
            check = false)
          val refused = inTrash("beach")
          refused.statusCode shouldBe 400
          refused.headers("content-type").head should include("application/json")
          ujson.read(refused.text())("error").str should include("trash")
          inTrash("- OR").statusCode shouldBe 200
        }
        // The bbox search filter is not the map's: a malformed one is ignored rather than refused
        val ignoredBounds = requests.get(
          s"$host/api/map/r/$repoId/bounds",
          params = Map("bbox" -> "bad"),
          cookies = testContext.cookies,
          check = false)
        ignoredBounds.statusCode shouldBe 200
        ujson.read(ignoredBounds.text()) shouldBe ujson.Obj("count" -> 0)
        val disabled = requests.get(
          s"$host/api/map/r/$repoId/geocode",
          params = Map("q" -> "Paris"),
          cookies = testContext.cookies,
          check = false)
        disabled.statusCode shouldBe 404
        ujson.read(disabled.text())("error").str should include("disabled")
    }
  }

  test("Every Map API route requires authentication") {

    /**
     * Setup:
     *
     * A repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The cells, bounds and geocode routes answer 401.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    withServer(App) {
      host =>
        List("cells", "bounds", "geocode").foreach {
          endpoint =>
            withClue(s"$endpoint: ") {
              requests.get(s"$host/api/map/r/$repoId/$endpoint", check = false).statusCode shouldBe 401
            }
        }
    }
  }
}
