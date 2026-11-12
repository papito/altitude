package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
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

  /** The people a Search text finds by name, with their cover and number of assets, by name */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/people")
  def peopleOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_people(App.altitude.service.search.peopleOptions(q)))

  /** The Locations, each with its Category before its name, by name; a Category is no option */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/options/locations")
  def locationOptions(repoId: String, q: String = "")(using request: Request): Response[String] =
    htmlResponse(htmx.html.search_options_locations(App.altitude.service.search.locationOptions(q)))

  /**
   * The panel's form filled in from a search's parameters, which the client sends verbatim: the text as typed, the folder, the
   * people and the Locations as chips named on the server, the people match and the two date ranges. The parameters that are no
   * filter of the panel (the view, the sort, the layout, paging) are accepted and ignored (`cask.QueryParams`).
   *
   * The parameters are read as a search of the repository view reads them (`SearchRequestParser`), so what a search refuses is a
   * plain-text 400 here too. An ID that names nothing in the repository, one that no longer exists or another repository's, is
   * left out of the form, never a 404: the panel shows what is left of a search.
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
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    val parsed = SearchRequestParser.parse(
      view = Const.Search.View.DEFAULT,
      q = q,
      folderId = folderId,
      personId = personId,
      albumId = None,
      locationId = locationId,
      bbox = None,
      peopleMatch = peopleMatch,
      takenFrom = takenFrom,
      takenTo = takenTo,
      importedFrom = importedFrom,
      importedTo = importedTo
    )
    val scope = parsed match
      case Left(message) => return textError(message, 400)
      case Right(scope) => scope

    // The chips keep the order the IDs were given in, which the scope's sets do not
    val names = App.altitude.service.search
      .filterNames(folderId, SearchRequestParser.idsOf(personId), SearchRequestParser.idsOf(locationId))

    htmlResponse(
      htmx.html.search_panel(
        text = q.getOrElse(""),
        names = names,
        peopleMatch = scope.peopleMatch,
        takenRange = scope.takenRange,
        importedRange = scope.importedRange))

  initialize()
