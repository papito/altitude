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
  // The service's field of an error, as the dialog's field
  private val DIALOG_FIELD_OF_ERROR = Map(
    FieldConst.WatchFolder.NAME -> Api.Field.WatchFolder.NAME,
    FieldConst.WatchFolder.PATH -> Api.Field.WatchFolder.PATH,
    FieldConst.WatchFolder.DEST_FOLDER_ID -> Api.Field.WatchFolder.DEST_FOLDER_ID
  )

  /**
   * The Watch folder dialog's fields, as an Admin submitted them or as an edit opens over them. `storedName` is the name an
   * edit's title shows, whatever was typed; `destination` the path of the destination folder, for its chip.
   */
  case class Form(
      watchFolderId: Option[String] = None,
      name: String = "",
      path: String = "",
      keepsStructure: Boolean = false,
      destFolderId: Option[String] = None,
      deletesImported: Boolean = false,
      storedName: Option[String] = None,
      destination: Option[String] = None):

    /** What the form asks the service for: a destination only with "Keep folder structure" on */
    def spec: WatchFolderSpec = WatchFolderSpec(name, path, destFolderId.filter(_ => keepsStructure), deletesImported)

  object Form:
    /** The form an edit opens over */
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

    /** The form a submit's JSON body holds: a switch is the text of its hidden input, "true" or "false" */
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

  /** A Watch folder's ID as a request names it: a UUID, or a 400 */
  def uuid(id: String): String =
    if Validators.isUuid(id) then id else throw ValidationException(C.Msg.Err.VALUE_NOT_A_UUID)

  private def isAdmin: Boolean = RequestContext.getAccount.accountType == AccountType.Admin

  /** The Library's Watch folders as the Import page shows them to the request's account: an Admin's cards carry their actions */
  def list: Html = htmx.html.watch_folders(App.altitude.service.watchFolder.cards(), isAdmin)

/**
 * The Watch folders of the Import page: the cards, which every account that holds Import in the Library reads, and what an Admin
 * does with a Watch folder: add, edit, pause, resume, scan now, retry its failed files and remove
 */
class WatchFolderController(using logger: Logger) extends BaseController:
  import WatchFolderController.*

  private val prefix = "htmx/watch-folders"

  private def watchFolders = App.altitude.service.watchFolder

  /** The Library's Watch folders, as cards; read-only for an account that is no Admin */
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

  /** The edit form of a Watch folder, over what is stored; one that is not the Library's is a 404 */
  @requireLogin()
  @requireAdmin()
  @cask.get(f"/$prefix/r/:repoId/dialogs/edit")
  def showEditDialog(repoId: String, watchFolderId: String)(using request: Request): Response[String] =
    answering {
      htmlResponse(rendered(Form.of(watchFolders.getById(uuid(watchFolderId)))))
    }

  /**
   * Adds the Watch folder the dialog describes, owned by the request's Admin. A field the rules refuse comes back as the form,
   * with the values submitted and the error under the field; a destination that is no folder of the Library is a 404.
   */
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

  /** An Admin's edit of a Watch folder, through the same form; the Admin owns it from here on */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/edit")
  def htmxEdit(repoId: String)(using request: Request): Response[String] =
    answering {
      val form = Form.ofWatchFolderBody(unscrubbedJson.get)
      val id = uuid(form.watchFolderId.getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED)))
      // The title names the Watch folder as stored, whatever name was typed
      saving(form.copy(storedName = Some(watchFolders.getById(id).name))) {
        val edited = watchFolders.update(id, form.spec)
        dialogSuccessResponse(ujson.Obj("message" -> s"Watch folder ${edited.name} saved"))
      }
    }

  /**
   * What the dialog says once "Delete imported files" is switched on: the number of files in the directory at `path` now, as a
   * scan would find them, or the warning alone for a path that cannot be a Watch folder's as it stands. `watchFolderId` is the
   * Watch folder edited, whose own path is no overlap.
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

  /**
   * "Scan now": starts a scan of the Watch folder and answers at once; the card follows the scan through the status feed. A
   * paused one, and one whose destination folder is in the Trash, is a 409 saying so.
   */
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

  /** "Retry failed": the failed files of the Watch folder are read again by its next scans */
  @requireLogin()
  @requireAdmin()
  @cask.put(f"/$prefix/r/:repoId/retry")
  def htmxRetry(repoId: String)(using request: Request): Response[String] =
    answering {
      watchFolders.retryFailed(watchFolderIdOf(unscrubbedJson.get)): Unit
      noContentResponse
    }

  /** The Remove dialog, which says what stays: the files of the directory and the Imports of its scans */
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

  // The Watch folder a write's body names, a UUID
  private def watchFolderIdOf(json: ujson.Obj): String =
    uuid(
      json.obj
        .get(Api.Field.WatchFolder.WATCH_FOLDER_ID)
        .flatMap(_.strOpt)
        .getOrElse(throw ValidationException(C.Msg.Err.VALUE_REQUIRED)))

  // The form with what the server knows of what it holds: the path of its destination folder, for the chip (a folder that is
  // not the Library's is a 404), and, with the deletion on, the number of files in its directory now
  private def rendered(form: Form, errors: Map[String, String] = Map.empty): Html =
    val destination = form.destFolderId.map {
      folderId =>
        App.altitude.service.imports.pathOf(App.altitude.service.folder.getById(uuid(folderId)).persistedId, NamedPath.SEPARATOR)
    }
    val fileCount = if form.deletesImported then watchFolders.fileCount(form.path, form.watchFolderId) else None
    val scanInterval = App.altitude.settings.current.watchFolderScanInterval
    htmx.html.watch_folder_form(form.copy(destination = destination), fileCount, scanInterval, errors)

  /**
   * Runs a submitted form's write, or answers the form again with the values submitted and the error of every field refused. One
   * error is the form's own, refused here before the service: "Keep folder structure" with no folder chosen.
   */
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
