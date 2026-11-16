package altitude.core.routes.web.partial

import cask.Request
import cask.Response
import org.slf4j.Logger
import play.twirl.api.Html

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.models.AccountType
import altitude.core.models.NamedPath
import altitude.core.models.Permission
import altitude.core.models.WatchFolder
import altitude.core.models.WatchFolderSpec
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireAdmin
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission

object WatchFolderController:
  private val DIALOG_FIELD_OF_ERROR = Map(
    FieldConst.WatchFolder.NAME -> Api.Field.WatchFolder.NAME,
    FieldConst.WatchFolder.PATH -> Api.Field.WatchFolder.PATH,
    FieldConst.WatchFolder.DEST_FOLDER_ID -> Api.Field.WatchFolder.DEST_FOLDER_ID
  )

  /** The dialog's fields. `storedName` is what an edit's title shows, whatever was typed; `destination` is the chip's path. */
  case class Form(
      watchFolderId: Option[String] = None,
      name: String = "",
      path: String = "",
      keepsStructure: Boolean = false,
      destFolderId: Option[String] = None,
      deletesImported: Boolean = false,
      storedName: Option[String] = None,
      destination: Option[String] = None):

    /** A destination only with "Keep folder structure" on */
    def spec: WatchFolderSpec = WatchFolderSpec(name, path, destFolderId.filter(_ => keepsStructure), deletesImported)

  object Form:
    def of(watchFolder: WatchFolder): Form =
      Form(
        watchFolderId = watchFolder.id,
        name = watchFolder.name,
        path = watchFolder.path,
        keepsStructure = watchFolder.destFolderId.isDefined,
        destFolderId = watchFolder.destFolderId,
        deletesImported = watchFolder.deletesImported,
        storedName = Some(watchFolder.name)
      )

    /** A switch arrives as the text of its hidden input, "true" or "false" */
    def ofWatchFolderBody(json: ujson.Obj): Form =
      def text(field: String): String = json.obj.get(field).flatMap(_.strOpt).getOrElse("")
      def isOn(field: String): Boolean =
        json.obj.get(field).exists(value => value.strOpt.contains("true") || value.boolOpt.contains(true))

      Form(
        watchFolderId = Option(text(Api.Field.WatchFolder.WATCH_FOLDER_ID)).filter(_.nonEmpty),
        name = text(Api.Field.WatchFolder.NAME),
        path = text(Api.Field.WatchFolder.PATH),
        keepsStructure = isOn(Api.Field.WatchFolder.KEEPS_STRUCTURE),
        destFolderId = Option(text(Api.Field.WatchFolder.DEST_FOLDER_ID).trim).filter(_.nonEmpty),
        deletesImported = isOn(Api.Field.WatchFolder.DELETES_IMPORTED)
      )

  def uuid(id: String): String =
    if Validators.isUuid(id) then id else throw ValidationException(C.Msg.Err.VALUE_NOT_A_UUID)

  private def isAdmin: Boolean = RequestContext.getAccount.accountType == AccountType.Admin

  /** An Admin's cards carry their actions */
  def list: Html = htmx.html.watch_folders(App.altitude.service.watchFolder.cards(), isAdmin)

