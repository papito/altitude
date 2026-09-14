package altitude.core.controller

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Api
import altitude.core.App
import altitude.core.Const

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
        val category = testApp.service.location.addCategory("Italy")
        val location = testApp.service.location.addLocation("Beach", 1, 2, Some(category.persistedId))
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
        add should include(s"""data-app-modal-title="${Const.UI.ADD_LOCATION_DIALOG_TITLE}"""")
        add should include("data-map-tile-url=")
        add should include("""data-geocoder-enabled="false"""")
        // The pin is placed on the map: the coordinates travel in hidden inputs, with a read-only readout
        add should include("""type="hidden" id="field-latitude" name="latitude"""")
        add should include("""type="hidden" id="field-longitude" name="longitude"""")
        add should include("""id="locationPinReadout"""")
        add should include("""id="locationEditorMap"""")
        add should include(s"""value="${category.persistedId}"""")
        (add should not).include(s"""value="${location.persistedId}"""")
        get("dialogs/add-category") should include("""id="addCategory"""")
        val rename = get("dialogs/rename-location", Map("id" -> location.persistedId))
        rename should include("Beach")
        rename should include(Const.UI.RENAME_LOCATION_DIALOG_TITLE)
        get("dialogs/rename-location", Map("id" -> category.persistedId)) should include(Const.UI.RENAME_CATEGORY_DIALOG_TITLE)
        val deleteCategory = get("dialogs/delete-location", Map("id" -> category.persistedId))
        deleteCategory should include("top level")
        deleteCategory should include(Const.UI.DELETE_CATEGORY_DIALOG_TITLE)
        val deleteLocation = get("dialogs/delete-location", Map("id" -> location.persistedId))
        deleteLocation should include("Beach")
        deleteLocation should include(Const.UI.DELETE_LOCATION_DIALOG_TITLE)
        val move = get("dialogs/move-location", Map("id" -> location.persistedId))
        move should include("""id="moveLocation"""")
        move should include(Const.UI.MOVE_LOCATION_DIALOG_TITLE)
        move should include("Beach")
        move should include(s"""value="${category.persistedId}" selected""")
        (move should not).include(s"""value="${root.persistedId}"""")
        val membership = get("dialogs/add-to-location")
        membership should include("""id="addToLocation"""")
        membership should include(s"""data-app-modal-title="${Const.UI.ADD_TO_LOCATION_DIALOG_TITLE}"""")
        membership should include("Italy › Beach")
        membership should include("Park")
        (membership should not).include(s"""value="${category.persistedId}"""")
    }
  }

  test("Add Location validates decimals, optional fields and duplicate names with form replacement") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        val category = testApp.service.location.addCategory("Italy")
        def post(json: ujson.Obj) = requests.post(
          s"$host/htmx/location/r/$repoId/add",
          headers = jsonHeaders,
          data = json.toString,
          cookies = testContext.cookies,
          check = false)
        def body() =
          ujson.Obj("name" -> "  Beach ", "latitude" -> "1.25", "longitude" -> "-2.5", "categoryId" -> category.persistedId)
        val created = post(body())
        created.statusCode shouldBe 200
        created.text() shouldBe ""
        val saved = testApp.service.location.getAll.find(_.name == "Beach").get
        saved.latitude shouldBe Some(1.25)
        saved.longitude shouldBe Some(-2.5)
        saved.categoryId shouldBe Some(category.persistedId)
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
            "categoryId" -> "not-a-uuid",
            "categoryId" -> saved.persistedId
          )
        ) {
          withClue(s"$field=$invalid: ") {
            val json = body()
            json("name") = "Other"
            json(field) = invalid
            val response = post(json)
            validation(response, "addLocation")
            if field != "name" then response.text() should include("Other")
          }
        }
        val missing = body()
        missing.obj.remove("latitude")
        val pinless = post(missing)
        validation(pinless, "addLocation")
        pinless.text() should include(Const.Msg.Err.PIN_REQUIRED)
        pinless.text() should include("Beach")
        val numeric =
          post(ujson.Obj("name" -> "Root", "latitude" -> -90, "longitude" -> 180, "categoryId" -> ""))
        numeric.statusCode shouldBe 200
        numeric.text() shouldBe ""
        val root = testApp.service.location.getAll.find(_.name == "Root").get
        root.categoryId shouldBe None
        testApp.service.location.getAll.size shouldBe 3
    }
  }

  test("Category add, rename, move, dialog membership and delete persist their changes") {
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    withServer(App) {
      host =>
        def postCategory(name: String) = requests.post(
          s"$host/htmx/location/r/$repoId/add-category",
          headers = jsonHeaders,
          data = ujson.Obj("name" -> name).toString,
          cookies = testContext.cookies,
          check = false)
        postCategory(" Italy ").text() shouldBe ""
        val category = testApp.service.location.getAll.head
        validation(postCategory("italy"), "addCategory")
        validation(postCategory(" "), "addCategory")
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
        put("move", ujson.Obj("id" -> location.persistedId, "categoryId" -> category.persistedId)).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).categoryId shouldBe Some(category.persistedId)
        validation(put("move", ujson.Obj("id" -> location.persistedId, "categoryId" -> location.persistedId)), "moveLocation")
        put("move", ujson.Obj("id" -> location.persistedId, "categoryId" -> "")).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).categoryId shouldBe None
        put("move", ujson.Obj("id" -> location.persistedId, "categoryId" -> category.persistedId)).statusCode shouldBe 200
        val first = testContext.persistAsset()
        val second = testContext.persistAsset()
        val membership = put(
          "assets",
          ujson.Obj("locationId" -> location.persistedId, "assetIds" -> s"${first.persistedId},${second.persistedId}"))
        membership.statusCode shouldBe 200
        membership.text() shouldBe ""
        testApp.service.location.getAssetIds(location.persistedId) shouldBe Set(first.persistedId, second.persistedId)
        // The dialog reports what the server applied, not the size of the selection: a repeat adds nothing
        def added(response: requests.Response): Int =
          ujson.read(response.headers(Api.Field.SUCCESS_DETAIL_HEADER.toLowerCase).head)("added").num.toInt
        added(membership) shouldBe 2
        val repeat = put("assets", ujson.Obj("locationId" -> location.persistedId, "assetIds" -> first.persistedId))
        repeat.statusCode shouldBe 200
        added(repeat) shouldBe 0
        validation(
          put("assets", ujson.Obj("locationId" -> category.persistedId, "assetIds" -> first.persistedId)),
          "addToLocation")
        def delete(id: String) = requests.delete(
          s"$host/htmx/location/r/$repoId/",
          params = Map("id" -> id),
          cookies = testContext.cookies,
          check = false)
        delete(category.persistedId).statusCode shouldBe 200
        testApp.service.location.getById(location.persistedId).categoryId shouldBe None
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
          withClue(s"$action with id '$id': ") {
            val response = requests.put(
              s"$host/htmx/location/r/$repoId/$action",
              headers = jsonHeaders,
              data = ujson.Obj("id" -> id, "name" -> "Changed").toString,
              cookies = testContext.cookies,
              check = false
            )
            response.statusCode shouldBe 400
          }
        }
        for (dialog <- List("rename-location", "delete-location", "move-location")) {
          withClue(s"$dialog: ") {
            requests
              .get(
                s"$host/htmx/location/r/$repoId/dialogs/$dialog",
                params = Map("id" -> foreign.persistedId),
                cookies = testContext.cookies,
                check = false)
              .statusCode shouldBe 404
          }
        }
        for (action <- List("rename", "move")) {
          withClue(s"$action: ") {
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
          "dialogs/add-category",
          "dialogs/rename-location",
          "dialogs/delete-location",
          "dialogs/move-location",
          "dialogs/add-to-location"
        ).foreach {
          path =>
            withClue(s"GET $path: ") {
              requests
                .get(s"$host/htmx/location/r/$repoId/$path", params = Map("id" -> repoId), headers = headers, check = false)
                .statusCode shouldBe 401
            }
        }
        List("add", "add-category").foreach {
          path =>
            withClue(s"POST $path: ") {
              requests.post(s"$host/htmx/location/r/$repoId/$path", headers = headers, check = false).statusCode shouldBe 401
            }
        }
        List("rename", "move", "assets").foreach {
          path =>
            withClue(s"PUT $path: ") {
              requests.put(s"$host/htmx/location/r/$repoId/$path", headers = headers, check = false).statusCode shouldBe 401
            }
        }
        requests
          .delete(s"$host/htmx/location/r/$repoId/", params = Map("id" -> repoId), headers = headers, check = false)
          .statusCode shouldBe 401
    }
  }
}
