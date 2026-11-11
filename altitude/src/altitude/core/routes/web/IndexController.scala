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

  /**
   * To the Library `user` lands in (`RepositoryService.landingRepositoryOf`). A user who can see none is told so in plain text,
   * the way a foreign Library is not found, rather than sent anywhere.
   */
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
      // The client seeds its search from the address, so a view the account does not see is refused here. A Guest sees Browse
      // alone, without the triaged assets; the nav offers it nothing else
      val triaged = params.value.get(Api.Field.Search.TRIAGED).flatMap(_.headOption).flatMap(_.toBooleanOption)
      refusedView(shown, triaged).getOrElse(mainPage(shown))

  /**
   * The address of one asset: the main page, over whose default results the client opens Asset detail on the asset named by the
   * path (js/search-results/detail-url.js, which also reads the Detail panel's `panel` parameter). An unknown asset, or another
   * repository's, is a 404 here, before any page is rendered around it, and so is a triaged asset or one in the trash for a
   * Guest, who sees Browse alone (`visibleOnly`).
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

  /** The nav marks the view asked for; the client reads the same parameter to seed its search */
  private def mainPage(view: String): cask.Response[String] =
    val payload = "<!doctype html>" + html.index(stats = App.altitude.service.stats.getStats, current = view)
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
