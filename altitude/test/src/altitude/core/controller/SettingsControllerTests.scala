package altitude.core.controller

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, include, not, should, shouldBe }

import altitude.core.App
import altitude.core.models.AccountType
import altitude.core.models.Permissions
import altitude.core.models.Settings

/** The Settings screen: an Admin's page and save, refused to everyone else. */
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

  /** The stored value of a Setting, reloaded from the database */
  private def storedValue(name: String): String = {
    testApp.settings.load()
    testApp.settings.current.value(name)
  }

  /** A regex for the `name` input carrying `value`, whatever attributes sit around and between them */
  private def field(name: String, value: String): String = s"""<input[^>]*name="${name.replace(".", "\\.")}"[^>]*value="$value""""

  test("An Admin opens the Settings page") {

    /**
     * Setup:
     *
     * An Admin, logged in to a repository.
     *
     * Assertions:
     *
     * The page answers 200 with the form, a Setting's label and its name as a field, and the nav carries the Settings link.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

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
     * A User, then a Guest, each logged in to a repository it holds a grant in.
     *
     * Assertions:
     *
     * The page answers 403 as plain text to a browser and as JSON to a JSON request, and the save is refused too. Neither sees
     * the Settings link in the nav.
     */
    for (accountType <- List(AccountType.User, AccountType.Guest)) {
      testContext = new TestContext(testApp)
      val repoId = loginAs(accountType, Permissions.none)

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
          ujson.read(api.text())("error").str shouldBe "Admin only"

          val saved = save(host, repoId, ujson.Obj(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD -> "0.9"))
          saved.statusCode shouldBe 403
          storedValue(Settings.FACE_YUNET_CONFIDENCE_THRESHOLD) shouldBe "0.85"

          val mainPage = requests.get(s"$host/r/$repoId", cookies = testContext.cookies, check = false)
          mainPage.statusCode shouldBe 200
          (mainPage.text() should not).include(s"""href="/settings/r/$repoId"""")
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
     * The answer is a validation replacement of the form with both errors beside their fields, which keep the submitted values;
     * no row changed.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

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
     * The answer is the form itself, not retargeted, carrying the success event and the saved values, which the rows hold. A
     * Setting not submitted keeps its value; an unchecked flag is saved as false.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

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
     * An Admin saves a JSON boolean and a JSON number, then a JSON array.
     *
     * Assertions:
     *
     * The boolean and the number are stored as text. The array is refused as a validation replacement, and nothing is written.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

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

  test("The Search section shows the Describe match threshold as 0.00009 and saves a small probability in plain notation") {

    /**
     * Setup:
     *
     * An Admin's page, then three saves of the Describe match threshold, whose default 0.00009 a JVM prints as 9.0E-5: the
     * default as text, a smaller value as a JSON number, and a value with an exponent.
     *
     * Assertions:
     *
     * The Search section lists the threshold, the result limit and the weaker matches ratio, and the threshold's field, Reset
     * default and help show 0.00009. Saving the default back leaves the field and the row at 0.00009. The JSON number is stored
     * and shown as 0.00002.
     *
     * Edge cases:
     *
     * The value with an exponent is refused as not a plain decimal, rather than as not a number, and nothing is written.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)
    val threshold = Settings.SEARCH_DESCRIBE_MIN_PROBABILITY

    withServer(App) {
      host =>
        val page = requests.get(pageUrl(host, repoId), cookies = testContext.cookies, check = false).text()
        page should include("<h2>Search</h2>")
        page should include("Describe match threshold")
        page should include("Describe result limit")
        page should include("Describe weaker matches")
        (page should include).regex(field(threshold, "0\\.00009"))
        (page should include).regex(field(Settings.SEARCH_DESCRIBE_MAX_RESULTS, "1000"))
        page should include("""data-setting-default="0.00009"""")
        page should include("Lower shows more, weaker matches. Default: 0.00009")
        (page should not).include("E-5")

        val unchanged = save(host, repoId, ujson.Obj(threshold -> "0.00009"))
        unchanged.statusCode shouldBe 200
        unchanged.headers.contains("hx-retarget") shouldBe false
        (unchanged.text() should include).regex(field(threshold, "0\\.00009"))
        storedValue(threshold) shouldBe "0.00009"

        val typed = save(host, repoId, ujson.Obj(threshold -> 0.00002))
        typed.statusCode shouldBe 200
        typed.headers.contains("hx-retarget") shouldBe false
        (typed.text() should include).regex(field(threshold, "0\\.00002"))
        (typed.text() should not).include("E-5")
        storedValue(threshold) shouldBe "0.00002"
        testApp.settings.current.searchDescribeMinProbability shouldBe 0.00002

        val refused = save(host, repoId, ujson.Obj(threshold -> "3e-5"))
        refused.statusCode shouldBe 200
        refused.headers("hx-retarget") shouldBe Seq("this")
        refused.text() should include("Must be a plain decimal, with no exponent")
        (refused.text() should not).include("Must be a number")
        storedValue(threshold) shouldBe "0.00002"
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
    val repoId = loginAs(AccountType.Admin, Permissions.all)

    withServer(App) {
      host =>
        val response = save(host, repoId, ujson.Obj(Settings.IMPORT_PARALLELISM -> "3"))

        response.statusCode shouldBe 200
        response.text() should include("Saved 3, running 0 until the server restarts")

        (save(host, repoId, ujson.Obj(Settings.IMPORT_PARALLELISM -> "0")).text() should not).include("until the server restarts")
    }
  }

  test("The import parallelism field says what is running and why, on the page and in a save's answer") {

    /**
     * Setup:
     *
     * An Admin's page, then a save of another import parallelism, while the server runs with the seed, 0.
     *
     * Assertions:
     *
     * The page carries the running line as the form's only note. A save's answer keeps it, since the saved value runs only from
     * the next start, between the restart notice and the field's help.
     */
    val repoId = loginAs(AccountType.Admin, Permissions.all)

    withServer(App) {
      host =>
        val note = s"""<div class="note">Running ${App.altitude.importParallelismResolution.summary}</div>"""

        val page = requests.get(pageUrl(host, repoId), cookies = testContext.cookies, check = false).text()
        page should include(note)
        "class=\"note\"".r.findAllIn(page).size shouldBe 1

        val saved = save(host, repoId, ujson.Obj(Settings.IMPORT_PARALLELISM -> "3")).text()
        val notice = saved.indexOf("Saved 3, running 0 until the server restarts")
        notice should be > -1
        saved.indexOf(note) should be > notice
        saved.indexOf("""<div class="help">How many threads""") should be > saved.indexOf(note)
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
    val repoId = loginAs(AccountType.Admin, Permissions.all)

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
