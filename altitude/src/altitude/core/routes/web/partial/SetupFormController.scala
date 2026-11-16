package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.AccountType
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.routes.BaseController
import altitude.core.routes.web.SessionController

class SetupFormController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  // The Library name is not scrubbed: its rule (Repository.validName) normalizes and checks it, and a trim would hide the
  // control characters at its ends that the rule refuses
  private val dataScrubber = DataScrubber(
    trim = List(
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.ADMIN_USERNAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2
    ),
    lower = List(Api.Field.Setup.ADMIN_EMAIL)
  )

  private val apiRequestValidator = ApiRequestValidator(
    required = List(
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.ADMIN_USERNAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2
    ),
    maxLengths = Map(
      Api.Field.Setup.ADMIN_EMAIL -> Api.Constraints.MAX_EMAIL_LENGTH,
      Api.Field.Setup.ADMIN_NAME -> Api.Constraints.MAX_NAME_LENGTH,
      Api.Field.Setup.PASSWORD -> Api.Constraints.MAX_PASSWORD_LENGTH
    ),
    minLengths = Map(
      Api.Field.Setup.ADMIN_EMAIL -> Api.Constraints.MIN_EMAIL_LENGTH,
      Api.Field.Setup.ADMIN_NAME -> Api.Constraints.MIN_NAME_LENGTH,
      Api.Field.Setup.PASSWORD -> Api.Constraints.MIN_PASSWORD_LENGTH
    ),
    email = List(Api.Field.Setup.ADMIN_EMAIL)
  )

  @cask.route(f"/$prefix/setup", methods = Seq("post"))
  def htmxAdminSetup(using request: Request): Response[String] =
    val jsonData = unscrubbedJson

    if App.altitude.isInitialized then
      val message = "Instance is already initialized."
      logger.warn(message)
      cask.Response(
        data = message,
        statusCode = 400
      )
    else
      val jsonIn: ujson.Obj = dataScrubber.scrub(jsonData.get)

      val validationException: ValidationException =
        try
          apiRequestValidator.validate(jsonIn)
          ValidationException()
        catch
          case validationEx: ValidationException =>
            validationEx
          case ex: Throwable =>
            logger.error(ex.getMessage, ex)
            cask.Response(
              data = "Server error",
              statusCode = 500
            )
            return cask.Abort(500)

      val repositoryName = jsonIn.obj.get(Api.Field.Setup.REPOSITORY_NAME).flatMap(_.strOpt).getOrElse("")
      val email = jsonIn.obj.get(Api.Field.Setup.ADMIN_EMAIL).flatMap(_.strOpt).getOrElse("")
      val name = jsonIn.obj.get(Api.Field.Setup.ADMIN_NAME).flatMap(_.strOpt).getOrElse("")
      val username = jsonIn.obj.get(Api.Field.Setup.ADMIN_USERNAME).flatMap(_.strOpt).getOrElse("")
      val password = jsonIn.obj.get(Api.Field.Setup.PASSWORD).flatMap(_.strOpt).getOrElse("")
      val password2 = jsonIn.obj.get(Api.Field.Setup.PASSWORD2).flatMap(_.strOpt).getOrElse("")

      if !validationException.errors.contains(Api.Field.Setup.PASSWORD) &&
        !validationException.errors.contains(Api.Field.Setup.PASSWORD2)
      then
        if password != password2 then
          validationException.errors.addOne(
            Api.Field.Setup.PASSWORD -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH
          )

      // The username rule, checked here too so its error shows beside the others; UserService.add applies it again
      if !validationException.errors.contains(Api.Field.Setup.ADMIN_USERNAME) then
        try User.validUsername(username)
        catch
          case ex: ValidationException =>
            ex.errors.get(FieldConst.User.USERNAME).foreach {
              error => validationException.errors += (Api.Field.Setup.ADMIN_USERNAME -> error)
            }

      // The Library name rule, likewise; addRepository applies it again
      try Repository.validName(repositoryName)
      catch
        case ex: ValidationException =>
          ex.errors.get(FieldConst.Repository.NAME).foreach {
            error => validationException.errors += (Api.Field.Setup.REPOSITORY_NAME -> error)
          }

      if validationException.errors.nonEmpty then
        val payload = "<!doctype html>" + htmx.html.setup_form(jsonIn, validationException.errors.toMap)
        cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
      else
        val userModel = User(
          email = email,
          username = username,
          name = name,
          accountType = AccountType.Admin
        )

        // The Library is recorded as the Admin's last active one when the redirect loads its page
        val (_, repo) = App.altitude.service.system.initializeSystem(
          repositoryName = repositoryName,
          adminModel = userModel,
          password = password,
          clientIp = BaseController.clientIpOf(request)
        )

        // Log the new Admin in
        val claims = App.altitude.service.user.claimsOf(RequestContext.getAccount.persistedId).get
        val token = App.altitude.service.paseto.createToken(claims)

        cask.Response(
          data = "",
          statusCode = 200,
          headers = Seq("HX-Redirect" -> s"/r/${repo.persistedId}"),
          cookies = Seq(SessionController.authCookie(token, claims.expiresAt))
        )

  initialize()
