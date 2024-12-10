package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.noException
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.ValidationException
import software.altitude.core.Validators.ApiRequestValidator
import software.altitude.core.{Const => C}
import software.altitude.test.core.TestFocus


@DoNotDiscover class ApiValidatorTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test multiple invalid email addresses") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      email=List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val invalidEmails = List(
      "invalid-email",
      "invalid@",
      "@domain.com",
      "invalid@domain,com",
      "invalid@domain..com"
    )

    invalidEmails.foreach { email =>
      val jsonIn = Json.obj(
        Api.Field.Setup.ADMIN_EMAIL -> email
      )

      val validationException = intercept[ValidationException] {
        validator.validate(jsonIn)
      }

      validationException.errors.size should be(1)
      validationException.errors.head._2 should be(C.Msg.Err.VALUE_NOT_AN_EMAIL)
    }
  }

  test("Test valid email addresses") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      email=List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val validEmails = List(
      "valid.email@example.com",
      "user+mailbox/department=shipping@example.com",
      "customer/department=shipping@example.com",
      "user@localserver",
      "user@subdomain.example.com"
    )

    validEmails.foreach { email =>
      val jsonIn = Json.obj(
        Api.Field.Setup.ADMIN_EMAIL -> email
      )

      noException should be thrownBy validator.validate(jsonIn)
    }
  }

  test("Test multiple failed required fields") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(Api.Field.ID, Api.Field.Folder.NAME, Api.Field.Setup.ADMIN_EMAIL),
      email=List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val jsonIn = Json.obj(
      Api.Field.Folder.PATH -> "Bright Future Path",
      Api.Field.Setup.ADMIN_EMAIL -> "invalid-email"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(3)
    validationException.errors(Api.Field.Setup.ADMIN_EMAIL) should be(C.Msg.Err.VALUE_NOT_AN_EMAIL)
  }

  test("Test failed max length") {
    val maxFieldLength = 5
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths=Map(Api.Field.Folder.NAME -> maxFieldLength)
    )

    val jsonIn = Json.obj(
      Api.Field.Folder.NAME -> "Bright Future Name",
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
      minLengths=Map(Api.Field.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = Json.obj(
      Api.Field.Setup.PASSWORD -> "lol/$",
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
      required=List(Api.Field.Setup.PASSWORD),
      minLengths=Map(Api.Field.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = Json.obj()

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_REQUIRED)
  }

  test("Test multiple failed length checks") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths=Map(Api.Field.Folder.NAME -> 5, Api.Field.Folder.PATH -> 10)
    )

    val jsonIn = Json.obj(
      Api.Field.Folder.NAME -> "Bright Future Name",
      Api.Field.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.maxLengths.size)
  }

  test("Test missing required field should not be checked for length") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(Api.Field.Folder.NAME),
      maxLengths=Map(Api.Field.Folder.NAME -> 5)
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
      required=List(Api.Field.Folder.NAME),
      maxLengths=Map(Api.Field.Folder.NAME -> maxFieldLength, Api.Field.Folder.PATH -> maxFieldLength)
    )

    val jsonIn = Json.obj(
      Api.Field.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors(Api.Field.Folder.PATH) should be(C.Msg.Err.VALUE_TOO_LONG.format(maxFieldLength))
    validationException.errors(Api.Field.Folder.NAME) should be(C.Msg.Err.VALUE_REQUIRED)

  }

  test("Test empty strings fail the required check") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      required=List(Api.Field.Folder.NAME)
    )

    val jsonIn = Json.obj(
      Api.Field.Folder.NAME -> ""
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.required.size)
  }


  test("Test multiple invalid UUIDs") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      uuid=List(Api.Field.ID)
    )

    val invalidUUIDs = List(
      "invalid-uuid",
      "12345678-1234-1234-1234-1234567890abz",
      "12345678-1234-1234-1234-1234567890",
      "12345678-1234-1234-1234-1234567890ab-1234"
    )

    invalidUUIDs.foreach { uuid =>
      val jsonIn = Json.obj(
        Api.Field.ID -> uuid
      )

      val validationException = intercept[ValidationException] {
        validator.validate(jsonIn)
      }

      validationException.errors.size should be(1)
      validationException.errors.head._2 should be(C.Msg.Err.VALUE_NOT_A_UUID)
    }
  }

  test("Test valid UUID") {
    val validator: ApiRequestValidator = ApiRequestValidator(
      uuid=List(Api.Field.ID)
    )

    val validUUID = "12345678-1234-1234-1234-1234567890ab"

    val jsonIn = Json.obj(
      Api.Field.ID -> validUUID
    )

    noException should be thrownBy validator.validate(jsonIn)
  }
}
