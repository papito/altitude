package altitude.core.routes.web

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.Api
import altitude.core.App
import altitude.core.Const
import altitude.core.NotFoundException
import altitude.core.models.User
import altitude.core.routes.decorators.authenticate
import altitude.core.routes.decorators.refusedView
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.renewedCookies
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.visibleOnly

class IndexController(using logger: Logger, caskLogger: cask.Logger, context: castor.Context) extends cask.Routes:
  @cask.get("/")
  def index()(request: Request): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      return Response("", 302, Seq("Location" -> "/setup"), Nil)

    authenticate(request) match
      case Right(login) =>
        logger.debug(s"User authenticated: ${login.claims.account.persistedId}")
        val answer = landing(login.claims.account)
        answer.copy(cookies = answer.cookies ++ renewedCookies(login))
      case Left(noLogin) => Response("", 302, Seq("Location" -> "/login"), noLogin.cookies)

  /** Redirects to the Library `user` lands in; a user who can see none gets a plain-text 404, as for a foreign Library */
  private def landing(user: User): cask.Response[String] =
    App.altitude.service.repository.landingRepositoryOf(user) match
      case Some(repo) => Response("", 302, Seq("Location" -> s"/r/${repo.persistedId}"), Nil)
      case None =>
        logger.info(s"User [${user.persistedId}] can see no library to land in")
        cask.Response(Const.Msg.Err.NO_LIBRARY, 404, Seq(("Content-Type", "text/plain")))

  @requireLogin()
  @rememberRepository()
  @cask.get("/r/:repoId")
  def repositoryView(
      repoId: String,
      view: Option[String] = None,
      personId: Option[String] = None,
      folderId: Option[String] = None,
      params: cask.QueryParams /* allow unknown params */ ): cask.Response[String] =
    if !App.altitude.isInitialized then
      logger.warn("App is not initialized, redirecting to setup")
      Response("", 302, Seq("Location" -> "/setup"), Nil)
    else
      val shown = view.getOrElse(Const.Search.View.DEFAULT)
      // The client seeds its search from the address, so a view the account may not see is refused here
      val triaged = params.value.get(Api.Field.Search.TRIAGED).flatMap(_.headOption).flatMap(_.toBooleanOption)
      refusedView(shown, triaged).getOrElse(mainPage(shown))

  /**
   * The address of one asset: the main page, on which the client opens Asset detail for the asset in the path
   * (js/search-results/detail-url.js). An asset the account may not open is a 404 before any page is rendered.
   */
  @requireLogin()
  @rememberRepository()
  @cask.get("/r/:repoId/asset/:assetId")
  def assetView(repoId: String, assetId: String, params: cask.QueryParams /* allow unknown params */ ): cask.Response[String] =
    val visible =
      try
        visibleOnly(App.altitude.service.asset.getById(assetId)): Unit
        true
      catch case _: NotFoundException => false
    if visible then mainPage(Const.Search.View.DEFAULT)
    else cask.Response("Asset not found", 404, Seq(("Content-Type", "text/plain")))

  private def mainPage(view: String): cask.Response[String] =
    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats, current = view)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
