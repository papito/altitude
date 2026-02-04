package altitude.core.integration

import altitude.core
import altitude.core.Altitude
import altitude.core.models.AccountType
import altitude.core.models.User
import altitude.core.util.Util
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.should
import org.scalatest.matchers.should.Matchers.shouldEqual

@DoNotDiscover class UserServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Can create and get a new user") {
    val user: User = testContext.persistUser()
    val storedUser: User = testApp.service.user.getById(user.persistedId)

    user.id shouldEqual storedUser.id
  }

  test("Can set user active repository") {
    val user: User = testContext.persistUser()
    testApp.service.user.setLastActiveRepoId(user, testContext.repository.persistedId)
  }

  test("Check valid user password") {
    val password = "MyPassword123"

    val userModel = User(
      email = Util.randomStr(),
      name = Util.randomStr(),
      accountType = AccountType.User
    )

    testContext.persistUser(Some(userModel), password = password)

    val user: Option[User] = testApp.service.user.loginAndGetUser(userModel.email, password)

    user should not be None
  }

  test("Logging in a user create a token in cache") {
    val password = "MyPassword123"

    val userModel = User(
      email = Util.randomStr(),
      name = Util.randomStr(),
      accountType = AccountType.User
    )

    testContext.persistUser(Some(userModel), password = password)

    val user: Option[User] = testApp.service.user.loginAndGetUser(userModel.email, password)

    testApp.app.usersByToken.nonEmpty shouldEqual true
    testApp.app.usersByToken.head._2.persistedId shouldEqual user.get.persistedId

  }
}
