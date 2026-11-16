package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.ValidationException
import altitude.core.models.Folder
import altitude.core.models.LocationKind
import altitude.core.models.TriageScope
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requireMember
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection

/**
 * The Triage explorer's summary, scope dialog and Quick view. Triage lists folders, People and Locations but finds only triaged
 * assets, so one with none is described by a dialog pointing to Browse. Every count runs the query the search of that view would
 * run, so it agrees with the results the links open.
 */
class TriageController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/triage"
  private val fields = Api.Field.Search

  @requireLogin()
  @requireMember()
  @cask.get(f"/$prefix/r/:repoId/summary")
  def summary(repoId: String)(using request: Request): Response[String] =
    htmlResponse(htmx.html.triage_summary(App.altitude.service.library.triageSummary()))

  /**
   * A click on a folder, a Person or a Location. One with triaged assets is a 204, and the client searches Triage scoped to it;
   * one without, and a folder always, is the dialog with its Browse count. The root folder and a Category are a 400.
   */
  @requireLogin()
  @requireMember()
  @cask.get(f"/$prefix/r/:repoId/dialogs/scope")
  def scopeDialog(
      repoId: String,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      locationId: Option[String] = None)(using request: Request): Response[String] =
    (folderId.filter(_.nonEmpty), personId.filter(_.nonEmpty), locationId.filter(_.nonEmpty)) match
      case (Some(id), None, None) => answering(folderDialog(id))
      case (None, Some(id), None) => answering(personDialog(id))
      case (None, None, Some(id)) => answering(locationDialog(id))
      case _ =>
        textError(s"Name one of ${fields.FOLDER_ID}, ${fields.PERSON_ID} or ${fields.LOCATION_ID}", 400)

  /** The first assets Browse shows for the folder by default, newest import first, with the same total as its dialog */
  @requireLogin()
  @requireMember()
  @cask.get(f"/$prefix/r/:repoId/modals/quick-view")
  def quickView(repoId: String, folderId: String)(using request: Request): Response[String] =
    answering {
      val folder = describedFolder(folderId)
      val browse = browseScope(folderId = Some(folder.persistedId))
      val newestFirst = SearchSort(Api.Field.SearchSort.BY_ASSET_CREATED_AT, SortDirection.DESC)
      // The total is counted exactly below, so the search skips its own capped one
      val shown = App.altitude.service.library
        .search(browse.query(rpp = Const.Search.QUICK_VIEW_SIZE, searchSort = List(newestFirst)).withoutTotal)
        .records
      val total = App.altitude.service.library.count(browse.query())
      logger.debug(s"Quick view of folder [${folder.persistedId}]: ${shown.size} of $total assets")

      val path = App.altitude.service.folder.getAncestors(folder.persistedId) :+ folder
      htmlResponse(htmx.html.quick_view_modal(Const.UI.QUICK_VIEW_DIALOG_TITLE, folder, path, shown, total))
    }

  private def folderDialog(id: String): Response[String] =
    val folder = describedFolder(id)
    val count = App.altitude.service.library.count(browseScope(folderId = Some(folder.persistedId)).query())
    logger.debug(s"Folder [$id] has $count assets in Browse")
    htmlResponse(htmx.html.triage_scope_dialog(TriageScope.InFolder(folder, count)))

  private def personDialog(id: String): Response[String] =
    val person = App.altitude.service.person.getLiveInRepository(id)
    val triaged = countInTriage(personId = Some(id))
    logger.debug(s"Person [$id] has $triaged assets in Triage")
    if triaged > 0 then noContentResponse else htmlResponse(htmx.html.triage_scope_dialog(TriageScope.OfPerson(person)))

  private def locationDialog(id: String): Response[String] =
    val location = App.altitude.service.location.getById(id)
    if location.kind == LocationKind.Category then throw ValidationException(s"[$id] is a Category, which holds no assets")

    val triaged = countInTriage(locationId = Some(id))
    logger.debug(s"Location [$id] has $triaged assets in Triage")
    if triaged > 0 then noContentResponse
    else
      val count = App.altitude.service.library.count(browseScope(locationId = Some(id)).query())
      htmlResponse(htmx.html.triage_scope_dialog(TriageScope.InLocation(location, count)))

  /** Not the root, which is the whole repository */
  private def describedFolder(id: String): Folder =
    if App.altitude.service.folder.isRootFolder(id) then
      throw ValidationException("The root folder is the whole repository: there is nothing to describe")
    App.altitude.service.folder.getById(id)

  private def countInTriage(personId: Option[String] = None, locationId: Option[String] = None): Int =
    App.altitude.service.library.count(scope(Const.Search.View.TRIAGE, None, personId, locationId).query())

  /** Browse with its defaults: no triaged or recycled asset */
  private def browseScope(folderId: Option[String] = None, locationId: Option[String] = None): SearchRequestParser.Scope =
    scope(Const.Search.View.DEFAULT, folderId, None, locationId)

  /** The scope a search of the view would run; with no text, map area or subset, the parser has nothing to refuse */
  private def scope(
      view: String,
      folderId: Option[String],
      personId: Option[String],
      locationId: Option[String]): SearchRequestParser.Scope =
    val params = SearchRequestParser.Params(view = view, folderId = folderId, personId = personId, locationId = locationId)
    SearchRequestParser.parse(params) match
      case Right(scope) => scope
      case Left(message) => throw IllegalStateException(message)

  initialize()
