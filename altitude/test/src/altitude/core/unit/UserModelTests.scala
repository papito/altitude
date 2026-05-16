package altitude.core.unit

import org.scalatest.{ funsuite, DoNotDiscover }
import org.scalatest.matchers.should.Matchers.{ equal, include, should }

import altitude.core.models.{ AccountType, User }

@DoNotDiscover class UserModelTests extends funsuite.AnyFunSuite {

  /*
  test("User model should convert to JSON") {
    val email = "webmaster@altitude-dam.com"

    val user = User(
      id = Some("123"),
      email = email,
      name = "Alice",
      accountType = AccountType.User,
      lastActiveRepoId = None
    )

    val jsonString = user.toJsonString
    jsonString should include("\"id\":\"123\",")

    val jsonValue = user.toJson
    jsonValue("email").str should equal(email)
  }
   */
}
