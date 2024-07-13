package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import play.api.libs.json.Json
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.{Const => C}
import software.altitude.test.core.TestFocus


@DoNotDiscover class ApiValidatorTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test multiple failed required fields") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(C.Api.ID, C.Api.Folder.NAME)
    )

    val jsonIn = Json.obj(
      C.Api.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.required.size)
  }

  test("Test failed max length") {
    val maxFieldLength = 5
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths=Map(C.Api.Folder.NAME -> maxFieldLength)
    )

    val jsonIn = Json.obj(
      C.Api.Folder.NAME -> "Bright Future Name",
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_TOO_LONG.format(maxFieldLength))
  }

  test("Test failed min length") {
    val minPasswordLength = 6
    val validator: ApiRequestValidator = ApiRequestValidator(
      minLengths=Map(C.Api.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = Json.obj(
      C.Api.Setup.PASSWORD -> "lol/$",
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_TOO_SHORT.format(minPasswordLength))
  }

  test("Test min length error should not override the REQUIRED error") {
    val minPasswordLength = 6
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(C.Api.Setup.PASSWORD),
      minLengths=Map(C.Api.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = Json.obj()

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.REQUIRED)
  }

  test("Test multiple failed length checks") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths=Map(C.Api.Folder.NAME -> 5, C.Api.Folder.PATH -> 10)
    )

    val jsonIn = Json.obj(
      C.Api.Folder.NAME -> "Bright Future Name",
      C.Api.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.maxLengths.size)
  }

  test("Test missing required field should not be checked for length") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(C.Api.Folder.NAME),
      maxLengths=Map(C.Api.Folder.NAME -> 5)
    )

    val jsonIn = Json.obj()

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
  }

  test("Test multiple types of checks failed") {
    val maxFieldLength = 10

    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(C.Api.Folder.NAME),
      maxLengths=Map(C.Api.Folder.NAME -> maxFieldLength, C.Api.Folder.PATH -> maxFieldLength)
    )

    val jsonIn = Json.obj(
      C.Api.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors(C.Api.Folder.PATH) should be(C.Msg.Err.VALUE_TOO_LONG.format(maxFieldLength))
    validationException.errors(C.Api.Folder.NAME) should be(C.Msg.Err.REQUIRED)

  }

  test("Test empty strings fail the required check") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(C.Api.Folder.NAME)
    )

    val jsonIn = Json.obj(
      C.Api.Folder.NAME -> ""
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.required.size)
  }
}
