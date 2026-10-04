package altitude.core.integration

import altitude.core
import altitude.test.TestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.empty
import org.scalatest.matchers.should.Matchers.should
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.matchers.should.Matchers.shouldEqual

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.models.AccountType
import altitude.core.models.User
import altitude.core.util.Util

@DoNotDiscover class UserServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Can create and get a new user") {

    /**
     * Setup:
     *
     * A regular user with a random email and name, added next to the common setup's user.
     *
     * Assertions:
     *
     * The user can be read back by its ID.
     */
    val user: User = testContext.persistUser()
    val storedUser: User = testApp.service.user.getById(user.persistedId)

    user.id shouldEqual storedUser.id
  }

  test("Can set user active repository") {

    /**
     * Setup:
     *
     * A new regular user and the common setup's repository.
     *
     * Assertions:
     *
     * The repository is stored as the user's last active one: reading the user back yields its ID.
     */
    val user: User = testContext.persistUser()
    testApp.service.user.setLastActiveRepoId(user, testContext.repository.persistedId)

    val storedUser: User = testApp.service.user.getById(user.persistedId)
    storedUser.lastActiveRepoId shouldEqual Some(testContext.repository.persistedId)
  }

  test("Check valid user password") {

    /**
     * Setup:
     *
     * A regular user with a known password.
     *
     * Assertions:
     *
     * Logging in with the user's email and that password succeeds and yields that user and a non-empty session token.
     */
    val password = "MyPassword123"

    val userModel = User(
      email = Util.randomStr(),
      name = Util.randomStr(),
      accountType = AccountType.User
    )

    val created: User = testContext.persistUser(Some(userModel), password = password)

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, password)

    loginResult match {
      case Some((user, token)) =>
        user.persistedId shouldEqual created.persistedId
        token should not be empty
      case None =>
        fail("Login failed: user or token is None")
    }
  }

  test("An email address names one account, whatever its case") {

    /**
     * Setup:
     *
     * A user whose email has upper-case letters, and a second user with the same email in lower case.
     *
     * Assertions:
     *
     * The second user is refused as a duplicate, and the first logs in with the email in either case.
     */
    val email = s"Mixed.Case.${Util.randomStr(8)}@Example.com"
    val user: User = testContext.persistUser(Some(testContext.makeUser().copy(email = email)))

    intercept[DuplicateException] {
      testApp.service.user.add(testContext.makeUser().copy(email = email.toLowerCase), password = TestContext.USER_PASSWORD)
    }

    for (spelling <- List(email, email.toLowerCase, email.toUpperCase)) withClue(spelling) {
      testApp.service.user.loginAndSetUser(spelling, TestContext.USER_PASSWORD).map(_._1.persistedId) shouldBe
        Some(user.persistedId)
    }
  }

  test("Login fails with invalid password") {

    /**
     * Setup:
     *
     * A regular user with a known password.
     *
     * Assertions:
     *
     * Logging in with the user's email and a different password yields nothing.
     */
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

    /**
     * Setup:
     *
     * An email that belongs to no user.
     *
     * Assertions:
     *
     * Logging in with it yields nothing.
     */
    val nonExistentEmail = "nonexistent@example.com"
    val password = "SomePassword123"

    val loginResult = testApp.service.user.loginAndSetUser(nonExistentEmail, password)

    loginResult shouldEqual None
  }

  if (testApp.dataSourceType == Const.DbEngineName.SQLITE) {
    test("Logging in does not wait for the SQLite write connection") {

      /**
       * Setup:
       *
       * A regular user with a known password, and a write transaction on a thread of its own that holds SQLite's single write
       * connection until the test releases it.
       *
       * Assertions:
       *
       * While the write connection is held, logging in still completes within a few seconds and succeeds, since a login only
       * reads.
       */
      val password = "MyPassword123"
      val userModel = User(email = Util.randomStr(), name = Util.randomStr(), accountType = AccountType.User)
      testContext.persistUser(Some(userModel), password = password)

      val writing = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      // A write transaction on a thread of its own holds the one write connection until released
      val writer = new Thread(
        () =>
          testApp.txManager.withTransaction {
            writing.countDown()
            release.await(30, TimeUnit.SECONDS): Unit
          })
      writer.start()

      try {
        writing.await(5, TimeUnit.SECONDS) shouldEqual true
        val login = Future(testApp.service.user.loginAndSetUser(userModel.email, password))
        Await.result(login, 5.seconds) should not be None
      } finally {
        release.countDown()
        writer.join()
      }
    }
  }
}
