package altitude.core.unit

import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers._

import altitude.core.routes.web.StyleGuideController
import altitude.core.util.StyleGuideScan

@DoNotDiscover class StyleGuideScanTests extends funsuite.AnyFunSuite with TestFocus {

  /** Writes `files` (relative path -> content) under a fresh `static`-like tree and scans its `css`, `js` and `views` roots */
  private def scan(files: (String, String)*): StyleGuideScan.Result =
    val dir: Path = Files.createTempDirectory("style-guide-scan")
    try
      files.foreach {
        (relPath, content) =>
          val file = dir.resolve(relPath)
          Files.createDirectories(file.getParent)
          Files.writeString(file, content)
      }
      StyleGuideScan.scan(
        roots = Seq(dir.resolve("css"), dir.resolve("js"), dir.resolve("views")),
        excludedFiles = Set("font-awesome.min.css", "style_guide.scala.html"))
    finally FileUtils.deleteDirectory(dir.toFile)

  private val coreCss =
    """:root {
      |    color-scheme: dark;
      |
      |    --background-color: #363636;
      |
      |    /* Larger spacing for larger app areas,
      |       like panels */
      |    --section-space: 35px;
      |    --border-width: 2px;
      |
      |    /* Panels */
      |
      |    --border: var(--border-width) solid rgba(129, 128, 128, 0.98);
      |    --unused: 1px;
      |    --font: "Segoe UI",
      |            sans-serif
      |}
      |
      |body { background: var(--background-color); color: #FFF; }
      |
      |/* a commented-out rule: var(--unused) #123456 */
      |#content.triage {
      |    --view-tint: rgba(55, 122, 28, 0.07);
      |    border: var(--border);
      |}
      |
      |#add { color: #fff; margin: var(--section-space, 10px); }
      |""".stripMargin

  test("Root tokens are the :root declarations of a stylesheet, in source order, with the comment directly above as a note") {

    /**
     * Setup:
     *
     * A stylesheet whose `:root` rule declares six tokens: one under a two-line comment, one directly after that one, one under a
     * comment separated from it by a blank line, and one whose value spans two lines and ends the rule without a semicolon.
     *
     * Assertions:
     *
     * The tokens are listed in source order with their declared values (whitespace collapsed) and their file and line. Only the
     * token directly under a comment carries it as its note.
     *
     * Edge cases:
     *
     * `color-scheme` is a plain property, not a token. A blank line or another declaration between a comment and a token leaves
     * the token without a note.
     */
    val result = scan("css/core.css" -> coreCss)

    result.rootTokens.map(_.name) shouldEqual
      Seq("--background-color", "--section-space", "--border-width", "--border", "--unused", "--font")
    result.rootTokens.map(_.value) shouldEqual
      Seq("#363636", "35px", "2px", "var(--border-width) solid rgba(129, 128, 128, 0.98)", "1px", "\"Segoe UI\", sans-serif")
    result.rootTokens.map(_.note) shouldEqual
      Seq(None, Some("Larger spacing for larger app areas, like panels"), None, None, None, None)
    result.rootTokens.head.location shouldBe "css/core.css:4"
  }

  test(
    "A token declared outside :root is scoped, in a stylesheet or a template, and one set only from JavaScript is a runtime token") {

    /**
     * Setup:
     *
     * A stylesheet with a token declared in an ordinary rule, a template whose style block declares a new token and redeclares a
     * root token, and a script that sets two tokens with `setProperty`: one declared nowhere, and the root token.
     *
     * Assertions:
     *
     * The three declarations outside `:root` are scoped tokens with their values and locations; the redeclared root token is
     * among them, which is what makes it an override. The token declared nowhere is the only runtime token, located at its
     * `setProperty`.
     */
    val result = scan(
      "css/core.css" -> coreCss,
      "views/htmx/folders.scala.html" ->
        """@()
          |<style>
          |    #folders {
          |        --folder-indent: 20px;
          |        --section-space: 720px;
          |    }
          |</style>
          |""".stripMargin,
      "js/common/tree.js" ->
        """el.style.setProperty("--depth", depth)
          |el.style.setProperty('--section-space', "1px")
          |""".stripMargin
    )

    result.scopedTokens.map(t => (t.name, t.value, t.location)) should contain theSameElementsAs Seq(
      ("--view-tint", "rgba(55, 122, 28, 0.07)", "css/core.css:23"),
      ("--folder-indent", "20px", "views/htmx/folders.scala.html:4"),
      ("--section-space", "720px", "views/htmx/folders.scala.html:5")
    )
    result.runtimeTokens.map(t => (t.name, t.locations)) shouldEqual Seq(("--depth", Seq("js/common/tree.js:1")))
  }

