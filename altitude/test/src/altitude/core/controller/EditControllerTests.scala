package altitude.core.controller

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.net.HttpCookie
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.AssetWithData
import altitude.core.models.EditRecipe
import altitude.core.models.Permission
import altitude.core.models.Permissions

/** The Edit's JSON API: an asset's Edit, a save, the render status and auto-straighten */
@DoNotDiscover class EditControllerTests extends ControllerTestCore {

  private val brighter = EditRecipe.Identity.copy(exposure = 0.4)

  /** The Edit's JSON with one key set, which may be one the Edit does not have */
  private def brighterWith(change: (String, ujson.Value)): ujson.Obj = {
    val recipe = brighter.toJson
    recipe(change._1) = change._2
    recipe
  }

  private def editUrl(host: String, repoId: String, assetId: String, path: String = ""): String =
    s"$host/api/edit/r/$repoId/asset/$assetId$path"

  private def get(url: String, cookies: Map[String, HttpCookie] = testContext.cookies): requests.Response =
    requests.get(url, cookies = cookies, check = false)

  private def send(
      method: String,
      url: String,
      body: ujson.Value,
      cookies: Map[String, HttpCookie] = testContext.cookies): requests.Response =
    requests.send(method)(
      url,
      headers = Map("Content-Type" -> "application/json"),
      data = ujson.write(body),
      cookies = cookies,
      check = false)

  private def save(host: String, repoId: String, assetId: String, base: Int, recipe: ujson.Value): requests.Response =
    send("PUT", editUrl(host, repoId, assetId), ujson.Obj("base" -> base, "recipe" -> recipe))

  private def autoStraightenUrl(host: String, repoId: String, assetId: String, recipe: ujson.Value): String =
    editUrl(host, repoId, assetId, "/auto-straighten?recipe=") + URLEncoder.encode(ujson.write(recipe), StandardCharsets.UTF_8)

  private def json(response: requests.Response): ujson.Value = {
    response.headers("content-type").head should include("application/json")
    ujson.read(response.text())
  }

  test("The Edit's routes answer its state, a save and the render status") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, and an imported 150 × 150 still image, never edited.
     *
     * Assertions:
     *
     * The state of the image is the original's, with exactly the state's fields: version 0, not rendered, revision 0, not edited,
     * no edited size, the size of its upright original and the identity Edit with every key. A save of Exposure +0.4 built on
     * version 0 answers the new state: version 1, edited, the image's size as its edited and its upright size, and the Edit as
     * sent. Saving the identity built on version 1 answers version 2, not edited, and the status says version 2 and revision 0,
     * not rendered (or rendered as the original, should the server's render have run by then).
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val assetId = testContext.persistAsset().persistedId

    withServer(App) {
      host =>
        val original = get(editUrl(host, repoId, assetId))
        original.statusCode shouldBe 200
        val state = json(original)
        state.obj.keySet shouldBe Set(
          "editVersion",
          "renderedVersion",
          "rev",
          "edited",
          "width",
          "height",
          "uprightWidth",
          "uprightHeight",
          "recipe")
        state("editVersion").num shouldBe 0
        state("renderedVersion") shouldBe ujson.Null
        state("rev").num shouldBe 0
        state("edited").bool shouldBe false
        state("width") shouldBe ujson.Null
        state("height") shouldBe ujson.Null
        (state("uprightWidth").num.toInt, state("uprightHeight").num.toInt) shouldBe (150, 150)
        state("recipe") shouldBe EditRecipe.Identity.toJson

        val saved = save(host, repoId, assetId, base = 0, recipe = brighter.toJson)
        saved.statusCode shouldBe 200
        val afterSave = json(saved)
        afterSave("editVersion").num shouldBe 1
        afterSave("edited").bool shouldBe true
        (afterSave("width").num.toInt, afterSave("height").num.toInt) shouldBe (150, 150)
        (afterSave("uprightWidth").num.toInt, afterSave("uprightHeight").num.toInt) shouldBe (150, 150)
        afterSave("recipe") shouldBe brighter.toJson

        val reverted = save(host, repoId, assetId, base = 1, recipe = EditRecipe.Identity.toJson)
        reverted.statusCode shouldBe 200
        json(reverted)("editVersion").num shouldBe 2
        json(reverted)("edited").bool shouldBe false

        val status = get(editUrl(host, repoId, assetId, "/status"))
        status.statusCode shouldBe 200
        // The server renders the identity 1.5 s after the save, which a slow run may reach
        val answer = json(status)
        answer.obj.keySet shouldBe Set("editVersion", "renderedVersion", "rev")
        (answer("editVersion"), answer("rev")) shouldBe (ujson.Num(2), ujson.Num(0))
        List(ujson.Null, ujson.Num(2)) should contain(answer("renderedVersion"))
    }
  }

