package software.altitude.core.controllers.htmx

import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.DataScrubber
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.controllers.BaseHtmxController
import software.altitude.core.models.AccountType
import software.altitude.core.models.User
import software.altitude.core.{Const => C}

class SetupController extends BaseHtmxController  {
  private final val log = LoggerFactory.getLogger(getClass)

  private val dataScrubber = DataScrubber(
    trim = List(
      C.Api.Fields.REPOSITORY_NAME,
      C.Api.Fields.ADMIN_EMAIL,
      C.Api.Fields.PASSWORD,
      C.Api.Fields.PASSWORD2),
    lower = List(C.Api.Fields.ADMIN_EMAIL)
  )

  private val apiRequestValidator = ApiRequestValidator(
    required = List(
      C.Api.Fields.REPOSITORY_NAME,
      C.Api.Fields.ADMIN_EMAIL,
      C.Api.Fields.PASSWORD,
      C.Api.Fields.PASSWORD2),
    maxLengths = Map(
      C.Api.Fields.REPOSITORY_NAME -> C.Api.Constraints.MAX_REPOSITORY_NAME_LENGTH,
      C.Api.Fields.ADMIN_EMAIL -> C.Api.Constraints.MAX_EMAIL_LENGTH,
      C.Api.Fields.PASSWORD -> C.Api.Constraints.MAX_PASSWORD_LENGTH,
    ),
    minLengths = Map(
      C.Api.Fields.REPOSITORY_NAME -> C.Api.Constraints.MIN_REPOSITORY_NAME_LENGTH,
      C.Api.Fields.ADMIN_EMAIL -> C.Api.Constraints.MIN_EMAIL_LENGTH,
      C.Api.Fields.PASSWORD -> C.Api.Constraints.MIN_PASSWORD_LENGTH,
    ),
  )

  post("/") {
    if (app.isInitialized) {
      val message = "Instance is already initialized."
      log.warn(message)
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

    val repositoryName = (jsonIn \ C.Api.Fields.REPOSITORY_NAME).asOpt[String].getOrElse("")
    val email = (jsonIn \ C.Api.Fields.ADMIN_EMAIL).asOpt[String].getOrElse("")
    val password = (jsonIn \ C.Api.Fields.PASSWORD).asOpt[String].getOrElse("")
    val password2 = (jsonIn \ C.Api.Fields.PASSWORD2).asOpt[String].getOrElse("")

    /*
    Continue with secondary validation checks (only if the primary validation checks have passed for these fields)
     */
    if (!validationException.errors.contains(C.Api.Fields.PASSWORD) &&
      !validationException.errors.contains(C.Api.Fields.PASSWORD2)) {
      if (password != password2) {
        validationException.errors.addOne(C.Api.Fields.PASSWORD -> C.Msg.Err.PASSWORDS_DO_NOT_MATCH)
      }
    }

    // if we have errors
    if (validationException.errors.nonEmpty) {
      halt(200, ssp(
          "/htmx/admin/setup_form",
          "fieldErrors" -> validationException.errors.toMap, // to immutable map
          "formJson" -> jsonIn)
      )
    }

    // Oh, we are committed at this point
    val userModel = new User(email= email, accountType = AccountType.Admin)

    app.service.system.initializeSystem(
      repositoryName=repositoryName,
      adminModel=userModel,
      password=password)

    // On OK, send a magical header so that HTMX can redirect to the landing page
    response.addHeader("HX-Redirect", "/")
  }

}
