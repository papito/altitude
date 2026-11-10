package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger
import play.twirl.api.Html

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
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection

/**
 * The routes of the Triage explorer: the Triage summary above its tabs, the scope dialog that answers a click on a folder, a
 * Person or a Location there, and Quick view, the read-only look at a folder that the folder's dialog offers.
 *
 * Triage lists what it manages but finds only triaged assets: a Person or a Location with some is searched in Triage, and one
 * without them, or a folder, which holds no triaged asset, is described by a popover dialog pointing to Browse. Every count is
 * `LibraryService.count` over the query the search of that view would run, so it agrees with the results the links open.
 */
class TriageController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/triage"
  private val fields = Api.Field.Search

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/summary")
  def summary(repoId: String)(using request: Request): Response[String] =
    html(htmx.html.triage_summary(App.altitude.service.library.triageSummary()))

  /**
   * What a click on a folder, a Person or a Location of the Triage explorer shows, named by exactly one of `folderId`, `personId`
   * or `locationId`; none or several (an empty value names nothing) is a 400.
   *
   * A Person or a Location with triaged assets is a 204 with no body: the client searches Triage scoped to it. One without is the
   * popover dialog saying so, with a link to it in Browse, unless a Location has no assets at all. A folder is always the dialog,
   * with its Browse count: the folder and its subfolders, without triaged or recycled assets. The root folder is the whole
   * repository and a 400, as is a Category, which holds no assets. An unknown folder, Person or Location, or another
   * repository's, is a 404, and so is a Person merged away or discarded as a bad match.
   */
  @requireLogin()
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
        cask.Response(
          s"Name one of ${fields.FOLDER_ID}, ${fields.PERSON_ID} or ${fields.LOCATION_ID}",
          400,
          Seq("Content-Type" -> "text/plain"))

  /**
   * Quick view of a folder: the folder's path, and the Previews of the first `Const.Search.QUICK_VIEW_SIZE` assets Browse shows
   * for it by default (the folder and its subfolders, newest import first), with how many there are in all. The root folder is a
   * 400; an unknown folder, or another repository's, is a 404.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/modals/quick-view")
  def quickView(repoId: String, folderId: String)(using request: Request): Response[String] =
    answering {
      val folder = describedFolder(folderId)
      val browse = browseScope(folderId = Some(folder.persistedId))
      val newestFirst = SearchSort(Api.Field.SearchSort.BY_ASSET_CREATED_AT, SortDirection.DESC)
      val shown = App.altitude.service.library
        .search(browse.query(rpp = Const.Search.QUICK_VIEW_SIZE, searchSort = List(newestFirst)))
        .records
      val total = App.altitude.service.library.count(browse.query())
      logger.debug(s"Quick view of folder [${folder.persistedId}]: ${shown.size} of $total assets")

      val path = App.altitude.service.folder.getAncestors(folder.persistedId) :+ folder
      html(htmx.html.quick_view_modal(Const.UI.QUICK_VIEW_DIALOG_TITLE, folder, path, shown, total))
    }

  private def folderDialog(id: String): Response[String] =
    val folder = describedFolder(id)
    val count = App.altitude.service.library.count(browseScope(folderId = Some(folder.persistedId)).query())
    logger.debug(s"Folder [$id] has $count assets in Browse")
    html(htmx.html.triage_scope_dialog(TriageScope.InFolder(folder, count)))

  private def personDialog(id: String): Response[String] =
    val person = App.altitude.service.person.getLiveInRepository(id)
    val triaged = countInTriage(personId = Some(id))
    logger.debug(s"Person [$id] has $triaged assets in Triage")
    if triaged > 0 then noContent else html(htmx.html.triage_scope_dialog(TriageScope.OfPerson(person)))

  private def locationDialog(id: String): Response[String] =
    val location = App.altitude.service.location.getById(id)
    if location.kind == LocationKind.Category then throw ValidationException(s"[$id] is a Category, which holds no assets")

    val triaged = countInTriage(locationId = Some(id))
    logger.debug(s"Location [$id] has $triaged assets in Triage")
    if triaged > 0 then noContent
    else
      val count = App.altitude.service.library.count(browseScope(locationId = Some(id)).query())
      html(htmx.html.triage_scope_dialog(TriageScope.InLocation(location, count)))

  /** A folder a dialog can describe: one of the repository's, but not the root, which is the whole repository */
  private def describedFolder(id: String): Folder =
    if App.altitude.service.folder.isRootFolder(id) then
      throw ValidationException("The root folder is the whole repository: there is nothing to describe")
    App.altitude.service.folder.getInRepository(id)

  private def countInTriage(personId: Option[String] = None, locationId: Option[String] = None): Int =
    App.altitude.service.library.count(scope(Const.Search.View.TRIAGE, None, personId, locationId).query())

  /** Browse with its defaults, scoped to a folder or a Location: no triaged or recycled asset */
  private def browseScope(folderId: Option[String] = None, locationId: Option[String] = None): SearchRequestParser.Scope =
    scope(Const.Search.View.DEFAULT, folderId, None, locationId)

  /** The scope a search of the view would run; with no text, map area or subset, the parser has nothing to refuse */
  private def scope(
      view: String,
      folderId: Option[String],
      personId: Option[String],
      locationId: Option[String]): SearchRequestParser.Scope =
    SearchRequestParser.parse(view, q = None, folderId, personId, albumId = None, locationId, bbox = None) match
      case Right(scope) => scope
      case Left(message) => throw IllegalStateException(message)

  private def html(payload: Html): Response[String] =
    cask.Response("<!doctype html>" + payload, 200, Seq("Content-Type" -> "text/html"))

  private def noContent: Response[String] = cask.Response("", 204, Seq("Content-Type" -> "text/html"))

  initialize()
