package altitude.core.integration

import altitude.core
import altitude.test.TestContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.LoneElement.convertToCollectionLoneElementWrapper
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.be
import org.scalatest.matchers.should.Matchers.contain
import org.scalatest.matchers.should.Matchers.empty
import org.scalatest.matchers.should.Matchers.should
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.matchers.should.Matchers.shouldEqual
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.models.AuditEventType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.User
import altitude.core.util.NameKey
import altitude.core.util.Util

@DoNotDiscover class UserServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("An account reads back as added, its email address and username stored beside their name keys") {

    /**
     * Setup:
     *
     * A User added with an email address and a username in mixed case, created by the common setup's account, with the Create
     * Libraries flag and the new-Library grant of Import and Curate own.
     *
     * Assertions:
     *
     * The account reads back by its ID with every field as added: active, never logged in, and with no last active Library. The
     * stored `email_key` and `username_key` are the name keys (`NameKey.of`) of the address and the username.
     */
    val creator = testContext.users.head
    val model = testContext
      .makeUser()
      .copy(
        email = s"Jane.${Util.randomStr(8)}@Example.com",
        username = s"Jane.${Util.randomStr(8)}",
        canCreateLibraries = true,
        grantsNewLibraries = true,
        newLibraryPermissions = Permissions.of(Permission.Import, Permission.CurateOwn),
        createdByAccountId = Some(creator.persistedId)
      )
    val user: User = testContext.persistUser(Some(model))

    testApp.service.user.getById(user.persistedId) shouldEqual model.copy(id = user.id)
    user.isActive shouldBe true
    user.lastLoginAt shouldBe None

