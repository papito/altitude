package altitude.core.routes.web

import altitude.core.{Api, App, RequestContext}
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.{ImportAsset, UserMetadata}
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.routes.decorators.requireLogin
import org.slf4j.Logger
import cask.model.Response
import io.undertow.server.handlers.form.{FormData, FormDataParser}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

object ImportController {
  private val uploadCancelRequest = TrieMap[String, Boolean]()

  def isCancelled(uploadId: String): Boolean =
    uploadCancelRequest.contains(uploadId)
}

class ImportController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @requireLogin()
  @cask.get("/pipeline/r/:repoId")
  def pipeline(repoId: String): cask.Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.pipeline(stats = stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @requireLogin()
  @cask.post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}/cancel")
  def cancelUpload(repoId: String, uploadId: String): cask.Response[String] =
    logger.warn(s"CANCELLING upload ID: $uploadId for repo $repoId")
    ImportController.uploadCancelRequest.update(uploadId, true)
    Response("", 200, Seq("Content-Type" -> "text/plain"), Nil)

  @requireLogin
  @cask.postForm("/import/r/:repoId/upload/:uploadId")
  def uploadFilesForm(files: Seq[cask.FormEntry] = Seq.empty, repoId: String, uploadId: String)(using request: cask.Request): cask.Response[String] =
    logger.info(s"Uploading selected files. Upload ID: $uploadId")

    val formData: FormData = request.exchange.getAttachment(FormDataParser.FORM_DATA)

    val uploadFormPayload = "<!doctype html>" + htmx.html.upload_form()
    if formData == null then
      logger.error("Not a multipart upload")
      return cask.Response(uploadFormPayload, 200, Seq(("Content-Type", "text/html")))

    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = RequestContext.getAccount)

    // Get all form field names and process file uploads
    val fieldNames = formData.iterator().asScala.toList

    for fieldName <- fieldNames if !ImportController.isCancelled(uploadId)
    do
      val formValues = formData.get(fieldName).asScala.toList
      print(formValues)
      for formValue <- formValues if formValue.isFileItem && !ImportController.isCancelled(uploadId) do
        logger.info("Next file")

        val fileItem = formValue.getFileItem
        val inputStream = fileItem.getInputStream
        val bytes = inputStream.readAllBytes()
        inputStream.close()

        val fileName = formValue.getFileName
        logger.info(s"Received file: $fileName")

        val importAsset = ImportAsset(fileName = fileName, data = bytes, metadata = UserMetadata())
        val assetWithData = App.altitude.service.library.convImportAsset2dataAsset(importAsset)

        logger.info(s"Adding file to import queue: $fileName")
        val fut = App.altitude.service.importPipeline.addToQueue((assetWithData, pipelineContext))
        Await.result(fut, Duration.Inf)

    logger.info("All files sent to queue")
    cask.Response(uploadFormPayload, 200, Seq(("Content-Type", "text/html")))

  @cask.websocket("/import/status")
  def pipelineStatus(userId: String): cask.WebsocketResult =
    cask.WsHandler { wsClient =>
      App.altitude.actorSystem ! ImportStatusWsActor.AddClient(userId, wsClient)
      wsClient.send(cask.Ws.Text("connected"))

      cask.WsActor {
        case cask.Ws.Error(e) =>
          logger.warn("Connection error: " + e.getMessage)
          App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
        case cask.Ws.Close(_, _) | cask.Ws.ChannelClosed() =>
          logger.info("Connection closed.")
          App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
      }
    }

  initialize()

