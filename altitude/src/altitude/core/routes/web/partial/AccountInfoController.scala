package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.App
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin

/** The account info dialog, open to every account with access to the Library in the path */
class AccountInfoController(using logger: Logger) extends BaseController:

  /** Built from the login token and the account's Libraries and times. An Admin sees every Library, so none are listed. */
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
