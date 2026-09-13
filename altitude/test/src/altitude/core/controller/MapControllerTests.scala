package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App

@DoNotDiscover class MapControllerTests extends ControllerTestCore {
  test("Map cells and bounds share the grid scope and count plotted points") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val parent = testApp.service.location.addParent("Parent")
        val location = testApp.service.location.addLocation("Beach", 10, 20, Some(parent.persistedId))
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
        val cells = get("cells", scope ++ Map("bbox" -> "-90,-180,90,180", "zoom" -> "10"))
        cells("countsPlottedPoints").bool shouldBe true
        cells("cells").arr.map(_("assetId").str).toSet shouldBe Set(ownPoint.persistedId, fallback.persistedId)
        cells("cells").arr.map(_("count").num).sum shouldBe 2
        cells("locations").arr.head shouldBe ujson.Obj(
          "id" -> location.persistedId,
          "name" -> "Beach",
          "parentName" -> "Parent",
          "latitude" -> 10,
          "longitude" -> 20,
          "count" -> 2)
        get("cells", scope ++ Map("bbox" -> "10.5,20.5,12,22", "zoom" -> "10"))("cells").arr
          .map(_("assetId").str)
          .toList shouldBe List(ownPoint.persistedId)
        // Panning clips plotted cells and Location pins, but a visible Location still counts every matching member.
        val pinViewport = get("cells", scope ++ Map("bbox" -> "9,19,10.5,20.5", "zoom" -> "10"))
        pinViewport("cells").arr.map(_("assetId").str).toList shouldBe List(fallback.persistedId)
        pinViewport("locations").arr.head("count").num shouldBe 2
        get("bounds", scope + ("bbox" -> "-1,-1,1,1")) shouldBe ujson.Obj("count" -> 0)
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

  test("Map input errors are JSON 400s and a disabled geocoder is a JSON 404") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val valid = Map("bbox" -> "-90,-180,90,180", "zoom" -> "5")
        val invalid = List(
          valid - "bbox",
          valid - "zoom",
          valid + ("bbox" -> "x"),
          valid + ("bbox" -> "20,0,10,1"),
          valid + ("bbox" -> "NaN,0,10,1"),
          valid + ("zoom" -> "five"),
          valid + ("zoom" -> "1.5"))
        invalid.foreach {
          params =>
            val response =
              requests.get(s"$host/api/map/r/$repoId/cells", params = params, cookies = testContext.cookies, check = false)
            response.statusCode shouldBe 400
            response.headers("content-type").head should include("application/json")
            ujson.read(response.text())("error").str should not be empty
        }
        val badBounds = requests.get(
          s"$host/api/map/r/$repoId/bounds",
          params = Map("bbox" -> "bad"),
          cookies = testContext.cookies,
          check = false)
        badBounds.statusCode shouldBe 400
        ujson.read(badBounds.text())("error").str should include("bbox")
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
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    withServer(App) {
      host =>
        List("cells", "bounds", "geocode").foreach {
          endpoint => requests.get(s"$host/api/map/r/$repoId/$endpoint", check = false).statusCode shouldBe 401
        }
    }
  }
}
