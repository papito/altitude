package software.altitude.core.controllers.web

import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.commons.io.IOUtils
import org.json4s.DefaultFormats
import org.json4s.Formats
import org.scalatra.RequestEntityTooLarge
import org.scalatra.Route
import org.scalatra.SessionSupport
import org.scalatra.atmosphere.AtmoReceive
import org.scalatra.atmosphere.AtmosphereClient
import org.scalatra.atmosphere.AtmosphereSupport
import org.scalatra.atmosphere.Connected
import org.scalatra.atmosphere.Disconnected
import org.scalatra.atmosphere.Error
import org.scalatra.atmosphere.JsonMessage
import org.scalatra.atmosphere.TextMessage
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.json.JValueResult
import org.scalatra.servlet.SizeConstraintExceededException

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import software.altitude.core.Api
import software.altitude.core.RequestContext
import software.altitude.core.actors.ImportStatusWsActor
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.controllers.web.ImportController.isCancelled
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.UserMetadata
import software.altitude.core.pipeline.PipelineTypes.PipelineContext

object ImportController {
  private val uploadCancelRequest = TrieMap[String, Boolean]()

  def isCancelled(uploadId: String): Boolean = {
    ImportController.uploadCancelRequest.contains(uploadId)
  }
}

class ImportController
  extends BaseWebController
  with AtmosphereSupport
  with JValueResult
  with JacksonJsonSupport
  with SessionSupport {
  private val fileSizeLimitGB = 10

  implicit protected val jsonFormats: Formats = DefaultFormats
  // configureMultipartHandling(MultipartConfig(maxFileSize = Some(fileSizeLimitGB*1024*1024)))

  before() {
    requireLogin()
  }

  val importView: Route = get("/r/:repoId") {
    contentType = "text/html"
    layoutTemplate("/WEB-INF/templates/views/import.ssp")
  }

  atmosphere("/status") {
    val userId = params("userId")

    val client = new AtmosphereClient {
      def receive: AtmoReceive = {
        case Connected =>
          logger.info("Client connected ...")
          app.actorSystem ! ImportStatusWsActor.AddClient(userId, this)

        case Disconnected(_, Some(_)) =>
          app.actorSystem ! ImportStatusWsActor.RemoveClient(userId, this)

        case Error(Some(error)) =>
          logger.error(s"WS Error: $error")

        case TextMessage(_) =>
        case JsonMessage(_) =>
      }
    }

    client
  }

  val uploadFilesForm: Route = post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}") {
    contentType = "text/html"
    val uploadId = params(Api.Field.Upload.UPLOAD_ID)
    logger.info(s"Uploading selected files. Upload ID: $uploadId")

    val servletFileUpload = new ServletFileUpload()

    if (!ServletFileUpload.isMultipartContent(request)) {
      logger.error("Not a multipart upload")
      halt(200, layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp"))
    }

    val pipelineContext = PipelineContext(repository = RequestContext.getRepository, account = RequestContext.getAccount)

    val iter = servletFileUpload.getItemIterator(request)

    while (iter.hasNext && !isCancelled(uploadId)) {
      logger.debug("Next file")

      val fileItemStream = iter.next()
      val fStream = fileItemStream.openStream()
      val bytes = IOUtils.toByteArray(fStream)
      fStream.close()
      logger.info(s"Received file: ${fileItemStream.getName}]")

      val importAsset = new ImportAsset(fileName = fileItemStream.getName, data = bytes, metadata = UserMetadata())
      val assetWithData = app.service.library.convImportAsset2dataAsset(importAsset)

      val fut = app.service.importPipeline.addToQueue(assetWithData, pipelineContext)
      Await.result(fut, Duration.Inf)
    }

    logger.info("All files sent to queue")
    layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp")
  }

  error {
    case _: SizeConstraintExceededException =>
      RequestEntityTooLarge(s"File size exceeds ${fileSizeLimitGB}GB")
  }
}
