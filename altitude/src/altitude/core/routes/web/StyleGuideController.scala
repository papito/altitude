package altitude.core.routes.web

import cask.Request
import cask.model.Response
import java.nio.file.Path
import org.slf4j.Logger

import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.Environment
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.StyleGuideScan

object StyleGuideController:
  /** The sample dialog's one field */
  object Field:
    val NAME = "name"

  /** Where styles are written, under the `altitude` module directory */
  def scanRoots(moduleDir: Path): Seq[Path] =
    Seq("css", "js").map(moduleDir.resolve("static").resolve(_)) :+ moduleDir.resolve("views")

  // The vendored stylesheets (the icon font, Leaflet's), whose literals are no app's to tokenize; the guide itself, whose
  // specimens would count as uses of what they demonstrate; the setup page's stylesheet, whose `--setup-*` palette is that
  // page's own, not a token of the app; and the design tweaks panel's adapter (static/js/design-tweaks.js), which names
  // tokens to configure the panel (its sections, scale and seed targets), not to use them. The panel itself is the vendored
  // tokentweak package under static/js/lib, which the scan skips with every other library
  val excludedFiles: Set[String] = Set(
    "font-awesome.min.css",
    "leaflet.css",
    "style_guide.scala.html",
    "style_guide.css",
    "style-guide.js",
    "setup.css",
    "design-tweaks.js"
  )

/**
 * The style guide: a page of the design tokens, the shared primitives and a live sample dialog, and the source scan as JSON for
 * the design tweaks panel. `App` registers it in dev only, so in any other environment these routes do not exist.
 */
class StyleGuideController(using logger: Logger) extends BaseController:
  import StyleGuideController.{ excludedFiles, scanRoots, Field }

  private val prefix = "htmx/style-guide"

  // The working directory of a dev run is the repository root
  private val moduleDir = Path.of(Environment.ROOT_PATH, "altitude")

  private val nameScrubber = DataScrubber(trim = List(Field.NAME))
  private val nameValidator = ApiRequestValidator(required = List(Field.NAME))

  /** The page. The source tree is scanned on every request, so an edit to a stylesheet or a template shows on reload. */
  @requireLogin()
  @cask.get("/style-guide/r/:repoId")
  def styleGuide(repoId: String): Response[String] =
    val scan = StyleGuideScan.scan(scanRoots(moduleDir), excludedFiles)
    logger.debug(s"Style guide scanned ${scan.filesScanned} source files")

    // Embedded in a script element, which a "</" in a scanned value would otherwise be able to close
    val scanJson = ujson.write(scan.toJson).replace("</", "<\\/")
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.style_guide(stats = stats, scanJson = scanJson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The source scan as JSON: the sources of the design tweaks panel (the tokentweak package, `static/js/lib/tokentweak.js`, which
   * the adapter `static/js/design-tweaks.js` points here). The package reads its sources schema v1 from it, `version`,
   * `rootTokens` (each token's location and note for the CSS export), `literals` and `lengthLiterals` (the Not reachable tab);
   * the other keys are the style guide's and ignored. The tokens themselves the panel reads from the served stylesheet.
   */
  @requireLogin()
  @cask.get("/style-guide/scan.json")
  def scanJson(): Response[String] =
    val scan = StyleGuideScan.scan(scanRoots(moduleDir), excludedFiles)
    logger.debug(s"Style guide scanned ${scan.filesScanned} source files for the design tweaks panel")
    cask.Response(ujson.write(scan.toJson), 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/sample")
  def showSampleDialog(repoId: String): Response[String] =
    val payload = "<!doctype html>" + htmx.html.style_guide_sample_dialog()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /** The sample dialog's operation: validates as a real dialog does, and persists nothing */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/sample")
  def htmxSubmitSample(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = nameScrubber.scrub(unscrubbedJson.get)

    try nameValidator.validate(jsonIn)
    catch
      case validationException: ValidationException =>
        val payload = "<!doctype html>" + htmx.html.style_guide_sample_dialog(fieldErrors = validationException.errors.toMap)
        return dialogFormValidationResponse(payload)

    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  initialize()
