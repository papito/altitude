package altitude.core.routes.web

import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.models.Permission
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.rememberRepository
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.decorators.requirePermission
import altitude.core.routes.web.partial.UserActionController

/**
 * The Users page, an Admin's or that of a holder of Create Users or Create Guests in the Library: every account for an Admin, the
 * accounts it created for anyone else. Its dialogs and writes are `UserActionController`'s.
 */
class UsersController(using logger: Logger) extends BaseController:

  @requireLogin()
  @requirePermission(Permission.CreateUsers, Permission.CreateGuests)
  @rememberRepository()
  @cask.get("/users/r/:repoId")
  def page(repoId: String): Response[String] =
    val stats = App.altitude.service.stats.getStats
    val payload = "<!doctype html>" + html.users(stats, UserActionController.accounts(RequestContext.getAccount))
    cask.Response(payload, 200, Seq(("Content-Type", "text/html")))

  initialize()
