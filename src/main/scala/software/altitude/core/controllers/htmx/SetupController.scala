package software.altitude.core.controllers.htmx
import org.scalatra.Route
import play.api.libs.json.JsObject
import software.altitude.core.Api
import software.altitude.core.DataScrubber
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.AccountType
import software.altitude.core.models.User
import software.altitude.core.{Const => C}

class SetupController extends BaseHtmxController {

  private val dataScrubber = DataScrubber(
    trim = List(
      Api.Field.Setup.REPOSITORY_NAME,
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2),
    lower = List(Api.Field.Setup.ADMIN_EMAIL)
  )

  private val apiRequestValidator = ApiRequestValidator(
    required = List(
      Api.Field.Setup.REPOSITORY_NAME,
      Api.Field.Setup.ADMIN_EMAIL,
      Api.Field.Setup.ADMIN_NAME,
      Api.Field.Setup.PASSWORD,
      Api.Field.Setup.PASSWORD2),
    maxLengths = Map(
      Api.Field.Setup.REPOSITORY_NAME -> Api.Constraints.MAX_REPOSITORY_NAME_LENGTH,
      Api.Field.Setup.ADMIN_EMAIL -> Api.Constraints.MAX_EMAIL_LENGTH,
      Api.Field.Setup.ADMIN_NAME -> Api.Constraints.MAX_NAME_LENGTH,
      Api.Field.Setup.PASSWORD -> Api.Constraints.MAX_PASSWORD_LENGTH,
    ),
    minLengths = Map(
      Api.Field.Setup.REPOSITORY_NAME -> Api.Constraints.MIN_REPOSITORY_NAME_LENGTH,
      Api.Field.Setup.ADMIN_EMAIL -> Api.Constraints.MIN_EMAIL_LENGTH,
      Api.Field.Setup.ADMIN_NAME -> Api.Constraints.MIN_NAME_LENGTH,
      Api.Field.Setup.PASSWORD -> Api.Constraints.MIN_PASSWORD_LENGTH,
    ),
  )

  val htmxAdminSetup: Route = post("/") {
    if (app.isInitialized) {
      val message = "Instance is already initialized."
      logger.warn(message)
      halt(400, message)
    }

    val jsonIn: JsObject = dataScrubber.scrub(unscrubbedReqJson.get)

    val validationException: ValidationException = try {
      apiRequestValidator.validate(jsonIn)
      ValidationException()
    }
    catch {
      case validationEx: ValidationException =>
        validationEx
      case ex: Throwable =>
        logger.error(ex.getMessage, ex)
        halt(500, "Server error")
    }

    val repositoryName = (jsonIn \ Api.Field.Setup.REPOSITORY_NAME).asOpt[String].getOrElse("")
    val email = (jsonIn \ Api.Field.Setup.ADMIN_EMAIL).asOpt[String].getOrElse("")
    val name = (jsonIn \ Api.Field.Setup.ADMIN_NAME).asOpt[String].getOrElse("")
    val password = (jsonIn \ Api.Field.Setup.PASSWORD).asOpt[String].getOrElse("")
    val password2 = (jsonIn \ Api.Field.Setup.PASSWORD2).asOpt[String].getOrElse("")

    /*
    Continue with secondary validation checks (only if the primary validation checks have passed for these fields)
     */
    if (!validationException.errors.contains(Api.Field.Setup.PASSWORD) &&
      !validationException.errors.contains(Api.Field.Setup.PASSWORD2)) {
      if (password != password2) {
        validationException.errors.addOne(Api.Field.Setup.PASSWORD -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH)
      }
    }

    // if we have errors
    if (validationException.errors.nonEmpty) {
      halt(200, ssp(
          "htmx/admin/setup_form.ssp",
          Api.Modal.FIELD_ERRORS -> validationException.errors.toMap, // to immutable map
          Api.Modal.FORM_JSON -> jsonIn)
      )
    }

    // Oh, we are committed at this point
    val userModel = new User(
      email= email,
      name = name,
      accountType = AccountType.Admin)

    app.service.system.initializeSystem(
      repositoryName=repositoryName,
      adminModel=userModel,
      password=password)

    // On OK, send a magical header so that HTMX can redirect to the landing page
    response.addHeader("HX-Redirect", "/")
  }

}
