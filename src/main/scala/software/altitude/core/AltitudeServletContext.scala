package software.altitude.core

import org.scalatra.ScalatraServlet
import org.scalatra.servlet.ServletApiImplicits._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.controllers.api._
import software.altitude.core.controllers.htmx.{FolderActionController, SetupController, UserInterfaceHtmxController}
import software.altitude.core.controllers.web.ImportController
import software.altitude.core.controllers.web.IndexController
import software.altitude.core.controllers.web.SecuredStaticFileController
import software.altitude.core.controllers.web.SessionController

import javax.servlet.ServletContext

object AltitudeServletContext {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.info("Initializing application context... ")
  val app: Altitude = new Altitude

  val endpoints: Seq[(ScalatraServlet, String)] = List(
    (new IndexController, "/"),
    (new ImportController, "/import/*"),

    (new AssetController, "/api/v1/assets/*"),
    (new SearchController, "/api/v1/search/*"),
    (new FolderController, "/api/v1/folders/*"),
    (new TrashController, "/api/v1/trash/*"),
    (new StatsController, "/api/v1/stats/*"),
    (new MetadataController, "/api/v1/metadata/*"),
    (new SessionController, "/sessions/*"),
    (new SecuredStaticFileController, "/content/*"),
    (new UserInterfaceHtmxController, "/htmx/*"),
    (new FolderActionController, "/htmx/folder/*"),

    (new SetupController, "/htmx/admin/setup/*"),

    // (new admin.MetadataController, "/api/v1/admin/metadata/*"),
    // (new FileSystemBrowserController, "/navigate/*"),
    // (new ImportController(actorSystem), "/import/*")
  )

  def mountEndpoints(context: ServletContext): Unit = {
    endpoints.foreach { case (servlet, path) =>
      context.mount(servlet, path)
    }
  }
}

trait AltitudeServletContext {
  val app: Altitude = AltitudeServletContext.app
}