  test("The Edit's state gives the size of the upright original, turned by its EXIF orientation") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, and an imported image stored 640 × 724 under EXIF orientation 6, so its upright
     * original is 724 × 640.
     *
     * Assertions:
     *
     * The state of the image never edited, and the state a save turning it a quarter answers, give the upright size 724 × 640;
     * the save's edited size is the frame's, 640 × 724.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val asset = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(IntegrationTestUtil.getImportAsset("images/exif/orientation-6.jpg")))
    val assetId = asset.persistedId

    withServer(App) {
      host =>
        def upright(state: ujson.Value): (Int, Int) = (state("uprightWidth").num.toInt, state("uprightHeight").num.toInt)

        upright(json(get(editUrl(host, repoId, assetId)))) shouldBe (724, 640)

        val turned = json(save(host, repoId, assetId, base = 0, recipe = brighter.copy(rotate = 90).toJson))
        upright(turned) shouldBe (724, 640)
        (turned("width").num.toInt, turned("height").num.toInt) shouldBe (640, 724)
    }
  }

  test("A save built on a stale version is a 409 carrying the current state") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, and an imported image saved once.
     *
     * Assertions:
     *
     * A save built on version 0 is answered 409 with an error and the current state (version 1, its Edit). The Edit is still at
     * version 1, with its Edit.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val assetId = testContext.persistAsset().persistedId
    testApp.service.assetEdit.save(assetId, 0, brighter, testContext.user)

    withServer(App) {
      host =>
        val response = save(host, repoId, assetId, base = 0, recipe = brighter.copy(contrast = 10).toJson)
        response.statusCode shouldBe 409
        val body = json(response)
        body("error").str should not be empty
        body("state")("editVersion").num shouldBe 1
        body("state")("recipe") shouldBe brighter.toJson

        val stored = testApp.service.assetEdit.get(assetId)
        (stored.editVersion, stored.recipe) shouldBe (1, brighter)
    }
  }

  test("A refused Edit, a body that is not one and an asset that cannot be edited are refused") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, an imported image and an imported Video.
     *
     * Assertions:
     *
     * A save whose Edit has an unknown key, one with a value out of range, one with no `base`, one whose `base` is not a whole
     * number, one with no `recipe` and one whose body is not JSON are each a 400 with an error, as is a save for the Video.
     * Nothing is stored for either asset.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val assetId = testContext.persistAsset().persistedId
    val video = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(
        IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.black(1))).toFile)))

    withServer(App) {
      host =>
        val url = editUrl(host, repoId, assetId)
        val refusals = List(
          save(host, repoId, assetId, 0, brighterWith("dehaze" -> 5)),
          save(host, repoId, assetId, 0, brighterWith("contrast" -> 300)),
          send("PUT", url, ujson.Obj("recipe" -> brighter.toJson)),
          send("PUT", url, ujson.Obj("base" -> 0.5, "recipe" -> brighter.toJson)),
          send("PUT", url, ujson.Obj("base" -> 0)),
          requests.put(
            url,
            headers = Map("Content-Type" -> "application/json"),
            data = "{base",
            cookies = testContext.cookies,
            check = false),
          save(host, repoId, video.persistedId, 0, brighter.toJson)
        )
        for (response <- refusals) withClue(response.text()) {
          response.statusCode shouldBe 400
          json(response)("error").str should not be empty
        }

        testApp.service.assetEdit.editOf(testApp.service.asset.getById(assetId)) shouldBe None
        testApp.service.assetEdit.editOf(video) shouldBe None
    }
  }

  test("Auto-straighten answers the Straighten that levels the image's lines, and refuses what cannot be edited") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, an imported 1600 × 1200 image of dark lines on white, each rising 2.7° to the
     * right, and an imported Video.
     *
     * Assertions:
     *
     * Auto-straighten with the image's Edit answers `{angle}` of 2.7 ± 0.2, the clockwise Straighten that levels the lines; with
     * the image turned a half turn, the lines rise the same way and the answer is the same. A request without a `recipe`, one
     * whose `recipe` is no Edit, and one for the Video are 400s with an error.
     *
     * Edge cases:
     *
     * An imported blank image has no lines to level by: the answer is `{angle: null}`, so the client keeps its Straighten.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val drawing = new Mat(1200, 1600, CvType.CV_8UC3, new Scalar(255, 255, 255))
    val rise = Math.tan(Math.toRadians(2.7))
    for (i <- 0 until 8) {
      val y = 200.0 + i * 110
      Imgproc.line(drawing, new Point(150, y), new Point(1450, y - 1300 * rise), new Scalar(30, 30, 30), 4, Imgproc.LINE_AA)
    }
    val bytes = new MatOfByte()
    Imgcodecs.imencode(".jpg", drawing, bytes)
    val lines = testApp.service.library
      .addAsset(AssetWithData(testContext.makeAsset(), testApp.service.staging.stage(bytes.toArray)))
      .persistedId
    val blankBytes = new MatOfByte()
    Imgcodecs.imencode(".jpg", new Mat(600, 800, CvType.CV_8UC3, new Scalar(255, 255, 255)), blankBytes)
    val blank = testApp.service.library
      .addAsset(AssetWithData(testContext.makeAsset(), testApp.service.staging.stage(blankBytes.toArray)))
      .persistedId
    val video = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(
        IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.black(1))).toFile)))

    withServer(App) {
      host =>
        for (recipe <- List(EditRecipe.Identity, EditRecipe.Identity.copy(rotate = 180, straighten = -10))) {
          val response = get(autoStraightenUrl(host, repoId, lines, recipe.toJson))
          response.statusCode shouldBe 200
          json(response)("angle").num shouldBe 2.7 +- 0.2
        }
        val none = get(autoStraightenUrl(host, repoId, blank, EditRecipe.Identity.toJson))
        none.statusCode shouldBe 200
        json(none)("angle") shouldBe ujson.Null

        val refusals = List(
          get(editUrl(host, repoId, lines, "/auto-straighten")),
          get(autoStraightenUrl(host, repoId, lines, brighterWith("dehaze" -> 5))),
          get(editUrl(host, repoId, lines, "/auto-straighten?recipe=%7Bbase")),
          get(autoStraightenUrl(host, repoId, video.persistedId, brighter.toJson))
        )
        for (response <- refusals) withClue(response.text()) {
          response.statusCode shouldBe 400
          json(response)("error").str should not be empty
        }
    }
  }

  test("The Edit's routes are a 403 without Edit images and a 404 for another Library's asset") {

    /**
     * Setup:
     *
     * A Library with an imported image, a User logged in who holds every Permission there but Edit images, and a User who holds
     * every Permission there and in a second Library, which holds an imported image saved once.
     *
     * Assertions:
     *
     * The first User is answered 403 by every route of the Edit. The second, asking through the first Library for the second
     * Library's image, is answered 404 by every route, as for an asset that does not exist, and the image's Edit is unchanged.
     */
    val repoId = loginAs(AccountType.User, Permissions(Permissions.all.mask & ~Permission.EditImages.mask))
    val library = testContext.repository
    val assetId = testContext.persistAsset().persistedId
    val withoutEditImages = testContext.cookies

    val owner = testContext.persistUser()
    testContext.grant(owner, library)
    val theirs = testContext.persistRepository()
    testContext.grant(owner, theirs)
    testApp.service.repository.switchContextToRepository(theirs)
    val theirAsset = testContext.persistAsset(repository = Some(theirs), user = Some(owner)).persistedId
    testApp.service.assetEdit.save(theirAsset, 0, brighter, owner)
    // Rendered now, so the render the save queued does not change the Edit while the test compares it
    testApp.service.editRender.render(theirAsset)
    val before = testApp.service.assetEdit.get(theirAsset)

    withServer(App) {
      host =>
        def requestsFor(assetId: String, cookies: Map[String, HttpCookie]): List[requests.Response] = List(
          get(editUrl(host, repoId, assetId), cookies),
          get(editUrl(host, repoId, assetId, "/status"), cookies),
          send("PUT", editUrl(host, repoId, assetId), ujson.Obj("base" -> 1, "recipe" -> brighter.toJson), cookies),
          get(autoStraightenUrl(host, repoId, assetId, brighter.toJson), cookies)
        )

        requestsFor(assetId, withoutEditImages).map(_.statusCode) shouldBe List(403, 403, 403, 403)

        val ownerCookies = cookiesOf(host, owner)
        requestsFor(theirAsset, ownerCookies).map(_.statusCode) shouldBe List(404, 404, 404, 404)
        requestsFor(UUID.randomUUID.toString, ownerCookies).map(_.statusCode) shouldBe List(404, 404, 404, 404)
    }

    testApp.service.assetEdit.get(theirAsset) shouldBe before
  }
}
