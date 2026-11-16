package altitude.core.routes.web

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.requireAccountCreator
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.web.partial.UserActionController

class UsersController(using logger: Logger) extends BaseController:

  @requireLogin()
  @requireAccountCreator()
  @rememberRepository()
  @cask.get("/users/r/:repoId")
  def page(repoId: String): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.users(stats, UserActionController.accounts(RequestContext.getAccount))
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
