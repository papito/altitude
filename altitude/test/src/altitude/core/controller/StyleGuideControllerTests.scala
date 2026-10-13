package altitude.core.controller

import cask.router.Decorator
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.{ include, should, shouldBe }
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.App
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

  test("Outside dev the style guide does not exist: its routes answer 404 and the nav has no button for it") {

    /**
     * Setup:
     *
     * A logged-in user's repository, and the app as the test environment starts it.
     *
     * Assertions:
     *
     * The page, the sample dialog and the sample submission each answer 404, and neither the main page's nav nor a nav reload
     * carries the Style Guide button.
     */
    testContext.persistRepository()
    val repoId = testContext.repository.persistedId
    login()

    withServer(App) {
      host =>
        def get(path: String) = requests.get(s"$host/$path", cookies = testContext.cookies, check = false)

        get(s"style-guide/r/$repoId").statusCode shouldBe 404
        get(s"htmx/style-guide/r/$repoId/dialogs/sample").statusCode shouldBe 404
        requests
          .post(
            s"$host/htmx/style-guide/r/$repoId/sample",
            headers = Map("Content-Type" -> "application/json"),
            data = ujson.write(ujson.Obj("name" -> "x")),
            cookies = testContext.cookies,
            check = false
          )
          .statusCode shouldBe 404

        val index = get(s"r/$repoId")
        index.statusCode shouldBe 200
        index.text().contains("menu style-guide") shouldBe false
        get(s"htmx/nav/r/$repoId").text().contains("menu style-guide") shouldBe false
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
     * start and the sample dialog need.
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
}
