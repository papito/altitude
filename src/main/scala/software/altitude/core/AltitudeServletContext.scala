package software.altitude.core

import org.scalatra.ScalatraServlet
import org.scalatra.servlet.ServletApiImplicits._
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.controllers.htmx.AlbumActionController
import software.altitude.core.controllers.htmx.AssetActionController
import software.altitude.core.controllers.htmx.FolderActionController
import software.altitude.core.controllers.htmx.PeopleActionController
import software.altitude.core.controllers.htmx.SearchResultsController
import software.altitude.core.controllers.htmx.SetupController
import software.altitude.core.controllers.web.ContentViewController
import software.altitude.core.controllers.web.ImportController
import software.altitude.core.controllers.web.IndexController
import software.altitude.core.controllers.web.SessionController
import software.altitude.core.models.Repository
import software.altitude.core.models.User

import javax.servlet.ServletContext

object AltitudeServletContext {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)
  logger.info("Initializing application context... ")

  val app: Altitude = new Altitude

  // id -> user
  var usersById: Map[String, User] = Map[String, User]()
  // email -> user
  var usersByEmail: Map[String, User] = Map[String, User]()
  // email -> password hash
  var usersPasswordHashByEmail: Map[String, String] = Map[String, String]()
    // token -> user
  var usersByToken: Map[String, User] = Map[String, User]()
  // id -> repository
  var repositoriesById: Map[String, Repository] = Map[String, Repository]()

  def clearState(): Unit = {
    usersById = Map.empty
    usersByEmail = Map.empty
    usersByToken = Map.empty
    usersPasswordHashByEmail = Map.empty
    repositoriesById = Map.empty
  }

  val endpoints: Seq[(ScalatraServlet, String)] = List(
    (new IndexController, "/"),
    (new ImportController, "/import/*"),

    (new SessionController, "/sessions/*"),
    (new ContentViewController, "/content/*"),
    (new FolderActionController, "/htmx/folder/*"),
    (new AssetActionController, "/htmx/asset/*"),
    (new AlbumActionController, "/htmx/album/*"),
    (new PeopleActionController, "/htmx/people/*"),
    (new SearchResultsController, "/htmx/search/*"),

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
  var usersById: Map[String, User] = AltitudeServletContext.usersById
  var usersByEmail: Map[String, User] = AltitudeServletContext.usersByEmail
  var usersByToken: Map[String, User] = AltitudeServletContext.usersByToken
  var usersPasswordHashByEmail: Map[String, String] = AltitudeServletContext.usersPasswordHashByEmail
  var repositoriesById: Map[String, Repository] = AltitudeServletContext.repositoriesById
}
