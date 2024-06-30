package software.altitude.core.controllers.web

import org.scalatra.RequestEntityTooLarge
import org.scalatra.servlet.FileUploadSupport
import org.scalatra.servlet.MultipartConfig
import org.scalatra.servlet.SizeConstraintExceededException
import software.altitude.core.controllers.BaseWebController

class ImportController extends BaseWebController with FileUploadSupport {
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
          logger.info(s"Received file: $file")

/*        val importAsset = new ImportAsset(
          fileName = file.getName,
          data = file.get(),
          metadata = Metadata())

          app.service.assetImport.importAsset(importAsset)*/
      }

      case None =>
        logger.warn("No files received for upload")
        halt(200, layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp"))
    }

    layoutTemplate("/WEB-INF/templates/views/htmx/upload_form.ssp")
  }

  error {
    case _: SizeConstraintExceededException =>
      RequestEntityTooLarge(s"File size exceeds ${fileSizeLimitGB}GB")
  }
}
