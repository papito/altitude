package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.ValidationException
import altitude.core.models.ImportOutcome
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

/**
 * The Import history of the Import page: the Library's Imports, and the Import files of one. Both are read by every account that
 * holds Import in the Library, whichever account delivered the Import, unlike the upload routes (`ImportController`), which take
 * an Import's own account alone.
 */
class ImportHistoryController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/import"
  private val sizes = Const.ImportHistory

  /**
   * The top of the Library's Import history: its first `shown` Imports, the ones in progress first, then the newest first.
   * `shown` is held between what the history shows at first and the most it shows.
   */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.get(f"/$prefix/r/:repoId/history")
  def history(repoId: String, shown: Int = sizes.IMPORTS_STEP)(using request: Request): Response[String] =
    val within = shown.max(sizes.IMPORTS_STEP).min(sizes.MAX_IMPORTS)
    htmlResponse(htmx.html.import_history(App.altitude.service.imports.history(within), within))

  /**
   * The first `shown` Import files of an Import in path order, those of one `outcome` when it names one (an unknown one is a
   * 400). `shown` is held between what the list shows at first and the most it shows. An Import that is not the Library's is a
   * 404.
   */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.get(f"/$prefix/r/:repoId/imports/:importId/files")
  def files(repoId: String, importId: String, outcome: String = "", shown: Int = sizes.FILES_STEP)(using
      request: Request): Response[String] =
    answering {
      val filter = Option(outcome).filter(_.nonEmpty).map {
        named =>
          ImportOutcome.values.find(_.dbValue == named).getOrElse(throw ValidationException(s"[$named] is no outcome of a file"))
      }
      val within = shown.max(sizes.FILES_STEP).min(sizes.MAX_FILES)
      logger.debug(s"Files of Import [$importId]: the first $within${filter.fold("")(of => s" ${of.dbValue}")}")
      htmlResponse(htmx.html.import_files(App.altitude.service.imports.filesShown(importId, filter, within), within))
    }

  initialize()
