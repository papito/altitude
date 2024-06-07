package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.models.User

@DoNotDiscover class UserServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("Can create and get a new user") {
    val user: User = altitude.service.user.addUser(User())
    user.id should not be None

    val storedUser: User = altitude.service.user.getUserById(user.id.get)
    storedUser.createdAt should not be None
    user.id shouldEqual storedUser.id
  }
}
