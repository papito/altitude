package software.altitude.core.controllers.web

import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{RequestEntityTooLarge, SessionSupport}
import org.scalatra.atmosphere.{AtmoReceive, AtmosphereClient, AtmosphereSupport, Connected, Disconnected, Error, JsonMessage, TextMessage}
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig, SizeConstraintExceededException}
import software.altitude.core.{DuplicateException, RequestContext}
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.models.{Asset, ImportAsset, Metadata}

import scala.concurrent.ExecutionContext.Implicits.global

class ImportController extends BaseWebController with FileUploadSupport with AtmosphereSupport   with JValueResult
  with JacksonJsonSupport with SessionSupport {
  private val fileSizeLimitGB = 10

  implicit protected val jsonFormats: Formats = DefaultFormats

  private val userToWsClientLookup = collection.mutable.Map[String, List[AtmosphereClient]]()

  // HTMX WS client expects a div with id "status" to update the status ticker
  private val successStatusTickerTemplate = "<div id=\"statusText\">%s</div>"
  private val warningStatusTickerTemplate = "<div id=\"statusText\" class=\"warning\">%s</div>"

  // I don't feel strongly about this
  configureMultipartHandling(MultipartConfig(maxFileSize = Some(fileSizeLimitGB*1024*1024)))

  before() {
    requireLogin()
  }

  get("/") {
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

  post("/upload") {
    contentType = "text/html"

    fileMultiParams.get("files") match {
      case Some(files) => files.foreach { file =>
          logger.info(s"Received file: $file")

          val importAsset = new ImportAsset(
            fileName = file.getName,
            data = file.get(),
            metadata = Metadata())

        app.executorService.submit(new Runnable {
          override def run(): Unit = {
            try {
              val importedAsset: Option[Asset] = app.service.assetImport.importAsset(importAsset)
              if (importedAsset.isDefined) {
                sendWsStatusToUserClients(
                  successStatusTickerTemplate.format("Imported: " + importedAsset.get.fileName))
              }
            } catch {
              case _: DuplicateException =>
                logger.warn(s"Duplicate asset: ${importAsset.fileName}")

                sendWsStatusToUserClients(
                  warningStatusTickerTemplate.format(s"Ignoring duplicate: ${importAsset.fileName}"))
              case e: Exception => logger.error("Error importing asset:", e)
            }
          }
        })
      }
      case None =>
        logger.warn("No files received for upload")
        halt(200, layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp"))
    }

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
