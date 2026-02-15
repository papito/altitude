package altitude.core.routes.web

import altitude.core.{Api, App, RequestContext}
import altitude.core.actors.ImportStatusWsActor
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.routes.decorators.{extractToken, requireLogin}
import cask.Request
import cask.model.Response
import io.undertow.server.handlers.form.FormDataParser
import io.undertow.websockets.WebSocketConnectionCallback
import io.undertow.websockets.core.{AbstractReceiveListener, BufferedTextMessage, WebSocketChannel, WebSockets}
import io.undertow.websockets.spi.WebSocketHttpExchange
import org.slf4j.Logger
import scala.collection.concurrent.TrieMap


object WebController {
  private val uploadCancelRequest = TrieMap[String, Boolean]()

  def isCancelled(uploadId: String): Boolean = {
    WebController.uploadCancelRequest.contains(uploadId)
  }
}

class WebController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @cask.staticFiles("/static/")
  def staticFileRoutes() = "altitude/static"

  @cask.get("/")
  def index()(request: Request): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      return Response("", 302, Seq("Location" -> "/setup"), Nil)
    }

    extractToken(request) match {
      case Some(token) =>
        App.altitude.service.user.getUserFromToken(token) match {
          case Some(user) =>
            logger.info(s"User authenticated: ${user.email}")
            Response("", 302, Seq("Location" -> s"/r/${user.lastActiveRepoId.get}"), Nil)

          case None =>
            Response("", 302, Seq("Location" -> "/login"), Nil)
        }
      case None =>
        Response("", 302, Seq("Location" -> "/login"), Nil)
    }
  }

  @cask.post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}/cancel")
    def cancelUpload(repoId: String, uploadId: String)(using request: Request): cask.Response[String] = {
        logger.warn(s"CANCELLING upload ID: $uploadId for repo $repoId")
        WebController.uploadCancelRequest.update(uploadId, true)
        Response("", 200, Seq("Content-Type" -> "text/plain"), Nil)
    }

  @cask.postForm("/r/:repoId/upload/:uploadId")
  def uploadFilesForm(image: cask.FormFile, repoId: String, uploadId: String)(implicit request: cask.Request): cask.Response[String] = {
    logger.info(s"Uploading selected files. Upload ID: $uploadId")

    val formData = request.exchange.getAttachment(FormDataParser.FORM_DATA)

//    if (!formData.iterator().hasNext) {
      logger.error("Not a multipart upload")
      val payload = "<!doctype html>" + htmx.html.upload_form
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
//    }
  }

  @requireLogin()
  @cask.get("/r/:repoId")
  def repositoryView(repoId: String)(using request: Request): cask.Response[String] = {
    if (!App.altitude.isInitialized) {
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    }

    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.index(stats = stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  @cask.get("/setup")
  def setup(): cask.Response[String] = {
    val payload = "<!doctype html>" + html.setup()
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }

  @requireLogin()
  @cask.get("/pipeline/r/:repoId")
  def pipeline(repoId: String): cask.Response[String] = {
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.pipeline(stats = stats)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
  }


  @cask.websocket("/import/status")
  def pipelineStatus(userId: String): cask.WebsocketResult = {
    cask.WsHandler { wsClient =>
      App.altitude.actorSystem ! ImportStatusWsActor.AddClient(userId, wsClient)
      wsClient.send(cask.Ws.Text("connected"))

      cask.WsActor {
        case cask.Ws.Error(e) => {
          logger.warn("Connection error: " + e.getMessage)
          App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
        }
        case cask.Ws.Close(_, _) | cask.Ws.ChannelClosed() => {
          logger.info("Connection closed.")
          App.altitude.actorSystem ! ImportStatusWsActor.RemoveClient(userId, wsClient)
        }
      }
    }
  }

  initialize()
