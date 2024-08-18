package software.altitude.core.controllers.web

import org.scalatra.Route
import org.scalatra.ScalatraServlet
import org.scalatra.UrlGeneratorSupport
import org.scalatra.scalate.ScalateSupport
import org.scalatra.scalate.ScalateUrlGeneratorSupport
import software.altitude.core.auth.AuthenticationSupport

class SessionController
  extends ScalatraServlet
    with UrlGeneratorSupport
    with ScalateUrlGeneratorSupport
    with ScalateSupport
    with AuthenticationSupport {

  before("/new") {
    logger.info("SessionsController: checking whether to run RememberMeStrategy: " + !isAuthenticated)

    if (!isAuthenticated) {
      scentry.authenticate("RememberMeStrategy")
    }
  }

  /**
   * WARNING: hard-coded in AuthenticationSupport
   */
  val newSession: Route = get("/new") {
    if (isAuthenticated) redirect("/")

    contentType = "text/html"
    layoutTemplate("/WEB-INF/templates/views/login.ssp")
  }

  val doLogin: Route = post("/login") {
    scentry.authenticate()

    if (isAuthenticated) {
      redirect("/")
    } else {
      layoutTemplate("/WEB-INF/templates/views/login.ssp")
    }
  }

  val logout: Route = post("/logout") {
    scentry.logout()
    redirect("/")
  }

}
