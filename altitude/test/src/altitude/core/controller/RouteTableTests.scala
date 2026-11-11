package altitude.core.controller

import altitude.test.TestContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import scala.jdk.CollectionConverters.*
import scala.meta.*
import scala.util.matching.Regex

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.models.Person
import altitude.core.models.Repository

/**
 * One table over every route of `App.allRoutes`: who may call it, and how it answers another repository's IDs. A route the table
 * does not list fails the first test, so a new route has to be added to it, with its ID parameters probed.
 */
@DoNotDiscover class RouteTableTests extends ControllerTestCore {

  /** Who may call a route: anyone logged in, or anyone, for the reason given */
  private enum Access:
    case Login
    case Public(reason: String)

  /**
   * What a route takes: no entity ID at all, a parameter that names no row of a repository (with the reason), or entity IDs,
   * which the probes send
   */
  private enum Ids:
    case NoEntity
    case NotEntity(params: Set[String], reason: String)
    case Probed(probes: Fixtures => List[Probe])

  /**
   * A request to a route with IDs of the other repository, and its answer: `path` fills the path parameters other than `:repoId`,
   * `query` and `body` the rest, `json` asks for JSON. An answer other than a 404 says why in `why`.
   */
  private case class Probe(
      status: Int,
      path: Map[String, String] = Map.empty,
      query: Map[String, String] = Map.empty,
      body: Option[ujson.Value] = None,
      json: Boolean = false,
      why: String = "") {
    def sent: String = (path.values ++ query.values ++ body.map(ujson.write(_))).mkString(" ")
  }

  /**
   * A route: its method, its path, the IDs it takes and who may call it. `ignored` names the ID fields the route's code names
   * through `Api.Field` but does not act on (one it writes into its answer, or one a helper it shares with other routes handles),
   * each with why.
   */
  private case class Row(
      method: String,
      path: String,
      ids: Ids,
      access: Access = Access.Login,
      ignored: Map[String, String] = Map.empty) {
    def key: (String, String) = (method, path)

    def ignoring(fields: (String, String)*): Row = copy(ignored = ignored ++ fields)
  }

  /**
   * The user's repository (`mine`) and their other repository (`theirs`) and the IDs a probe sends: the `my*` IDs are the first
   * repository's, the rest the other's. Every name in the other repository carries `MARK`, which no answer may show.
   */
  private case class Fixtures(
      mine: Repository,
      theirs: Repository,
      myAsset: String,
      myRootFolder: String,
      myFolder: String,
      myAlbum: String,
      myLocation: String,
      myPerson: String,
      myFace: String,
      asset: String,
      assetFileName: String,
      frames: List[String],
      recycled: String,
      folder: String,
      album: String,
      location: String,
      category: String,
      person: String,
      face: String,
      sequence: String) {

    /** The other repository's IDs, which an answer may show only when the request sent them */
    def theirIds: List[String] =
      List(asset, recycled, folder, album, location, category, person, face, sequence, theirs.rootFolderId) ++ frames

    /** The same requests with every ID of the other repository replaced by one that does not exist */
    def unknown: Fixtures = {
      def random = UUID.randomUUID.toString
      copy(
        asset = random,
        frames = frames.map(_ => random),
        recycled = random,
        folder = random,
        album = random,
        location = random,
        category = random,
        person = random,
        face = random,
        sequence = random
      )
    }
  }

  private val MARK = "Quxtheirs"

  private val GET = "GET"
  private val POST = "POST"
  private val PUT = "PUT"
  private val DELETE = "DELETE"

  private def json(fields: (String, ujson.Value)*): Option[ujson.Value] = Some(ujson.Obj.from(fields))
  private def ids(values: String*): ujson.Value = ujson.Arr.from(values.map(ujson.Str(_)))

  private def none(method: String, path: String, access: Access = Access.Login): Row = Row(method, path, Ids.NoEntity, access)
  private def probed(method: String, path: String)(probes: Fixtures => List[Probe]): Row = Row(method, path, Ids.Probed(probes))

  private val publicRoute = Access.Public

  private val SCRUBBED_CATEGORY = "the Location dialogs' shared scrubber trims it, and the route reads no category"

