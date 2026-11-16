package altitude.core.routes.web

import cask.Request
import cask.model.Response
import java.nio.file.Path
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.Environment
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.Repository
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.StyleGuideScan

object StyleGuideController:
  object Field:
    val NAME = "name"

  /** Where styles are written, under the `altitude` module directory */
  def scanRoots(moduleDir: Path): Seq[Path] =
    Seq("css", "js").map(moduleDir.resolve("static").resolve(_)) :+ moduleDir.resolve("views")

  // Left out of the scan: vendored stylesheets, whose literals are not the app's; the guide's own files, whose specimens would
  // count as uses; the setup page's private `--setup-*` palette; and the design tweaks adapter, which names tokens to
  // configure the panel rather than to use them. Everything under a `lib/` directory is skipped by the scan itself
  val excludedFiles: Set[String] = Set(
    "font-awesome.min.css",
    "leaflet.css",
    "flatpickr.css",
    "style_guide.scala.html",
    "style_guide.css",
    "style-guide.js",
    "setup.css",
    "design-tweaks.js"
  )

/** The style guide page and the source scan behind it. `App` registers these routes in dev only. */
class StyleGuideController(using logger: Logger) extends BaseController:
  import StyleGuideController.{ excludedFiles, scanRoots, Field }

  private val prefix = "htmx/style-guide"

  // The working directory of a dev run is the repository root
  private val moduleDir = Path.of(Environment.ROOT_PATH, "altitude")

  private val nameScrubber = DataScrubber(trim = List(Field.NAME))
  private val nameValidator = ApiRequestValidator(required = List(Field.NAME))

  /** Scans the source tree on every request, so an edit shows on reload */
  @requireLogin()
  @rememberRepository()
  @cask.get("/style-guide/r/:repoId")
  def styleGuide(repoId: String): Response[String] =
    val scan = StyleGuideScan.scan(scanRoots(moduleDir), excludedFiles)
    logger.debug(s"Style guide scanned ${scan.filesScanned} source files")

    // Embedded in a script element, which a "</" in a scanned value could otherwise close
    val scanJson = ujson.write(scan.toJson).replace("</", "<\\/")
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.style_guide(stats = stats, scanJson = scanJson)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The source scan for the design tweaks panel (`static/js/lib/tokentweak.js`), which reads its sources schema v1 from it:
   * `version`, `rootTokens`, `literals` and `lengthLiterals`. The other keys are the style guide's.
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

  /** The New library dialog in its name-taken error state, posting to the sample operation below rather than the create route */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/new-library")
  def showNewLibrarySample(repoId: String): Response[String] =
    htmlResponse(
      htmx.html.new_library_dialog(
        fieldErrors = Map(Api.Field.Library.NAME -> C.Msg.Err.LIBRARY_NAME_TAKEN.format("client smith")),
        formJson = ujson.Obj(Api.Field.Library.NAME -> "client  smith"),
        postUrl = newLibrarySampleUrl(repoId)
      ))

  /** Validates the name as the create route does, except for whether it is taken, and creates nothing */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/new-library")
  def htmxSubmitNewLibrarySample(repoId: String)(using request: Request): Response[String] =
    val jsonIn: ujson.Obj = unscrubbedJson.get
    val name = jsonIn.obj.get(Api.Field.Library.NAME).flatMap(_.strOpt).getOrElse("")

    try Repository.validName(name)
    catch
      case ex: ValidationException =>
        return dialogFormValidationResponse(
          "<!doctype html>" + htmx.html.new_library_dialog(
            fieldErrors = Map(Api.Field.Library.NAME -> ex.errors.getOrElse(FieldConst.Repository.NAME, ex.message)),
            formJson = jsonIn,
            postUrl = newLibrarySampleUrl(repoId)
          ))

    cask.Response("", 200, Seq(("Content-Type", "text/html")))

  private def newLibrarySampleUrl(repoId: String) = s"/$prefix/r/$repoId/new-library"

  /** Validates as a real dialog does and persists nothing */
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