/** The Import page's Watch folders: any holder of Import reads the cards; every write is an Admin's */
class WatchFolderController(using logger: Logger) extends BaseController:
  import WatchFolderController.*

  private val prefix = "htmx/watch-folders"

  private def watchFolders = App.altitude.service.watchFolder

  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.get(f"/$prefix/r/:repoId/list")
  def htmxList(repoId: String)(using request: Request): Response[String] =
    htmlResponse(WatchFolderController.list)

  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/add")
  def showAddDialog(repoId: String)(using request: Request): Response[String] =
    htmlResponse(rendered(Form()))

  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/edit")
  def showEditDialog(repoId: String, watchFolderId: String)(using request: Request): Response[String] =
    answering {
      htmlResponse(rendered(Form.of(watchFolders.getById(uuid(watchFolderId)))))
    }

  /** Owned by the request's Admin. A destination that is no folder of the Library is a 404. */
  @requireLogin()
  @requireAdmin()
  @cask.post(f"/$prefix/r/:repoId/add")
  def htmxAdd(repoId: String)(using request: Request): Response[String] =
    answering {
      val form = Form.ofWatchFolderBody(unscrubbedJson.get)
      saving(form) {
        val added = watchFolders.add(form.spec)
        dialogSuccessResponse(ujson.Obj("message" -> s"Watch folder ${added.name} added"))
      }
    }

  /** The editing Admin owns the Watch folder from here on */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/edit")
  def htmxEdit(repoId: String)(using request: Request): Response[String] =
    answering {
      val form = Form.ofWatchFolderBody(unscrubbedJson.get)
      val id = uuid(form.watchFolderId.getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED)))
      saving(form.copy(storedName = Some(watchFolders.getById(id).name))) {
        val edited = watchFolders.update(id, form.spec)
        dialogSuccessResponse(ujson.Obj("message" -> s"Watch folder ${edited.name} saved"))
      }
    }

  /**
   * The notice once "Delete imported files" is on: how many files a scan would find at `path` now, or the warning alone for a
   * path that cannot be a Watch folder's. `watchFolderId` is the one edited, whose own path is no overlap.
   */
  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/deletion-notice")
  def deletionNotice(repoId: String, path: String = "", watchFolderId: String = "")(using request: Request): Response[String] =
    answering {
      val self = Option(watchFolderId).filter(_.nonEmpty).map(uuid)
      htmlResponse(includes.html.watch_folder_deletion_notice(watchFolders.fileCount(path, self)))
    }

  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/pause")
  def htmxPause(repoId: String)(using request: Request): Response[String] =
    answering {
      watchFolders.pause(watchFolderIdOf(unscrubbedJson.get))
      noContentResponse
    }

  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/resume")
  def htmxResume(repoId: String)(using request: Request): Response[String] =
    answering {
      watchFolders.resume(watchFolderIdOf(unscrubbedJson.get))
      noContentResponse
    }

  /** Starts a scan and answers at once. A paused Watch folder, or one whose destination is in the Trash, is a 409. */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/scan")
  def htmxScan(repoId: String)(using request: Request): Response[String] =
    answering {
      val id = watchFolderIdOf(unscrubbedJson.get)
      logger.info(s"Account [${RequestContext.getAccount.persistedId}] asked for a scan of Watch folder [$id]")
      watchFolders.scanNow(id): Unit
      noContentResponse
    }

  /** The failed files are read again by the next scans */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/retry")
  def htmxRetry(repoId: String)(using request: Request): Response[String] =
    answering {
      watchFolders.retryFailed(watchFolderIdOf(unscrubbedJson.get)): Unit
      noContentResponse
    }

  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/remove")
  def showRemoveDialog(repoId: String, watchFolderId: String)(using request: Request): Response[String] =
    answering {
      htmlResponse(htmx.html.remove_watch_folder_dialog(watchFolders.getById(uuid(watchFolderId))))
    }

  @requireLogin()
  @requireAdmin()
  @cask.delete(f"/$prefix/r/:repoId/watch-folder")
  def htmxRemove(repoId: String, watchFolderId: String)(using request: Request): Response[String] =
    answering {
      val watchFolder = watchFolders.getById(uuid(watchFolderId))
      watchFolders.remove(watchFolder.persistedId)
      dialogSuccessResponse(ujson.Obj("message" -> s"Watch folder ${watchFolder.name} removed"))
    }

  private def watchFolderIdOf(json: ujson.Obj): String =
    uuid(
      json.obj
        .get(Api.Field.WatchFolder.WATCH_FOLDER_ID)
        .flatMap(_.strOpt)
        .getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED)))

  // With the destination's path for its chip (a folder that is not the Library's is a 404) and, with deletion on, the file count
  private def rendered(form: Form, errors: Map[String, String] = Map.empty): Html =
    val destination = form.destFolderId.map {
      folderId =>
        App.altitude.service.imports.pathOf(App.altitude.service.folder.getById(uuid(folderId)).persistedId, NamedPath.SEPARATOR)
    }
    val fileCount = if form.deletesImported then watchFolders.fileCount(form.path, form.watchFolderId) else None
    val scanInterval = App.altitude.settings.current.watchFolderScanInterval
    htmx.html.watch_folder_form(form.copy(destination = destination), fileCount, scanInterval, errors)

  /** Runs the write, or answers the form again with its errors. "Keep folder structure" with no folder is refused here. */
  private def saving(form: Form)(write: => Response[String]): Response[String] =
    def again(errors: Map[String, String]): Response[String] =
      val fieldErrors = errors.map((key, error) => DIALOG_FIELD_OF_ERROR.getOrElse(key, key) -> error)
      logger.debug(s"Watch folder form refused: ${fieldErrors.keys.mkString(", ")}")
      dialogFormValidationResponse("<!doctype html>" + rendered(form, fieldErrors))

    if form.keepsStructure && form.destFolderId.isEmpty then
      again(Map(Api.Field.WatchFolder.DEST_FOLDER_ID -> C.Msg.Err.DESTINATION_REQUIRED))
    else
      try write
      catch case ex: ValidationException if ex.errors.nonEmpty => again(ex.errors.toMap)

  initialize()
