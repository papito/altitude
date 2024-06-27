package software.altitude.core.controllers.web

import org.scalatra.{Ok, RequestEntityTooLarge}
import org.scalatra.servlet.{FileUploadSupport, MultipartConfig, SizeConstraintExceededException}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import software.altitude.core.controllers.BaseWebController

class ImportController extends BaseWebController with FileUploadSupport {
  private final val log = LoggerFactory.getLogger(getClass)

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(10*1024*1024)))

  get("/") {
    contentType = "text/html"
    ssp("/import")
  }

  post("/upload") {
    fileMultiParams.get("files") match {
      case Some(files) => files.foreach { file =>
        println(s"Received file: ${file}")
        file.getName
      }

      case None => println("No files received")
    }

    Ok(Json.obj(
      "file1" -> Json.obj(
        "name" -> "test1.txt",
        "size" -> 1234)), headers = Map("Content-Type" -> "application/json; charset=UTF-8"))
  }

  error {
    case e: SizeConstraintExceededException => RequestEntityTooLarge("File size exceeds 10GB")
  }
}