  test("A token's references are the occurrences of its whole name that do not declare it") {

    /**
     * Setup:
     *
     * The stylesheet, where tokens are referenced plainly, with a fallback, and from another token's declaration, and where one
     * token's name is the prefix of another's; a script that reads one token by name and sets another; and a comment in each file
     * naming a token.
     *
     * Assertions:
     *
     * Each token counts its plain, fallback, in-declaration and by-name references. A token referenced nowhere counts none.
     *
     * Edge cases:
     *
     * `--border` is not counted inside `--border-width`. A name inside a comment, a declaration, and a `setProperty` call are not
     * references. A `//` inside a string is not a comment.
     */
    val result = scan(
      "css/core.css" -> coreCss,
      "js/common/modal.js" ->
        """// reads --unused, in a comment only
          |/* and --unused again */
          |const gap = getComputedStyle(el).getPropertyValue("--border-width")
          |const url = "http://example.com/" + "--background-color"
          |el.style.setProperty("--section-space", "1px")
          |""".stripMargin
    )

    result.references("--background-color") shouldBe 2
    result.references("--section-space") shouldBe 1
    result.references("--border-width") shouldBe 2
    result.references("--border") shouldBe 1
    result.references.getOrElse("--unused", 0) shouldBe 0
    result.references.getOrElse("--view-tint", 0) shouldBe 0
  }

  test("Color literals are counted where they are written as values, and not inside a token's declaration") {

    /**
     * Setup:
     *
     * The stylesheet, with a literal in two spellings in ordinary rules, literals inside token declarations and inside a comment,
     * and two ID selectors made of hexadecimal letters; and a template with an `rgb()` literal in its style block and a fragment
     * link.
     *
     * Assertions:
     *
     * The literals are the ones in ordinary declarations, each spelling with its count and locations.
     *
     * Edge cases:
     *
     * Literals inside a token declaration or a comment, an ID selector (`#add {`), and a fragment link (`href="#fed"`) are not
     * literals.
     */
    val result = scan(
      "css/core.css" -> coreCss,
      "views/index.scala.html" ->
        """<style>
          |    #fed, main { color: rgb(76, 75, 75); background: #fff }
          |</style>
          |<a href="#fed">x</a>
          |""".stripMargin
    )

    result.literals.map(l => (l.value, l.locations)) should contain theSameElementsAs Seq(
      ("#FFF", Seq("css/core.css:19")),
      ("#fff", Seq("css/core.css:27", "views/index.scala.html:2")),
      ("rgb(76, 75, 75)", Seq("views/index.scala.html:2"))
    )
  }

