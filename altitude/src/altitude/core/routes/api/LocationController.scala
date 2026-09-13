package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import scala.util.Try

import altitude.core.Api
import altitude.core.App
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.models.Location
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** Location JSON API: the list the Locations tab renders, and the membership changes made by drag/drop and the batch footer */
class LocationController(using logger: Logger) extends BaseController:
  private val prefix = "api/location"

  /** Parents and Locations in path order, with nullable pin and parent fields and membership counts */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/list")
  def getLocationList(repoId: String)(using request: Request): Response[String] =
    val json = ujson.Arr.from(App.altitude.service.location.getAll.map(toJson))
    cask.Response(json.toString, 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/assets")
  def addAssetsToLocation(repoId: String)(using request: Request): Response[String] =
    membershipChange("added", App.altitude.service.location.addAssets)

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/assets")
  def removeAssetsFromLocation(repoId: String)(using request: Request): Response[String] =
    membershipChange("removed", App.altitude.service.location.removeAssets)

  /** Bad payloads and parent targets are client errors; a Location outside this repository is not found. */
  private def membershipChange(countKey: String, change: (String, Set[String]) => Int)(using request: Request): Response[String] =
    val (locationId, assetIds) = Try(membershipRequest(unscrubbedJson.get)).toOption match
      case None => return error("Expected locationId and an assetIds array of strings", 400)
      case Some(membership) => membership
    try
      val count = change(locationId, assetIds)
      cask.Response(ujson.Obj(countKey -> count).toString, 200, Seq("Content-Type" -> "application/json"))
    catch
      case ex: NotFoundException => error(ex.getMessage, 404)
      case ex: IllegalOperationException => error(ex.getMessage, 400)

  private def error(message: String, status: Int): Response[String] =
    cask.Response(ujson.Obj("error" -> message).toString, status, Seq("Content-Type" -> "application/json"))

  private def membershipRequest(jsonIn: ujson.Obj): (String, Set[String]) =
    (jsonIn(Api.Field.Location.LOCATION_ID).str, jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet)

  // Hand-built rather than the model codec: the API is camelCase
  private def toJson(location: Location): ujson.Obj =
    ujson.Obj(
      "id" -> location.persistedId,
      "name" -> location.name,
      "kind" -> location.kind.dbValue,
      "parentId" -> location.parentId.map(ujson.Str(_)).getOrElse(ujson.Null),
      "parentName" -> location.parentName.map(ujson.Str(_)).getOrElse(ujson.Null),
      "latitude" -> location.latitude.map(ujson.Num(_)).getOrElse(ujson.Null),
      "longitude" -> location.longitude.map(ujson.Num(_)).getOrElse(ujson.Null),
      "radiusM" -> location.radiusM.map(n => ujson.Num(n)).getOrElse(ujson.Null),
      "numOfAssets" -> location.numOfAssets
    )

  initialize()
