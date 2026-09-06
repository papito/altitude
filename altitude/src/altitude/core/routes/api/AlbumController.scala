package altitude.core.routes.api

import cask.Request
import cask.Response
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.models.Album
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** Album JSON API: the list the Albums tab renders, and the membership changes made by drag/drop and the batch footer */
class AlbumController(using logger: Logger) extends BaseController:
  private val prefix = "api/album"

  /** Every album of the repository, by name: [ { "id": "uuid", "name": "album name", "numOfAssets": <count> }, ... ] */
  @requireLogin()
  @cask.get(f"/$prefix/r/:repoId/list")
  def getAlbumList(repoId: String)(using request: Request): Response[String] =
    val json = ujson.Arr.from(App.altitude.service.album.getAll.map(toJson))
    cask.Response(json.toString, 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.put(f"/$prefix/r/:repoId/assets")
  def addAssetsToAlbum(repoId: String)(using request: Request): Response[String] =
    val (albumId, assetIds) = membershipRequest(unscrubbedJson.get)
    logger.info(s"Adding assets ${assetIds.mkString(", ")} to album $albumId")

    val added = App.altitude.service.album.addAssets(albumId, assetIds)
    cask.Response(ujson.Obj("added" -> added).toString, 200, Seq(("Content-Type", "application/json")))

  @requireLogin()
  @cask.delete(f"/$prefix/r/:repoId/assets")
  def removeAssetsFromAlbum(repoId: String)(using request: Request): Response[String] =
    val (albumId, assetIds) = membershipRequest(unscrubbedJson.get)
    logger.info(s"Removing assets ${assetIds.mkString(", ")} from album $albumId")

    val removed = App.altitude.service.album.removeAssets(albumId, assetIds)
    cask.Response(ujson.Obj("removed" -> removed).toString, 200, Seq(("Content-Type", "application/json")))

  private def membershipRequest(jsonIn: ujson.Obj): (String, Set[String]) =
    (jsonIn(Api.Field.Album.ALBUM_ID).str, jsonIn(Api.Field.ASSET_IDS).arr.map(_.str).toSet)

  // Hand-built rather than the model codec: the API is camelCase
  private def toJson(album: Album): ujson.Obj =
    ujson.Obj(
      "id" -> album.persistedId,
      "name" -> album.name,
      "numOfAssets" -> album.numOfAssets
    )

  initialize()
