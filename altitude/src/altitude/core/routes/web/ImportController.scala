package altitude.core.routes.web

import cask.endpoints.WebsocketResult
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.IllegalOperationException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.AccountType
import altitude.core.models.ImportFile
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.authenticate
import altitude.core.routes.decorators.forbidden
import altitude.core.routes.decorators.isSameOriginOrNone
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.routes.web.partial.WatchFolderController
import altitude.core.util.Util

class ImportController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends BaseController:
  private def imports = App.altitude.service.imports

  @requireLogin()
  @requirePermission(Permission.Import)
  @rememberRepository()
  @cask.get("/pipeline/r/:repoId")
  def pipeline(repoId: String): cask.Response[String] =
    val stats = App.altitude.service.stats.getStats
    val history = imports.history(Const.ImportHistory.IMPORTS_STEP)
    val payload = "<!doctype html>" + html.pipeline(stats = stats, history = history, watchFolders = WatchFolderController.list)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  /**
   * Opens an upload's Import. A `destFolderId` (none is Triage) takes Organize as well as Import; `expectedFiles` lets the Import
   * history count the files yet to come as remaining.
   */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.post("/import/r/:repoId/imports")
  def openImport(repoId: String)(using request: cask.Request): cask.Response[String] =
    answeringJson {
      val json = unscrubbedJson.get
      ApiRequestValidator(maxLengths = Map(Api.Field.Import.NAME -> Api.Constraints.MAX_IMPORT_NAME_LENGTH)).validate(json)

      val opened = imports.openUpload(
        name = text(json, Api.Field.Import.NAME),
        destFolderId = text(json, Api.Field.Import.DEST_FOLDER_ID),
        expectedFiles = expectedFiles(json))
      jsonResponse(ujson.Obj(Api.Field.ID -> opened.persistedId))
    }

  /** None when absent or null; anything but a whole number from 0 that fits an Int is a `ValidationException` */
  private def expectedFiles(json: ujson.Obj): Option[Int] =
    json.obj.get(Api.Field.Import.EXPECTED_FILES).filterNot(_.isNull).map {
      case ujson.Num(files) if files >= 0 && files <= Int.MaxValue && files == files.floor => files.toInt
      case _ => throw ValidationException(Const.Msg.Err.EXPECTED_FILES_INVALID)
    }

  /** A text field of a JSON body, trimmed; none when it is absent, empty or no text */
  private def text(json: ujson.Obj, field: String): Option[String] =
    json.obj.get(field).flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)

  /**
   * Takes one file of an upload: the body is the file, `relPath` its path relative to what was chosen. The request waits while
   * the import queue is full. A file recorded but not handed to the pipeline is answered as failed, not as a failed request, so
   * the page does not send it again; so is a file whose destination folder is in the Trash, recorded failed and never staged.
   *
   * A refused file (a bad path, an empty file) is a 400 and is not recorded, refused before its body is read where the path says
   * so. An Import that is not the account's own upload is a 403, and one that takes no more files a 409; the page stops on
   * either.
   */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.post("/import/r/:repoId/imports/:importId/files")
  def uploadFile(repoId: String, importId: String, relPath: String = "")(using request: cask.Request): cask.Response[String] =
    answeringUpload {
      imports.requireTakes(importId, relPath)

      // A request that says its length, as a browser's does, is failed for a lost destination before its body is staged
      val length = request.exchange.getRequestContentLength
      val lost = if length > 0 then imports.failOnArrival(importId, relPath, length) else None

      fileResponse(lost.getOrElse(imports.upload(importId, relPath, App.altitude.service.staging.stage(request.data))))
    }

  /** Records as failed a file the page could not send after its retries; refused as [[uploadFile]] refuses a path and an Import */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.post("/import/r/:repoId/imports/:importId/failures")
  def recordFailedFile(repoId: String, importId: String)(using request: cask.Request): cask.Response[String] =
    answeringUpload {
      val json = unscrubbedJson.get
      ApiRequestValidator(required = List(Api.Field.Import.REL_PATH)).validate(json)
      val sizeBytes = json.obj.get(Api.Field.Import.SIZE_BYTES).flatMap(_.numOpt).map(_.toLong).filter(_ >= 0).getOrElse(0L)

      fileResponse(imports.addFailedFile(importId, json(Api.Field.Import.REL_PATH).str, sizeBytes))
    }

  /**
   * No more files will come, whether the page sent them all or the upload was cancelled; the Import stays in progress until its
   * files are settled
   */
  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.post("/import/r/:repoId/imports/:importId/close")
  def closeImport(repoId: String, importId: String): cask.Response[String] =
    answeringJson {
      imports.closeUpload(importId)
      jsonResponse(ujson.Obj())
    }

  /** An Import that takes no more files is a 409, which the page tells from a refused file's 400 */
  private def answeringUpload(f: => cask.Response[String]): cask.Response[String] =
    answeringJson {
      try f
      catch case ex: IllegalOperationException => jsonError(ex.getMessage, 409)
    }

  private def fileResponse(file: ImportFile): cask.Response[String] =
    val json = ujson.Obj(Api.Field.ID -> file.persistedId, Api.Field.Import.OUTCOME -> file.outcome.dbValue)
    file.reason.foreach(json(Api.Field.Import.REASON) = _)
    jsonResponse(json)

  /**
   * The import status stream of the path's Library, for an account holding Import in it. A RawDecorator cannot wrap a WebSocket
   * endpoint, whose result is no response, so what `requireLogin` and `requirePermission` check is checked here. A stale token's
   * rebuild is not set as a cookie, which the upgrade's answer cannot carry; the next page load sets it. The account's sockets
   * close when its versions change (`ImportStatusWsActor.CloseAllFor`), so a socket never outlives the Permission that opened it.
   */
  @cask.websocket("/import/r/:repoId/status")
  def pipelineStatus(repoId: String)(using request: cask.Request): cask.WebsocketResult =
    if !isSameOriginOrNone(request) then
      logger.warn(
        s"Refused an import status handshake from another origin [${Util.printable(Option(request.exchange.getRequestHeaders.getFirst("Origin")).getOrElse(""), 200)}]")
      return refused(403, "Forbidden: another origin")

    authenticate(request) match
      case Left(noLogin) =>
        WebsocketResult.Response(
          cask.Response(
            "Authentication required",
            statusCode = 401,
            headers = Seq("Content-Type" -> "text/plain"),
            cookies = noLogin.cookies))
      case Right(login) =>
        val account = login.claims.account
        val userId = account.persistedId
        // The Library `repoContext` found for the path, and the account's Permissions in it, if it can see it
        val granted = RequestContext.repository.value.flatMap(
          repository => login.claims.permissionsIn(repository.persistedId).map(repository.persistedId -> _))

        granted match
          case None =>
            logger.warn(
              s"Refused an import status handshake of account [$userId]: no access to repository [${Util.printable(repoId, 64)}]")
            refused(404, Const.Msg.Err.LIBRARY_NOT_FOUND)
          case Some(_) if account.accountType == AccountType.Guest =>
            logger.warn(s"Refused an import status handshake of Guest [$userId]")
            WebsocketResult.Response(forbidden(Const.Msg.Err.FORBIDDEN_GUEST, json = false))
          case Some((_, permissions)) if !permissions.has(Permission.Import) =>
            logger.warn(s"Refused an import status handshake of account [$userId]: no Import in the repository")
            WebsocketResult.Response(forbidden(Const.Msg.Err.FORBIDDEN_PERMISSION.format(Permission.Import.label), json = false))
          case Some((repositoryId, _)) =>
            cask.WsHandler {
              wsClient =>
                App.altitude.actorSystem ! ImportStatusWsActor.AddClient(repositoryId, userId, wsClient)

                cask.WsActor {
                  case cask.Ws.Error(e) =>
                    logger.warn("Connection error: " + e.getMessage)
                    App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(repositoryId, userId, wsClient)
                  case cask.Ws.Close(_, _) | cask.Ws.ChannelClosed() =>
                    logger.debug("Connection closed.")
                    App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(repositoryId, userId, wsClient)
                }
            }

  private def refused(status: Int, why: String): cask.WebsocketResult =
    WebsocketResult.Response(cask.Response(why, statusCode = status, headers = Seq("Content-Type" -> "text/plain")))

  initialize()
