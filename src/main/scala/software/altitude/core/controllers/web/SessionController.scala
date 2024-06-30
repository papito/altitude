package software.altitude.core.controllers.web

import org.scalatra.ScalatraServlet
import org.scalatra.scalate.ScalateSupport
import software.altitude.core.auth.AuthenticationSupport

class SessionController extends ScalatraServlet with ScalateSupport with AuthenticationSupport {

  before("/new") {
    logger.info("SessionsController: checking whether to run RememberMeStrategy: " + !isAuthenticated)

    if (!isAuthenticated) {
      scentry.authenticate("RememberMeStrategy")
    }
  }

  get("/new") {
    if (isAuthenticated) redirect("/")

    contentType = "text/html"
    layoutTemplate("/WEB-INF/templates/views/login.ssp")
  }

  post("/") {
    scentry.authenticate()

    if (isAuthenticated) {
      redirect("/")
    } else {
      layoutTemplate("/WEB-INF/templates/views/login.ssp")
    }
  }

  //FIXME: use POST
  get("/logout") {
    scentry.logout()
    redirect("/")
  }

}
