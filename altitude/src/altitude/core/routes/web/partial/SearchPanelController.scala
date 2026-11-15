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
 * The routes of the advanced search panel: the options its three comboboxes are offered for the text typed so far, and its form
 * filled in from the parameters of a search.
 *
 * An options route answers the `role="option"` elements of the names that have the typed text `q`, matched as the Search text
 * matches a name, the first `Const.Search.MAX_FILTER_OPTIONS` of them; with nothing typed, or text with no usable term, the first
 * of every name. The names are the context repository's alone, read afresh on every request.
 */
class SearchPanelController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/search"

  /** The folders by path, in the folder tree's order; the root is no option */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/folders")
  def folderOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_folders(App.altitude.service.search.folderOptions(q)))

  /**
   * The people a Search text finds by name, with their cover and the number of assets a search of them finds, by name. `view` and
   * `triaged` are the search's, as the panel's form holds them ([[viewOf]]): in the repository view only `triaged=true` counts a
   * Person's triaged assets, that view's rule (`SearchRequestParser`), and in the Triage view their triaged assets alone are
   * counted, where `triaged=false` is a 400, as a search of it is. A Person with nothing to find is no option. A Guest, who sees
   * Browse alone, is refused the triaged assets with a 403 (`refusedView`).
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

  /** The Locations, each with its Category before its name, by name; a Category is no option */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/locations")
  def locationOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_locations(App.altitude.service.search.locationOptions(q)))

  /**
   * The panel's form filled in from a search's parameters, which the client sends verbatim: the description (`describe`), without
   * the white space around it, the text as typed, the folder, the people and the Locations as chips named on the server, the
   * people match and the two date ranges. `view` and `triaged` are the view the search is of ([[viewOf]]) and its triage filter,
   * the assets it covers: the form opens on them, Library, Triage or All, and its People options count by them; with the Triage
   * view or `triaged=true` a Guest is refused as a search is (`refusedView`). The parameters that are no filter of the panel (the
   * sort, the grouping, the layout, paging) are accepted and ignored (`cask.QueryParams`), beside a description too, where a
   * search refuses them.
   *
   * The parameters are read as a search of that view reads them (`SearchRequestParser`), so what a search refuses is a plain-text
   * 400 here too. An ID that names nothing in the repository, one that no longer exists or another repository's, is left out of
   * the form, never a 404: the panel shows what is left of a search.
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

  /**
   * The view a search from the panel is of, as the panel's routes read `view`: the Triage view when the request names it, and the
   * repository view for anything else, since the panel searches no trash
   */
  private def viewOf(view: String): String =
    if view == Const.Search.View.TRIAGE then view else Const.Search.View.DEFAULT

  initialize()
