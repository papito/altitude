package altitude.core.routes.web.partial

import cask.Request
import cask.model.Response
import org.slf4j.Logger

import altitude.core.{ Const => C }
import altitude.core.Api
import altitude.core.App
import altitude.core.DataScrubber
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator
import altitude.core.models.AccountType
import altitude.core.models.User
import altitude.core.routes.BaseController
import altitude.core.routes.web.SessionController

class SetupFormController(using logger: Logger) extends BaseController:
  private val prefix = "htmx"

  private val dataScrubber = DataScrubber(
    trim = List(
      Api.Field.Setup.REPOSITORY_NAME,
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2
    ),
    lower = List(Api.Field.Setup.ADMIN_EMAIL)
  )

  private val apiRequestValidator = ApiRequestValidator(
    required = List(
      Api.Field.Setup.REPOSITORY_NAME,
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2
    ),
    maxLengths = Map(
      Api.Field.Setup.REPOSITORY_NAME -> Api.Constraints.MAX_REPOSITORY_NAME_LENGTH,
      Api.Field.Setup.ADMIN_EMAIL -> Api.Constraints.MAX_EMAIL_LENGTH,
      Api.Field.Setup.ADMIN_NAME -> Api.Constraints.MAX_NAME_LENGTH,
      Api.Field.Setup.PASSWORD -> Api.Constraints.MAX_PASSWORD_LENGTH
    ),
    minLengths = Map(
      Api.Field.Setup.REPOSITORY_NAME -> Api.Constraints.MIN_REPOSITORY_NAME_LENGTH,
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
      // Parse JSON from request body
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
      val password = jsonIn.obj.get(Api.Field.Setup.PASSWORD).flatMap(_.strOpt).getOrElse("")
      val password2 = jsonIn.obj.get(Api.Field.Setup.PASSWORD2).flatMap(_.strOpt).getOrElse("")

      // Secondary validation checks
      if !validationException.errors.contains(Api.Field.Setup.PASSWORD) &&
        !validationException.errors.contains(Api.Field.Setup.PASSWORD2)
      then
        if password != password2 then
          validationException.errors.addOne(
            Api.Field.Setup.PASSWORD -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH
          )

      // If we have errors, return form with errors
      if validationException.errors.nonEmpty then
        val payload = "<!doctype html>" + htmx.html.setup_form(jsonIn, validationException.errors.toMap)
        cask.Response(payload, 200, Seq(("Content-Type", "text/html")))
      else
        // Initialize system
        val userModel = User(
          email = email,
          name = name,
          accountType = AccountType.Admin
        )

        val (admin, repo) = App.altitude.service.system.initializeSystem(
          repositoryName = repositoryName,
          adminModel = userModel,
          password = password
        )

        App.altitude.service.user.setLastActiveRepoId(admin, repo.persistedId)

        // Auto-login the newly created admin user
        val adminUser = RequestContext.getAccount
        val token = App.altitude.service.paseto.createToken(adminUser)

        // Send HTML redirect header with auth cookie
        cask.Response(
          data = "",
          statusCode = 200,
          headers = Seq("HX-Redirect" -> s"/r/${repo.persistedId}"),
          cookies = Seq(
            cask.model.Cookie(
              name = SessionController.AUTH_COOKIE_NAME,
              value = token,
              path = "/",
              maxAge = SessionController.COOKIE_MAX_AGE_SECONDS,
              httpOnly = true,
              secure = true,
              sameSite = "Strict"
            ))
        )

  initialize()