  /** Every route of the app, each answering another repository's IDs as it would an unknown ID */
  private val table: List[Row] = List(
    // Public: what runs before or without a login
    none(GET, "/api/health", publicRoute("the health check of a load balancer or a container")),
    none(GET, "/login", publicRoute("the login page")),
    none(POST, "/login", publicRoute("the login")),
    none(POST, "/logout", publicRoute("ending a session needs no valid one")),
    none(POST, "/api/logout", publicRoute("ending a session needs no valid one")),
    none(GET, "/setup", publicRoute("setup runs before any account exists")),
    none(POST, "/htmx/setup", publicRoute("setup runs before any account exists, and refuses once the instance is set up")),
    none(GET, "/static/", publicRoute("the app's own scripts, styles and fonts")),
    none(GET, "/assets/:name", publicRoute("the app's stylesheet bundle")),
    Row(
      "WEBSOCKET",
      "/import/status",
      Ids.NotEntity(Set("userId"), "out of scope: the WebSocket, which names its client by the account's ID"),
      publicRoute("out of scope: the WebSocket")
    ),
    // Pages
    none(GET, "/"),
    probed(GET, "/r/:repoId") {
      fx =>
        List(
          Probe(
            200,
            query = Map("personId" -> fx.person, "folderId" -> fx.folder),
            why = "reads neither ID: the client seeds its search from them"))
    },
    probed(GET, "/r/:repoId/asset/:assetId")(fx => List(Probe(404, path = Map("assetId" -> fx.asset)))),
    none(GET, "/pipeline/r/:repoId"),
    none(GET, "/settings/r/:repoId"),
    Row(
      POST,
      "/r/:repoId/upload/:uploadId/cancel",
      Ids.NotEntity(Set("uploadId"), "the client's token for one upload, no row of a repository")),
    Row(
      POST,
      "/import/r/:repoId/upload/:uploadId",
      Ids.NotEntity(Set("uploadId"), "the client's token for one upload, no row of a repository")),
    probed(GET, "/content/r/:repoId/:dataType/:itemId") {
      fx =>
        List(
          Probe(404, path = Map("dataType" -> Const.DataStore.PREVIEW, "itemId" -> fx.asset)),
          Probe(404, path = Map("dataType" -> Const.DataStore.FILE, "itemId" -> fx.asset)),
          Probe(404, path = Map("dataType" -> Const.DataStore.FACE, "itemId" -> fx.face))
        )
    },
    // Assets
    probed(PUT, "/api/asset/r/:repoId/move") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.FOLDER_ID -> fx.folder, Api.Field.ASSET_IDS -> ids(fx.myAsset))),
          Probe(
            200,
            body = json(Api.Field.FOLDER_ID -> fx.myFolder, Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "assets that are not the repository's are left out, as when they are gone"
          )
        )
    },
    probed(DELETE, "/api/asset/r/:repoId/move") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "recycling leaves out assets that are not the repository's"))
    },
    probed(PUT, "/api/asset/r/:repoId/restore")(fx => List(Probe(404, body = json(Api.Field.ASSET_IDS -> ids(fx.recycled))))),
    probed(DELETE, "/api/asset/r/:repoId/purge") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.ASSET_IDS -> ids(fx.recycled)),
            why = "purging leaves out assets that are not the repository's"))
    },
    probed(GET, "/htmx/asset/r/:repoId/modals/asset-detail/:assetId") {
      fx => List(Probe(404, path = Map("assetId" -> fx.asset)), Probe(404, path = Map("assetId" -> fx.asset), json = true))
    },
    probed(GET, "/htmx/asset/r/:repoId/detail-panel/:assetId")(fx => List(Probe(404, path = Map("assetId" -> fx.asset)))),
    probed(PUT, "/htmx/asset/r/:repoId/face/:faceId/person") {
      fx =>
        List(
          Probe(404, path = Map("faceId" -> fx.face), body = json(Api.Field.PERSON_ID -> fx.myPerson)),
          Probe(404, path = Map("faceId" -> fx.myFace), body = json(Api.Field.PERSON_ID -> fx.person))
        )
    },
    probed(PUT, "/htmx/asset/r/:repoId/face/:faceId/person/name") {
      fx =>
        List(
          Probe(
            404,
            path = Map("faceId" -> fx.face),
            body = json(Api.Field.PERSON_ID -> fx.person, Api.Field.Person.NAME -> "Renamed")),
          Probe(
            404,
            path = Map("faceId" -> fx.myFace),
            body = json(Api.Field.PERSON_ID -> fx.person, Api.Field.Person.NAME -> "Renamed"))
        )
    },
    probed(PUT, "/htmx/asset/r/:repoId/face/:faceId/person/merge") {
      fx =>
        List(
          Probe(
            404,
            path = Map("faceId" -> fx.face),
            body = json(Api.Field.PERSON_ID -> fx.person, Api.Field.Person.DEST_PERSON_ID -> fx.myPerson)),
          Probe(
            404,
            path = Map("faceId" -> fx.myFace),
            body = json(Api.Field.PERSON_ID -> fx.myPerson, Api.Field.Person.DEST_PERSON_ID -> fx.person))
        )
    },
    probed(DELETE, "/htmx/asset/r/:repoId/face/:faceId")(fx => List(Probe(404, path = Map("faceId" -> fx.face)))),
    // Folders
    none(GET, "/api/folder/r/:repoId/tree"),
    none(GET, "/htmx/folder/r/:repoId/tab"),
    probed(GET, "/htmx/folder/r/:repoId/dialogs/add-folder") {
      fx =>
        List(
          Probe(
            200,
            query = Map("parentId" -> fx.folder),
            why = "reads nothing: the empty form carries the parent for the add, which checks it"))
    },
    probed(GET, "/htmx/folder/r/:repoId/dialogs/rename-folder") {
      fx =>
        List(
          Probe(404, query = Map("id" -> fx.folder, "parentId" -> fx.myRootFolder)),
          Probe(
            200,
            query = Map("id" -> fx.myFolder, "parentId" -> fx.folder),
            why = "`parentId` is not read: the dialog is the folder's")
        )
    },
    probed(GET, "/htmx/folder/r/:repoId/dialogs/delete-folder") {
      fx =>
        List(
          Probe(404, query = Map("id" -> fx.folder, "parentId" -> fx.myRootFolder)),
          Probe(
            200,
            query = Map("id" -> fx.myFolder, "parentId" -> fx.folder),
            why = "`parentId` is not read: the dialog is the folder's")
        )
    },
    probed(POST, "/htmx/folder/r/:repoId/add") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.Folder.NAME -> "Added", Api.Field.Folder.PARENT_ID -> fx.folder),
            why = "the dialog's form again, with the parent's field error, as for a name refused"
          ))
    },
    probed(PUT, "/htmx/folder/r/:repoId/rename") {
      fx => List(Probe(404, body = json(Api.Field.ID -> fx.folder, Api.Field.Folder.NAME -> "Renamed")))
    },
    probed(PUT, "/htmx/folder/r/:repoId/move") {
      fx =>
        List(
          Probe(404, query = Map("movedFolderId" -> fx.folder, "newParentId" -> fx.myFolder)),
          Probe(
            400,
            query = Map("movedFolderId" -> fx.myFolder, "newParentId" -> fx.folder),
            why = "a destination that is no folder of the repository is a bad request, as an unknown one is"
          )
        )
    },
    probed(DELETE, "/htmx/folder/r/:repoId/")(fx => List(Probe(404, query = Map("id" -> fx.folder)))),
    // Albums
    none(GET, "/api/album/r/:repoId/list"),
    probed(PUT, "/api/album/r/:repoId/assets") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.Album.ALBUM_ID -> fx.album, Api.Field.ASSET_IDS -> ids(fx.myAsset))),
          Probe(
            200,
            body = json(Api.Field.Album.ALBUM_ID -> fx.myAlbum, Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "assets that are not the repository's are left out, and the count says none was added"
          )
        )
    },
    probed(DELETE, "/api/album/r/:repoId/assets") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.Album.ALBUM_ID -> fx.album, Api.Field.ASSET_IDS -> ids(fx.asset))),
          Probe(
            200,
            body = json(Api.Field.Album.ALBUM_ID -> fx.myAlbum, Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "assets that are not the repository's are left out, and the count says none was removed"
          )
        )
    },
    none(GET, "/htmx/album/r/:repoId/tab"),
    none(GET, "/htmx/album/r/:repoId/dialogs/add-album"),
    probed(GET, "/htmx/album/r/:repoId/dialogs/rename-album")(fx => List(Probe(404, query = Map("id" -> fx.album)))),
    probed(GET, "/htmx/album/r/:repoId/dialogs/delete-album")(fx => List(Probe(404, query = Map("id" -> fx.album)))),
    none(POST, "/htmx/album/r/:repoId/add"),
    probed(PUT, "/htmx/album/r/:repoId/rename") {
      fx => List(Probe(404, body = json(Api.Field.ID -> fx.album, Api.Field.Album.NAME -> "Renamed")))
    },
    none(GET, "/htmx/album/r/:repoId/dialogs/add-to-album"),
    probed(PUT, "/htmx/album/r/:repoId/assets") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.Album.ALBUM_ID -> fx.album, Api.Field.ASSET_IDS -> fx.myAsset),
            why = "the dialog's form again, with the album's field error"),
          Probe(
            200,
            body = json(Api.Field.Album.ALBUM_ID -> fx.myAlbum, Api.Field.ASSET_IDS -> fx.asset),
            why = "assets that are not the repository's are left out, and the success says none was added"
          )
        )
    },
    probed(DELETE, "/htmx/album/r/:repoId/")(fx => List(Probe(404, query = Map("id" -> fx.album)))),
    // Locations
    none(GET, "/api/location/r/:repoId/list"),
    probed(PUT, "/api/location/r/:repoId/assets") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.Location.LOCATION_ID -> fx.location, Api.Field.ASSET_IDS -> ids(fx.myAsset))),
          Probe(
            200,
            body = json(Api.Field.Location.LOCATION_ID -> fx.myLocation, Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "assets that are not the repository's are left out, and the count says none was added"
          )
        )
    },
    probed(DELETE, "/api/location/r/:repoId/assets") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.Location.LOCATION_ID -> fx.location, Api.Field.ASSET_IDS -> ids(fx.asset))),
          Probe(
            200,
            body = json(Api.Field.Location.LOCATION_ID -> fx.myLocation, Api.Field.ASSET_IDS -> ids(fx.asset)),
            why = "assets that are not the repository's are left out, and the count says none was removed"
          )
        )
    },
    none(GET, "/htmx/location/r/:repoId/tab"),
    none(GET, "/htmx/location/r/:repoId/dialogs/add-location"),
    none(GET, "/htmx/location/r/:repoId/dialogs/add-category"),
    none(GET, "/htmx/location/r/:repoId/dialogs/add-to-location"),
    probed(GET, "/htmx/location/r/:repoId/dialogs/rename-location")(fx => List(Probe(404, query = Map("id" -> fx.location)))),
    probed(GET, "/htmx/location/r/:repoId/dialogs/delete-location")(fx => List(Probe(404, query = Map("id" -> fx.location)))),
    probed(GET, "/htmx/location/r/:repoId/dialogs/move-location")(fx => List(Probe(404, query = Map("id" -> fx.location))))
      .ignoring(Api.Field.Location.CATEGORY_ID -> "written into the form as the Location's category, not read"),
    probed(POST, "/htmx/location/r/:repoId/add") {
      fx =>
        List(
          Probe(
            200,
            body = json(
              Api.Field.Location.NAME -> "Added",
              Api.Field.Location.LATITUDE -> "1",
              Api.Field.Location.LONGITUDE -> "1",
              Api.Field.Location.CATEGORY_ID -> fx.category),
            why = "the dialog's form again, with the category's field error"
          ))
    },
    none(POST, "/htmx/location/r/:repoId/add-category").ignoring(Api.Field.Location.CATEGORY_ID -> SCRUBBED_CATEGORY),
    probed(PUT, "/htmx/location/r/:repoId/rename") {
      fx => List(Probe(404, body = json(Api.Field.ID -> fx.location, Api.Field.Location.NAME -> "Renamed")))
    }.ignoring(Api.Field.Location.CATEGORY_ID -> SCRUBBED_CATEGORY),
    probed(PUT, "/htmx/location/r/:repoId/move") {
      fx =>
        List(
          Probe(404, body = json(Api.Field.ID -> fx.location)),
          Probe(
            200,
            body = json(Api.Field.ID -> fx.myLocation, Api.Field.Location.CATEGORY_ID -> fx.category),
            why = "the dialog's form again, with the category's field error"
          )
        )
    },
    probed(PUT, "/htmx/location/r/:repoId/assets") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.Location.LOCATION_ID -> fx.location, Api.Field.ASSET_IDS -> fx.myAsset),
            why = "the dialog's form again, with the Location's field error"
          ),
          Probe(
            200,
            body = json(Api.Field.Location.LOCATION_ID -> fx.myLocation, Api.Field.ASSET_IDS -> fx.asset),
            why = "assets that are not the repository's are left out, and the success says none was added"
          )
        )
    }.ignoring(Api.Field.Location.CATEGORY_ID -> SCRUBBED_CATEGORY),
    probed(DELETE, "/htmx/location/r/:repoId/")(fx => List(Probe(404, query = Map("id" -> fx.location)))),
    // Map
    probed(GET, "/api/map/r/:repoId/cells") {
      fx =>
        val viewport = Map("viewport" -> "-90,-180,90,180", "zoom" -> "3")
        List("folderId" -> fx.folder, "personId" -> fx.person, "albumId" -> fx.album, "locationId" -> fx.location).map {
          filter =>
            Probe(
              200,
              query = viewport + filter,
              why = "a scope the repository does not hold matches nothing, so the map is empty")
        }
    },
    probed(GET, "/api/map/r/:repoId/bounds") {
      fx =>
        List("folderId" -> fx.folder, "personId" -> fx.person, "albumId" -> fx.album, "locationId" -> fx.location).map {
          filter =>
            Probe(200, query = Map(filter), why = "a scope the repository does not hold matches nothing, so there are no bounds")
        }
    },
    none(GET, "/api/map/r/:repoId/geocode"),
    // Search, Triage and the nav
    probed(GET, "/htmx/search/r/:repoId") {
      fx =>
        List("folderId" -> fx.folder, "personId" -> fx.person, "albumId" -> fx.album, "locationId" -> fx.location)
          .map(filter => Probe(404, query = Map(filter)))
    },
    none(GET, "/htmx/triage/r/:repoId/summary"),
    probed(GET, "/htmx/triage/r/:repoId/dialogs/scope") {
      fx =>
        List("folderId" -> fx.folder, "personId" -> fx.person, "locationId" -> fx.location)
          .map(filter => Probe(404, query = Map(filter)))
    },
    probed(GET, "/htmx/triage/r/:repoId/modals/quick-view")(fx => List(Probe(404, query = Map("folderId" -> fx.folder)))),
    none(GET, "/htmx/nav/r/:repoId"),
    none(DELETE, "/htmx/trash/r/:repoId/purge"),
    none(GET, "/htmx/view-settings/r/:repoId/dialogs/view-settings"),
    none(POST, "/htmx/settings/r/:repoId"),
    none(GET, "/htmx/library/r/:repoId/dialogs/new"),
    none(POST, "/htmx/library/r/:repoId/new"),
    // Curation
    none(GET, "/api/curation/r/:repoId/count"),
    probed(POST, "/api/curation/r/:repoId/resolve") {
      fx =>
        List(
          Probe(
            400,
            body = json(Api.Field.Curation.KEEP_IDS -> ids(fx.frames.head), Api.Field.Curation.RECYCLE_IDS -> ids()),
            why = "a Pick that is no live asset of the repository is refused, as the set would be recycled with nothing kept"
          ),
          Probe(
            200,
            body = json(Api.Field.Curation.KEEP_IDS -> ids(fx.myAsset), Api.Field.Curation.RECYCLE_IDS -> ids(fx.frames*)),
            why = "recycling leaves out assets that are not the repository's"
          )
        )
    },
    probed(POST, "/api/curation/r/:repoId/reopen") {
      fx =>
        List(
          Probe(
            404,
            body = json(
              Api.Field.Curation.KEEP_IDS -> ids(fx.myAsset),
              Api.Field.Curation.RECYCLE_IDS -> ids(fx.recycled),
              Api.Field.Curation.SEQUENCE_OF -> ujson.Obj())
          ),
          Probe(
            200,
            body = json(
              Api.Field.Curation.KEEP_IDS -> ids(fx.frames.head),
              Api.Field.Curation.RECYCLE_IDS -> ids(),
              Api.Field.Curation.SEQUENCE_OF -> ujson.Obj(fx.frames.head -> fx.sequence)
            ),
            why = "a Pick that is no asset of the repository is put back in no Sequence"
          )
        )
    },
    none(GET, "/htmx/curation/r/:repoId"),
    none(GET, "/htmx/curation/r/:repoId/queue"),
    probed(GET, "/htmx/curation/r/:repoId/sequence/:sequenceId") {
      fx =>
        List(
          Probe(
            204,
            path = Map("sequenceId" -> fx.sequence),
            why = "a Sequence with too few frames of the repository is passed over, as one gone from the queue is"))
    },
    probed(POST, "/htmx/curation/r/:repoId/selection") {
      fx =>
        List(
          Probe(
            200,
            body = json(Api.Field.ASSET_IDS -> fx.frames.mkString(",")),
            why = "assets that are not the repository's are left out, and the set says too few frames are left"
          ))
    },
    // People
    none(GET, "/htmx/people/r/:repoId/tab"),
    none(GET, "/htmx/people/r/:repoId/list"),
    probed(GET, "/htmx/people/r/:repoId/assignable")(fx => List(Probe(404, query = Map("faceId" -> fx.face)))),
    probed(GET, "/htmx/people/r/:repoId/p/:personId/title")(fx => List(Probe(404, path = Map("personId" -> fx.person)))),
    probed(GET, "/htmx/people/r/:repoId/modals/choose-person-cover-face") {
      fx => List(Probe(404, query = Map("personId" -> fx.person)))
    },
    probed(PUT, "/htmx/people/r/:repoId/p/:personId/cover-image") {
      fx =>
        List(
          Probe(404, path = Map("personId" -> fx.person), query = Map("faceId" -> fx.myFace)),
          Probe(404, path = Map("personId" -> fx.myPerson), query = Map("faceId" -> fx.face))
        )
    },
    probed(GET, "/htmx/people/r/:repoId/p/:personId/name/edit")(fx => List(Probe(404, path = Map("personId" -> fx.person)))),
    probed(PUT, "/htmx/people/r/:repoId/p/:personId/name/edit") {
      fx => List(Probe(404, path = Map("personId" -> fx.person), body = json(Api.Field.Person.NAME -> "Renamed")))
    },
    probed(GET, "/htmx/people/r/:repoId/dialogs/rename-person")(fx => List(Probe(404, query = Map("personId" -> fx.person)))),
    probed(PUT, "/htmx/people/r/:repoId/p/:personId/rename") {
      fx => List(Probe(404, path = Map("personId" -> fx.person), body = json(Api.Field.Person.NAME -> "Renamed")))
    },
    probed(GET, "/htmx/people/r/:repoId/p/:personId/name")(fx => List(Probe(404, path = Map("personId" -> fx.person)))),
    probed(GET, "/htmx/people/r/:repoId/modals/merge") {
      fx =>
        List(
          Probe(404, query = Map("mergeSourceId" -> fx.person, "mergeDestId" -> fx.myPerson)),
          Probe(404, query = Map("mergeSourceId" -> fx.myPerson, "mergeDestId" -> fx.person))
        )
    },
    probed(PUT, "/htmx/people/r/:repoId/src/:srcPersonId/dest/:destPersonId") {
      fx =>
        List(
          Probe(404, path = Map("srcPersonId" -> fx.person, "destPersonId" -> fx.myPerson)),
          Probe(404, path = Map("srcPersonId" -> fx.myPerson, "destPersonId" -> fx.person))
        )
    },
    probed(PUT, "/htmx/people/r/:repoId/p/:personId/hide")(fx => List(Probe(404, path = Map("personId" -> fx.person)))),
    probed(PUT, "/htmx/people/r/:repoId/p/:personId/show")(fx => List(Probe(404, path = Map("personId" -> fx.person)))),
    probed(DELETE, "/htmx/people/r/:repoId/p/:personId")(fx => List(Probe(404, path = Map("personId" -> fx.person))))
  )

  /**
   * A route of the app as cask registers it: the method, the path, the names of the endpoint's parameters, and the controller
   * class and method that answer it
   */
  private case class Endpoint(method: String, path: String, params: List[String], controller: String, handler: String) {
    def key: (String, String) = (method, path)

    /** The parameters that name a row: `id`, or any `...Id` but the repository's, which the path's own check covers */
    def idParams: Set[String] = params.filter(RouteTableTests.isIdName).toSet - "repoId"

    /**
     * The ID fields the route reads from its body: those its code names through `Api.Field` (see [[RouteTableTests.idFieldsOf]])
     * that are no parameter of the endpoint, which reads its parameters from the path or the query
     */
    def bodyIdFields: Set[String] = RouteTableTests.idFieldsOf(controller, handler) -- params
  }

  private def endpoints: List[Endpoint] =
    App.allRoutes.toList.flatMap {
      routes =>
        routes.caskMetadata.value.flatMap {
          metadata =>
            val params = metadata.entryPoint.argSignatures.flatten.map(_.name).toList
            metadata.endpoint.methods.map {
              method =>
                Endpoint(
                  method.toUpperCase,
                  metadata.endpoint.path,
                  params,
                  routes.getClass.getSimpleName,
                  metadata.entryPoint.name)
            }
        }
    }

  private val pathParam: Regex = ":([A-Za-z]+)".r

  /** The URL of `path` in `repoId`, its other path parameters filled from `values` */
  private def url(host: String, path: String, repoId: String, values: Map[String, String]): String =
    host + pathParam.replaceAllIn(
      path,
      m => Regex.quoteReplacement(if m.group(1) == "repoId" then repoId else values(m.group(1))))

  private def send(
      host: String,
      row: Row,
      probe: Probe,
      repoId: String,
      cookies: Map[String, java.net.HttpCookie]): requests.Response = {
    // Identity, because the client cannot read a compressed empty body (a 204)
    val headers = Map("Accept-Encoding" -> "identity") ++
      (if probe.body.isDefined then Map("Content-Type" -> "application/json") else Map.empty) ++
      (if probe.json then Map("Accept" -> "application/json") else Map.empty)
    val requester = requests.send(row.method)
    val target = url(host, row.path, repoId, probe.path)
    probe.body match {
      case Some(body) =>
        requester(
          target,
          params = probe.query,
          headers = headers,
          data = ujson.write(body),
          cookies = cookies,
          check = false,
          maxRedirects = 0)
      case None =>
        requester(target, params = probe.query, headers = headers, cookies = cookies, check = false, maxRedirects = 0)
    }
  }

  /** IDs that stand for the fixtures where only the shape of the probes matters */
  private lazy val placeholders: Fixtures = {
    def id = UUID.randomUUID.toString
    val repository = Repository(id = Some(id), name = "placeholder", ownerAccountId = id, rootFolderId = id, fileStoreType = "")
    Fixtures(
      mine = repository,
      theirs = repository,
      myAsset = id,
      myRootFolder = id,
      myFolder = id,
      myAlbum = id,
      myLocation = id,
      myPerson = id,
      myFace = id,
      asset = id,
      assetFileName = "",
      frames = List(id, id),
      recycled = id,
      folder = id,
      album = id,
      location = id,
      category = id,
      person = id,
      face = id,
      sequence = id
    )
  }

  test("Every route is in the table, and every ID parameter and body ID field of a route is probed") {

    /**
     * Setup:
     *
     * The routes of `App.allRoutes`, as cask registers them, the table, and the controllers' sources, parsed with Scalameta.
     *
     * Assertions:
     *
     * Every route is a row of the table and every row is a route. A route with an ID parameter (`id` or a name ending in `Id`,
     * the repository's left out), in its path or its query, has a probe that sends each of them, or says why the parameter names
     * no row. A route whose code names an ID field (`id`, or a name ending in `Id` or `Ids`) through `Api.Field`, in its method
     * or in a member it reaches (the controller's, `BaseController`'s or an object's under `routes`), reads it from the body: a
     * probe sends it in the body, or the row says why it names no row, or why the route does not act on it. A row that says the
     * route takes no entity ID has no such parameter or field. Every probe that expects an answer other than a 404 says why.
     *
     * Edge cases:
     *
     * A field the row says the route does not act on is one its code names, so the row's reason cannot outlive the code.
     */
    val routes = endpoints
    val rows = table.map(row => row.key -> row).toMap

    table.map(_.key).diff(table.map(_.key).distinct) shouldBe empty
    withClue("Routes the table does not list: ")(routes.map(_.key).filterNot(rows.contains) shouldBe empty)
    withClue("Rows that are no route: ")(table.map(_.key).filterNot(routes.map(_.key).toSet.contains) shouldBe empty)

    for (route <- routes; row <- rows.get(route.key)) withClue(s"[${route.method} ${route.path}]: ") {
      row.ignored.keySet.diff(route.bodyIdFields) shouldBe empty
      row.ignored.values.filter(_.isEmpty) shouldBe empty
      val bodyIdFields = route.bodyIdFields -- row.ignored.keySet

      row.ids match {
        case Ids.NoEntity => (route.idParams ++ bodyIdFields) shouldBe empty
        case Ids.NotEntity(params, reason) =>
          (route.idParams ++ bodyIdFields) shouldBe params
          reason should not be empty
        case Ids.Probed(probes) =>
          val built = probes(placeholders)
          built should not be empty
          route.idParams.diff(built.flatMap(probe => probe.path.keySet ++ probe.query.keySet).toSet) shouldBe empty
          bodyIdFields.diff(built.flatMap(_.body.toList.flatMap(_.obj.keySet)).toSet) shouldBe empty
          built.filter(_.status != 404).filter(_.why.isEmpty) shouldBe empty
      }
    }
  }

  test("Every route but the public ones refuses an anonymous request") {

    /**
     * Setup:
     *
     * An instance with a repository, and no login.
     *
     * Assertions:
     *
     * Every route of the table that is not public answers a request with no session with a redirect to the login (302) or a 401,
     * whatever its path parameters hold.
     *
     * Edge cases:
     *
     * The public routes, each with the reason it is public, are the login, logout, setup, the health check, the app's own static
     * files and the WebSocket, which is out of scope here.
     */
    val repoId = testContext.persistRepository().persistedId

    withServer(App) {
      host =>
        for (row <- table if row.access == Access.Login) withClue(s"[${row.method} ${row.path}]: ") {
          val values = pathParam.findAllMatchIn(row.path).map(_.group(1) -> UUID.randomUUID.toString).toMap
          val response = send(host, row, Probe(0, path = values), repoId, cookies = Map.empty)
          List(302, 401) should contain(response.statusCode)
        }
    }
  }

  /**
   * The user's repository with an asset, a folder, an album, a Location and an unnamed Person with a Face, and their other
   * repository with an imported asset (with a Preview, a point, a Face with its files, a folder, an album and a Location in a
   * category holding it), a Person with that Face, two triaged assets in one Sequence and a recycled asset. The context is the
   * user's repository again when it returns.
   */
  private def fixtures(mine: Repository): Fixtures = {
    val myAsset = testContext.insertAsset(testContext.makeAsset(repository = Some(mine)))
    val myFolder = testApp.service.folder.add("Mine")
    val myAlbum = testApp.service.album.add("Mine")
    val myLocation = testApp.service.location.addLocation("Mine", 1.0, 1.0)
    val myPerson = testApp.service.person.addPerson(Person())
    val myFace = testContext.addTestFace(myPerson, myAsset, TestContext.unitVector(1))

    val theirs = testContext.persistRepository()
    testApp.service.repository.switchContextToRepository(theirs)
    val asset = testContext.persistAsset(repository = Some(theirs))
    testContext.setAssetCoordinates(asset.persistedId, 48.8566, 2.3522)
    val folder = testApp.service.folder.add(s"$MARK folder")
    val album = testApp.service.album.add(s"$MARK album")
    testApp.service.album.addAssets(album.persistedId, Set(asset.persistedId))
    val category = testApp.service.location.addCategory(s"$MARK category")
    val location = testApp.service.location.addLocation(s"$MARK place", 48.8566, 2.3522, Some(category.persistedId))
    testApp.service.location.addAssets(location.persistedId, Set(asset.persistedId))
    val person = testApp.service.person.addPerson(Person(name = Some(s"$MARK Eve")))
    val face = testContext.addTestFace(person, asset, TestContext.unitVector(2), withFiles = true)
    val frames = List.fill(2)(testContext.insertAsset(testContext.makeAsset(repository = Some(theirs), isTriaged = true)))
    val sequence = UUID.randomUUID.toString
    testContext.setAssetSequence(frames, sequence)
    val recycled = testContext.insertAsset(testContext.makeAsset(repository = Some(theirs)))
    testApp.service.library.recycleAssets(Set(recycled.persistedId))
    testApp.service.repository.switchContextToRepository(mine)

    Fixtures(
      mine = mine,
      theirs = theirs,
      myAsset = myAsset.persistedId,
      myRootFolder = mine.rootFolderId,
      myFolder = myFolder.persistedId,
      myAlbum = myAlbum.persistedId,
      myLocation = myLocation.persistedId,
      myPerson = myPerson.persistedId,
      myFace = myFace.persistedId,
      asset = asset.persistedId,
      assetFileName = asset.fileName,
      frames = frames.map(_.persistedId),
      recycled = recycled.persistedId,
      folder = folder.persistedId,
      album = album.persistedId,
      location = location.persistedId,
      category = category.persistedId,
      person = person.persistedId,
      face = face.persistedId,
      sequence = sequence
    )
  }

  test("Every route answers another repository's IDs as it answers unknown ones, naming nothing and changing nothing") {

    /**
     * Setup:
     *
     * A logged-in user's repository with an asset, a folder, an album, a Location and a Person with a Face, and a second
     * repository of theirs holding a row of every kind a route takes an ID of, each named with a mark: an asset with a Preview, a
     * point, a Face with its files, a folder, an album and a Location in a category, a Person with that Face, two triaged assets
     * in a Sequence and a recycled asset.
     *
     * Assertions:
     *
     * Through the first repository, every probe of the table answers the second repository's IDs with the status the table gives,
     * a 404 unless the row says why, and answers IDs that do not exist the same way. No answer carries the mark, the imported
     * asset's file name, or an ID of the second repository that the request did not send. No row of either repository changes.
     *
     * Edge cases:
     *
     * The routes that do not answer 404 by design: a dialog's form that shows a field error (adding a folder under the parent, an
     * album or Location chosen in the batch dialogs, a category chosen for a Location), the empty add-folder dialog, Curation's
     * set of a Sequence (204), moving a folder under the parent (400), the batch operations and Curation, which leave out assets
     * that are not the repository's (and refuse a Pick that is none), the map, which is empty for a scope the repository does not
     * hold, and the library page, which reads neither ID it is given.
     */
    val mine = testContext.persistRepository()
    login()

    withServer(App) {
      host =>
        val fx = fixtures(mine)
        val mineBefore = testContext.rowsOf(fx.mine)
        val theirsBefore = testContext.rowsOf(fx.theirs)
        val unknown = fx.unknown

        for (row <- table) row.ids match {
          case Ids.Probed(probes) =>
            for ((probe, unknownProbe) <- probes(fx).zip(probes(unknown))) {
              withClue(s"[${row.method} ${row.path}] [${probe.sent}]: ") {
                val response = send(host, row, probe, mine.persistedId, testContext.cookies)
                response.statusCode shouldBe probe.status
                val text = response.text()
                (text should not).include(MARK)
                (text should not).include(fx.assetFileName)
                for (id <- fx.theirIds if !probe.sent.contains(id)) (text should not).include(id)

                testContext.rowsOf(fx.mine) shouldBe mineBefore
                testContext.rowsOf(fx.theirs) shouldBe theirsBefore
              }
              withClue(s"[${row.method} ${row.path}] unknown [${unknownProbe.sent}]: ") {
                send(host, row, unknownProbe, mine.persistedId, testContext.cookies).statusCode shouldBe probe.status
                testContext.rowsOf(fx.mine) shouldBe mineBefore
              }
            }
          case _ =>
        }
    }
  }
}

