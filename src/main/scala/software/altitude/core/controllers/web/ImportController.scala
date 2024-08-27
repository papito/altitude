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
import org.scalatra.json.JValueResult
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.servlet.SizeConstraintExceededException
import software.altitude.core.Api
import software.altitude.core.DuplicateException
import software.altitude.core.RequestContext
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.controllers.web.ImportController.isCancelled
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.Metadata

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global

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

  private class ImportCounters {
    val processedSoFar = new AtomicInteger(0)
    val inProgress = new AtomicInteger(0)
    val total = new AtomicInteger(0)
    val isFinishedUploading = new AtomicBoolean(false)
  }

  private val importAssetCountPerRepo = new ConcurrentHashMap[String, ImportCounters]()

  implicit protected val jsonFormats: Formats = DefaultFormats

  private val userToWsClientLookup = collection.mutable.Map[String, List[AtmosphereClient]]()

  // HTMX WS client expects a div with id "status" to update the status ticker
  private val successStatusTickerTemplate = "<div id=\"statusText\">%s</div>"
  private val warningStatusTickerTemplate = "<div id=\"statusText\" class=\"warning\">%s</div>"
  private val errorStatusTickerTemplate = "<div id=\"statusText\" class=\"error\">%s</div>"

  // I don't feel strongly about this
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
          logger.info(s"Associating user $userId with client $uuid")

          if (!userToWsClientLookup.contains(userId)) {
            userToWsClientLookup += userId -> List(this)
          } else {
            userToWsClientLookup(userId) = this :: userToWsClientLookup(userId)
          }

        case Disconnected(disconnector, Some(error)) =>
          logger.info(s"Disassociating user $userId with client $uuid")
          val clients = userToWsClientLookup(userId)
          userToWsClientLookup(userId) = clients.filterNot(_ == this)

        case Error(Some(error)) =>
            logger.error(s"WS Error: $error")

        case TextMessage(text) =>
        case JsonMessage(json) =>
      }
    }

    client
  }

  val cancelUpload: Route = delete(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}/cancel") {
    val uploadId = params(Api.Field.Upload.UPLOAD_ID)
    logger.info(s"CANCELLING upload ID: $uploadId")
    ImportController.uploadCancelRequest(uploadId) = true
  }

  val uploadFilesForm: Route = post(s"/r/:repoId/upload/:${Api.Field.Upload.UPLOAD_ID}") {
    contentType = "text/html"
    val uploadId = params(Api.Field.Upload.UPLOAD_ID)
    logger.info(s"Uploading selected files. Upload ID: $uploadId")

    val servletFileUpload = new ServletFileUpload()
    servletFileUpload.setFileSizeMax(fileSizeLimitGB * 1024 * 1024)

    if (!ServletFileUpload.isMultipartContent(request)) {
      logger.error("Not a multipart upload")
      halt(200, layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp"))
    }

    val importStatus = importAssetCountPerRepo.computeIfAbsent(uploadId, _ => new ImportCounters )

    val iter = servletFileUpload.getItemIterator(request)
    while (iter.hasNext && !isCancelled(uploadId)) {
      logger.debug("Next file")
      importStatus.total.addAndGet(1)

      val fileItemStream = iter.next()
      val fStream = fileItemStream.openStream()
      val bytes = IOUtils.toByteArray(fStream)
      logger.info(s"Received file: ${fileItemStream.getName}]")

      val importAsset = new ImportAsset(
        fileName = fileItemStream.getName,
        data = bytes,
        metadata = Metadata())

      app.executorService.submit(new Runnable {
        override def run(): Unit = {
          logger.debug(s"Processing file ${importAsset.fileName} in thread")
          try {
            val processedSoFar = importStatus.processedSoFar.addAndGet(1)
            importStatus.inProgress.addAndGet(1)

            app.service.assetImport.importAsset(importAsset)

            val importedStatusText = s"Imported $processedSoFar: <span>${importAsset.fileName}</span>"

            sendWsStatusToUserClients(successStatusTickerTemplate.format(importedStatusText))
          } catch {
            case _: DuplicateException =>
              logger.warn(s"Duplicate asset: ${importAsset.fileName}")
              val duplicateStatusText = s"Ignoring duplicate: <span>${importAsset.fileName}</span>"
              sendWsStatusToUserClients(warningStatusTickerTemplate.format(duplicateStatusText))

            case e: Exception =>
              logger.error("Error importing asset:", e)

              val errorStatusText = s"Error: <span>${importAsset.fileName}</span>"
              sendWsStatusToUserClients(errorStatusTickerTemplate.format(errorStatusText))

          } finally {
            val isFinishedUploading =  importStatus.isFinishedUploading.get()
            val inProgress = importStatus.inProgress.decrementAndGet()
            val processedSoFar = importStatus.processedSoFar.get()
            val total = importStatus.total.get()
            val _isCancelled = isCancelled(uploadId)

            logger.info(s"Is cancelled: ${_isCancelled}, is finished uploading: $isFinishedUploading, total: $total, in progress: $inProgress, processed so far: $processedSoFar")
            val isDone = isFinishedUploading && (processedSoFar == total) && inProgress == 0

            if (isDone || _isCancelled) {
              // save the model after all files have been processed
              app.service.faceRecognition.saveModel()

              sendWsStatusToUserClients(
                successStatusTickerTemplate.format("All files processed"))
            }
          }
        }
      })

      fStream.close()
    }

    // if false, then true
    importStatus.isFinishedUploading.compareAndExchange(false, true)

    layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp")
  }

  private def sendWsStatusToUserClients(message: String): Unit = {
    userToWsClientLookup.get(RequestContext.getAccount.persistedId).foreach { clients =>
      clients.foreach { client =>client.send(message)
      }
    }
  }

  error {
    case _: SizeConstraintExceededException =>
      RequestEntityTooLarge(s"File size exceeds ${fileSizeLimitGB}GB")
  }
}
