package altitude.core.controller

import cask.router.Decorator
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ be, fullyMatch, include, regex, should, shouldBe, startWith }
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.routes.web.StyleGuideController

/** `App` as it is in dev: its decorators and routes, plus the style guide's, which `App` registers only there */
object DevApp extends cask.Main:
  given logger: Logger = LoggerFactory.getLogger(getClass)

  override def mainDecorators: Seq[Decorator[?, ?, ?, ?]] = App.mainDecorators

  override def allRoutes: Seq[cask.Routes] = App.allRoutes :+ new StyleGuideController

@DoNotDiscover class StyleGuideControllerTests extends ControllerTestCore {

  private val sectionIds = Seq(
    "colors",
    "typography",
    "spacing",
    "borders-shadows",
    "panels-layout",
    "icons",
    "buttons",
    "forms",
    "navigation-tabs",
    "menus-dialogs",
    "feedback",
    "badges-states",
    "token-audit"
  )

  test(
    "Outside dev the style guide and the design tweaks panel do not exist: the routes answer 404 and the pages carry neither") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and the app as the test environment starts it.
     *
     * Assertions:
     *
     * The page, the scan JSON, the sample dialog, the New library sample and their submissions each answer 404, and neither the
     * main page's nav nor a nav reload carries the Style Guide button. The main page loads neither the panel's adapter nor the
     * vendored tokentweak package nor its boot script, and holds no pre-paint script reading the tweaks.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def get(path: String) = requests.get(s"$host/$path", cookies = testContext.cookies, check = false)

        get(s"style-guide/r/$repoId").statusCode shouldBe 404
        get("style-guide/scan.json").statusCode shouldBe 404
        for (sample <- List("sample", "new-library")) {
          withClue(s"[$sample]: ") {
            get(s"htmx/style-guide/r/$repoId/dialogs/$sample").statusCode shouldBe 404
            requests
              .post(
                s"$host/htmx/style-guide/r/$repoId/$sample",
                headers = Map("Content-Type" -> "application/json"),
                data = ujson.write(ujson.Obj("name" -> "x")),
                cookies = testContext.cookies,
                check = false
              )
              .statusCode shouldBe 404
          }
        }

