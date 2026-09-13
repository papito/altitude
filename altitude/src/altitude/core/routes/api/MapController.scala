package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.Const
import altitude.core.GeocoderException
import altitude.core.routes.BaseController
import altitude.core.routes.SearchRequestParser
import altitude.core.routes.decorators.requireLogin
import altitude.core.util.BoundingBox

/**
 * Viewport aggregates and place-name lookup; no route sends the map a library's asset rows.
 *
 * Cells and bounds take the search scope as the grid does, so the client sends its search parameters verbatim: the ones that only
 * shape a grid (`sort`, `layout`, grouping, paging) are accepted and ignored (`cask.QueryParams`), and the `bbox` search filter
 * is ignored too. `bbox` is the crowded-pin panel's scope, and the map behind the panel keeps showing the whole search;
 * `viewport` is the map's own clipping box. A visible Location keeps its full matching membership count even when a member's own
 * GPS point is outside the viewport.
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
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    val scope = mapScope(view, q, folderId, personId, albumId, locationId)
    val box = viewport match
      case None => return jsonError("viewport is required", 400)
      case Some(value) =>
        try BoundingBox.parse(value)
        catch case ex: IllegalArgumentException => return jsonError(s"Invalid viewport: ${ex.getMessage}", 400)
    val level = zoom.flatMap(_.toIntOption) match
      case None => return jsonError("zoom must be an integer", 400)
      case Some(level) => level
    logger.debug(s"Map cells in $box at zoom $level")
    val result = App.altitude.service.library.mapCells(scope.query(), box, level)
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
                "parentName" -> location.parentName.map(ujson.Str(_)).getOrElse(ujson.Null),
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
      params: cask.QueryParams /* allow unknown params */ )(using request: Request): Response[String] =
    val result = App.altitude.service.library.mapBounds(mapScope(view, q, folderId, personId, albumId, locationId).query())
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
  @cask.get(f"/$prefix/r/:repoId/geocode")
  def geocode(repoId: String, q: String = "")(using request: Request): Response[String] =
    val geocoder = App.altitude.service.geocoder
    if !geocoder.isEnabled then return jsonError("The geocoder is disabled", 404)
    try
      jsonResponse(
        ujson.Arr.from(
          geocoder
            .search(q)
            .map(place => ujson.Obj("label" -> place.label, "latitude" -> place.latitude, "longitude" -> place.longitude))))
    catch case ex: GeocoderException => jsonError(ex.getMessage, 502)

  /** The search scope without the `bbox` filter: the map plots the whole search, only the panel and the grid apply `bbox`. */
  private def mapScope(
      view: String,
      q: Option[String],
      folderId: Option[String],
      personId: Option[String],
      albumId: Option[String],
      locationId: Option[String]): SearchRequestParser.Scope =
    SearchRequestParser.parse(view, q, folderId, personId, albumId, locationId, bbox = None) match
      case Right(scope) => scope
      case Left(message) => throw IllegalStateException(message) // unreachable without a bbox

  initialize()
