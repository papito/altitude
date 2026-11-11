package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/**
 * Every account's info dialog, from the info button of the nav's account menu: who the account is, its type, its Permissions in
 * the Library of the page it opens on and in every Library it sees, and its times. The path names that Library, so any account
 * with access to it reaches the dialog.
 */
class AccountInfoController(using logger: Logger) extends BaseController:

  /**
   * The dialog, from the login token (the account, its grants and the session's expiry) and from the database: the names of the
   * Libraries the account sees, and when it was added and last logged in (`UserService.accountTimes`). An Admin's Libraries are
   * all of them, which the dialog says without listing them.
   */
  @requireLogin()
  @cask.get("/htmx/account/r/:repoId/dialogs/info")
  def showDialog(repoId: String)(using request: Request): Response[String] =
    val account = RequestContext.getAccount
    val libraries =
      if account.accountType == AccountType.Admin then Nil
      else
        App.altitude.service.repository
          .getAccessibleTo(account)
          .flatMap(library => RequestContext.grants.value.get(library.persistedId).map(library -> _))
    htmlResponse(
      htmx.html.account_info_dialog(
        account,
        libraries,
        App.altitude.service.user.accountTimes(account.persistedId),
        RequestContext.sessionExpiresAt.value))

  initialize()
