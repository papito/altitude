package software.altitude.core.controllers.web

import org.scalatra.RequestEntityTooLarge
import org.scalatra.servlet.FileUploadSupport
import org.scalatra.servlet.MultipartConfig
import org.scalatra.servlet.SizeConstraintExceededException
import software.altitude.core.{Const, Environment, RequestContext}
import software.altitude.core.controllers.BaseWebController
import software.altitude.core.models.{ImportAsset, Metadata, Repository}
import software.altitude.core.util.Query

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

    // FIXME temporary environment-specific hacks
    Environment.ENV match {
      case Environment.PROD =>
      case Environment.DEV =>
        val res = app.service.repository.query(new Query().add(Const.Repository.NAME -> "Personal"))
        RequestContext.repository.value = Some(res.records.head: Repository)
        logger.warn(s"Using hardcoded repository: ${RequestContext.repository.value.get.name}")
      case Environment.TEST =>
        val testRepoId: String = request.getHeader(Const.Api.REPO_TEST_HEADER_ID)
        val repo: Repository = app.service.repository.getById(testRepoId)
        RequestContext.repository.value = Some(repo)
      case _ => throw new RuntimeException("Unknown environment")
    }

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
