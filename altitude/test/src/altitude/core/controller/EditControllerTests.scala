package altitude.core.controller

import altitude.test.IntegrationTestUtil
import altitude.test.TestVideos
import java.net.HttpCookie
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.EditRecipe
import altitude.core.models.Permission
import altitude.core.models.Permissions

/** The Edit's JSON API: an asset's Edit and its History, a step, a head move and the render status */
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

  private def step(host: String, repoId: String, assetId: String, base: Int, recipe: ujson.Value): requests.Response =
    send("POST", editUrl(host, repoId, assetId, "/steps"), ujson.Obj("base" -> base, "recipe" -> recipe))

  private def moveHead(host: String, repoId: String, assetId: String, base: Int, seq: Int): requests.Response =
    send("PUT", editUrl(host, repoId, assetId, "/head"), ujson.Obj("base" -> base, "seq" -> seq))

  private def json(response: requests.Response): ujson.Value = {
    response.headers("content-type").head should include("application/json")
    ujson.read(response.text())
  }

  test("The Edit's routes answer its state, a step, a head move and the render status") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, and an imported 150 × 150 still image, never edited.
     *
     * Assertions:
     *
     * The state of the image is the Original: head 0, labeled "Original", not rendered, revision 0, not edited, no edited size,
     * the identity Edit with every key, and a History of the Original alone, by no one and not the User's. A step of Exposure
     * +0.4 built on head 0 answers the new state: head 1, labeled "Exposure +0.40", edited, the image's size, the Edit as sent,
     * and a History of that step, by the User, timed and marked as the User's, over the Original. After another account's step,
     * the History names that account on its step and does not mark it as the User's. Moving the head back to 0 answers the
     * Original again, and the status says head 0, not rendered, revision 0.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val me = testContext.user
    val assetId = testContext.persistAsset().persistedId
    val other = testContext.persistUser()

    withServer(App) {
      host =>
        val original = get(editUrl(host, repoId, assetId))
        original.statusCode shouldBe 200
        val state = json(original)
        state("head").num shouldBe 0
        state("label").str shouldBe "Original"
        state("rendered") shouldBe ujson.Null
        state("rev").num shouldBe 0
        state("edited").bool shouldBe false
        state("width") shouldBe ujson.Null
        state("height") shouldBe ujson.Null
        state("recipe") shouldBe EditRecipe.Identity.toJson
        state("steps") shouldBe ujson.Arr(
          ujson.Obj("seq" -> 0, "label" -> "Original", "author" -> ujson.Null, "createdAt" -> ujson.Null, "mine" -> false))

        val stepped = step(host, repoId, assetId, base = 0, recipe = brighter.toJson)
        stepped.statusCode shouldBe 200
        val afterStep = json(stepped)
        afterStep("head").num shouldBe 1
        afterStep("label").str shouldBe "Exposure +0.40"
        afterStep("edited").bool shouldBe true
        (afterStep("width").num.toInt, afterStep("height").num.toInt) shouldBe (150, 150)
        afterStep("recipe") shouldBe brighter.toJson
        val newest = afterStep("steps").arr.head
        newest("seq").num shouldBe 1
        newest("label").str shouldBe "Exposure +0.40"
        newest("author").str shouldBe me.name
        newest("createdAt").str should not be empty
        newest("mine").bool shouldBe true
        afterStep("steps").arr.map(_("seq").num.toInt) shouldBe List(1, 0)

        testApp.service.assetEdit.addStep(assetId, 1, brighter.copy(contrast = 5), other)
        val history = json(get(editUrl(host, repoId, assetId)))("steps").arr
        history.map(step => (step("seq").num.toInt, step("author").strOpt, step("mine").bool)) shouldBe List(
          (2, Some(other.name), false),
          (1, Some(me.name), true),
          (0, None, false))

        val moved = moveHead(host, repoId, assetId, base = 2, seq = 0)
        moved.statusCode shouldBe 200
        json(moved)("head").num shouldBe 0
        json(moved)("edited").bool shouldBe false

        val status = get(editUrl(host, repoId, assetId, "/status"))
        status.statusCode shouldBe 200
        json(status) shouldBe ujson.Obj("head" -> 0, "rendered" -> ujson.Null, "rev" -> 0)
    }
  }

  test("A change built on a stale head is a 409 carrying the current state") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, and an imported image with one step.
     *
     * Assertions:
     *
     * A step built on head 0 is answered 409 with an error and the current state (head 1, its Edit), and so is a head move built
     * on head 0. The Edit is still at step 1, with one step.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val assetId = testContext.persistAsset().persistedId
    testApp.service.assetEdit.addStep(assetId, 0, brighter, testContext.user)

    withServer(App) {
      host =>
        for (
          response <- List(
            step(host, repoId, assetId, base = 0, recipe = brighter.copy(contrast = 10).toJson),
            moveHead(host, repoId, assetId, base = 0, seq = 0))
        ) {
          response.statusCode shouldBe 409
          val body = json(response)
          body("error").str should not be empty
          body("state")("head").num shouldBe 1
          body("state")("recipe") shouldBe brighter.toJson
        }

        val stored = testApp.service.assetEdit.get(assetId)
        stored.head shouldBe 1
        stored.steps.map(_.seq) shouldBe List(1, 0)
    }
  }

  test("A refused Edit, a body that is not one, an unknown step and an asset that cannot be edited are refused") {

    /**
     * Setup:
     *
     * A logged-in User holding every Permission, an imported image and an imported Video.
     *
     * Assertions:
     *
     * A step whose Edit has an unknown key, one with a value out of range, one with no `base`, one whose `base` is not a whole
     * number and one whose body is not JSON are each a 400 with an error, as is a step for the Video. A head move to a step the
     * Edit does not have is a 404. Nothing is stored for either asset.
     */
    val repoId = testContext.persistRepository().persistedId
    login()
    val assetId = testContext.persistAsset().persistedId
    val video = testApp.service.library.addAsset(
      testApp.service.library.convImportAsset2dataAsset(
        IntegrationTestUtil.fileToImportAsset(TestVideos.clip(Seq(TestVideos.black(1))).toFile)))

    withServer(App) {
      host =>
        val stepsUrl = editUrl(host, repoId, assetId, "/steps")
        val refusals = List(
          step(host, repoId, assetId, 0, brighterWith("dehaze" -> 5)),
          step(host, repoId, assetId, 0, brighterWith("contrast" -> 300)),
          send("POST", stepsUrl, ujson.Obj("recipe" -> brighter.toJson)),
          send("POST", stepsUrl, ujson.Obj("base" -> 0.5, "recipe" -> brighter.toJson)),
          requests.post(
            stepsUrl,
            headers = Map("Content-Type" -> "application/json"),
            data = "{base",
            cookies = testContext.cookies,
            check = false),
          step(host, repoId, video.persistedId, 0, brighter.toJson)
        )
        for (response <- refusals) withClue(response.text()) {
          response.statusCode shouldBe 400
          json(response)("error").str should not be empty
        }

        moveHead(host, repoId, assetId, base = 0, seq = 7).statusCode shouldBe 404

        testApp.service.assetEdit.headOf(testApp.service.asset.getById(assetId)) shouldBe None
        testApp.service.assetEdit.headOf(video) shouldBe None
    }
  }

  test("The Edit's routes are a 403 without Edit images and a 404 for another Library's asset") {

    /**
     * Setup:
     *
     * A Library with an imported image, a User logged in who holds every Permission there but Edit images, and a User who holds
     * every Permission there and in a second Library, which holds an imported image with one step.
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
    testApp.service.assetEdit.addStep(theirAsset, 0, brighter, owner)
    val before = testApp.service.assetEdit.get(theirAsset)

    withServer(App) {
      host =>
        def requestsFor(assetId: String, cookies: Map[String, HttpCookie]): List[requests.Response] = List(
          get(editUrl(host, repoId, assetId), cookies),
          get(editUrl(host, repoId, assetId, "/status"), cookies),
          send("POST", editUrl(host, repoId, assetId, "/steps"), ujson.Obj("base" -> 1, "recipe" -> brighter.toJson), cookies),
          send("PUT", editUrl(host, repoId, assetId, "/head"), ujson.Obj("base" -> 1, "seq" -> 0), cookies)
        )

        requestsFor(assetId, withoutEditImages).map(_.statusCode) shouldBe List(403, 403, 403, 403)

        val ownerCookies = cookiesOf(host, owner)
        requestsFor(theirAsset, ownerCookies).map(_.statusCode) shouldBe List(404, 404, 404, 404)
        requestsFor(UUID.randomUUID.toString, ownerCookies).map(_.statusCode) shouldBe List(404, 404, 404, 404)
    }

    testApp.service.assetEdit.get(theirAsset) shouldBe before
  }
}
