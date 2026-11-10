package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.App
import altitude.core.Const
import altitude.core.Validators
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/**
 * Curation's partials: the modal's shell, the rows of its queue, and the fragment of a Curation set from one Sequence or a
 * selection
 */
class CurationActionController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/curation"

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId")
  def shell(repoId: String)(using request: Request): Response[String] = htmlResponse(htmx.html.curation())

  /** The queue's rows, which the shell's Sequence rail lists, in the order Curation walks them */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/queue")
  def queue(repoId: String)(using request: Request): Response[String] =
    htmlResponse(htmx.html.curation_queue(App.altitude.service.curation.queue()))

  /** No content when too few frames are left: a queue's Sequence gone meanwhile is passed over without comment */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/sequence/:sequenceId")
  def sequenceSet(repoId: String, sequenceId: String)(using request: Request): Response[String] =
    if !Validators.isUuid(sequenceId) then return textError(Const.Msg.Err.VALUE_NOT_A_UUID, 400)

    App.altitude.service.curation.sequenceSet(sequenceId) match
      case None => noContentResponse
      case set => htmlResponse(htmx.html.curation_set(set))

  /**
   * The set of the selection in the body (`selectionIds`, which refuses an empty one). The user asked for this set, so too few
   * frames left is a fragment saying so, not silence.
   */
  @requireLogin()
  @cask.post(f"/$prefix/r/:repoId/selection")
  def selectionSet(repoId: String)(using request: Request): Response[String] =
    Try(selectionIds(unscrubbedJson.get)).toOption match
      case None => textError("Expected assetIds, a comma-separated list of UUIDs", 400)
      case Some(assetIds) if assetIds.size > Const.Curation.MAX_SELECTION =>
        logger.debug(s"Refused a Curation selection of ${assetIds.size} images")
        textError(s"Curation compares at most ${Const.Curation.MAX_SELECTION} images at a time", 400)
      case Some(assetIds) => htmlResponse(htmx.html.curation_set(App.altitude.service.curation.selectionSet(assetIds)))

  initialize()