    val keys = testApp.txManager.asReadOnly {
      query("SELECT email_key, username_key FROM account WHERE id = ?", user.persistedId).head
    }
    keys("email_key") shouldBe NameKey.of(model.email)
    keys("username_key") shouldBe NameKey.of(model.username)
  }

  test("Remembering a Library writes the account only when it is not the one recorded") {

    /**
     * Setup:
     *
     * A new User with no last active Library, the common setup's Library and a second one.
     *
     * Assertions:
     *
     * Remembering the first Library records it, reports a write and stamps the account's update time. Remembering it again
     * reports no write and leaves the update time as it was (cleared beforehand, so a write would show). Remembering the second
     * Library records it and reports a write.
     */
    val user: User = testContext.persistUser()
    val first = testContext.repository.persistedId
    val second = testContext.persistRepository().persistedId

    def updatedAt: Option[AnyRef] = testApp.txManager.asReadOnly {
      Option(query("SELECT updated_at FROM account WHERE id = ?", user.persistedId).head("updated_at"))
    }
    def lastActive: Option[String] = testApp.service.user.getById(user.persistedId).lastActiveRepoId

    testApp.service.user.rememberLastActiveRepository(user, first) shouldBe true
    lastActive shouldBe Some(first)
    updatedAt should not be empty

    testApp.txManager.withTransaction(update("UPDATE account SET updated_at = NULL WHERE id = ?", user.persistedId))
    testApp.service.user.rememberLastActiveRepository(user, first) shouldBe false
    lastActive shouldBe Some(first)
    updatedAt shouldBe None

    testApp.service.user.rememberLastActiveRepository(user, second) shouldBe true
    lastActive shouldBe Some(second)
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

    val userModel = testContext.makeUser()

    val created: User = testContext.persistUser(Some(userModel), password = password)

    val loginResult = testApp.service.user.loginAndSetUser(userModel.email, password)

    loginResult match {
      case Some((user, token, _)) =>
        user.persistedId shouldEqual created.persistedId
        token should not be empty
      case None =>
        fail("Login failed: user or token is None")
    }
  }

  test("An email address names one account, whatever its case or Unicode form") {

    /**
     * Setup:
     *
     * A user whose email has upper-case letters, then a second user with the same email in lower case.
     *
     * Assertions:
     *
     * The second user is refused as a duplicate, and the first logs in with the email in either case or with its first letter
     * full-width, which the login folds.
     */
    val email = s"Mixed.Case.${Util.randomStr(8)}@Example.com"
    val fullWidth = "\uFF2D" + email.drop(1)
    val user: User = testContext.persistUser(Some(testContext.makeUser().copy(email = email)))

    intercept[DuplicateException] {
      testApp.service.user.add(testContext.makeUser().copy(email = email.toLowerCase), password = TestContext.USER_PASSWORD)
    }

    for (spelling <- List(email, email.toLowerCase, email.toUpperCase, fullWidth)) withClue(spelling) {
      testApp.service.user.loginAndSetUser(spelling, TestContext.USER_PASSWORD).map(_._1.persistedId) shouldBe
        Some(user.persistedId)
    }
  }

  test("A username names one account, whatever its case") {

    /**
     * Setup:
     *
     * A user named "Jane.Doe" (under a random suffix), then a second user of another email address named "jane.doe".
     *
     * Assertions:
     *
     * The second user is refused as a duplicate, and only the first holds the username.
     */
    val username = s"Jane.Doe.${Util.randomStr(8)}"
    testContext.persistUser(username = Some(username))

    intercept[DuplicateException] {
      testApp.service.user.add(testContext.makeUser().copy(username = username.toLowerCase), password = TestContext.USER_PASSWORD)
    }

    testApp.txManager.asReadOnly {
      query("SELECT id FROM account WHERE username_key = ?", NameKey.of(username)).size
    } shouldBe 1
  }

  test("A username keeps to the rule: 3 to 32 ASCII letters, digits, dots, underscores and hyphens") {

    /**
     * Setup:
     *
     * Accounts added under usernames of 2 and 33 characters, with a space, with an "@", with a full-width letter, and empty; and
     * accounts under usernames of 3 and 32 characters using every character the rule allows.
     *
     * Assertions:
     *
     * Each refused username fails with a `ValidationException` whose error, under the username field, states the rule (empty:
     * required), and adds no account. Each allowed username is added as typed.
     */
    val refused = List(
      "ab",
      "a" * 33,
      "jane doe",
      "jane@doe",
      "\uFF2Aane",
      ""
    )
    for (username <- refused) withClue(s"[$username]: ") {
      val error = intercept[ValidationException] {
        testApp.service.user.add(testContext.makeUser().copy(username = username), password = TestContext.USER_PASSWORD)
      }
      error.errors(FieldConst.User.USERNAME) shouldBe
        (if (username.isEmpty) Const.Msg.Err.VALUE_REQUIRED else Const.Msg.Err.USERNAME_RULE)
      testApp.txManager.asReadOnly {
        query("SELECT id FROM account WHERE username = ?", username).size
      } shouldBe 0
    }

    val suffix = Util.randomStr(8)
    for (username <- List(s"a.${suffix.take(1)}", s"A-b_c.9$suffix".padTo(32, 'z'))) withClue(s"[$username]: ") {
      val added = testContext.persistUser(username = Some(username))
      testApp.service.user.getById(added.persistedId).username shouldBe username
    }
  }

  test("Two transactions adding one username at once leave one account") {

    /**
     * Setup:
     *
     * Two adds of an account under one username, in other capitals and of other email addresses, from two transactions: the first
     * holds its transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second add fails with a `DuplicateException` once the first commits, and one account holds the username, the first's,
     * as it spelled it. The database decides it: on PostgreSQL both transactions insert before either commits.
     */
    val username = s"Race.${Util.randomStr(8)}"

    intercept[DuplicateException] {
      contend(
        first = testApp.service.user.add(testContext.makeUser().copy(username = username), TestContext.USER_PASSWORD): Unit,
        second =
          testApp.service.user.add(testContext.makeUser().copy(username = username.toUpperCase), TestContext.USER_PASSWORD): Unit
      )
    }

    testApp.txManager.asReadOnly {
      query("SELECT username FROM account WHERE username_key = ?", NameKey.of(username)).map(_("username"))
    } shouldBe List(username)
  }

  test("A login by username finds the account whatever the case it is typed in; a login without an \"@\" is no email") {

    /**
     * Setup:
     *
     * A User with a username in mixed case.
     *
     * Assertions:
     *
     * The User logs in with the username as stored, in lower case and in upper case, and with its email address.
     *
     * Edge cases:
     *
     * A second account's username is the first's email address's local part: a login with that local part, which holds no "@", is
     * looked up as a username and logs the second account in.
     */
    val user = testContext.persistUser(username = Some(s"Mixed.Case.${Util.randomStr(8)}"))
    val localPart = user.email.takeWhile(_ != '@')
    val second = testContext.persistUser(username = Some(localPart))

    for (login <- List(user.username, user.username.toLowerCase, user.username.toUpperCase, user.email)) withClue(login) {
      testApp.service.user.loginAndSetUser(login, TestContext.USER_PASSWORD).map(_._1.persistedId) shouldBe
        Some(user.persistedId)
    }
    testApp.service.user.loginAndSetUser(localPart, TestContext.USER_PASSWORD).map(_._1.persistedId) shouldBe
      Some(second.persistedId)
  }

  test("An account's name and email address keep to their rules") {

    /**
     * Setup:
     *
     * Accounts added with a one-character name, an 81-character name, an email address with no "@", one of 81 characters, and one
     * with a full-width letter.
     *
     * Assertions:
     *
     * Each fails with a `ValidationException` whose error is under the name or the email field, and adds no account: the login
     * token's length is bounded by the longest name and email address these rules allow.
     */
    val refused = List(
      testContext.makeUser().copy(name = "J") -> FieldConst.User.NAME,
      testContext.makeUser().copy(name = "J" * 81) -> FieldConst.User.NAME,
      testContext.makeUser().copy(email = "jane.example.com") -> FieldConst.User.EMAIL,
      testContext.makeUser().copy(email = s"${"j" * 69}@example.com") -> FieldConst.User.EMAIL,
      testContext.makeUser().copy(email = s"\uFF2Aane.${Util.randomStr(8)}@example.com") -> FieldConst.User.EMAIL
    )
    for ((model, field) <- refused) withClue(s"[${model.name}] [${model.email}]: ") {
      val error = intercept[ValidationException](testApp.service.user.add(model, password = TestContext.USER_PASSWORD))
      error.errors.keySet shouldBe Set(field)
      testApp.txManager.asReadOnly(query("SELECT id FROM account WHERE username = ?", model.username).size) shouldBe 0
    }
  }

  test("A deactivated account is refused at login as a wrong password is") {

    /**
     * Setup:
     *
     * A User deactivated by hand-written SQL.
     *
     * Assertions:
     *
     * A login with its username and its right password yields nothing, as one with a wrong password does, and records a failed
     * login naming the account, with nothing recorded as its last login.
     */
    val user = testContext.persistUser()
    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE id = ?", false, user.persistedId))

    testApp.service.user.loginAndSetUser(user.username, TestContext.USER_PASSWORD, Some("10.0.0.7")) shouldBe None
    testApp.service.user.loginAndSetUser(user.username, "wrong-password", Some("10.0.0.7")) shouldBe None

    val failures = auditEventsOf(user, AuditEventType.LoginFailed)
    failures.size shouldBe 2
    failures.map(_("client_ip")).distinct shouldBe List("10.0.0.7")
    testApp.service.user.getById(user.persistedId).lastLoginAt shouldBe None
  }

  test("A login records the last login time and its event with the client IP; a failed one records its own event") {

    /**
     * Setup:
     *
     * A User never logged in; a login by its username from one IP, then a login with a wrong password from another, and one
     * naming no account.
     *
     * Assertions:
     *
     * The login sets the account's last login time, and records a successful login whose actor is the User, with the first IP.
     * The wrong password records a failed login with no actor, the User as its target, the typed login in its detail and the
     * second IP; the unknown login a failed login with no actor and no target. The last login time stays the successful one's.
     */
    val user = testContext.persistUser()
    val unknown = s"nobody.${Util.randomStr(8)}"

    testApp.service.user.loginAndSetUser(user.username, TestContext.USER_PASSWORD, Some("10.0.0.1")) should not be None
    val loggedInAt = testApp.service.user.getById(user.persistedId).lastLoginAt
    loggedInAt should not be None

    testApp.service.user.loginAndSetUser(user.username, "wrong-password", Some("10.0.0.2")) shouldBe None
    testApp.service.user.loginAndSetUser(unknown, "wrong-password", Some("10.0.0.3")) shouldBe None

    val success = auditEventsOf(user, AuditEventType.LoginSucceeded).loneElement
    success("client_ip") shouldBe "10.0.0.1"
    ujson.read(success("actor").toString)("id").str shouldBe user.persistedId

    val failure = auditEventsOf(user, AuditEventType.LoginFailed).loneElement
    failure("client_ip") shouldBe "10.0.0.2"
    failure("actor") shouldBe null
    ujson.read(failure("detail").toString)("login").str shouldBe user.username

    val unknownFailure = testApp.txManager.asReadOnly {
      query("SELECT * FROM audit_event WHERE client_ip = ?", "10.0.0.3")
    }.loneElement
    unknownFailure("target") shouldBe null
    ujson.read(unknownFailure("detail").toString)("login").str shouldBe unknown

    testApp.service.user.getById(user.persistedId).lastLoginAt shouldBe loggedInAt
  }

  test("A login with a control character fails as a wrong password does, and its event keeps the login printable and short") {

    /**
     * Setup:
     *
     * A User, and logins of its username with a NUL character inside it, and of 200 characters with a line break.
     *
     * Assertions:
     *
     * Each login yields nothing, as a wrong password does, on both engines (PostgreSQL refuses a NUL in a text parameter, so the
     * login is never looked up). Each records a failed login whose detail holds the login with the control character escaped, cut
     * to 64 characters, with no NUL or line break in it.
     */
    val user = testContext.persistUser()
    val withNul = s"${user.username}\u0000"
    val long = "x" * 100 + "\n" + "y" * 99

    for ((login, ip) <- List(withNul -> "10.0.1.1", long -> "10.0.1.2")) withClue(ip) {
      testApp.service.user.loginAndSetUser(login, TestContext.USER_PASSWORD, Some(ip)) shouldBe None
      val recorded = ujson
        .read(
          testApp.txManager
            .asReadOnly(query("SELECT detail FROM audit_event WHERE client_ip = ?", ip))
            .loneElement("detail")
            .toString)("login")
        .str
      recorded.length should be <= 64
      recorded.exists(_.isControl) shouldBe false
    }
    ujson
      .read(
        testApp.txManager
          .asReadOnly(query("SELECT detail FROM audit_event WHERE client_ip = ?", "10.0.1.1"))
          .loneElement("detail")
          .toString)("login")
      .str shouldBe s"${user.username}\\u0000"
  }

  test("The dev user's claims are built from the database on every request, and a failed dev login is not retried at once") {

    /**
     * Setup:
     *
     * A User logged in as the dev user by its username and password; then granted a Library, then deactivated by hand-written
     * SQL, neither raising its version. Dev credentials that name no account.
     *
     * Assertions:
     *
     * The dev user's claims carry the grant once it is written, and none once the account is deactivated. The credentials that
     * name no account fail twice in a row with one failed login recorded: the failure is remembered for a while rather than
     * logged in again on every request.
     */
    val user = testContext.persistUser()
    val credentials = (user.username, TestContext.USER_PASSWORD)
    testApp.service.user.devUser(credentials).map(_.account.persistedId) shouldBe Some(user.persistedId)

    val library = testContext.persistRepository()
    testApp.txManager.withTransaction {
      update(
        "INSERT INTO repository_grant (account_id, repository_id, permissions) VALUES (?, ?, ?)",
        user.persistedId,
        library.persistedId,
        1)
    }
    testApp.service.user.devUser(credentials).map(_.grants.keySet) shouldBe Some(Set(library.persistedId))

    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE id = ?", false, user.persistedId))
    testApp.service.user.devUser(credentials) shouldBe None

    val nobody = s"nobody.${Util.randomStr(8)}"
    testApp.service.user.devUser((nobody, "password123")) shouldBe None
    testApp.service.user.devUser((nobody, "password123")) shouldBe None
    testApp.txManager
      .asReadOnly {
        query("SELECT detail FROM audit_event WHERE event = ?", AuditEventType.LoginFailed.dbValue)
      }
      .count(row => ujson.read(row("detail").toString)("login").str == nobody) shouldBe 1
  }

  /**
   * Runs a login of `user` with `password` while another transaction holds the account (its row lock on PostgreSQL, the one write
   * connection on SQLite) with `change` written in it, released once the login waits for it: the login's result
   */
  private def loginWhileHeld(user: User, password: String)(change: => Unit): Option[(User, String, java.time.Instant)] = {
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val (writer, writerFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        change
        holding.countDown()
        release.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      holding.await(5, TimeUnit.SECONDS) shouldEqual true
      val result = new java.util.concurrent.atomic.AtomicReference[Option[(User, String, java.time.Instant)]](None)
      val (login, loginFailure) = onAnotherThread(result.set(testApp.service.user.loginAndSetUser(user.username, password)))

      // On PostgreSQL the login waits for the row lock, which pg_stat_activity shows; on SQLite its thread parks in the pool
      def loginIsWaiting: Boolean = testApp.dataSourceType match {
        case Const.DbEngineName.POSTGRES =>
          testApp.txManager.asReadOnly {
            query("SELECT pid FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()").nonEmpty
          }
        case _ => List(Thread.State.WAITING, Thread.State.TIMED_WAITING).contains(login.getState)
      }
      Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(20, Millis))) {
        loginIsWaiting shouldBe true
      }
      release.countDown()
      login.join()
      loginFailure.get.foreach(throw _)
      result.get
    } finally {
      release.countDown()
      writer.join()
      writerFailure.get.foreach(throw _)
    }
  }

  test("A login checks the password before it waits to record the login") {

    /**
     * Setup:
     *
     * A User, and another transaction holding the account (its row lock on PostgreSQL, the write connection on SQLite) with the
     * User's name changed in it, until the login waits for it.
     *
     * Assertions:
     *
     * The login, started while the account is held, comes to wait for it and, once released, succeeds and records its last login
     * time. The account it answers has the name from before the change: it was read, and the password checked, before the login
     * waited for any writer.
     */
    val user = testContext.persistUser()
    val result = loginWhileHeld(user, TestContext.USER_PASSWORD) {
      update("UPDATE account SET name = ? WHERE id = ?", "Renamed Meanwhile", user.persistedId)
    }

    result.map(_._1.name) shouldBe Some(user.name)
    testApp.service.user.getById(user.persistedId).lastLoginAt should not be None
  }

  test("A login whose password changes while it waits to record it fails") {

    /**
     * Setup:
     *
     * A User, and another transaction holding the account with its password hash changed to another password's, until the login
     * with the first password waits for it.
     *
     * Assertions:
     *
     * Once released, the login yields nothing and records no last login time: it checked the old hash, but the hash it reads
     * again, after reading the account's version, is the new one, so no token is minted for a password that no longer holds.
     */
    val user = testContext.persistUser()
    val result = loginWhileHeld(user, TestContext.USER_PASSWORD) {
      update("UPDATE account SET password_hash = ? WHERE id = ?", Util.hashPassword("AnotherPassword456"), user.persistedId)
    }

    result shouldBe None
    testApp.service.user.getById(user.persistedId).lastLoginAt shouldBe None
  }

  /** The audit events of `event` whose actor or target is the account, oldest first */
  private def auditEventsOf(account: User, event: AuditEventType): List[Map[String, AnyRef]] =
    testApp.txManager
      .asReadOnly {
        query("SELECT * FROM audit_event WHERE event = ? ORDER BY created_at", event.dbValue)
      }
      .filter(
        row =>
          List("actor", "target").exists(
            column => Option(row(column)).exists(json => ujson.read(json.toString)("id").str == account.persistedId)))

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

    val userModel = testContext.makeUser()

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
    test("Remembering the Library recorded already does not wait for the SQLite write connection") {

      /**
       * Setup:
       *
       * A User whose last active Library is the common setup's, and a write transaction on a thread of its own that holds
       * SQLite's single write connection until the test releases it.
       *
       * Assertions:
       *
       * While the write connection is held, remembering the recorded Library again completes within a few seconds and reports no
       * write, since it only reads the account to find there is nothing to write.
       */
      val user: User = testContext.persistUser()
      val repoId = testContext.repository.persistedId
      testApp.service.user.rememberLastActiveRepository(user, repoId) shouldBe true

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
        val remember = Future(testApp.service.user.rememberLastActiveRepository(user, repoId))
        Await.result(remember, 5.seconds) shouldBe false
      } finally {
        release.countDown()
        writer.join()
      }
    }
  }
}