        val index = get(s"r/$repoId")
        index.statusCode shouldBe 200
        index.text().contains("menu style-guide") shouldBe false
        index.text().contains("design-tweaks") shouldBe false
        index.text().contains("designTweaks") shouldBe false
        index.text().contains("tokentweak") shouldBe false
        val nav = get(s"htmx/nav/r/$repoId").text()
        nav.contains("menu style-guide") shouldBe false
        nav.contains("tokentweak") shouldBe false
    }
  }

  test("The scan JSON route answers the source scan as JSON, with the tokens, the literals and the length literals") {

    /**
     * Setup:
     *
     * A logged-in user, and the app with the style guide's routes registered, as in dev.
     *
     * Assertions:
     *
     * The route answers 200 as JSON in the scan's shape, the design tweaks panel's sources: `version` 1 (the sources schema the
     * tokentweak package reads), the root tokens, each with a name, a value and a location, the color literals and the length
     * literals, each with a property, a value and locations, and the icons. The tests run in a sandbox whose working directory
     * holds no sources, so the scan is empty there; `StyleGuideScanTests` scans the real tree.
     */
    testContext.persistRepository()
    login()

    withServer(DevApp) {
      host =>
        val response = requests.get(s"$host/style-guide/scan.json", cookies = testContext.cookies, check = false)

        response.statusCode shouldBe 200
        response.headers("content-type").head should include("application/json")

        val scan = ujson.read(response.text())
        scan("version").num shouldBe 1
        scan("rootTokens").arr.foreach {
          t =>
            t("name").str should startWith("--")
            t("value").str.nonEmpty shouldBe true
            t("location").str should include(":")
        }
        scan("literals").arr.foreach(l => l("locations").arr.nonEmpty shouldBe true)
        scan("lengthLiterals").arr.foreach {
          l =>
            l("property").str.nonEmpty shouldBe true
            (l("value").str should fullyMatch).regex("\\d*\\.?\\d+(?:px|rem|em)")
            l("locations").arr.nonEmpty shouldBe true
        }
        scan("icons").obj.values.foreach(_.num should be >= 1.0)
        scan("references").obj.values.foreach(_.num should be >= 1.0)
    }
  }

  test("The style guide page renders every section and embeds the source scan") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and the app with the style guide's routes registered, as in dev.
     *
     * Assertions:
     *
     * The page answers 200, holds the scan JSON element and an element for each section, and loads the two libraries the app
     * start and the sample dialog need. Its Library menu specimen offers New library, which opens the New library sample.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(DevApp) {
      host =>
        val page = requests.get(s"$host/style-guide/r/$repoId", cookies = testContext.cookies, check = false)

        page.statusCode shouldBe 200
        page.text() should include("""<script type="application/json" id="styleGuideScan">""")
        sectionIds.foreach(id => page.text() should include(s"""<section id="$id">"""))
        page.text() should include("/static/js/lib/interact.min.js")
        page.text() should include("/static/js/lib/json-enc.js")
        page.text() should include("""id="styleGuideLibraryMenuHost"""")
        page.text() should include(s"""hx-get="/htmx/style-guide/r/$repoId/dialogs/new-library"""")
    }
  }

  test("The sample dialog renders, and submitting it validates the name without persisting anything") {

    /**
     * Setup:
     *
     * A logged-in user's repository, the app with the style guide's routes registered, and names posted to the sample endpoint as
     * JSON.
     *
     * Assertions:
     *
     * The dialog fragment renders its form. A name answers an empty 200. A blank name answers the form carrying the error,
     * retargeted to replace the submitted form in place.
     *
     * Edge cases:
     *
     * A name of spaces only is blank.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(DevApp) {
      host =>
        val dialog =
          requests.get(s"$host/htmx/style-guide/r/$repoId/dialogs/sample", cookies = testContext.cookies, check = false)
        dialog.statusCode shouldBe 200
        dialog.text() should include("""id="styleGuideSample"""")

        def post(name: String) = requests.post(
          s"$host/htmx/style-guide/r/$repoId/sample",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(ujson.Obj("name" -> name)),
          cookies = testContext.cookies,
          check = false
        )

        val named = post("A name")
        named.statusCode shouldBe 200
        named.text() shouldBe ""

        val blank = post("   ")
        blank.statusCode shouldBe 200
        blank.headers("hx-retarget") shouldBe Seq("this")
        blank.text() should include("""id="styleGuideSample"""")
        blank.text() should include("""class="error"""")
    }
  }

  test("The New library sample is the New library dialog in error, and submitting it applies the name rule and creates nothing") {

    /**
     * Setup:
     *
     * A logged-in user's Library, the app with the style guide's routes registered, and names posted to the New library sample's
     * endpoint as JSON.
     *
     * Assertions:
     *
     * The sample renders the New library dialog with "client smith" typed and the error a taken name gets, its form posting to
     * the sample's endpoint rather than the create route. A blank name and a one-character name answer the dialog with the
     * Library name rule's errors under the field, retargeted to replace the submitted form in place, and a valid name answers an
     * empty 200.
     *
     * Edge cases:
     *
     * No submission creates a Library.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()
    val libraries = testApp.service.repository.getAll.size

    withServer(DevApp) {
      host =>
        val dialog =
          requests.get(s"$host/htmx/style-guide/r/$repoId/dialogs/new-library", cookies = testContext.cookies, check = false)
        dialog.statusCode shouldBe 200
        dialog.text() should include("""id="newLibrary"""")
        dialog.text() should include(s"""hx-post="/htmx/style-guide/r/$repoId/new-library"""")
        dialog.text() should include("""value="client  smith"""")
        dialog.text() should include(s"""<div class="error">${Const.Msg.Err.LIBRARY_NAME_TAKEN.format("client smith")}</div>""")

        def post(name: String) = requests.post(
          s"$host/htmx/style-guide/r/$repoId/new-library",
          headers = Map("Content-Type" -> "application/json"),
          data = ujson.write(ujson.Obj(Api.Field.Library.NAME -> name)),
          cookies = testContext.cookies,
          check = false
        )

        for (
          (name, error) <- List("" -> Const.Msg.Err.LIBRARY_NAME_REQUIRED, "C" -> Const.Msg.Err.LIBRARY_NAME_TOO_SHORT.format(2))
        ) {
          withClue(s"[$name]: ") {
            val refused = post(name)
            refused.statusCode shouldBe 200
            refused.headers("hx-retarget") shouldBe Seq("this")
            refused.text() should include("""id="newLibrary"""")
            refused.text() should include(s"""<div class="error">$error</div>""")
            refused.text() should include(s"""value="$name"""")
          }
        }

        val valid = post("Client Smith")
        valid.statusCode shouldBe 200
        valid.text() shouldBe ""

        testApp.service.repository.getAll.size shouldBe libraries
    }
  }

  test("The style guide page's browser tab title names the page and its Library") {

    /**
     * Setup:
     *
     * A logged-in user with the Library "Smith & Sons", and the app as it is in dev.
     *
     * Assertions:
     *
     * The page is titled "Style Guide · Smith & Sons · Altitude", the name HTML-escaped and between U+2068 and U+2069.
     */
    val user = testContext.persistUser()
    val repoId = addLibrary("Smith & Sons", user).persistedId
    login()

    withServer(DevApp) {
      host =>
        val page = requests.get(s"$host/style-guide/r/$repoId", cookies = testContext.cookies, check = false)

        page.statusCode shouldBe 200
        page.text() should include("<title>Style Guide · \u2068Smith &amp; Sons\u2069 · Altitude</title>")
    }
  }

  test("The style guide page records its Library as the last active one, and a reload writes nothing") {

    /**
     * Setup:
     *
     * A logged-in user with Libraries A and B, and the app as it is in dev.
     *
     * Assertions:
     *
     * A's style guide page answers and records A when B is recorded before the load, and a reload of it writes nothing.
     */
    val user = testContext.persistUser()
    val a = addLibrary("Library A", user)
    val b = addLibrary("Library B", user)
    login()

    withServer(DevApp)(host => assertPageRecords(host, user, s"style-guide/r/${a.persistedId}", a, b))
  }
}
