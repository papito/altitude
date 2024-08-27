package software.altitude.core.controllers.api.navigate

import org.scalatra.CorsSupport
import org.scalatra.InternalServerError
import org.scalatra.ScalatraServlet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.Api

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Paths

class FileSystemBrowserController extends ScalatraServlet with CorsSupport {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)
  private val userHomeDir = System.getProperty("user.home")

  // OPTIMIZE: Does this even work in Windows?

  before() {
    contentType = "application/json; charset=UTF-8"
  }

  get("/fs/listing") {
    val currentPathFile: File = new File(params.getOrElse(Api.Field.PATH, userHomeDir))
    getDirectoryListing(currentPathFile)
  }

  get("/fs/listing/parent") {
    val currentPathFile = new File(params.getOrElse(Api.Field.PATH, ""))
    val parentDir: String = currentPathFile.getParent
    val parentDirFile = if (parentDir != null) new File(parentDir) else currentPathFile
    getDirectoryListing(parentDirFile)
  }

  get("/fs/listing/child") {
    val currentPathFile: File = new File(params.getOrElse(Api.Field.PATH, ""))
    val childDirName = params.get(Api.Field.CHILD_DIR)
    val childDirPath = Paths.get(currentPathFile.getAbsolutePath, childDirName.get)
    getDirectoryListing(childDirPath.toFile)
  }

  get("/fs/listing/jump") {
    val currentPathFile: File = new File(params.getOrElse(Api.Field.PATH, ""))
    val position = params.getOrElse(Api.Field.PATH_POS, "1").toInt
    val paths: List[File] = Iterator.iterate(currentPathFile)(_.getParentFile).takeWhile(_ != null).toList.reverse
    getDirectoryListing(paths(position + 1))
  }

  private def getDirectoryListing(file: File): JsObject = {
    logger.debug(s"Getting directory name list for $file")
    val allContents: Seq[File] = file.listFiles().toSeq

    val files: Seq[String] = allContents
      .filter(_.isFile)
      .map(_.getName)
      .filter(!_.startsWith("."))
      .sorted

    val directories: Seq[String] = allContents
      .filter(_.isDirectory)
      .map(_.getName)
      .filter(!_.startsWith("."))
      .sorted

    // FIXME: bush league - use proper Path ways to do this
    val pathBreadcrumbs = file.getAbsolutePath.split(File.separator.toCharArray).filter(!_.isBlank).toList

    Json.obj(
      Api.Field.FILES -> files,
      Api.Field.DIRECTORIES -> directories,
      Api.Field.CURRENT_PATH -> file.getAbsolutePath,
      Api.Field.CURRENT_PATH_BREADCRUMBS -> pathBreadcrumbs
    )
  }

  error {
    case ex: Exception =>
      ex.printStackTrace()
      val sw: StringWriter = new StringWriter()
      val pw: PrintWriter = new PrintWriter(sw)
      ex.printStackTrace(pw)
      logger.error(s"Exception ${sw.toString}")
      InternalServerError(sw.toString)
  }
}