  test("Length literals are the px, rem and em values of the sizing properties, outside token declarations") {

    /**
     * Setup:
     *
     * A stylesheet with lengths in a font size, a width inside `calc()`, a min-width, a grid's tracks and an inset; lengths in
     * properties that are not sizing ones (a padding, a border width, a letter spacing, a line height); a token declaration
     * holding a length; a zero and a viewport unit; a commented-out size; and a template with an inline width.
     *
     * Assertions:
     *
     * The literals are those of the sizing properties, grouped by property and value, each with its locations, in property and
     * value order.
     *
     * Edge cases:
     *
     * `--snackbar-width` is a token declaration and `border-width` is not `width`, so neither counts. A unitless zero, a `vh`
     * length, a number inside a decimal or a name, and a length in a comment are not literals.
     */
    val result = scan(
      "css/core.css" ->
        """:root { --snackbar-width: 31.25rem; }
          |/* height: 99px */
          |#snackbar { font-size: 12px; width: calc(var(--snackbar-width) + 10px); min-width: 5rem; }
          |#person { grid-template-columns: 100px 1fr; top: 50px; height: 0; max-height: 80vh; }
          |.cell { padding: 10px; border-width: 2px; letter-spacing: 1px; line-height: 1.4; font-size: 0.9em }
          |.other { font-size: 12px; left: 1.5rem }
          |""".stripMargin,
      "views/htmx/people.scala.html" -> """<div style="width: 20px; --face-box: 80px"></div>"""
    )

    result.lengthLiterals.map(l => (l.property, l.value, l.locations)) shouldEqual Seq(
      ("font-size", "0.9em", Seq("css/core.css:5")),
      ("font-size", "12px", Seq("css/core.css:3", "css/core.css:6")),
      ("grid-template-columns", "100px", Seq("css/core.css:4")),
      ("left", "1.5rem", Seq("css/core.css:6")),
      ("min-width", "5rem", Seq("css/core.css:3")),
      ("top", "50px", Seq("css/core.css:4")),
      ("width", "10px", Seq("css/core.css:3")),
      ("width", "20px", Seq("views/htmx/people.scala.html:1"))
    )
  }

  test("Icons are the Font Awesome icon names of templates and scripts, without the modifier classes") {

    /**
     * Setup:
     *
     * A template with two icons, one of them spinning, and a script that uses one of the two again.
     *
     * Assertions:
     *
     * Each icon name is listed with the number of times it is used; `fa-spin` is a modifier and is not listed.
     */
    val result = scan(
      "views/nav.scala.html" -> """<i class="fas fa-trash"></i><i class="fas fa-spinner fa-spin"></i>""",
      "js/common/tree.js" -> """iconEl.className = "fas fa-trash""""
    )

    result.icons shouldEqual Map("fa-trash" -> 2, "fa-spinner" -> 1)
  }

  test("The scan skips lib directories and excluded files, and a missing root is empty") {

    /**
     * Setup:
     *
     * A script under a `lib` directory, the excluded Font Awesome stylesheet and the excluded style guide template, each using a
     * token and an icon; no `js` or `views` roots beyond those.
     *
     * Assertions:
     *
     * Nothing is found and no file is counted as scanned. Scanning roots that do not exist finds nothing either.
     */
    val result = scan(
      "js/lib/vendor.js" -> """el.style.setProperty("--vendor", 1); x = "fas fa-vendor"""",
      "css/font-awesome.min.css" -> """:root { --fa: 1px } .fa-trash { color: #fff }""",
      "views/style_guide.scala.html" -> """<i class="fas fa-swatchbook" style="color: var(--fa)"></i>"""
    )

    result.filesScanned shouldBe 0
    result.rootTokens shouldBe empty
    result.runtimeTokens shouldBe empty
    result.references shouldBe empty
    result.literals shouldBe empty
    result.lengthLiterals shouldBe empty
    result.icons shouldBe empty

    scan().filesScanned shouldBe 0
  }

  test("The style guide's scan of the source tree leaves the vendored stylesheets out") {

    /**
     * Setup:
     *
     * The application's own source tree, scanned from the style guide's roots with its exclusions, as the page scans it.
     *
     * Assertions:
     *
     * The vendored stylesheets in `static/css` (Font Awesome's and Leaflet's) are present, and none of their color literals or
     * tokens is in the scan.
     */
    // Tests run in a sandbox: the sources are found through the directory build.mill hands them
    val moduleDir = Path.of(sys.env("ALTITUDE_SOURCE_DIR")).getParent
    val vendored = Seq("font-awesome.min.css", "leaflet.css")
    vendored.foreach(name => Files.exists(moduleDir.resolve(s"static/css/$name")) shouldBe true)

    val result = StyleGuideScan.scan(StyleGuideController.scanRoots(moduleDir), StyleGuideController.excludedFiles)
    val locations = result.literals.flatMap(_.locations) ++ (result.rootTokens ++ result.scopedTokens).map(_.location)

    result.filesScanned should be > 0
    vendored.foreach(name => locations.filter(_.startsWith(s"css/$name")) shouldBe empty)
  }
}
