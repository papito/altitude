package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App

@DoNotDiscover class LocationControllerTests extends ControllerTestCore {
  test("Location list has camelCase fields, path order, parent names and persisted counts") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val parent = testApp.service.location.addParent("Italy")
        val child = testApp.service.location.addLocation("Alba", 44.7, 8.0, Some(parent.persistedId), Some(100))
        val root = testApp.service.location.addLocation("Beach", 1, 2)
        val asset = testContext.persistAsset()
        testApp.service.location.addAssets(child.persistedId, Set(asset.persistedId))
        val response = requests.get(s"$host/api/location/r/$repoId/list", cookies = testContext.cookies, check = false)
        response.statusCode shouldBe 200
        response.headers("content-type").head should include("application/json")
        val rows = ujson.read(response.text()).arr
        rows.map(_("id").str).toList shouldBe List(root.persistedId, parent.persistedId, child.persistedId)
        rows.last.obj.keySet.toSet shouldBe Set(
          "id",
          "name",
          "kind",
          "parentId",
          "parentName",
          "latitude",
          "longitude",
          "radiusM",
          "numOfAssets")
        rows.last("parentName").str shouldBe "Italy"
        rows.last("parentId").str shouldBe parent.persistedId
        rows.last("latitude").num shouldBe 44.7
        rows.last("longitude").num shouldBe 8.0
        rows.last("radiusM").num shouldBe 100
        rows.last("numOfAssets").num shouldBe 1
        rows.last("kind").str shouldBe "location"
        rows(1)("kind").str shouldBe "parent"
        rows(1)("latitude") shouldBe ujson.Null
        rows.head("parentId") shouldBe ujson.Null
    }
  }

  test("Location membership endpoints persist idempotent additions and removals") {
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

  test("Invalid membership payloads and parent targets are JSON 400s; foreign Locations are 404s") {
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()
    withServer(App) {
      host =>
        val parent = testApp.service.location.addParent("Parent")
        val location = testApp.service.location.addLocation("Here", 1, 2)
        val asset = testContext.persistAsset()
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testApp.service.location.addLocation("Elsewhere", 3, 4)
        testApp.service.repository.switchContextToRepository(repo)
        val invalid = List(
          ujson.Obj(),
          ujson.Obj("locationId" -> location.persistedId, "assetIds" -> "not-an-array"),
          ujson.Obj("locationId" -> parent.persistedId, "assetIds" -> ujson.Arr(asset.persistedId))
        )
        for (payload <- invalid) {
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
