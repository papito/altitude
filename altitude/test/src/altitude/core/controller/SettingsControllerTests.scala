package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, not, should, shouldBe }

import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.Settings

/**
 * The Settings screen: an Admin's page and save, a User's and a Guest's 403, the admin-only nav link, a validation replacement
 * that writes nothing, a save that announces its success event, and the PostgreSQL-only Settings absent on SQLite.
 */
@DoNotDiscover class SettingsControllerTests extends ControllerTestCore {

  private def pageUrl(host: String, repoId: String) = s"$host/settings/r/$repoId"
  private def saveUrl(host: String, repoId: String) = s"$host/htmx/settings/r/$repoId"

  private def save(host: String, repoId: String, values: ujson.Obj): requests.Response =
    requests.post(
      saveUrl(host, repoId),
      data = values.toString,
      headers = Map("Content-Type" -> "application/json"),
      cookies = testContext.cookies,
      check = false)

  /** The stored value of a Setting, read fresh from the database through the test instance */
  private def storedValue(name: String): String = {
    testApp.settings.load()
    testApp.settings.current.value(name)
  }

  /** The field of `name` rendered with `value`, wherever its attributes fall */
  private def field(name: String, value: String): String = s"""<input[^>]*name="${name.replace(".", "\\.")}"[^>]*value="$value""""

