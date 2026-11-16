package altitude.core.routes.web.partial

import cask.Request
import cask.model.Cookie
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.routes.BaseController
import altitude.core.routes.decorators.requireLogin
import altitude.core.routes.web.SessionController

object OwnPasswordController:

  /**
   * A fresh session cookie after a password change, which revoked every earlier token (`AccountVersions.revoke`), so the session
   * that made the change carries on. Empty when the account is gone or deactivated meanwhile.
   */
  def renewedSession(accountId: String): Seq[Cookie] =
    App.altitude.service.user
      .claimsOf(accountId)
      .map(claims => SessionController.authCookie(App.altitude.service.paseto.createToken(claims), claims.expiresAt))
      .toSeq

/** Changing one's own password. The routes name no Library, since a password is the account's. */
class OwnPasswordController(using logger: Logger) extends BaseController:
  private val prefix = "htmx/account"

  @requireLogin()
  @cask.get(f"/$prefix/dialogs/password")
  def showDialog()(using request: Request): Response[String] =
    htmlResponse(htmx.html.own_password_dialog())

  /** Every other session of the account is refused from then on; this one is given a new token */
  @requireLogin()
  @cask.put(f"/$prefix/password")
  def htmxChange()(using request: Request): Response[String] =
    answering {
      val account = RequestContext.getAccount
      val json = unscrubbedJson.get
      def text(field: String) = json.obj.get(field).flatMap(_.strOpt).getOrElse("")
      val (current, chosen, repeated) =
        (text(Api.Field.User.CURRENT_PASSWORD), text(Api.Field.User.NEW_PASSWORD), text(Api.Field.User.NEW_PASSWORD2))

      try
        if chosen != repeated then
          val ex = ValidationException(C.Msg.Err.PASSWORDS_DO_NOT_MATCH)
          ex.errors += Api.Field.User.NEW_PASSWORD2 -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH
          throw ex
        App.altitude.service.user.changeOwnPassword(
          account.persistedId,
          current,
          chosen,
          currentField = Api.Field.User.CURRENT_PASSWORD,
          newField = Api.Field.User.NEW_PASSWORD,
          clientIp = BaseController.clientIpOf(request)
        )
        logger.info(s"Account [${account.persistedId}] changed its password")
        dialogSuccessResponse(ujson.Obj("message" -> "Your password is changed"))
          .copy(cookies = OwnPasswordController.renewedSession(account.persistedId))
      catch
        case ex: ValidationException if ex.errors.nonEmpty =>
          dialogFormValidationResponse("<!doctype html>" + htmx.html.own_password_dialog(ex.errors.toMap))
    }

  initialize()
