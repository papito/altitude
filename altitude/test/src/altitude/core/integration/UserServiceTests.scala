package altitude.core.integration

import altitude.core
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.should
import org.scalatest.matchers.should.Matchers.shouldEqual

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.models.User
import altitude.core.util.Util

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

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, password)

    loginResult match {
      case Some((user, token)) =>
        user should not be None
        token should not be None
      case None =>
        fail("Login failed: user or token is None")
    }
  }

  test("Login fails with invalid password") {
    val password = "MyPassword123"
    val wrongPassword = "WrongPassword456"

    val userModel = User(
      email = Util.randomStr(),
      name = Util.randomStr(),
      accountType = AccountType.User
    )

    testContext.persistUser(Some(userModel), password = password)

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, wrongPassword)

    loginResult shouldEqual None
  }

  test("Login fails with non-existent user") {
    val nonExistentEmail = "nonexistent@example.com"
    val password = "SomePassword123"

    val loginResult = testApp.service.user.loginAndSetUser(nonExistentEmail, password)

    loginResult shouldEqual None
  }

  if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
    test("Logging in does not wait for the SQLite write connection") {
      val password = "MyPassword123"
      val userModel = User(email = Util.randomStr(), name = Util.randomStr(), accountType = AccountType.User)
      testContext.persistUser(Some(userModel), password = password)

      val writing = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      // A write transaction on a thread of its own holds the one write connection until released
      val writer = new Thread(
        () =>
          RequestContext.conn.withValue(None)(testApp.txManager.withTransaction {
            writing.countDown()
            release.await(30, TimeUnit.SECONDS): Unit
          }))
      writer.start()

      try {
        writing.await(5, TimeUnit.SECONDS) shouldEqual true
        val login = Future(RequestContext.conn.withValue(None)(testApp.service.user.loginAndSetUser(userModel.email, password)))
        Await.result(login, 5.seconds) should not be None
      } finally {
        release.countDown()
        writer.join()
      }
    }
  }
}
