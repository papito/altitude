package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App

@DoNotDiscover class LocationActionControllerTests extends ControllerTestCore {
  private val jsonHeaders = Map("Content-Type" -> "application/json")

  private def validation(response: requests.Response, form: String): Unit = {
    response.statusCode shouldBe 200
    response.headers("hx-retarget") shouldBe Seq("this")
    response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
    response.text() should include(s"""id="$form"""")
    response.text() should include("""class="error"""")
  }

  test("All Location dialogs render the appropriate kinds, values, and map configuration") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val parent = testApp.service.location.addParent("Italy")
        val location = testApp.service.location.addLocation("Beach", 1, 2, Some(parent.persistedId))
        val root = testApp.service.location.addLocation("Park", 3, 4)
        def get(path: String, params: Map[String, String] = Map.empty): String = {
          val response =
            requests.get(s"$host/htmx/location/r/$repoId/$path", params = params, cookies = testContext.cookies, check = false)
          response.statusCode shouldBe 200
          response.headers("content-type").head should include("text/html")
          response.text() should startWith("<!doctype html>")
          response.text()
        }
        get("tab") should include("""id="locationList"""")
        val add = get("dialogs/add-location")
        add should include("""id="addLocation"""")
        add should include("""data-app-fragment="modal"""")
        add should include("data-map-tile-url=")
        add should include("""data-geocoder-enabled="false"""")
        add should include(s"""value="${parent.persistedId}"""")
        (add should not).include(s"""value="${location.persistedId}"""")
        get("dialogs/add-parent") should include("""id="addParent"""")
        get("dialogs/rename-location", Map("id" -> location.persistedId)) should include("Beach")
        get("dialogs/rename-location", Map("id" -> parent.persistedId)) should include("Rename parent")
        get("dialogs/delete-location", Map("id" -> parent.persistedId)) should include("top level")
        get("dialogs/delete-location", Map("id" -> location.persistedId)) should include("Beach")
        val move = get("dialogs/move-location", Map("id" -> location.persistedId))
        move should include("""id="moveLocation"""")
        move should include(s"""value="${parent.persistedId}" selected""")
        (move should not).include(s"""value="${root.persistedId}"""")
        val membership = get("dialogs/add-to-location")
        membership should include("""id="addToLocation"""")
        membership should include("Italy - Beach")
        membership should include("Park")
        (membership should not).include(s"""value="${parent.persistedId}"""")
    }
  }

  test("Add Location validates decimals, optional fields and duplicate names with form replacement") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val parent = testApp.service.location.addParent("Italy")
        def post(json: ujson.Obj) = requests.post(
          s"$host/htmx/location/r/$repoId/add",
          headers = jsonHeaders,
          data = json.toString,
          cookies = testContext.cookies,
          check = false)
        def body() = ujson.Obj(
          "name" -> "  Beach ",
          "latitude" -> "1.25",
          "longitude" -> "-2.5",
          "parentId" -> parent.persistedId,
          "radiusM" -> "100")
        val created = post(body())
        created.statusCode shouldBe 200
        created.text() shouldBe ""
        val saved = testApp.service.location.getAll.find(_.name == "Beach").get
        saved.latitude shouldBe Some(1.25)
        saved.longitude shouldBe Some(-2.5)
        saved.parentId shouldBe Some(parent.persistedId)
        saved.radiusM shouldBe Some(100)
        validation(post(body()), "addLocation")
        for (
          (field, invalid) <- List(
            "name" -> " ",
            "latitude" -> "",
            "latitude" -> "north",
            "latitude" -> "91",
            "latitude" -> "NaN",
            "longitude" -> "-181",
            "longitude" -> "Infinity",
            "radiusM" -> "0",
            "radiusM" -> "1.5",
            "parentId" -> "not-a-uuid",
            "parentId" -> saved.persistedId
          )
        ) {
          val json = body()
          json("name") = "Other"
          json(field) = invalid
          val response = post(json)
          validation(response, "addLocation")
          if field != "name" then response.text() should include("Other")
        }
        val missing = body()
        missing.obj.remove("latitude")
        validation(post(missing), "addLocation")
        val numeric = post(ujson.Obj("name" -> "Root", "latitude" -> -90, "longitude" -> 180, "parentId" -> "", "radiusM" -> ""))
        numeric.statusCode shouldBe 200
        numeric.text() shouldBe ""
        val root = testApp.service.location.getAll.find(_.name == "Root").get
        root.parentId shouldBe None
        root.radiusM shouldBe None
        testApp.service.location.getAll.size shouldBe 3
    }
  }

  test("Parent add, rename, move, dialog membership and delete persist their changes") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        def postParent(name: String) = requests.post(
          s"$host/htmx/location/r/$repoId/add-parent",
          headers = jsonHeaders,
          data = ujson.Obj("name" -> name).toString,
          cookies = testContext.cookies,
          check = false)
        postParent(" Italy ").text() shouldBe ""
        val parent = testApp.service.location.getAll.head
        validation(postParent("italy"), "addParent")
        validation(postParent(" "), "addParent")
        val location = testApp.service.location.addLocation("Beach", 1, 2)
        def put(action: String, payload: ujson.Obj) = requests.put(
          s"$host/htmx/location/r/$repoId/$action",
          headers = jsonHeaders,
          data = payload.toString,
          cookies = testContext.cookies,
          check = false)
        validation(put("rename", ujson.Obj("id" -> location.persistedId, "name" -> "italy")), "renameLocation")
        validation(put("rename", ujson.Obj("id" -> location.persistedId, "name" -> "")), "renameLocation")
        put("rename", ujson.Obj("id" -> location.persistedId, "name" -> " Coast ")).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).name shouldBe "Coast"
        put("move", ujson.Obj("id" -> location.persistedId, "parentId" -> parent.persistedId)).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).parentId shouldBe Some(parent.persistedId)
        validation(put("move", ujson.Obj("id" -> location.persistedId, "parentId" -> location.persistedId)), "moveLocation")
        put("move", ujson.Obj("id" -> location.persistedId, "parentId" -> "")).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).parentId shouldBe None
        put("move", ujson.Obj("id" -> location.persistedId, "parentId" -> parent.persistedId)).statusCode shouldBe 200
        val first = testContext.persistAsset()
        val second = testContext.persistAsset()
        val membership = put(
          "assets",
          ujson.Obj("locationId" -> location.persistedId, "assetIds" -> s"${first.persistedId},${second.persistedId}"))
        membership.statusCode shouldBe 200
        membership.text() shouldBe ""
        testApp.service.location.getAssetIds(location.persistedId) shouldBe Set(first.persistedId, second.persistedId)
        validation(put("assets", ujson.Obj("locationId" -> parent.persistedId, "assetIds" -> first.persistedId)), "addToLocation")
        def delete(id: String) = requests.delete(
          s"$host/htmx/location/r/$repoId/",
          params = Map("id" -> id),
          cookies = testContext.cookies,
          check = false)
        delete(parent.persistedId).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).parentId shouldBe None
        delete(location.persistedId).statusCode shouldBe 200
        testApp.service.location.getAll shouldBe Nil
        testApp.service.asset.getById(first.persistedId).persistedId shouldBe first.persistedId
    }
  }

  test("Missing or malformed action IDs are 400s, and foreign Location dialogs and mutations are 404s") {
    val repo = testContext.persistRepository()
    val repoId = repo.persistedId
    login()
    withServer(App) {
      host =>
        val otherRepo = testContext.persistRepository()
        testApp.service.repository.switchContextToRepository(otherRepo)
        val foreign = testApp.service.location.addLocation("Elsewhere", 3, 4)
        testApp.service.repository.switchContextToRepository(repo)
        for (action <- List("rename", "move"); id <- List("", "bad-id")) {
          val response = requests.put(
            s"$host/htmx/location/r/$repoId/$action",
            headers = jsonHeaders,
            data = ujson.Obj("id" -> id, "name" -> "Changed").toString,
            cookies = testContext.cookies,
            check = false
          )
          response.statusCode shouldBe 400
        }
        for (dialog <- List("rename-location", "delete-location", "move-location")) {
          requests
            .get(
              s"$host/htmx/location/r/$repoId/dialogs/$dialog",
              params = Map("id" -> foreign.persistedId),
              cookies = testContext.cookies,
              check = false)
            .statusCode shouldBe 404
        }
        for (action <- List("rename", "move")) {
          requests
            .put(
              s"$host/htmx/location/r/$repoId/$action",
              headers = jsonHeaders,
              data = ujson.Obj("id" -> foreign.persistedId, "name" -> "Changed").toString,
              cookies = testContext.cookies,
              check = false
            )
            .statusCode shouldBe 404
        }
        requests
          .delete(
            s"$host/htmx/location/r/$repoId/",
            params = Map("id" -> foreign.persistedId),
            cookies = testContext.cookies,
            check = false)
          .statusCode shouldBe 404
        testApp.service.repository.switchContextToRepository(otherRepo)
        testApp.service.location.getById(foreign.persistedId).name shouldBe "Elsewhere"
    }
  }

  test("Location action routes require authentication") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    val headers = Map("Accept" -> "application/json")
    withServer(App) {
      host =>
        List(
          "tab",
          "dialogs/add-location",
          "dialogs/add-parent",
          "dialogs/rename-location",
          "dialogs/delete-location",
          "dialogs/move-location",
          "dialogs/add-to-location"
        ).foreach {
          path =>
            requests
              .get(s"$host/htmx/location/r/$repoId/$path", params = Map("id" -> repoId), headers = headers, check = false)
              .statusCode shouldBe 401
        }
        List("add", "add-parent").foreach {
          path => requests.post(s"$host/htmx/location/r/$repoId/$path", headers = headers, check = false).statusCode shouldBe 401
        }
        List("rename", "move", "assets").foreach {
          path => requests.put(s"$host/htmx/location/r/$repoId/$path", headers = headers, check = false).statusCode shouldBe 401
        }
        requests
          .delete(s"$host/htmx/location/r/$repoId/", params = Map("id" -> repoId), headers = headers, check = false)
          .statusCode shouldBe 401
    }
  }
}
