package altitude.core.routes.web

import cask.endpoints.WebsocketResult
import cask.model.Response
import io.undertow.server.handlers.form.FormData
import io.undertow.server.handlers.form.FormDataParser
import org.slf4j.Logger

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.routes.decorators.authenticate
import altitude.core.routes.decorators.forbidden
import altitude.core.routes.decorators.isSameOriginOrNone
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.util.Util

object ImportController:
  private val uploadCancelRequest = TrieMap[String, Boolean]()

  def isCancelled(uploadId: String): Boolean =
    uploadCancelRequest.contains(uploadId)

class ImportController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @requireLogin()
  @requirePermission(Permission.Import)
  @rememberRepository()
  @cask.get("/pipeline/r/:repoId")
  def pipeline(repoId: String): cask.Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.pipeline(stats = stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @requirePermission(Permission.Import)
  @cask.post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}/cancel")
  def cancelUpload(repoId: String, uploadId: String): cask.Response[String] =
    logger.warn(s"CANCELLING upload ID: $uploadId for repo $repoId")
    ImportController.uploadCancelRequest.update(uploadId, true)
    Response("", 200, Seq("Content-Type" -> "text/plain"), Nil)

  @requireLogin
  @requirePermission(Permission.Import)
  @cask.postForm("/import/r/:repoId/upload/:uploadId")
  def uploadFilesForm(files: Seq[cask.FormEntry] = Seq.empty, repoId: String, uploadId: String)(using
      request: cask.Request): cask.Response[String] =
    logger.debug(s"Uploading selected files. Upload ID: $uploadId")

    val formData: FormData = request.exchange.getAttachment(FormDataParser.FORM_DATA)

    val uploadFormPayload = "<!doctype html>" + htmx.html.upload_form()
    if formData == null then
      logger.error("Not a multipart upload")
      return cask.Response(uploadFormPayload, 200, Seq(("Content-Type", "text/html")))

    val pipelineContext = PipelineContext(
      repository = RequestContext.getRepository,
      account = Some(RequestContext.getAccount),
      permissions = RequestContext.permissions.value)

    // Get all form field names and process file uploads
    val fieldNames = formData.iterator().asScala.toList

    for fieldName <- fieldNames if !ImportController.isCancelled(uploadId)
    do
      val formValues = formData.get(fieldName).asScala.toList
      for formValue <- formValues if formValue.isFileItem && !ImportController.isCancelled(uploadId) do
        logger.trace("Next file")

        val fileItem = formValue.getFileItem
        val fileName = formValue.getFileName
        logger.debug(s"Received file: $fileName")

        // Undertow deletes its temp file when the request ends, before the queued pipeline runs, so the file is moved out
        // of its hands; a small item it kept in memory is written out instead
        val staged =
          if fileItem.isInMemory then
            val inputStream = fileItem.getInputStream
            try App.altitude.service.staging.stage(inputStream.readAllBytes())
            finally inputStream.close()
          else App.altitude.service.staging.stageMove(fileItem.getFile)
        val assetWithData = App.altitude.service.library.stagedFileToAsset(fileName, staged)

        logger.debug(s"Adding file to import queue: $fileName")
        // Waits while the queue is full. A file the queue refuses fails the request, and the rest of the upload with it.
        val fut = App.altitude.service.importPipeline.addToQueue((assetWithData, pipelineContext))
        Await.result(fut, Duration.Inf)

    logger.debug("All files sent to queue")
    cask.Response(uploadFormPayload, 200, Seq(("Content-Type", "text/html")))

  /**
   * The import status stream of the logged-in account: the account comes from the login (`decorators.authenticate`), never from
   * the request, so no one opens another account's feed. A handshake from a page of another origin is refused with a 403
   * (`decorators.isSameOriginOrNone`; one with no Origin, which no browser sends, is let through to the login check), and one
   * with no login with a 401, which clears a session cookie whose token was refused, and a Guest's with a 403: a Guest imports
   * nothing, so it has no feed. A RawDecorator cannot wrap a WebSocket endpoint, whose result is no response, so the login is
   * checked here rather than by `requireLogin`. A stale token's rebuild is not set as a cookie, which the upgrade's answer cannot
   * carry; the next page load sets it. The account's sockets close when its versions change (`ImportStatusWsActor.CloseAllFor`).
   */
  @cask.websocket("/import/status")
  def pipelineStatus()(using request: cask.Request): cask.WebsocketResult =
    if !isSameOriginOrNone(request) then
      logger.warn(
        s"Refused an import status handshake from another origin [${Util.printable(Option(request.exchange.getRequestHeaders.getFirst("Origin")).getOrElse(""), 200)}]")
      return WebsocketResult.Response(
        cask.Response("Forbidden: another origin", statusCode = 403, headers = Seq("Content-Type" -> "text/plain")))

    authenticate(request) match
      case Left(noLogin) =>
        WebsocketResult.Response(
          cask.Response(
            "Authentication required",
            statusCode = 401,
            headers = Seq("Content-Type" -> "text/plain"),
            cookies = noLogin.cookies))
      case Right(login) if login.claims.account.accountType == AccountType.Guest =>
        logger.warn(s"Refused an import status handshake of Guest [${login.claims.account.persistedId}]")
        WebsocketResult.Response(forbidden(Const.Msg.Err.FORBIDDEN_GUEST, json = false))
      case Right(login) =>
        val userId = login.claims.account.persistedId
        cask.WsHandler {
          wsClient =>
            App.altitude.actorSystem ! ImportStatusWsActor.AddClient(userId, wsClient)
            wsClient.send(cask.Ws.Text("connected"))

            cask.WsActor {
              case cask.Ws.Error(e) =>
                logger.warn("Connection error: " + e.getMessage)
                App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
              case cask.Ws.Close(_, _) | cask.Ws.ChannelClosed() =>
                logger.debug("Connection closed.")
                App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
            }
        }

  initialize()
