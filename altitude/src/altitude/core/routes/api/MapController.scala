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

/** Viewport aggregates and place-name lookup; no route sends the map a library's asset rows. */
class MapController(using logger: Logger) extends BaseController:
  private val prefix = "api/map"

  // Sort accompanies the search scope but only orders grids, never aggregates.
  // bbox and zoom are strings so invalid or missing values receive our JSON error, including non-integer zooms.
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/cells")
  def cells(
      repoId: String,
      bbox: Option[String] = None,
      zoom: Option[String] = None,
      view: String = Const.Search.View.DEFAULT,
      q: Option[String] = None,
      sort: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None)(using request: Request): Response[String] =
    val scope = SearchRequestParser.parse(view, q, folderId, personId, albumId, locationId, bbox) match
      case Left(message) => return error(message, 400)
      case Right(scope) => scope
    val viewport = scope.bbox match
      case None => return error("bbox is required", 400)
      case Some(viewport) => viewport
    val level = zoom.flatMap(_.toIntOption) match
      case None => return error("zoom must be an integer", 400)
      case Some(level) => level
    logger.debug(s"Map cells in $viewport at zoom $level")
    // The viewport clips cells and pins, not the search's assets: visible Locations keep their full matching membership count.
    val result = App.altitude.service.library.mapCells(scope.copy(bbox = None).query(), viewport, level)
    json(
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
      sort: Option[String] = None,
      folderId: Option[String] = None,
      personId: Option[String] = None,
      albumId: Option[String] = None,
      locationId: Option[String] = None,
      bbox: Option[String] = None)(using request: Request): Response[String] =
    val scope = SearchRequestParser.parse(view, q, folderId, personId, albumId, locationId, bbox) match
      case Left(message) => return error(message, 400)
      case Right(scope) => scope
    val result = App.altitude.service.library.mapBounds(scope.query())
    logger.debug(s"Map bounds: $result")
    json(
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
    if !geocoder.isEnabled then return error("The geocoder is disabled", 404)
    try
      json(
        ujson.Arr.from(
          geocoder
            .search(q)
            .map(place => ujson.Obj("label" -> place.label, "latitude" -> place.latitude, "longitude" -> place.longitude))))
    catch case ex: GeocoderException => error(ex.getMessage, 502)

  private def json(value: ujson.Value, status: Int = 200): Response[String] =
    cask.Response(value.toString, status, Seq("Content-Type" -> "application/json"))

  private def error(message: String, status: Int): Response[String] = json(ujson.Obj("error" -> message), status)

  initialize()