  test("An Admin opens the Settings page") {

    /**
     * Setup:
     *
     * A repository owned by an Admin, logged in.
     *
     * Assertions:
     *
     * The page answers 200 with the form, a Setting's label and its name as a field, and the nav carries the Settings link.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val response = requests.get(pageUrl(host, repoId), cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.text() should include("""id="settingsForm"""")
        response.text() should include("Detector confidence threshold")
        response.text() should include(s"""name="${Settings.FACE_YUNET_CONFIDENCE_THRESHOLD}"""")
        response.text() should include(s"""href="/settings/r/$repoId"""")
    }
  }

  test("A User and a Guest are refused with 403, without a redirect") {

    /**
     * Setup:
     *
     * A repository owned by a User, then one owned by a Guest, each logged in.
     *
     * Assertions:
     *
     * The page answers 403 as plain text for a browser request and as JSON for a request that accepts JSON; the save route is
     * refused the same way. A User's own pages carry no Settings link in the nav.
     */
    for (accountType <- List(AccountType.User, AccountType.Guest)) {
      testContext = new TestContext(testApp)
      val repoId = loginAs(accountType)

      withServer(App) {
        host =>
          val page = requests.get(pageUrl(host, repoId), cookies = testContext.cookies, check = false, maxRedirects = 0)
          page.statusCode shouldBe 403
          page.headers("content-type").head should include("text/plain")

          val api = requests.get(
            pageUrl(host, repoId),
            headers = Map("Accept" -> "application/json"),
            cookies = testContext.cookies,
            check = false,
            maxRedirects = 0)
          api.statusCode shouldBe 403
          api.headers("content-type").head should include("application/json")
          ujson.read(api.text())("error").str shouldBe "Forbidden"

          val saved = save(host, repoId, ujson.Obj(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.9"))
          saved.statusCode shouldBe 403
          storedValue(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD) shouldBe "0.85"

          val importPage = requests.get(s"$host/pipeline/r/$repoId", cookies = testContext.cookies, check = false)
          importPage.statusCode shouldBe 200
          (importPage.text() should not).include(s"""href="/settings/r/$repoId"""")
      }
    }
  }

  test("Without a login the page redirects to the login page, as any page does") {

    /**
     * Setup:
     *
     * A repository, and no login.
     *
     * Assertions:
     *
     * The login requirement answers first, with the redirect every page gets, not the Admin check's 403.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId

    withServer(App) {
      host =>
        val response = requests.get(pageUrl(host, repoId), check = false, maxRedirects = 0)
        response.statusCode shouldBe 302
        response.headers("location").head should include("/login")
    }
  }

  test("An invalid save re-renders the form in place with the error beside its field and writes nothing") {

    /**
     * Setup:
     *
     * An Admin saves a detector threshold above its bound and a smallest face larger than the detection box.
     *
     * Assertions:
     *
     * The answer is a validation replacement (retargeted to the form), naming both errors beside their fields and keeping the
     * submitted values in those fields; neither row changed.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val response = save(
          host,
          repoId,
          ujson.Obj(
            Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "7",
            Settings.FACE_DETECTION_MIN_FACE_SIZE -> "5000"
          ))

        response.statusCode shouldBe 200
        response.headers("hx-retarget") shouldBe Seq("this")
        response.headers("hx-reswap") shouldBe Seq("outerHTML settle:0")
        response.text() should include("Must be at most 1")
        response.text() should include("Must be at least the smallest face")
        (response.text() should include).regex(field(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD, "7"))
        (response.text() should include).regex(field(Settings.FACE_DETECTION_MIN_FACE_SIZE, "5000"))

        storedValue(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD) shouldBe "0.85"
        storedValue(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "50"
        storedValue(Settings.FACE_DETECTION_BOUNDING_BOX_SIZE) shouldBe "1280"
    }
  }

  test("A save writes the values, re-renders the form and announces its success event") {

    /**
     * Setup:
     *
     * An Admin saves a new detector threshold, turns the place-name search on and leaves everything else as it is.
     *
     * Assertions:
     *
     * The answer is the form itself (no retarget) carrying the success event and the saved values; the rows hold them; a Setting
     * that was not submitted keeps its value. An unchecked flag is saved as false.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val response = save(
          host,
          repoId,
          ujson.Obj(
            Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.9",
            Settings.MAP_GEOCODER_ENABLED -> "true"
          ))

        response.statusCode shouldBe 200
        response.headers.contains("hx-retarget") shouldBe false
        response.text() should include("""id="settingsForm"""")
        response.text() should include("""data-app-success-event="SETTINGS_SAVED_EVENT"""")
        (response.text() should include).regex(field(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD, "0.9"))

        storedValue(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD) shouldBe "0.9"
        storedValue(Settings.MAP_GEOCODER_ENABLED) shouldBe "true"
        storedValue(Settings.FACE_DETECTION_MIN_FACE_SIZE) shouldBe "50"

        // A form with the flag unchecked submits no value for it
        save(host, repoId, ujson.Obj(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.9")).statusCode shouldBe 200
        storedValue(Settings.MAP_GEOCODER_ENABLED) shouldBe "false"
    }
  }

  test("A flag or a number sent as JSON true or a number is saved as its text; another JSON type is refused") {

    /**
     * Setup:
     *
     * An Admin's save carries a JSON boolean for the place-name search and a JSON number for the match count, then a JSON array
     * for the match count.
     *
     * Assertions:
     *
     * The boolean and the number are stored as text; the array is answered as a validation replacement naming the field, and
     * nothing is written.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val typed =
          save(host, repoId, ujson.Obj(Settings.MAP_GEOCODER_ENABLED -> true, Settings.FACE_RECOGNITION_MATCH_COUNT -> 7))
        typed.statusCode shouldBe 200
        typed.headers.contains("hx-retarget") shouldBe false
        storedValue(Settings.MAP_GEOCODER_ENABLED) shouldBe "true"
        storedValue(Settings.FACE_RECOGNITION_MATCH_COUNT) shouldBe "7"

        val refused = save(host, repoId, ujson.Obj(Settings.FACE_RECOGNITION_MATCH_COUNT -> ujson.Arr(1)))
        refused.statusCode shouldBe 200
        refused.headers("hx-retarget") shouldBe Seq("this")
        refused.text() should include("Incorrect value type")
        storedValue(Settings.FACE_RECOGNITION_MATCH_COUNT) shouldBe "7"
    }
  }

  test("A Restart setting saved with another value carries a notice naming the saved and the running value") {

    /**
     * Setup:
     *
     * An Admin saves an import parallelism of 3 while the server runs with the seed, 0.
     *
     * Assertions:
     *
     * The re-rendered form carries the notice with both values; saving the running value back removes it.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val response = save(host, repoId, ujson.Obj(Settings.IMPORT_PARALLELISM -> "3"))

        response.statusCode shouldBe 200
        response.text() should include("Saved 3, running 0 until the server restarts")

        (save(host, repoId, ujson.Obj(Settings.IMPORT_PARALLELISM -> "0")).text() should not).include("until the server restarts")
    }
  }

  test("The PostgreSQL-only Settings are not rendered on SQLite") {

    /**
     * Setup:
     *
     * An Admin's page, on the controller tests' SQLite instance.
     *
     * Assertions:
     *
     * Neither PostgreSQL-only Setting is on the page; an Operations Setting of both engines is.
     */
    val repoId = loginAs(AccountType.Admin)

    withServer(App) {
      host =>
        val response = requests.get(pageUrl(host, repoId), cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        (response.text() should not).include(Settings.FACE_RECOGNITION_HNSW_EF_SEARCH)
        (response.text() should not).include(Settings.DB_POSTGRES_READ_STATEMENT_TIMEOUT)
        response.text() should include(Settings.PIPELINE_SHUTDOWN_TIMEOUT)
    }
  }
}
