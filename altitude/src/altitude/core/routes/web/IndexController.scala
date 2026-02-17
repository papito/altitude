package altitude.core.routes.web

import altitude.core.{App, Const}
import altitude.core.routes.decorators.extractToken
import altitude.core.routes.decorators.requireLogin
import cask.Request
import cask.model.Response
import org.slf4j.Logger

class IndexController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @cask.get("/")
  def index()(request: Request): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      return Response("", 302, Seq("Location" -> "/setup"), Nil)

    val devEmail: String = App.altitude.config.getString(Const.Conf.DEV_USER)
    val devPassword: String = App.altitude.config.getString(Const.Conf.DEV_PASSWORD)

    if (devEmail.nonEmpty && devPassword.nonEmpty) {
      val devUserRes = App.altitude.service.user.loginAndSetUser(devEmail, devPassword)
      val devUser = devUserRes.map(_._1)

      if devUser.isEmpty then
        logger.warn(s"Dev user login failed for email: $devEmail")
        return Response("", 302, Seq("Location" -> "/login"), Nil)

      logger.info(s"User authenticated: ${devUserRes.get._1.email}")
      return Response("", 302, Seq("Location" -> s"/r/${devUser.get.lastActiveRepoId.get}"), Nil)
    }

    extractToken(request) match
      case Some(token) =>
        App.altitude.service.user.getUserFromToken(token) match
          case Some(user) =>
            logger.info(s"User authenticated: ${user.email}")
            Response("", 302, Seq("Location" -> s"/r/${user.lastActiveRepoId.get}"), Nil)
          case None =>
            Response("", 302, Seq("Location" -> "/login"), Nil)
      case None =>
        Response("", 302, Seq("Location" -> "/login"), Nil)

  @requireLogin()
  @cask.get("/r/:repoId")
  def repositoryView(repoId: String, view: Option[String] = None): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    else
      val stats = App.altitude.service.stats.getStats
      val payload = "<!doctype html>" + html.index(stats = stats)
      cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
