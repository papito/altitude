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

/**
 * The style guide: a page of the design tokens, the shared primitives and a live sample dialog. `App` registers it in dev only,
 * so in any other environment these routes do not exist.
 */
class StyleGuideController(using logger: Logger) extends BaseController:
  import StyleGuideController.Field

  private val prefix = "htmx/style-guide"

  /** Where styles are written, under the working directory of a dev run (the repository root) */
  private val scanRoots: Seq[Path] =
    Seq("css", "js").map(Path.of(Environment.ROOT_PATH, "altitude", "static", _)) :+
      Path.of(Environment.ROOT_PATH, "altitude", "views")

  // The vendored icon font, and the guide itself: its specimens would count as uses of what they demonstrate
  private val excludedFiles = Set("font-awesome.min.css", "style_guide.scala.html", "style-guide.js")

  private val nameScrubber = DataScrubber(trim = List(Field.NAME))
  private val nameValidator = ApiRequestValidator(required = List(Field.NAME))

  /** The page. The source tree is scanned on every request, so an edit to a stylesheet or a template shows on reload. */
  @requireLogin()
  @cask.get("/style-guide/r/:repoId")
  def styleGuide(repoId: String): Response[String] =
    val scan = StyleGuideScan.scan(scanRoots, excludedFiles)
    logger.debug(s"Style guide scanned ${scan.filesScanned} source files")

    // Embedded in a script element, which a "</" in a scanned value would otherwise be able to close
    val scanJson = ujson.write(scan.toJson).replace("</", "<\\/")
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.style_guide(stats = stats, scanJson = scanJson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

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
