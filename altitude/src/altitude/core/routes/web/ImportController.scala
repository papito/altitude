package altitude.core.routes.web

import altitude.core.{Api, App}
import altitude.core.actors.ImportStatusWsActor
import altitude.core.routes.decorators.requireLogin
import org.slf4j.Logger
import cask.Request
import cask.model.Response
import io.undertow.server.handlers.form.FormDataParser

import scala.collection.concurrent.TrieMap

object ImportController {
  private val uploadCancelRequest = TrieMap[String, Boolean]()

  def isCancelled(uploadId: String): Boolean =
    uploadCancelRequest.contains(uploadId)
}

class ImportController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  // FIXME: add more login enforcement here

  @requireLogin()
  @cask.get("/pipeline/r/:repoId")
  def pipeline(repoId: String): cask.Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.pipeline(stats = stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  @cask.post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}/cancel")
  def cancelUpload(repoId: String, uploadId: String)(using request: Request): cask.Response[String] =
    logger.warn(s"CANCELLING upload ID: $uploadId for repo $repoId")
    ImportController.uploadCancelRequest.update(uploadId, true)
    Response("", 200, Seq("Content-Type" -> "text/plain"), Nil)

  @cask.postForm("/r/:repoId/upload/:uploadId")
  def uploadFilesForm(image: cask.FormFile, repoId: String, uploadId: String)(implicit request: cask.Request): cask.Response[String] =
    logger.info(s"Uploading selected files. Upload ID: $uploadId")

    val formData = request.exchange.getAttachment(FormDataParser.FORM_DATA)

    logger.error("Not a multipart upload")
    val payload = "<!doctype html>" + htmx.html.upload_form
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

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

