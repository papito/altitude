package software.altitude.core.controllers.web

import org.scalatra.RequestEntityTooLarge
import org.scalatra.servlet.FileUploadSupport
import org.scalatra.servlet.MultipartConfig
import org.scalatra.servlet.SizeConstraintExceededException
import org.slf4j.LoggerFactory
import software.altitude.core.controllers.BaseWebController

class ImportController extends BaseWebController with FileUploadSupport {
  private final val log = LoggerFactory.getLogger(getClass)
  private val fileSizeLimitGB = 10

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(fileSizeLimitGB*1024*1024)))

  before() {
    requireLogin()
  }

  get("/") {
    contentType = "text/html"
    layoutTemplate("/WEB-INF/templates/views/import.ssp")
  }

  post("/upload") {
    contentType = "text/html"

    fileMultiParams.get("files") match {
      case Some(files) => files.foreach { file =>
        println(s"Received file: $file")
        println(file.getName)
        val data = file.get()
      }

      case None =>
        log.warn("No files received for upload")
        halt(200, layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp"))
    }

    layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp")
  }

  error {
    case _: SizeConstraintExceededException =>
      RequestEntityTooLarge(s"File size exceeds ${fileSizeLimitGB}GB")
  }
}
