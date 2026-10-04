package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.noException
import org.scalatest.matchers.should.Matchers.should

import altitude.core.Api
import altitude.core.Const as C
import altitude.core.ValidationException
import altitude.core.Validators.ApiRequestValidator

@DoNotDiscover class ApiValidatorTests extends funsuite.AnyFunSuite with TestFocus {

  test("Test multiple invalid email addresses") {

    /**
     * Setup:
     *
     * A validator with an email check on the admin email field, run once for each of five malformed addresses.
     *
     * Assertions:
     *
     * Every address fails with exactly one error, the not-an-email message.
     *
     * Edge cases:
     *
     * No @ at all, no domain, no local part, a comma in the domain, and two dots in a row in the domain.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      email = List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val invalidEmails = List(
      "invalid-email",
      "invalid@",
      "@domain.com",
      "invalid@domain,com",
      "invalid@domain..com"
    )

    invalidEmails.foreach {
      email =>
        val jsonIn = ujson.Obj(
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

    /**
     * Setup:
     *
     * A validator with an email check on the admin email field, run once for each of five well-formed addresses.
     *
     * Assertions:
     *
     * None of them is rejected.
     *
     * Edge cases:
     *
     * Plus, slash and equals signs in the local part, a host with no top-level domain, and a subdomain.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      email = List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val validEmails = List(
      "valid.email@example.com",
      "user+mailbox/department=shipping@example.com",
      "customer/department=shipping@example.com",
      "user@localserver",
      "user@subdomain.example.com"
    )

    validEmails.foreach {
      email =>
        val jsonIn = ujson.Obj(
          Api.Field.Setup.ADMIN_EMAIL -> email
        )

        noException should be thrownBy validator.validate(jsonIn)
    }
  }

  test("Test multiple failed required fields") {

    /**
     * Setup:
     *
     * A validator that requires an ID, a folder name and an admin email and checks the email's format, over a request that has
     * only a folder path and a malformed admin email.
     *
     * Assertions:
     *
     * Every failing field is reported at once: the two missing fields as required, and the email that is present but malformed as
     * not an email.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.ID, Api.Field.Folder.NAME, Api.Field.Setup.ADMIN_EMAIL),
      email = List(Api.Field.Setup.ADMIN_EMAIL)
    )

    val jsonIn = ujson.Obj(
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

    /**
     * Setup:
     *
     * A validator with a five-character limit on the folder name, over a longer name.
     *
     * Assertions:
     *
     * The name fails with a single too-long error that states the limit.
     */
    val maxFieldLength = 5
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths = Map(Api.Field.Folder.NAME -> maxFieldLength)
    )

    val jsonIn = ujson.Obj(
      Api.Field.Folder.NAME -> "Bright Future Name"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_TOO_LONG.format(maxFieldLength))
  }

  test("Test failed min length") {

    /**
     * Setup:
     *
     * A validator with a six-character minimum on the password, over a five-character password.
     *
     * Assertions:
     *
     * The password fails with a single too-short error that states the minimum.
     */
    val minPasswordLength = 6
    val validator: ApiRequestValidator = ApiRequestValidator(
      minLengths = Map(Api.Field.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = ujson.Obj(
      Api.Field.Setup.PASSWORD -> "lol/$"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_TOO_SHORT.format(minPasswordLength))
  }

  test("Test min length error should not override the REQUIRED error") {

    /**
     * Setup:
     *
     * A validator that both requires the password and sets a minimum length for it, over an empty request.
     *
     * Assertions:
     *
     * The missing password is reported once, as required, and not also as too short.
     */
    val minPasswordLength = 6
    val validator: ApiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Setup.PASSWORD),
      minLengths = Map(Api.Field.Setup.PASSWORD -> minPasswordLength)
    )

    val jsonIn = ujson.Obj()

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
    validationException.errors.head._2 should be(C.Msg.Err.VALUE_REQUIRED)
  }

  test("Test multiple failed length checks") {

    /**
     * Setup:
     *
     * A validator with length limits on the folder name and path, over a request where both are too long.
     *
     * Assertions:
     *
     * Both fields are reported, one error per length limit.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      maxLengths = Map(Api.Field.Folder.NAME -> 5, Api.Field.Folder.PATH -> 10)
    )

    val jsonIn = ujson.Obj(
      Api.Field.Folder.NAME -> "Bright Future Name",
      Api.Field.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.maxLengths.size)
  }

  test("Test missing required field should not be checked for length") {

    /**
     * Setup:
     *
     * A validator that requires the folder name and limits its length, over an empty request.
     *
     * Assertions:
     *
     * The missing name yields a single error - the length check skips a field that is not there.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Folder.NAME),
      maxLengths = Map(Api.Field.Folder.NAME -> 5)
    )

    val jsonIn = ujson.Obj()

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(1)
  }

  test("Test multiple types of checks failed") {

    /**
     * Setup:
     *
     * A validator that requires the folder name and limits both name and path to ten characters, over a request with only an
     * overlong path.
     *
     * Assertions:
     *
     * Different kinds of failures are reported together, each under its own field: the path as too long, the name as required.
     */
    val maxFieldLength = 10

    val validator: ApiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Folder.NAME),
      maxLengths = Map(Api.Field.Folder.NAME -> maxFieldLength, Api.Field.Folder.PATH -> maxFieldLength)
    )

    val jsonIn = ujson.Obj(
      Api.Field.Folder.PATH -> "Bright Future Path"
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors(Api.Field.Folder.PATH) should be(C.Msg.Err.VALUE_TOO_LONG.format(maxFieldLength))
    validationException.errors(Api.Field.Folder.NAME) should be(C.Msg.Err.VALUE_REQUIRED)

  }

  test("Test empty strings fail the required check") {

    /**
     * Setup:
     *
     * A validator that requires the folder name, over a request whose name is an empty string.
     *
     * Assertions:
     *
     * An empty value counts as missing and fails the required check.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      required = List(Api.Field.Folder.NAME)
    )

    val jsonIn = ujson.Obj(
      Api.Field.Folder.NAME -> ""
    )

    val validationException = intercept[ValidationException] {
      validator.validate(jsonIn)
    }

    validationException.errors.size should be(validator.required.size)
  }

  test("Test multiple invalid UUIDs") {

    /**
     * Setup:
     *
     * A validator with a UUID check on the ID field, run once for each of four malformed IDs.
     *
     * Assertions:
     *
     * Every ID fails with exactly one error, the not-a-UUID message.
     *
     * Edge cases:
     *
     * Free text, a last group with an extra non-hex character, a last group that is too short, and an extra trailing group.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      uuid = List(Api.Field.ID)
    )

    val invalidUUIDs = List(
      "invalid-uuid",
      "12345678-1234-1234-1234-1234567890abz",
      "12345678-1234-1234-1234-1234567890",
      "12345678-1234-1234-1234-1234567890ab-1234"
    )

    invalidUUIDs.foreach {
      uuid =>
        val jsonIn = ujson.Obj(
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

    /**
     * Setup:
     *
     * A validator with a UUID check on the ID field, over a well-formed UUID.
     *
     * Assertions:
     *
     * The ID is accepted.
     */
    val validator: ApiRequestValidator = ApiRequestValidator(
      uuid = List(Api.Field.ID)
    )

    val validUUID = "12345678-1234-1234-1234-1234567890ab"

    val jsonIn = ujson.Obj(
      Api.Field.ID -> validUUID
    )

    noException should be thrownBy validator.validate(jsonIn)
  }
}
