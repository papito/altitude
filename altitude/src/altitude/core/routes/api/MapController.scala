package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.GeocoderException
import altitude.core.IllegalOperationException
import altitude.core.QueryTimeoutException
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.decorators.refusedView
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.util.BoundingBox

/**
 * Viewport aggregates and place-name lookup; no route sends the map a library's asset rows.
 *
 * Cells and bounds take the search scope as the grid does, an advanced search's people, Locations and date ranges included, so
 * the client sends its search parameters verbatim: the ones that only shape a grid (`sort`, `layout`, grouping, paging) are
 * accepted and ignored (`cask.QueryParams`), and the `bbox` search filter is ignored too. `bbox` is the crowded-pin panel's
 * scope, and the map behind the panel keeps showing the whole search; `viewport` is the map's own clipping box. A visible
 * Location keeps its full matching membership count even when a member's own GPS point is outside the viewport. A Guest's cells
 * or bounds of Triage or the Trash, or of `triaged=true`, are a JSON 403 (`refusedView`): it sees Browse alone.
 */
class MapController(using logger: Logger) extends BaseController:
  private val prefix = "api/map"

  // viewport and zoom are strings so invalid or missing values receive our JSON error, including non-integer zooms.
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/cells")
  def cells(
      repoId: String,
      viewport: Option[String] = None,
      zoom: Option[String] = None,
      view: String = Const.Search.View.DEFAULT,
      q: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None,
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None,
      triaged: Option[Boolean] = None,
      subset: Option[String] = None,
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    refusedView(view, triaged, json = true) match
      case Some(refusal) => return refusal
      case None =>
    val parsed =
      mapScope(
        view,
        q,
        folderId,
        personId,
        albumId,
        locationId,
        triaged,
        subset,
        peopleMatch,
        takenFrom,
        takenTo,
        importedFrom,
        importedTo)
    val scope = parsed match
      case Left(message) => return jsonError(message, 400)
      case Right(scope) => scope
    val box = viewport match
      case None => return jsonError("viewport is required", 400)
      case Some(value) =>
        try BoundingBox.parse(value)
        catch case ex: IllegalArgumentException => return jsonError(s"Invalid viewport: ${ex.getMessage}", 400)
    val level = zoom.flatMap(_.toIntOption) match
      case None => return jsonError("zoom must be an integer", 400)
      case Some(level) => level
    logger.debug(s"Map cells in $box at zoom $level")
    val result =
      try App.altitude.service.library.mapCells(scope.query(), box, level)
      catch case _: QueryTimeoutException => return jsonError(Const.Msg.Err.SEARCH_TIMED_OUT, 503)
    jsonResponse(
      ujson.Obj(
        "cells" -> ujson.Arr.from(
          result.cells.map(
            cell =>
              ujson.Obj(
                "count" -> cell.count,
                "latitude" -> cell.latitude,
                "longitude" -> cell.longitude,
                "assetId" -> cell.assetId))),
        "locations" -> ujson.Arr.from(
          result.locations.map(
            location =>
              ujson.Obj(
                "id" -> location.id,
                "name" -> location.name,
                "categoryName" -> location.categoryName.map(ujson.Str(_)).getOrElse(ujson.Null),
                "latitude" -> location.latitude,
                "longitude" -> location.longitude,
                "count" -> location.count
              ))),
        "countsPlottedPoints" -> true
      ))

  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/bounds")
  def bounds(
      repoId: String,
      view: String = Const.Search.View.DEFAULT,
      q: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None,
      peopleMatch: Option[String] = None,
      takenFrom: Option[String] = None,
      takenTo: Option[String] = None,
      importedFrom: Option[String] = None,
      importedTo: Option[String] = None,
      triaged: Option[Boolean] = None,
      subset: Option[String] = None,
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    refusedView(view, triaged, json = true) match
      case Some(refusal) => return refusal
      case None =>
    val parsed =
      mapScope(
        view,
        q,
        folderId,
        personId,
        albumId,
        locationId,
        triaged,
        subset,
        peopleMatch,
        takenFrom,
        takenTo,
        importedFrom,
        importedTo)
    val scope = parsed match
      case Left(message) => return jsonError(message, 400)
      case Right(scope) => scope
    val result =
      try App.altitude.service.library.mapBounds(scope.query())
      catch case _: QueryTimeoutException => return jsonError(Const.Msg.Err.SEARCH_TIMED_OUT, 503)
    logger.debug(s"Map bounds: $result")
    jsonResponse(
      result.fold(ujson.Obj("count" -> 0))(
        bounds =>
          ujson.Obj(
            "south" -> bounds.south,
            "west" -> bounds.west,
            "north" -> bounds.north,
            "east" -> bounds.east,
            "count" -> bounds.count)))

  @requireLogin()
  @requirePermission(Permission.Organize)
  @cask.get(f"/$prefix/r/:repoId/geocode")
  def geocode(repoId: String, q: String = "")(using request: Request): Response[String] =
    try
      jsonResponse(
        ujson.Arr.from(
          App.altitude.service.geocoder
            .search(q)
            .map(place => ujson.Obj("label" -> place.label, "latitude" -> place.latitude, "longitude" -> place.longitude))))
    catch
      case _: IllegalOperationException => jsonError("The geocoder is disabled", 404)
      case ex: GeocoderException => jsonError(ex.getMessage, 502)

  /**
   * The search scope without the `bbox` filter: the map plots the whole search, only the panel and the grid apply `bbox`. A scope
   * the parser refuses (Search text with the trash view, an unknown `subset`, a malformed date or people match, too many people
   * or Locations) is the message of a JSON 400.
   */
  private def mapScope(
      view: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      locationId: Option[String],
      triaged: Option[Boolean],
      subset: Option[String],
      peopleMatch: Option[String],
      takenFrom: Option[String],
      takenTo: Option[String],
      importedFrom: Option[String],
      importedTo: Option[String]): Either[String, SearchRequestParser.Scope] =
    SearchRequestParser.parse(
      view,
      q,
      folderId,
      personId,
      albumId,
      locationId,
      bbox = None,
      triaged,
      subset,
      peopleMatch,
      takenFrom,
      takenTo,
      importedFrom,
      importedTo)

  initialize()
