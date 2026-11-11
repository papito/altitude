package altitude.core.integration

import altitude.test.TestContext
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.FieldConst
import altitude.core.ForbiddenException
import altitude.core.IllegalOperationException
import altitude.core.ValidationException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AccountSnapshot
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.AuditEventType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.service.RepositoryService
import altitude.core.service.UserService.TokenCheck
import altitude.core.util.JsonCodec
import altitude.core.util.Util

/**
 * Account management (`UserService`'s writes behind the Users page): create, update, deactivate, reactivate, delete and the
 * password changes, the ceiling of a creator who is no Admin, the guards on Admins, and the three statements that reach an
 * account's assets in every Library at once
 */
@DoNotDiscover class AccountManagementTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val users = testApp.service.user

  private def persistAdmin(): User = testContext.persistUser(Some(testContext.makeAdminUser()))

  private def addLibrary(): Repository =
    testApp.service.repository.addRepository(name = TestContext.nextRepositoryName(), fileStoreType = C.StorageEngineName.FS)

  /** A User or a Guest to create, of random names */
  private def model(accountType: AccountType = AccountType.User): User = testContext.makeUser().copy(accountType = accountType)

  private def grantsOf(account: User): Map[String, Permissions] =
    testApp.txManager.asReadOnly {
      testApp.DAO.repositoryGrant.getByAccount(account.persistedId).map(grant => grant.repositoryId -> grant.permissions).toMap
    }

  /** The audit events naming the account as their target, oldest first */
  private def eventsOn(account: User): List[String] =
    testApp.txManager.asReadOnly {
      query("SELECT event, target, created_at FROM audit_event ORDER BY created_at")
        .filter(row => Option(row("target")).exists(_.toString.contains(account.persistedId)))
        .map(_("event").toString)
    }

  /** The details of the audit events of `event` naming the account as their target */
  private def detailsOn(account: User, event: AuditEventType): List[ujson.Value] =
    testApp.txManager.asReadOnly {
      query("SELECT target, detail FROM audit_event WHERE event = ?", event.dbValue)
        .filter(row => Option(row("target")).exists(_.toString.contains(account.persistedId)))
        .map(row => ujson.read(row("detail").toString))
    }

  private def version(account: User): Long = testApp.service.accountVersions.current(account.persistedId)

  /** The state an Admin's edit form opens the account over, which its save carries back */
  private def stateOf(account: User): String = users.openForEdit(account.persistedId).state

  /** An asset `owner` imported into `library`, the context left on the first Library of the test */
  private def assetIn(library: Repository, owner: User): Asset = {
    switchContextRepo(library)
    val asset = testContext.insertAsset(testContext.makeAsset(repository = Some(library), user = Some(owner)))
    switchContextRepo(testContext.repository)
    asset
  }

  /** The stored owner and importer record of each asset, by hand-written SQL that reaches every Library */
  private def ownership(assets: Asset*): Map[String, (String, AccountSnapshot)] =
    testApp.txManager.asReadOnly {
      assets.map {
        asset =>
          val row = query("SELECT owner_user_id, original_owner FROM asset WHERE id = ?", asset.persistedId).head
          asset.persistedId -> (row("owner_user_id").toString, JsonCodec.read[AccountSnapshot](row("original_owner").toString))
      }.toMap
    }

  private def accountRow(account: User): Option[User] =
    testApp.txManager.withTransaction(testApp.DAO.user.lockWithPasswordHash(account.persistedId)).map(_._1)

  private def logsIn(account: User, password: String): Boolean = users.loginAndSetUser(account.username, password).isDefined

  test("An Admin creates an account with its grants, as its creator, active, with the event recorded") {

    /**
     * Setup:
     *
     * An Admin, and two Libraries; the Admin creates a User granted Import and Organize in the first and access alone in the
     * second, with the Create Libraries flag and the new-Library grant of Import.
     *
     * Assertions:
     *
     * The account is stored active, created by the Admin, with its flag and new-Library grant, and holds exactly the two grants
     * asked for. It logs in with the password given. Its creation is recorded as an event naming the Admin.
     */
    val admin = persistAdmin()
    val (first, second) = (addLibrary(), addLibrary())
    val grants =
      Map(first.persistedId -> Permissions.of(Permission.Import, Permission.Organize), second.persistedId -> Permissions.none)

    val created = users.create(
      admin.persistedId,
      model()
        .copy(canCreateLibraries = true, grantsNewLibraries = true, newLibraryPermissions = Permissions.of(Permission.Import)),
      "new password 1",
      grants
    )

    val stored = users.getById(created.persistedId)
    stored.isActive shouldBe true
    stored.createdByAccountId shouldBe Some(admin.persistedId)
    stored.canCreateLibraries shouldBe true
    stored.grantsNewLibraries shouldBe true
    stored.newLibraryPermissions shouldBe Permissions.of(Permission.Import)
    grantsOf(created) shouldBe grants
    logsIn(created, "new password 1") shouldBe true
    eventsOn(created) should contain(AuditEventType.AccountCreated.dbValue)
  }

  test("A new account keeps to the account rules, each refusal on its field, and nothing is written") {

    /**
     * Setup:
     *
     * An Admin and a Library; accounts to create with an invalid username, a name too short, an address that is none, a password
     * too short, and a User with no Library; then one with as many Libraries as the bound and one past it.
     *
     * Assertions:
     *
     * The first is refused with a `ValidationException` holding the error of each of its fields (username, name, email,
     * password); the User with no Library is refused on its grants (`LIBRARY_REQUIRED`), and one past the bound with
     * `TOO_MANY_GRANTS`. None is stored.
     *
     * Edge cases:
     *
     * An Admin needs no Library, and a User given exactly as many Libraries as the bound is created holding each of them.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val bad = model().copy(username = "a b", name = "x", email = "nope")

    val ex =
      intercept[ValidationException](users.create(admin.persistedId, bad, "short", Map(library.persistedId -> Permissions.none)))
    ex.errors.keySet shouldBe Set(FieldConst.User.USERNAME, FieldConst.User.NAME, FieldConst.User.EMAIL, FieldConst.User.PASSWORD)

    val noLibrary = model()
    intercept[ValidationException](users.create(admin.persistedId, noLibrary, "password 1", Map.empty))
      .errors(FieldConst.User.GRANTS) shouldBe C.Msg.Err.LIBRARY_REQUIRED

    val tooMany = (0 to RepositoryService.MAX_GRANTS_PER_ACCOUNT).map(_ => BaseDao.genId -> Permissions.none).toMap
    intercept[ValidationException](users.create(admin.persistedId, model(), "password 1", tooMany))
      .errors(FieldConst.User.GRANTS) shouldBe C.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT)

    testApp.txManager.asReadOnly {
      query("SELECT id FROM account WHERE username = ? OR username = ?", bad.username, noLibrary.username)
    } shouldBe empty

    users.create(admin.persistedId, model(AccountType.Admin), "password 1", Map.empty).accountType shouldBe AccountType.Admin

    val atBound = List.fill(RepositoryService.MAX_GRANTS_PER_ACCOUNT)(addLibrary().persistedId -> Permissions.none).toMap
    grantsOf(users.create(admin.persistedId, model(), "password 1", atBound)) shouldBe atBound
  }

  test("An email address or a username another account holds is refused on its field, on a create and on an update") {

    /**
     * Setup:
     *
     * An Admin, a Library and a User; then a create under the User's email address in other capitals, a create under its username
     * in other capitals, and an update of a second User to the first one's username.
     *
     * Assertions:
     *
     * Each is a `ValidationException` on its field, `EMAIL_TAKEN` or `USERNAME_TAKEN`, and the second User keeps its username.
     */
    val admin = persistAdmin()
    val grants = Map(addLibrary().persistedId -> Permissions.none)
    val user = users.create(admin.persistedId, model(), "password 1", grants)

    intercept[ValidationException] {
      users.create(admin.persistedId, model().copy(email = user.email.toUpperCase), "password 1", grants)
    }.errors(FieldConst.User.EMAIL) shouldBe C.Msg.Err.EMAIL_TAKEN
    intercept[ValidationException] {
      users.create(admin.persistedId, model().copy(username = user.username.toUpperCase), "password 1", grants)
    }.errors(FieldConst.User.USERNAME) shouldBe C.Msg.Err.USERNAME_TAKEN

    val other = users.create(admin.persistedId, model(), "password 1", grants)
    intercept[ValidationException] {
      users.update(admin.persistedId, other.copy(username = user.username), grants, stateOf(other))
    }.errors(FieldConst.User.USERNAME) shouldBe C.Msg.Err.USERNAME_TAKEN
    users.getById(other.persistedId).username shouldBe other.username
  }

  test("A creator who is no Admin gives only the Libraries and Permissions it may pass on") {

    /**
     * Setup:
     *
     * A User holding Import, Curate own and Create Users in one Library, Create Guests alone in a second, and nothing in a third.
     *
     * Assertions:
     *
     * It creates a User with Import in the first Library, and a Guest in the second, each recorded as its creator. Each of these
     * is a `ForbiddenException` and writes no account: a User given Organize in the first Library (a Permission it does not
     * hold), a User in the second (no Create Users there), a Guest in the first (no Create Guests there), a User in the third
     * Library or in one that does not exist, an Admin, and a User with the Create Libraries flag or the new-Library grant.
     */
    val creator = testContext.persistUser()
    val (first, second, third) = (addLibrary(), addLibrary(), addLibrary())
    testContext.grant(creator, first, Permissions.of(Permission.Import, Permission.CurateOwn, Permission.CreateUsers))
    testContext.grant(creator, second, Permissions.of(Permission.CreateGuests))

    val user =
      users.create(creator.persistedId, model(), "password 1", Map(first.persistedId -> Permissions.of(Permission.Import)))
    val guest =
      users.create(creator.persistedId, model(AccountType.Guest), "password 1", Map(second.persistedId -> Permissions.none))
    users.getById(user.persistedId).createdByAccountId shouldBe Some(creator.persistedId)
    users.getById(guest.persistedId).createdByAccountId shouldBe Some(creator.persistedId)

    val refused = List(
      model() -> Map(first.persistedId -> Permissions.of(Permission.Organize)),
      model() -> Map(second.persistedId -> Permissions.none),
      model(AccountType.Guest) -> Map(first.persistedId -> Permissions.none),
      model() -> Map(third.persistedId -> Permissions.none),
      model() -> Map(BaseDao.genId -> Permissions.none),
      model(AccountType.Admin) -> Map.empty[String, Permissions],
      model().copy(canCreateLibraries = true) -> Map(first.persistedId -> Permissions.none),
      model().copy(grantsNewLibraries = true) -> Map(first.persistedId -> Permissions.none)
    )
    for ((account, grants) <- refused) withClue(s"[${account.accountType}] $grants: ") {
      a[ForbiddenException] should be thrownBy users.create(creator.persistedId, account, "password 1", grants)
      testApp.txManager.asReadOnly(query("SELECT id FROM account WHERE username = ?", account.username)) shouldBe empty
    }
  }

  test("An account is held to its type: an Admin keeps no grant or flag, a Guest no Permission") {

    /**
     * Setup:
     *
     * An Admin and a Library; an Admin created with a grant, the Create Libraries flag and the new-Library grant, and a Guest
     * created with Import in the Library, the Create Libraries flag and the new-Library grant of Import.
     *
     * Assertions:
     *
     * The new Admin holds no grant, flag or new-Library grant. The Guest holds access to the Library with no Permission, no flag,
     * and a new-Library grant of access alone.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val flags = (account: User) =>
      account.copy(
        canCreateLibraries = true,
        grantsNewLibraries = true,
        newLibraryPermissions = Permissions.of(Permission.Import))
    val grants = Map(library.persistedId -> Permissions.of(Permission.Import))

    val newAdmin =
      users.getById(users.create(admin.persistedId, flags(model(AccountType.Admin)), "password 1", grants).persistedId)
    grantsOf(newAdmin) shouldBe empty
    (newAdmin.canCreateLibraries, newAdmin.grantsNewLibraries, newAdmin.newLibraryPermissions) shouldBe (
      false,
      false,
      Permissions.none)

    val guest = users.getById(users.create(admin.persistedId, flags(model(AccountType.Guest)), "password 1", grants).persistedId)
    grantsOf(guest) shouldBe Map(library.persistedId -> Permissions.none)
    (guest.canCreateLibraries, guest.grantsNewLibraries, guest.newLibraryPermissions) shouldBe (false, true, Permissions.none)
  }

  test("An Admin's update rewrites the importer records of the account's assets in every Library, and nobody else's") {

    /**
     * Setup:
     *
     * An Admin, two Libraries, and two Users each holding every Permission in both. The first User imported an asset into each
     * Library, and a third asset that the second User owns now; the second User imported an asset of its own. Then the Admin
     * changes the first User's name, username and email address, and its grants.
     *
     * Assertions:
     *
     * The importer record of the first User's three assets, in both Libraries and whoever owns them, names the account as it is
     * now; the owners stay as they were. The second User's asset keeps its record. The account is stored with its new names and
     * grants, its version is bumped, and the edit and the change of Permissions are recorded as events.
     */
    val admin = persistAdmin()
    val (first, second) = (addLibrary(), addLibrary())
    val all = Map(first.persistedId -> Permissions.all, second.persistedId -> Permissions.all)
    val user = users.create(admin.persistedId, model(), "password 1", all)
    val other = users.create(admin.persistedId, model(), "password 1", all)
    val mine = List(assetIn(first, user), assetIn(second, user))
    val handedOver = assetIn(second, user)
    testApp.txManager.withTransaction(
      update("UPDATE asset SET owner_user_id = ? WHERE id = ?", other.persistedId, handedOver.persistedId))
    val others = assetIn(first, other)
    val before = version(user)

    val changed = user.copy(
      name = "Renamed Person",
      username = s"renamed.${Util.randomStr(6)}",
      email = s"r.${Util.randomStr(6)}@example.com")
    users.update(admin.persistedId, changed, Map(first.persistedId -> Permissions.of(Permission.Import)), stateOf(user))

    val owners = ownership(mine :+ handedOver :+ others*)
    for (asset <- mine) owners(asset.persistedId) shouldBe (user.persistedId, AccountSnapshot.of(changed))
    owners(handedOver.persistedId) shouldBe (other.persistedId, AccountSnapshot.of(changed))
    owners(others.persistedId) shouldBe (other.persistedId, AccountSnapshot.of(other))

    val stored = users.getById(user.persistedId)
    (stored.name, stored.username, stored.email) shouldBe (changed.name, changed.username, changed.email)
    grantsOf(user) shouldBe Map(first.persistedId -> Permissions.of(Permission.Import))
    version(user) should be > before
    (eventsOn(user) should contain).allOf(AuditEventType.AccountEdited.dbValue, AuditEventType.PermissionsChanged.dbValue)
  }

  test(
    "A type change: to Admin drops the grants, to Guest clears every Permission and keeps the Libraries, from Admin takes the grants given") {

    /**
     * Setup:
     *
     * An Admin, two Libraries, and a User holding Import in the first and Organize in the second. The Admin makes the User a
     * Guest with its grants as they are, then an Admin, then a User again with Import in the second Library.
     *
     * Assertions:
     *
     * As a Guest it keeps both Libraries with no Permission; as an Admin it holds no grant; as a User again it holds Import in
     * the second Library alone. Each change of type is recorded.
     */
    val admin = persistAdmin()
    val (first, second) = (addLibrary(), addLibrary())
    val held =
      Map(first.persistedId -> Permissions.of(Permission.Import), second.persistedId -> Permissions.of(Permission.Organize))
    val user = users.create(admin.persistedId, model(), "password 1", held)

    users.update(admin.persistedId, user.copy(accountType = AccountType.Guest), held, stateOf(user))
    grantsOf(user) shouldBe Map(first.persistedId -> Permissions.none, second.persistedId -> Permissions.none)

    users.update(admin.persistedId, user.copy(accountType = AccountType.Admin), held, stateOf(user))
    grantsOf(user) shouldBe empty
    users.getById(user.persistedId).accountType shouldBe AccountType.Admin

    users.update(
      admin.persistedId,
      user.copy(accountType = AccountType.User),
      Map(second.persistedId -> Permissions.of(Permission.Import)),
      stateOf(user))
    grantsOf(user) shouldBe Map(second.persistedId -> Permissions.of(Permission.Import))
    eventsOn(user).count(_ == AuditEventType.AccountTypeChanged.dbValue) shouldBe 3
  }

  test("Only an Admin changes an account, and an Admin cannot take the Admin type from its own account") {

    /**
     * Setup:
     *
     * Two Admins, a Library, and a User holding every Permission in it; the User tries to update, deactivate, delete and set the
     * password of the second Admin; the first Admin tries to make itself a User.
     *
     * Assertions:
     *
     * Each of the User's attempts is a `ForbiddenException` and changes nothing. The Admin's own demotion is a
     * `ValidationException` on the account type (`OWN_ACCOUNT`), and it stays an Admin.
     */
    val admin = persistAdmin()
    val otherAdmin = persistAdmin()
    val library = addLibrary()
    val user = users.create(admin.persistedId, model(), "password 1", Map(library.persistedId -> Permissions.all))

    a[ForbiddenException] should be thrownBy users.update(
      user.persistedId,
      otherAdmin.copy(name = "Taken Over"),
      Map.empty,
      stateOf(otherAdmin))
    a[ForbiddenException] should be thrownBy users.deactivate(user.persistedId, otherAdmin.persistedId)
    a[ForbiddenException] should be thrownBy users.delete(user.persistedId, otherAdmin.persistedId)
    a[ForbiddenException] should be thrownBy users.setPassword(user.persistedId, otherAdmin.persistedId, "taken over")
    accountRow(otherAdmin).map(account => (account.name, account.isActive)) shouldBe Some((otherAdmin.name, true))
    logsIn(otherAdmin, TestContext.USER_PASSWORD) shouldBe true

    intercept[ValidationException] {
      users.update(
        admin.persistedId,
        admin.copy(accountType = AccountType.User),
        Map(library.persistedId -> Permissions.all),
        stateOf(admin))
    }.errors(FieldConst.User.ACCOUNT_TYPE) shouldBe C.Msg.Err.OWN_ACCOUNT
    users.getById(admin.persistedId).accountType shouldBe AccountType.Admin
  }

  test("A deactivated account can do nothing until it is reactivated; an Admin cannot deactivate or delete its own account") {

    /**
     * Setup:
     *
     * An Admin and a User with access to a Library, logged in; the Admin deactivates the User, then reactivates it; then tries to
     * deactivate and to delete its own account.
     *
     * Assertions:
     *
     * Deactivated, the User's login is refused and its claims are gone, and its version was bumped; reactivated, it logs in
     * again. Both changes are recorded. The Admin's own deactivation and deletion are each an `IllegalOperationException`
     * (`OWN_ACCOUNT`), and it stays active.
     *
     * Edge cases:
     *
     * The token the User held before its deactivation stays refused once it is reactivated: a deactivation revokes the account's
     * tokens, so the reactivated account logs in again.
     */
    val admin = persistAdmin()
    val user = users.create(admin.persistedId, model(), "password 1", Map(addLibrary().persistedId -> Permissions.none))
    val token = users.loginAndSetUser(user.username, "password 1").get._2
    val before = version(user)

    users.deactivate(admin.persistedId, user.persistedId)
    logsIn(user, "password 1") shouldBe false
    users.claimsOf(user.persistedId) shouldBe None
    version(user) should be > before

    users.reactivate(admin.persistedId, user.persistedId)
    users.checkToken(token, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = false)
    logsIn(user, "password 1") shouldBe true
    (eventsOn(user) should contain).allOf(AuditEventType.AccountDeactivated.dbValue, AuditEventType.AccountReactivated.dbValue)

    intercept[IllegalOperationException](
      users.deactivate(admin.persistedId, admin.persistedId)).getMessage shouldBe C.Msg.Err.OWN_ACCOUNT
    intercept[IllegalOperationException](
      users.delete(admin.persistedId, admin.persistedId)).getMessage shouldBe C.Msg.Err.OWN_ACCOUNT
    accountRow(admin).map(_.isActive) shouldBe Some(true)
  }

  test("Deleting an account gives its assets in every Library to the Admin, takes its grants and clears it as a creator") {

    /**
     * Setup:
     *
     * An Admin, two Libraries, a User holding every Permission in both who imported an asset into each and recycled one of them,
     * an account the User created, and a second User with an asset of its own. The Admin counts the User's assets, then deletes
     * the User.
     *
     * Assertions:
     *
     * The count is the User's two assets, in both Libraries, the recycled one included, and the delete answers the same number.
     * Both assets are the Admin's now, with their importer records left naming the User; the second User's asset stays its own.
     * The account and its grants are gone, the account it created has no creator, its version was bumped, and the delete is
     * recorded with the number of assets moved.
     */
    val admin = persistAdmin()
    val (first, second) = (addLibrary(), addLibrary())
    val all = Map(first.persistedId -> Permissions.all, second.persistedId -> Permissions.all)
    val user = users.create(admin.persistedId, model(), "password 1", all)
    val created = users.create(user.persistedId, model(), "password 1", Map(first.persistedId -> Permissions.none))
    val other = users.create(admin.persistedId, model(), "password 1", all)
    val mine = List(assetIn(first, user), assetIn(second, user))
    switchContextRepo(second)
    testApp.service.library.recycleAssets(Set(mine.last.persistedId))
    switchContextRepo(testContext.repository)
    val others = assetIn(first, other)
    val before = version(user)

    users.countOwnedAssets(user.persistedId) shouldBe 2
    users.delete(admin.persistedId, user.persistedId) shouldBe 2

    val owners = ownership(mine :+ others*)
    for (asset <- mine) owners(asset.persistedId) shouldBe (admin.persistedId, AccountSnapshot.of(user))
    owners(others.persistedId) shouldBe (other.persistedId, AccountSnapshot.of(other))
    users.countOwnedAssets(admin.persistedId) shouldBe 2

    accountRow(user) shouldBe None
    testApp.txManager.asReadOnly(
      query("SELECT repository_id FROM repository_grant WHERE account_id = ?", user.persistedId)) shouldBe empty
    users.getById(created.persistedId).createdByAccountId shouldBe None
    version(user) should be > before
    detailsOn(user, AuditEventType.AccountDeleted) shouldBe List(ujson.Obj("assetsMoved" -> 2))
  }

  test("Two Admins removing each other at once leave one of them active") {

    /**
     * Setup:
     *
     * Two active Admins, and no other; each deactivates the other from a transaction of its own, the first holding its
     * transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second is refused once the first commits (the second Admin is no longer active, so it may act no more), and the first
     * Admin is the one active Admin left.
     */
    // A removal locks the active Admins' rows before it reads the actor among them: on PostgreSQL, without the lock both would
    // read two active Admins and both would commit
    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE account_type = 'Admin'", Boolean.box(false)))
    val (admin, otherAdmin) = (persistAdmin(), persistAdmin())

    intercept[ForbiddenException] {
      contend(
        first = users.deactivate(admin.persistedId, otherAdmin.persistedId),
        second = users.deactivate(otherAdmin.persistedId, admin.persistedId))
    }

    testApp.txManager.withTransaction(testApp.DAO.user.lockActiveAdmins()).map(_.persistedId) shouldBe List(admin.persistedId)
  }

  test(
    "A change of the name, the username or the email address alone rewrites the importer records; every edit bumps the version") {

    /**
     * Setup:
     *
     * An Admin, a Library, and a User holding Import there who imported an asset into it. The Admin changes the User's name
     * alone, then its username alone, then its email address alone, then its Create Libraries flag alone, each with its grants as
     * they are.
     *
     * Assertions:
     *
     * After each change of a name, the asset's importer record names the account as it is now, the other two fields as they were
     * before. Every one of the four edits raises the account's version, the flag's included, which changes no importer record.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val grants = Map(library.persistedId -> Permissions.of(Permission.Import))
    var account = users.create(admin.persistedId, model(), "password 1", grants)
    val asset = assetIn(library, account)

    val edits: List[(String, User => User)] = List(
      "name" -> (_.copy(name = s"Renamed ${Util.randomStr(6)}")),
      "username" -> (_.copy(username = s"renamed.${Util.randomStr(6)}")),
      "email" -> (_.copy(email = s"r.${Util.randomStr(6)}@example.com")),
      "flag" -> (_.copy(canCreateLibraries = true))
    )
    for ((field, edit) <- edits) withClue(s"[$field]: ") {
      val before = version(account)
      val changed = edit(account)
      users.update(admin.persistedId, changed, grants, stateOf(account))

      ownership(asset)(asset.persistedId) shouldBe (account.persistedId, AccountSnapshot.of(changed))
      version(account) should be > before
      account = changed
    }
    users.getById(account.persistedId).canCreateLibraries shouldBe true
  }

  test("Two Admins demoting each other at once leave one of them an Admin") {

    /**
     * Setup:
     *
     * Two active Admins, and no other; each makes the other a User from a transaction of its own, the first holding its
     * transaction open until the second waits for it.
     *
     * Assertions:
     *
     * The second is refused once the first commits (the second Admin is no longer an Admin, so it may act no more), and the first
     * Admin is the one active Admin left.
     */
    // On PostgreSQL, without the lock of the active Admins' rows both would read two Admins and both would commit
    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE account_type = 'Admin'", Boolean.box(false)))
    val (admin, otherAdmin) = (persistAdmin(), persistAdmin())
    val grants = Map(addLibrary().persistedId -> Permissions.none)
    val (adminState, otherState) = (stateOf(admin), stateOf(otherAdmin))

    intercept[ForbiddenException] {
      contend(
        first = users.update(admin.persistedId, otherAdmin.copy(accountType = AccountType.User), grants, otherState): Unit,
        second = users.update(otherAdmin.persistedId, admin.copy(accountType = AccountType.User), grants, adminState): Unit
      )
    }

    testApp.txManager.withTransaction(testApp.DAO.user.lockActiveAdmins()).map(_.persistedId) shouldBe List(admin.persistedId)
  }

  test("Two Admins deleting each other at once leave one of them") {

    /**
     * Setup:
     *
     * Two active Admins, and no other; each deletes the other from a transaction of its own, the first holding its transaction
     * open until the second waits for it.
     *
     * Assertions:
     *
     * The second is refused once the first commits (the second Admin is gone, so it may act no more), and the first Admin is the
     * one active Admin left.
     */
    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE account_type = 'Admin'", Boolean.box(false)))
    val (admin, otherAdmin) = (persistAdmin(), persistAdmin())

    intercept[ForbiddenException] {
      contend(
        first = users.delete(admin.persistedId, otherAdmin.persistedId): Unit,
        second = users.delete(otherAdmin.persistedId, admin.persistedId): Unit)
    }

    testApp.txManager.withTransaction(testApp.DAO.user.lockActiveAdmins()).map(_.persistedId) shouldBe List(admin.persistedId)
  }

  test("An Admin demoting another while that one deletes it leaves one of them an Admin") {

    /**
     * Setup:
     *
     * Two active Admins, and no other; the first makes the second a User from a transaction of its own, holding it open until the
     * second, deleting the first from another transaction, waits for it.
     *
     * Assertions:
     *
     * The delete is refused once the demotion commits (its actor is no longer an Admin), and the first Admin is the one active
     * Admin left, its account still there.
     */
    testApp.txManager.withTransaction(update("UPDATE account SET is_active = ? WHERE account_type = 'Admin'", Boolean.box(false)))
    val (admin, otherAdmin) = (persistAdmin(), persistAdmin())
    val grants = Map(addLibrary().persistedId -> Permissions.none)
    val otherState = stateOf(otherAdmin)

    intercept[ForbiddenException] {
      contend(
        first = users.update(admin.persistedId, otherAdmin.copy(accountType = AccountType.User), grants, otherState): Unit,
        second = users.delete(otherAdmin.persistedId, admin.persistedId): Unit
      )
    }

    testApp.txManager.withTransaction(testApp.DAO.user.lockActiveAdmins()).map(_.persistedId) shouldBe List(admin.persistedId)
  }

  test("An edit saved over an account another Admin changed since the form was opened is refused, and changes nothing") {

    /**
     * Setup:
     *
     * Two Admins, a Library, and a User holding Import there. Both Admins open the User's edit form; the second saves the User
     * with Organize added; then the first saves it renamed, from the form it opened before.
     *
     * Assertions:
     *
     * The first Admin's save is a `ValidationException` (`ACCOUNT_CHANGED`) and writes nothing: the User keeps its name and the
     * second Admin's grant. Saved again from a form opened now, the rename goes through.
     */
    val admin = persistAdmin()
    val otherAdmin = persistAdmin()
    val library = addLibrary()
    val user =
      users.create(admin.persistedId, model(), "password 1", Map(library.persistedId -> Permissions.of(Permission.Import)))
    val opened = stateOf(user)
    val both = Map(library.persistedId -> Permissions.of(Permission.Import, Permission.Organize))

    users.update(otherAdmin.persistedId, user, both, opened)
    val refused = intercept[ValidationException] {
      users.update(admin.persistedId, user.copy(name = "Renamed User"), Map(library.persistedId -> Permissions.none), opened)
    }
    refused.message shouldBe C.Msg.Err.ACCOUNT_CHANGED
    users.getById(user.persistedId).name shouldBe user.name
    grantsOf(user) shouldBe both

    users.update(admin.persistedId, user.copy(name = "Renamed User"), both, stateOf(user))
    users.getById(user.persistedId).name shouldBe "Renamed User"
  }

  test("An edit saved over an account granted a new Library since the form was opened is refused") {

    /**
     * Setup:
     *
     * An Admin, a Library, and a User holding Import there whose new-Library switch is on. The Admin opens the User's edit form;
     * then a Library is created, which grants the User access to it; then the Admin saves the form, its grants as it showed them.
     *
     * Assertions:
     *
     * The save is a `ValidationException` (`ACCOUNT_CHANGED`), and the User keeps the new Library's grant.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val shown = Map(library.persistedId -> Permissions.of(Permission.Import))
    val user = users.create(
      admin.persistedId,
      model().copy(grantsNewLibraries = true, newLibraryPermissions = Permissions.of(Permission.Import)),
      "password 1",
      shown)
    val opened = stateOf(user)

    val added = addLibrary()
    grantsOf(user).keySet should contain(added.persistedId)

    intercept[ValidationException](users.update(admin.persistedId, user, shown, opened)).message shouldBe
      C.Msg.Err.ACCOUNT_CHANGED
    grantsOf(user).keySet should contain(added.persistedId)
  }

  test("A creator deactivated since its login creates nothing") {

    /**
     * Setup:
     *
     * An Admin, a Library, and a User holding Create Users there, which the Admin deactivates.
     *
     * Assertions:
     *
     * The User's create of an account in the Library is a `ForbiddenException`, and no account of that username exists.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val creator =
      users.create(admin.persistedId, model(), "password 1", Map(library.persistedId -> Permissions.of(Permission.CreateUsers)))
    users.deactivate(admin.persistedId, creator.persistedId)

    val refused = model()
    a[ForbiddenException] should be thrownBy users.create(
      creator.persistedId,
      refused,
      "password 1",
      Map(library.persistedId -> Permissions.none))
    testApp.txManager.asReadOnly(query("SELECT id FROM account WHERE username = ?", refused.username)) shouldBe empty
  }

  test("A new-Library grant's Permissions are kept only with its switch on, and only an Admin gives them") {

    /**
     * Setup:
     *
     * An Admin, a Library, and a User holding Import and Create Users there; the User creates an account with the new-Library
     * switch off but Import in its Permissions; the Admin creates one the same way, and one with the switch on.
     *
     * Assertions:
     *
     * The User's create is a `ForbiddenException` and writes no account. The Admin's account with the switch off is stored with
     * no new-Library Permissions, and the one with the switch on keeps Import.
     */
    val admin = persistAdmin()
    val library = addLibrary()
    val grants = Map(library.persistedId -> Permissions.none)
    val creator = users.create(
      admin.persistedId,
      model(),
      "password 1",
      Map(library.persistedId -> Permissions.of(Permission.Import, Permission.CreateUsers)))
    val masked = model().copy(newLibraryPermissions = Permissions.of(Permission.Import))

    a[ForbiddenException] should be thrownBy users.create(creator.persistedId, masked, "password 1", grants)
    testApp.txManager.asReadOnly(query("SELECT id FROM account WHERE username = ?", masked.username)) shouldBe empty

    users.getById(users.create(admin.persistedId, masked, "password 1", grants).persistedId).newLibraryPermissions shouldBe
      Permissions.none
    val switchedOn = users.create(
      admin.persistedId,
      model().copy(grantsNewLibraries = true, newLibraryPermissions = Permissions.of(Permission.Import)),
      "password 1",
      grants)
    users.getById(switchedOn.persistedId).newLibraryPermissions shouldBe Permissions.of(Permission.Import)
  }

  test(
    "An Admin sets an account's password: the old one fails, the new one logs in, and every session of the account is refused") {

    /**
     * Setup:
     *
     * An Admin and a User with access to a Library, whose token is minted at its current version; then the Admin sets the User's
     * password, first one too short, then a valid one.
     *
     * Assertions:
     *
     * The short one is a `ValidationException` on the password and changes nothing. After the valid one, the old password fails,
     * the new one logs in, the token minted before is refused (its account's tokens were revoked), and the change is recorded.
     */
    val admin = persistAdmin()
    val user = users.create(admin.persistedId, model(), "password 1", Map(addLibrary().persistedId -> Permissions.none))
    val token = users.loginAndSetUser(user.username, "password 1").get._2

    intercept[ValidationException](users.setPassword(admin.persistedId, user.persistedId, "short")).errors.keySet shouldBe
      Set(FieldConst.User.PASSWORD)
    logsIn(user, "password 1") shouldBe true

    users.setPassword(admin.persistedId, user.persistedId, "password 2")
    logsIn(user, "password 1") shouldBe false
    logsIn(user, "password 2") shouldBe true
    users.checkToken(token, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = false)
    eventsOn(user) should contain(AuditEventType.PasswordSet.dbValue)
  }

  test("An account changes its own password only with its current one, and its tokens minted before are refused") {

    /**
     * Setup:
     *
     * A User with access to a Library and a token of it; a change of its password with a wrong current password, one with a new
     * password too short, then one with the current password.
     *
     * Assertions:
     *
     * The wrong current password is a `ValidationException` on the current field (`WRONG_PASSWORD`), recorded as a failed change
     * with the client IP, and the short one is one on the new field; neither changes the password. After the third, the new
     * password logs in, the old one does not, the earlier token is refused, and claims built now (as the session that made the
     * change mints its new token from) hold.
     */
    val admin = persistAdmin()
    val user = users.create(admin.persistedId, model(), "password 1", Map(addLibrary().persistedId -> Permissions.none))
    val token = users.loginAndSetUser(user.username, "password 1").get._2

    intercept[ValidationException](
      users.changeOwnPassword(
        user.persistedId,
        "wrong one",
        "password 2",
        "current",
        "new",
        clientIp = Some("192.0.2.9"))).errors shouldBe scala.collection.mutable.Map("current" -> C.Msg.Err.WRONG_PASSWORD)
    testApp.txManager.asReadOnly {
      query("SELECT client_ip, target FROM audit_event WHERE event = ?", AuditEventType.PasswordChangeFailed.dbValue)
        .filter(_("target").toString.contains(user.persistedId))
        .map(_("client_ip"))
    } shouldBe List("192.0.2.9")
    intercept[ValidationException](
      users.changeOwnPassword(user.persistedId, "password 1", "short", "current", "new")).errors.keySet shouldBe Set("new")
    logsIn(user, "password 1") shouldBe true

    users.changeOwnPassword(user.persistedId, "password 1", "password 2", "current", "new")
    logsIn(user, "password 2") shouldBe true
    logsIn(user, "password 1") shouldBe false
    users.checkToken(token, renewable = true) shouldBe TokenCheck.Refused(clearsCookie = false)
    val fresh = users.claimsOf(user.persistedId).get
    users.checkToken(testApp.service.paseto.createToken(fresh), renewable = false) shouldBe
      TokenCheck.Current(fresh)
  }

  test("The Users page lists every account for an Admin, and for anyone else the accounts it created, in Libraries it sees") {

    /**
     * Setup:
     *
     * An Admin, two Libraries, and a User holding Create Users in the first alone; the User creates an account with access to the
     * first, and the Admin grants that account the second as well; the Admin creates an account of its own.
     *
     * Assertions:
     *
     * The Admin sees every account, its own creation and the User's among them, with every grant. The User sees the one account
     * it created, with its grant in the first Library alone.
     *
     * Edge cases:
     *
     * Once the Admin makes that account an Admin, the User sees it no more, and the Admin still does.
     */
    val admin = persistAdmin()
    val (first, second) = (addLibrary(), addLibrary())
    val creator =
      users.create(admin.persistedId, model(), "password 1", Map(first.persistedId -> Permissions.of(Permission.CreateUsers)))
    val created = users.create(creator.persistedId, model(), "password 1", Map(first.persistedId -> Permissions.none))
    users.update(
      admin.persistedId,
      created,
      Map(first.persistedId -> Permissions.none, second.persistedId -> Permissions.none),
      stateOf(created))
    val adminsOwn = users.create(admin.persistedId, model(), "password 1", Map(second.persistedId -> Permissions.none))

    val forAdmin =
      users.accountsVisibleTo(admin).map((account, grants) => account.persistedId -> grants.map(_.repositoryId).toSet).toMap
    forAdmin(created.persistedId) shouldBe Set(first.persistedId, second.persistedId)
    (forAdmin.keySet should contain).allOf(admin.persistedId, creator.persistedId, adminsOwn.persistedId)

    users.accountsVisibleTo(creator).map((account, grants) => account.persistedId -> grants.map(_.repositoryId)) shouldBe
      List(created.persistedId -> List(first.persistedId))

    users.update(admin.persistedId, created.copy(accountType = AccountType.Admin), Map.empty, stateOf(created))
    users.accountsVisibleTo(creator) shouldBe empty
    users.accountsVisibleTo(admin).map(_._1.persistedId) should contain(created.persistedId)
  }
}
