package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App

@DoNotDiscover class LocationControllerTests extends ControllerTestCore {
  test("Location list has camelCase fields, path order, category names and persisted counts") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the category "Italy", its Location "Alba" holding one imported asset, and the top-level
     * Location "Beach".
     *
     * Assertions:
     *
     * The Location list API answers flat camelCase JSON rows in path order, all with the same fields: a categorized Location
     * carries its category's ID and name, its pin and its asset count, a category has no pin, and a top-level Location has no
     * category.
     *
     * Edge cases:
     *
     * "Alba" is listed after its category "Italy", although it sorts first by name.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val category = testApp.service.location.addCategory("Italy")
        val child = testApp.service.location.addLocation("Alba", 44.7, 8.0, Some(category.persistedId))
        val root = testApp.service.location.addLocation("Beach", 1, 2)
        val asset = testContext.persistAsset()
        testApp.service.location.addAssets(child.persistedId, Set(asset.persistedId))
        val response = requests.get(s"$host/api/location/r/$repoId/list", cookies = testContext.cookies, check = false)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("application/json")
        val rows = ujson.read(response.text()).arr
        rows.map(_("id").str).toList shouldBe List(root.persistedId, category.persistedId, child.persistedId)
        rows.last.obj.keySet.toSet shouldBe Set(
          "id",
          "name",
          "kind",
          "categoryId",
          "categoryName",
          "latitude",
          "longitude",
          "numOfAssets")
        rows.last("categoryName").str shouldBe "Italy"
        rows.last("categoryId").str shouldBe category.persistedId
        rows.last("latitude").num shouldBe 44.7
        rows.last("longitude").num shouldBe 8.0
        rows.last("numOfAssets").num shouldBe 1
        rows.last("kind").str shouldBe "location"
        rows(1)("kind").str shouldBe "category"
        rows(1)("latitude") shouldBe ujson.Null
        rows.head("categoryId") shouldBe ujson.Null
    }
  }

  test("Location membership endpoints persist idempotent additions and removals") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the Location "Beach" and two imported assets.
     *
     * Assertions:
     *
     * The membership API adds both assets, then removes one, reporting the count it applied each time, and the Location's
     * membership follows.
     *
     * Edge cases:
     *
     * Adding the same assets again adds none.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val location = testApp.service.location.addLocation("Beach", 1, 2)
        val first = testContext.persistAsset()
        val second = testContext.persistAsset()
        val payload =
          ujson.Obj("locationId" -> location.persistedId, "assetIds" -> ujson.Arr(first.persistedId, second.persistedId))
        def add() = requests.put(
          s"$host/api/location/r/$repoId/assets",
          headers = Map("Content-Type" -> "application/json"),
          data = payload.toString,
          cookies = testContext.cookies,
          check = false
        )
        val added = add()
        added.statusCode shouldBe 200
        ujson.read(added.text())("added").num shouldBe 2
        ujson.read(add().text())("added").num shouldBe 0
        testApp.service.location.getAssetIds(location.persistedId) shouldBe Set(first.persistedId, second.persistedId)
        payload("assetIds") = ujson.Arr(first.persistedId)
        val removed = requests.delete(
          s"$host/api/location/r/$repoId/assets",
          headers = Map("Content-Type" -> "application/json"),
          data = payload.toString,
          cookies = testContext.cookies,
          check = false
        )
        removed.statusCode shouldBe 200
        ujson.read(removed.text())("removed").num shouldBe 1
        testApp.service.location.getAssetIds(location.persistedId) shouldBe Set(second.persistedId)
    }
  }

  test("Invalid membership payloads and category targets are JSON 400s; foreign Locations are 404s") {

    /**
     * Setup:
     *
     * A logged-in user's repository with the category "Category", the Location "Here" and one imported asset, and a second
     * repository holding the Location "Elsewhere".
     *
     * Assertions:
     *
     * An empty payload, asset IDs that are not an array and a category as the target are JSON 400s, the other repository's
     * Location is a JSON 404, and none of them adds anything.
     */
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()
    withServer(App) {
      host =>
        val category = testApp.service.location.addCategory("Category")
        val location = testApp.service.location.addLocation("Here", 1, 2)
        val asset = testContext.persistAsset()
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testApp.service.location.addLocation("Elsewhere", 3, 4)
        testApp.service.repository.switchContextToRepository(repo)
        val invalid = List(
          ujson.Obj(),
          ujson.Obj("locationId" -> location.persistedId, "assetIds" -> "not-an-array"),
          ujson.Obj("locationId" -> category.persistedId, "assetIds" -> ujson.Arr(asset.persistedId))
        )
        for (payload <- invalid) {
          withClue(s"$payload: ") {
            val response = requests.put(
              s"$host/api/location/r/$repoId/assets",
              headers = Map("Content-Type" -> "application/json"),
              data = payload.toString,
              cookies = testContext.cookies,
              check = false
            )
            response.statusCode shouldBe 400
            ujson.read(response.text())("error").str should not be empty
          }
        }
        val response = requests.put(
          s"$host/api/location/r/$repoId/assets",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.Obj("locationId" -> foreign.persistedId, "assetIds" -> ujson.Arr(asset.persistedId)).toString,
          cookies = testContext.cookies,
          check = false
        )
        response.statusCode shouldBe 404
        ujson.read(response.text())("error").str should not be empty
        testApp.service.location.getAssetIds(location.persistedId) shouldBe Set.empty
    }
  }

  test("Every Location API route requires authentication") {

    /**
     * Setup:
     *
     * A repository, and a client that has not logged in.
     *
     * Assertions:
     *
     * The list route and both membership routes answer 401.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    withServer(App) {
      host =>
        requests.get(s"$host/api/location/r/$repoId/list", check = false).statusCode shouldBe 401
        requests.put(s"$host/api/location/r/$repoId/assets", check = false).statusCode shouldBe 401
        requests.delete(s"$host/api/location/r/$repoId/assets", check = false).statusCode shouldBe 401
    }
  }
}
