package software.altitude.core

import org.scalatra.ScalatraServlet
import org.scalatra.servlet.ServletApiImplicits._
import org.slf4j.LoggerFactory
import software.altitude.core.controllers.api._
import software.altitude.core.controllers.htmx.SetupController
import software.altitude.core.controllers.web.ImportController
import software.altitude.core.controllers.web.IndexController
import software.altitude.core.controllers.web.SessionController

import javax.servlet.ServletContext

object AltitudeServletContext {
  private final val log = LoggerFactory.getLogger(getClass)
  log.info("Initializing application context... ")
  val app: Altitude = new Altitude

  // private val actorSystem = ActorSystem()

  val endpoints: Seq[(ScalatraServlet, String)] = List(
    (new IndexController, "/*"),
    (new ImportController, "/import/*"),

    (new AssetController, "/api/v1/assets/*"),
    (new SearchController, "/api/v1/search/*"),
    (new FolderController, "/api/v1/folders/*"),
    (new TrashController, "/api/v1/trash/*"),
    (new StatsController, "/api/v1/stats/*"),
    (new MetadataController, "/api/v1/metadata/*"),
    (new SessionController, "/sessions/*"),

    (new SetupController, "/htmx/admin/setup/*"),
    (new admin.MetadataController, "/api/v1/admin/metadata/*"),

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
