package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.models.CountedAssets
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.decorators.refusedView
import altitude.core.routes.decorators.requireLogin

/**
 * The advanced search panel: its comboboxes' options and its form filled in from a search. An options route matches `q` as the
 * Search text matches a name, up to `Const.Search.MAX_FILTER_OPTIONS`; with no usable term, the first names unfiltered.
 */
class SearchPanelController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/search"

  /** In the folder tree's order; the root is no option */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/folders")
  def folderOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_folders(App.altitude.service.search.folderOptions(q)))

  /**
   * Each Person counts the assets a search of them would find in the panel's `view` and `triaged` scope, so a Person with nothing
   * to find is no option.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/people")
  def peopleOptions(repoId: String, q: String = "", view: String = Const.Search.View.DEFAULT, triaged: Option[Boolean] = None)(
      using request: Request): Response[String] =
    val searched = viewOf(view)
    refusedView(searched, triaged) match
      case Some(refusal) => return refusal
      case None =>
    SearchRequestParser.parse(SearchRequestParser.Params(view = searched, triaged = triaged)) match
      case Left(message) => return textError(message, 400)
      case Right(_) =>
    val counted =
      if searched == Const.Search.View.TRIAGE then CountedAssets.Triaged
      else if triaged.contains(true) then CountedAssets.All
      else CountedAssets.Sorted
    htmlResponse(htmx.html.search_options_people(App.altitude.service.search.peopleOptions(q, counted)))

  /** A Category is no option */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/locations")
  def locationOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_locations(App.altitude.service.search.locationOptions(q)))

  /**
   * The panel's form filled in from a search's parameters, parsed as a search of that view parses them, so what a search refuses
   * is a 400 here too. Parameters that are no filter of the panel (sort, grouping, layout, paging) are ignored. An ID that names
   * nothing in the repository is left out of the form rather than a 404: the panel shows what is left of a search.
   */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/panel")
  def panel(
      repoId: String,
      q: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      locationId: Option[String] = None,
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None,
      view: String = Const.Search.View.DEFAULT,
      triaged: Option[Boolean] = None,
      describe: Option[String] = None,
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    val searched = viewOf(view)
    refusedView(searched, triaged) match
      case Some(refusal) => return refusal
      case None =>
    val parsed = SearchRequestParser.parse(
      SearchRequestParser.Params(
        view = searched,
        triaged = triaged,
        describe = describe,
        q = q,
        folderId = folderId,
        personId = personId,
        locationId = locationId,
        peopleMatch = peopleMatch,
        takenFrom = takenFrom,
        takenTo = takenTo,
        importedFrom = importedFrom,
        importedTo = importedTo
      ))
    val scope = parsed match
      case Left(message) => return textError(message, 400)
      case Right(scope) => scope

    htmlResponse(
      htmx.html.search_panel(
        description = scope.description.getOrElse(""),
        text = q.getOrElse(""),
        names = App.altitude.service.search.filterNames(scope.folderIds.headOption, scope.personIds, scope.locationIds),
        peopleMatch = scope.peopleMatch,
        takenRange = scope.takenRange,
        importedRange = scope.importedRange,
        view = searched,
        showTriaged = triaged.contains(true)
      ))

  /** The Triage view when named, else the repository view, since the panel searches no trash */
  private def viewOf(view: String): String =
    if view == Const.Search.View.TRIAGE then view else Const.Search.View.DEFAULT

  initialize()
