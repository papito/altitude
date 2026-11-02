package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.App
import altitude.core.Const
import altitude.core.ValidationException
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** Curation's partials: the modal's shell, and the fragment of a Curation set from one Sequence or a selection */
class CurationActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/curation"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId")
  def shell(repoId: String)(using request: Request): Response[String] = html(htmx.html.curation())

  /** No content when too few frames are left: a queue's Sequence gone meanwhile is passed over without comment */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/sequence/:sequenceId")
  def sequenceSet(repoId: String, sequenceId: String)(using request: Request): Response[String] =
    App.altitude.service.curation.sequenceSet(sequenceId) match
      case None => cask.Response("", 204, Seq(("Content-Type", "text/html")))
      case set => html(htmx.html.curation_set(set))

  /**
   * The set of the selection in the body (`selectionIds`, which refuses an empty one). The user asked for this set, so too few
   * frames left is a fragment saying so, not silence.
   */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/selection")
  def selectionSet(repoId: String)(using request: Request): Response[String] =
    val assetIds =
      try selectionIds(unscrubbedJson.get)
      catch case _: ValidationException => return badRequest("Expected assetIds, a comma-separated list of UUIDs")

    if assetIds.size > Const.Curation.MAX_SELECTION then
      logger.debug(s"Refused a Curation selection of ${assetIds.size} images")
      return badRequest(s"Curation compares at most ${Const.Curation.MAX_SELECTION} images at a time")

    html(htmx.html.curation_set(App.altitude.service.curation.selectionSet(assetIds)))

  private def html(payload: Html): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, Seq(("Content-Type", "text/html")))

  private def badRequest(message: String): Response[String] =
    cask.Response(message, 400, Seq(("Content-Type", "text/plain")))

  initialize()