object RouteTableTests {

  /** A parameter or field that names a row: `id`, or a name ending in `Id`, or `Ids` for a list of them */
  private def isIdName(name: String): Boolean = name == "id" || name.endsWith("Id") || name.endsWith("Ids")

  /** A member of a controller's source, a method or a field, and the controller class it is in */
  private case class Member(name: String, owner: Option[String], tree: Tree)

  private lazy val sourceRoot: Path = Paths.get(sys.env("ALTITUDE_SOURCE_DIR")).resolve("altitude/core")

  private def parse(path: Path): Tree =
    dialects.Scala3(Input.VirtualFile(path.toString, Files.readString(path))).parse[Source].get

  private def ancestors(tree: Tree): List[Tree] =
    Iterator.iterate(tree.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.toList

  /** The string constants of `Api` by their path, `Api.Field.Album.ALBUM_ID` being `List("Api", "Field", "Album", "ALBUM_ID")` */
  private lazy val apiConstants: Map[List[String], String] =
    parse(sourceRoot.resolve("Api.scala"))
      .collect {
        case value: Defn.Val =>
          val objects = ancestors(value).collect { case obj: Defn.Object => obj.name.value }.reverse
          (value.pats, value.rhs) match {
            case (List(name: Pat.Var), Lit.String(constant)) => Some((objects :+ name.name.value) -> constant)
            case _ => None
          }
      }
      .flatten
      .toMap

  /** A member of a class or an object: a method or a field defined in its body, not in another member */
  private def isMember(tree: Tree): Boolean =
    ancestors(tree).find(t => t.is[Template] || t.is[Defn.Def] || t.is[Defn.Val] || t.is[Term]).exists(_.is[Template])

  private def membersOf(tree: Tree): List[Member] = {
    def owner(member: Tree) = ancestors(member).collectFirst { case cls: Defn.Class => cls.name.value }
    tree.collect {
      case method: Defn.Def if isMember(method) => List(Member(method.name.value, owner(method), method))
      case field: Defn.Val if isMember(field) =>
        field.pats.collect { case name: Pat.Var => Member(name.name.value, owner(field), field) }
    }.flatten
  }

  /** Every member of the sources under `routes`: the controllers', `BaseController`'s and the objects', any controller's reach */
  private lazy val routeMembers: List[Member] = {
    val walk = Files.walk(sourceRoot.resolve("routes"))
    try
      walk.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toList
        .flatMap(path => membersOf(parse(path)))
    finally walk.close()
  }

  /** The names of a select chain such as `Api.Field.Album.ALBUM_ID`, none for one that does not start from a name */
  private def selectPath(term: Term): Option[List[String]] = term match {
    case Term.Name(name) => Some(List(name))
    case Term.Select(qual, Term.Name(name)) => selectPath(qual).map(_ :+ name)
    case _ => None
  }

  /**
   * The ID-shaped `Api.Field` values that the controller's method names (`id`, or a name ending in `Id` or `Ids`): in its own
   * body and in every member of the controller, of `BaseController` or of an object under `routes` (a companion among them) that
   * it reaches by name, transitively. A field aliasing part of `Api` (`private val fields = Api.Field.Curation`) is followed.
   * Reading by name over-approximates what the method reaches, so a field the route names without acting on it is one its row
   * says it ignores.
   */
  def idFieldsOf(controller: String, method: String): Set[String] = {
    val members = routeMembers.filter(member => member.owner.forall(Set(controller, "BaseController").contains))
    val byName = members.groupBy(_.name)
    val aliases: Map[String, List[String]] = members.flatMap {
      case Member(name, _, field: Defn.Val) =>
        selectPath(field.rhs).filter(path => path.headOption.contains("Api")).map(name -> _)
      case _ => None
    }.toMap

    def resolve(path: List[String]): Option[String] =
      apiConstants.get(aliases.get(path.head).fold(path)(_ ++ path.tail))

    def reached(pending: List[Member], seen: Set[Member]): Set[Member] = pending match {
      case Nil => seen
      case member :: rest if seen.contains(member) => reached(rest, seen)
      case member :: rest =>
        val names = member.tree.collect { case name: Term.Name => name.value }.toSet
        reached(rest ++ names.toList.flatMap(byName.getOrElse(_, Nil)), seen + member)
    }

    val start = members.filter(member => member.name == method && member.owner.contains(controller))
    require(start.nonEmpty, s"No method $method in $controller")
    reached(start, Set.empty)
      .flatMap(_.tree.collect { case select: Term.Select => selectPath(select).flatMap(resolve) }.flatten)
      .filter(isIdName)
  }
}
