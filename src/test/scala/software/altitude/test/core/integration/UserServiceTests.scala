package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Util
import software.altitude.core.models.AccountType
import software.altitude.core.models.User
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class UserServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Can create and get a new user") {
    val user: User = testContext.persistUser()
    val storedUser: User = altitude.service.user.getById(user.persistedId)

    storedUser.createdAt should not be None
    storedUser.updatedAt should be(None)

    user.id shouldEqual storedUser.id
  }

  test("Check valid user password") {
    val password = "MyPassword123"

    val userModel = User(
      email = Util.randomStr(),
      accountType = AccountType.User
    )

    testContext.persistUser(Some(userModel), password=password)

    val user: Option[User] = altitude.service.user.loginAndGetUser(userModel.email, password)

    user should not be None
  }
}
