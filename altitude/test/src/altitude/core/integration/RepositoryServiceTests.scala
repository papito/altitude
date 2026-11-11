package altitude.core.integration

import altitude.core
import java.sql.SQLException
import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ an, contain, empty, should, shouldBe, shouldEqual, thrownBy }

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.service.RepositoryService
import altitude.core.util.NameKey
import altitude.core.util.NaturalOrder
import altitude.core.util.Util

@DoNotDiscover class RepositoryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addLibrary(name: String): Repository =
    testApp.service.repository.addRepository(name = name, fileStoreType = C.StorageEngineName.FS)

  /** A Library under `name` that `user` holds a grant of every Permission in */
  private def addLibraryFor(name: String, user: User): Repository = {
    val library = addLibrary(name)
    testContext.grant(user, library)
    library
  }

  /** The Libraries of the whole instance holding a name key: every bundle's tests add Libraries to one database */
  private def librariesKeyed(nameKey: String): List[Repository] =
    testApp.service.repository.getAll.filter(_.nameKey == nameKey)

  /** Whether the account's login token opens the Library, as the claims built for it from the database say */
  private def canSee(account: User, library: Repository): Boolean =
    testApp.service.user.claimsOf(account.persistedId).exists(_.permissionsIn(library.persistedId).isDefined)

  private def persistAdmin(): User = testContext.persistUser(Some(testContext.makeAdminUser()))

  test("create repository") {

    /**
     * Setup:
     *
     * A second repository with a random name on the file-system store.
     *
     * Assertions:
     *
     * The repository reads back with its name and a creation time, and no update time yet.
     */
    val repo: Repository = addLibrary(Util.randomStr())

    val storedRepo: Repository = testApp.service.repository.getById(repo.persistedId)
    storedRepo.name shouldEqual repo.name
    storedRepo.createdAt should not be None
    storedRepo.updatedAt should be(None)
  }

  test("A Library's name is stored trimmed, with its whitespace collapsed and in NFC, beside its name key") {

    /**
     * Setup:
     *
     * A Library added under a name typed with spaces around it, a tab, a newline and a no-break space between its words, and an
     * "é" written as "e" and a combining acute accent.
     *
     * Assertions:
     *
     * The Library is returned and reads back with the name trimmed, each run of whitespace one space, and the "é" precomposed.
     * Its stored name key is that name's name key (`NameKey.of`).
     *
     * Edge cases:
     *
     * The key folds the non-ASCII capitals too ("Ö" to "ö"), which SQLite's `lower()` would leave.
     */
    val suffix = Util.randomStr()
    val repo = addLibrary(s"  \u00D6land \t\n Smith\u00A0 Cafe\u0301 $suffix  ")

    val expected = s"\u00D6land Smith Caf\u00E9 $suffix"
    repo.name shouldBe expected
    testApp.service.repository.getById(repo.persistedId).name shouldBe expected

    val storedKey = testApp.txManager.asReadOnly {
      query(s"SELECT ${FieldConst.Repository.NAME_KEY} FROM repository WHERE id = ?", repo.persistedId)
        .head(FieldConst.Repository.NAME_KEY)
    }
    storedKey shouldBe s"\u00F6land smith caf\u00E9 ${suffix.toLowerCase(Locale.ROOT)}"
  }

  test(
    "A Library name that is empty, too short, too long, or holds a control or format character or an unpaired surrogate fails") {

    /**
     * Setup:
     *
     * Library names that are empty, only whitespace, one character, one character once composed ("e" and a combining accent), 81
     * characters, 27 of U+FB2C (81 characters once in NFC), 70 of U+FDFA (a name key NFKC spells out to 2310 bytes), ones holding
     * a control character between words or at the end, ones holding an invisible format character (a zero-width space, a
     * right-to-left override, a directional isolate pair, a byte order mark, a soft hyphen), and ones holding an unpaired
     * surrogate, high or low.
     *
     * Assertions:
     *
     * Each add fails with a `ValidationException` that gives the name field its error, the control characters' for the format
     * characters and the unpaired surrogates too, and the name key error for the name whose key is too long, and no Library is
     * added.
     *
     * Edge cases:
     *
     * A name typed longer than 80 characters that is 80 once its whitespace is collapsed is accepted, and so are the two join
     * controls: an emoji sequence joined by ZERO WIDTH JOINER and a Persian word holding ZERO WIDTH NON-JOINER.
     */
    val controls = "Remove the control characters"
    val refused = List(
      "" -> "Enter a name for the library",
      " \t\n " -> "Enter a name for the library",
      "a" -> "Use at least 2 characters",
      "e\u0301" -> "Use at least 2 characters",
      "x" * 81 -> "Use at most 80 characters",
      lengthenedByNfc(27) -> "Use at most 80 characters",
      longKeyName(70) -> C.Msg.Err.NAME_KEY_TOO_LONG,
      "Client\u0007Smith" -> controls,
      s"Client ${Util.randomStr()}\u0000" -> controls,
      "Client\u200BSmith" -> controls,
      "Client \u202Ehtims" -> controls,
      "\u2066Client\u2069 Smith" -> controls,
      "\uFEFFClient Smith" -> controls,
      "Cli\u00ADent Smith" -> controls,
      "Client\uD800 Smith" -> controls,
      "Client \uDC00Smith" -> controls
    )

    val librariesBefore = testApp.service.repository.getAll.size

    for ((name, error) <- refused) {
      withClue(s"[$name]: ") {
        val ex = intercept[ValidationException](addLibrary(name))
        ex.errors.get(FieldConst.Repository.NAME) shouldBe Some(error)
      }
    }

    testApp.service.repository.getAll.size shouldBe librariesBefore

    val collapsedTo80 = addLibrary(s"${Util.randomStr(40)}     ${Util.randomStr(39)}")
    collapsedTo80.name.length shouldBe 80

    // A family emoji, three people joined by ZERO WIDTH JOINER, and "I want" in Persian, whose ZERO WIDTH NON-JOINER is spelling
    for (
      joined <- List(
        s"Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 ${Util.randomStr()}",
        s"\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645 ${Util.randomStr()}"
      )
    ) {
      addLibrary(joined).name shouldBe joined
    }
  }

  test("A second Library whose name differs only in case, spacing or Unicode composition is a duplicate") {

    /**
     * Setup:
     *
     * A Library named with a precomposed "é".
     *
     * Assertions:
     *
     * Adding a Library under the same name in other capitals, and under it with doubled spaces and a decomposed "é", each fails
     * with a `DuplicateException` saying a library of that name, as normalized, already exists. The instance keeps one Library of
     * that name.
     */
    val suffix = Util.randomStr()
    val original = addLibrary(s"Caf\u00E9 Smith $suffix")

    val attempts = List(
      (s"CAF\u00C9 SMITH ${suffix.toUpperCase}", s"CAF\u00C9 SMITH ${suffix.toUpperCase}"),
      (s"  Cafe\u0301   Smith $suffix", s"Caf\u00E9 Smith $suffix")
    )

    for ((name, normalized) <- attempts) {
      withClue(s"[$name]: ") {
        val ex = intercept[DuplicateException](addLibrary(name))
        ex.message shouldBe Some(s"A library named \u201C$normalized\u201D already exists")
      }
    }

    librariesKeyed(original.nameKey).map(_.persistedId) shouldBe List(original.persistedId)
  }

  test("A second Library whose name differs only by case fold or compatibility form is a duplicate") {

    /**
     * Setup:
     *
     * A Library named "Weiß", under a random suffix.
     *
     * Assertions:
     *
     * Adding a Library named "WEISS" (the case fold of "ß") or "Ｗeiß" with a full-width "W" (the compatibility form), under the
     * same suffix, fails with a `DuplicateException` saying a library of that name, as typed, already exists. The instance keeps
     * one Library of that name.
     */
    val suffix = Util.randomStr()
    val original = addLibrary(s"Wei\u00DF $suffix")

    for (name <- List(s"WEISS $suffix", s"\uFF37ei\u00DF $suffix")) {
      withClue(s"[$name]: ") {
        val ex = intercept[DuplicateException](addLibrary(name))
        ex.message shouldBe Some(s"A library named \u201C$name\u201D already exists")
      }
    }

    librariesKeyed(original.nameKey).map(_.persistedId) shouldBe List(original.persistedId)
  }

  test("Two transactions adding one Library name at once leave one Library") {

    /**
     * Setup:
     *
     * Two adds of a Library under one name, in other capitals, from two transactions: the first holds its transaction open until
     * the second waits for it.
     *
     * Assertions:
     *
     * The second add fails with a `DuplicateException` once the first commits, and the instance holds one Library of that name,
     * the first's, as it spelled it. The database decides it: on PostgreSQL both transactions insert before either commits.
     */
    val name = s"Race ${Util.randomStr()}"

    intercept[DuplicateException] {
      contend(
        first = addLibrary(name),
        second = addLibrary(name.toUpperCase)
      )
    }

    librariesKeyed(NameKey.of(name)).map(_.name) shouldBe List(name)
  }

  test("A User sees the Libraries it holds a grant in, alone, by name: case-insensitively and naturally") {

    /**
     * Setup:
     *
     * A User's grants in four Libraries, added out of order and named under one random prefix "client 10", "Client 2", "alpha"
     * and "Client 100", a Guest's grant in a fifth under the same prefix, and a sixth that grants nobody.
     *
     * Assertions:
     *
     * The User's Libraries are listed, and only those, as "alpha", "Client 2", "client 10", "Client 100". The User can see each
     * of them and neither of the other two.
     */
    val user = testContext.persistUser()
    val guest = testContext.persistUser(Some(testContext.makeUser().copy(accountType = AccountType.Guest)))
    val prefix = Util.randomStr()
    val granted = for (name <- List("client 10", "Client 2", "alpha", "Client 100")) yield addLibraryFor(s"$prefix $name", user)
    val guests = addLibrary(s"$prefix Client 3")
    testContext.grant(guest, guests, Permissions.none)
    val nobodys = addLibrary(s"$prefix Client 4")

    testApp.service.repository.getAccessibleTo(user).map(_.name) shouldBe
      List("alpha", "Client 2", "client 10", "Client 100").map(name => s"$prefix $name")

    for (library <- granted) canSee(user, library) shouldBe true
    for (library <- List(guests, nobodys)) canSee(user, library) shouldBe false
  }

  test("An Admin sees every Library, with no grant") {

    /**
     * Setup:
     *
     * An Admin, a Library a User holds a grant in, and a Library that grants nobody.
     *
     * Assertions:
     *
     * The Admin's Libraries are every Library of the instance, by name, and the Admin can see both new ones. The Admin holds no
     * grant in either.
     */
    val admin = persistAdmin()
    val usersLibrary = addLibraryFor(s"Users ${Util.randomStr()}", testContext.users.head)
    val nobodys = addLibrary(s"Nobody's ${Util.randomStr()}")

    testApp.service.repository.getAccessibleTo(admin).map(_.persistedId) shouldBe
      testApp.service.repository.getAll.sortBy(_.name)(using NaturalOrder).map(_.persistedId)

    for (library <- List(usersLibrary, nobodys)) {
      canSee(admin, library) shouldBe true
      testApp.service.repository.getGrant(admin, library.persistedId) shouldBe None
    }
  }

  test("A grant set again replaces its Permissions; an Admin takes no grant, and a Guest no Permission") {

    /**
     * Setup:
     *
     * A User granted Import in a Library, then granted Recycle and Purge in it; an Admin and a Guest.
     *
     * Assertions:
     *
     * The User holds Recycle and Purge in the Library, in one grant. A grant to the Admin, and a grant of a Permission to the
     * Guest, fail with `IllegalOperationException` and write nothing.
     *
     * Edge cases:
     *
     * The Guest's grant of no Permission is written, and gives it access.
     */
    val user = testContext.persistUser()
    val library = addLibrary(s"Grants ${Util.randomStr()}")

    testContext.grant(user, library, Permissions.of(Permission.Import))
    testContext.grant(user, library, Permissions.of(Permission.Recycle, Permission.Purge))

    testApp.service.repository.getGrant(user, library.persistedId) shouldBe Some(
      Permissions.of(Permission.Recycle, Permission.Purge))
    testApp.txManager.asReadOnly {
      query("SELECT account_id FROM repository_grant WHERE repository_id = ?", library.persistedId).size
    } shouldBe 1

    val admin = persistAdmin()
    val guest = testContext.persistUser(Some(testContext.makeUser().copy(accountType = AccountType.Guest)))
    intercept[IllegalOperationException](testContext.grant(admin, library))
    intercept[IllegalOperationException](testContext.grant(guest, library, Permissions.of(Permission.Import)))
    testApp.service.repository.getGrant(admin, library.persistedId) shouldBe None
    testApp.service.repository.getGrant(guest, library.persistedId) shouldBe None

    testContext.grant(guest, library, Permissions.none)
    testApp.service.repository.getGrant(guest, library.persistedId) shouldBe Some(Permissions.none)
    canSee(guest, library) shouldBe true
  }

  test("Deleting an account deletes its grants and never a Library") {

    /**
     * Setup:
     *
     * A User with no assets, granted every Permission in a Library of its own and in the common setup's Library.
     *
     * Assertions:
     *
     * Once the account row is deleted, both Libraries are still there, with their rows, and no grant of the account is left.
     */
    val user = testContext.persistUser()
    val library = addLibraryFor(s"Survivor ${Util.randomStr()}", user)
    testContext.grant(user, testContext.repository)
    val rowsBefore = testContext.rowsOf(library)

    testApp.txManager.withTransaction(update("DELETE FROM account WHERE id = ?", user.persistedId))

    for (survivor <- List(library, testContext.repository)) {
      testApp.service.repository.getAll.map(_.persistedId) should contain(survivor.persistedId)
    }
    testContext.rowsOf(library) shouldBe rowsBefore
    testApp.txManager.asReadOnly {
      query("SELECT repository_id FROM repository_grant WHERE account_id = ?", user.persistedId)
    } shouldBe Nil
  }

  test("Deleting an account that owns an asset is refused, and both stay") {

    /**
     * Setup:
     *
     * A User who imported an asset into the common setup's Library, then a hand-written delete of the User's account row.
     *
     * Assertions:
     *
     * The database refuses the delete (`asset.owner_user_id` references the account, with no delete action), and the account and
     * the asset are still there, the asset still the User's.
     */
    val user = testContext.persistUser()
    val asset = testContext.insertAsset(testContext.makeAsset(user = Some(user)))

    an[SQLException] should be thrownBy testApp.txManager.withTransaction(
      update("DELETE FROM account WHERE id = ?", user.persistedId))

    testApp.service.user.getById(user.persistedId).persistedId shouldBe user.persistedId
    (testApp.service.asset.getById(asset.persistedId): Asset).ownerUserId shouldBe user.persistedId
  }

  test("A grant names a stored account and Library, and its account type is read from the store") {

    /**
     * Setup:
     *
     * A User whose stored account type is then changed to Admin by hand, an account ID that names no account, and a Library ID
     * that names no Library.
     *
     * Assertions:
     *
     * A grant to the User is refused as one to an Admin (`IllegalOperationException`), the type being read from the stored
     * account, not from the model the caller holds. A grant to the unknown account, and one in the unknown Library, are
     * `NotFoundException`. None writes a grant.
     */
    val user = testContext.persistUser()
    val library = addLibrary(s"Grants ${Util.randomStr()}")
    testApp.txManager.withTransaction(update("UPDATE account SET account_type = 'Admin' WHERE id = ?", user.persistedId))

    intercept[IllegalOperationException](testContext.grant(user, library))
    intercept[NotFoundException](testApp.service.repository.setGrant(BaseDao.genId, library.persistedId, Permissions.all))
    intercept[NotFoundException] {
      testApp.service.repository.setGrant(testContext.users.head.persistedId, BaseDao.genId, Permissions.all)
    }

    testApp.txManager.asReadOnly {
      query(
        "SELECT account_id FROM repository_grant WHERE repository_id = ? OR account_id = ?",
        library.persistedId,
        user.persistedId)
    } shouldBe Nil
  }

  test("A user lands in their last active Library") {

    /**
     * Setup:
     *
     * A User's grants in three Libraries, the last active one being neither the first by name nor the newest.
     *
     * Assertions:
     *
     * The landing Library is the last active one, which stays recorded.
     */
    val user = testContext.persistUser()
    val prefix = Util.randomStr()
    addLibraryFor(s"$prefix A", user)
    val lastActive = addLibraryFor(s"$prefix B", user)
    addLibraryFor(s"$prefix C", user)
    testApp.service.user.rememberLastActiveRepository(user, lastActive.persistedId)

    testApp.service.repository.landingRepositoryOf(user).map(_.persistedId) shouldBe Some(lastActive.persistedId)
    testApp.service.user.getById(user.persistedId).lastActiveRepoId shouldBe Some(lastActive.persistedId)
  }

  test("A last active Library that is unset, missing or one the user cannot see falls back to the first it can see by name") {

    /**
     * Setup:
     *
     * A User's grants in the Libraries "Client 10" and "Client 2" (under one random prefix), the second added last, and a Library
     * "Client 1" another User holds a grant in.
     *
     * Assertions:
     *
     * With no last active Library, with one that names no Library, and with the other User's, the landing Library is "Client 2",
     * and it is recorded as the last active one.
     */
    val user = testContext.persistUser()
    val other = testContext.persistUser()
    val prefix = Util.randomStr()
    addLibraryFor(s"$prefix Client 10", user)
    val firstByName = addLibraryFor(s"$prefix Client 2", user)
    val foreign = addLibraryFor(s"$prefix Client 1", other)

    def landsInFirstByName(): Unit = {
      testApp.service.repository.landingRepositoryOf(user).map(_.persistedId) shouldBe Some(firstByName.persistedId)
      testApp.service.user.getById(user.persistedId).lastActiveRepoId shouldBe Some(firstByName.persistedId)
    }

    testApp.service.user.getById(user.persistedId).lastActiveRepoId shouldBe None
    landsInFirstByName()

    for (lastActiveId <- List(BaseDao.genId, foreign.persistedId)) {
      withClue(s"[$lastActiveId]: ") {
        testApp.service.user.rememberLastActiveRepository(user, lastActiveId)
        landsInFirstByName()
      }
    }
  }

  test("A user who can see no Library has none to land in") {

    /**
     * Setup:
     *
     * A User who holds no grant, with no last active Library, beside the common setup's Library.
     *
     * Assertions:
     *
     * There is no landing Library, and nothing is recorded.
     */
    val user = testContext.persistUser()

    testApp.service.repository.landingRepositoryOf(user) shouldBe None
    testApp.service.user.getById(user.persistedId).lastActiveRepoId shouldBe None
  }

  /** An account whose new-Library switch is on, with `permissions` for a new Library */
  private def grantingNewLibraries(accountType: AccountType, permissions: Permissions): User =
    testContext.persistUser(
      Some(
        testContext
          .makeUser()
          .copy(accountType = accountType, grantsNewLibraries = true, newLibraryPermissions = permissions)))

  test(
    "A Library created grants its creator every Permission, and every account whose switch is on its new-Library Permissions") {

    /**
     * Setup:
     *
     * A User with the Create Libraries flag, a User and a Guest whose new-Library switch is on (Import and Curate own for the
     * User, access alone for the Guest), a User whose switch is off, and an Admin with the switch on. The first User creates a
     * Library, then the Admin creates another.
     *
     * Assertions:
     *
     * In the first Library the creator holds every Permission, the User with the switch on Import and Curate own, and the Guest
     * access alone; the User with the switch off and the Admin hold no grant. The versions of the accounts granted were bumped.
     * In the Admin's Library the two accounts with the switch on hold their grants, and the Admin, who sees it by type, none.
     */
    val creator = testContext.persistUser(Some(testContext.makeUser().copy(canCreateLibraries = true)))
    val user = grantingNewLibraries(AccountType.User, Permissions.of(Permission.Import, Permission.CurateOwn))
    val guest = grantingNewLibraries(AccountType.Guest, Permissions.none)
    val switchedOff = testContext.persistUser()
    val admin = testContext.persistUser(
      Some(testContext.makeAdminUser().copy(grantsNewLibraries = true, newLibraryPermissions = Permissions.all)))
    def versionOf(account: User) = testApp.service.accountVersions.current(account.persistedId)
    val versionsBefore = List(creator, user, guest).map(versionOf)

    val library = testApp.service.repository.addRepository(
      s"Created ${Util.randomStr()}",
      C.StorageEngineName.FS,
      creatorId = Some(creator.persistedId))

    def grantIn(library: Repository, account: User) = testApp.service.repository.getGrant(account, library.persistedId)
    grantIn(library, creator) shouldBe Some(Permissions.all)
    grantIn(library, user) shouldBe Some(Permissions.of(Permission.Import, Permission.CurateOwn))
    grantIn(library, guest) shouldBe Some(Permissions.none)
    grantIn(library, switchedOff) shouldBe None
    grantIn(library, admin) shouldBe None
    List(creator, user, guest).map(versionOf).zip(versionsBefore).foreach((after, before) => after should be > before)

    val adminsLibrary = testApp.service.repository.addRepository(
      s"Created ${Util.randomStr()}",
      C.StorageEngineName.FS,
      creatorId = Some(admin.persistedId))
    grantIn(adminsLibrary, user) shouldBe Some(Permissions.of(Permission.Import, Permission.CurateOwn))
    grantIn(adminsLibrary, guest) shouldBe Some(Permissions.none)
    grantIn(adminsLibrary, admin) shouldBe None
  }

  test("An account at its bound of grants is skipped by a new Library; a creator at its bound creates none") {

    /**
     * Setup:
     *
     * A User whose new-Library switch is on and a User with the Create Libraries flag, each holding grants in as many Libraries
     * as the bound allows. Another account creates a Library, then the User with the flag tries to.
     *
     * Assertions:
     *
     * The first Library is created, and the User at its bound is not granted it. The creator at its bound is refused with
     * `IllegalOperationException` (`TOO_MANY_GRANTS`), no Library of the name it gave exists, and the request's Library is still
     * the one it was before the refused add.
     */
    val full = grantingNewLibraries(AccountType.User, Permissions.of(Permission.Import))
    val creator = testContext.persistUser(Some(testContext.makeUser().copy(canCreateLibraries = true)))
    for (_ <- 1 to RepositoryService.MAX_GRANTS_PER_ACCOUNT) {
      val library = addLibrary(s"Bound ${Util.randomStr()}")
      testContext.grant(full, library)
      testContext.grant(creator, library)
    }

    val library = addLibrary(s"Past the bound ${Util.randomStr()}")
    testApp.service.repository.getGrant(full, library.persistedId) shouldBe None

    val name = s"Refused ${Util.randomStr()}"
    intercept[IllegalOperationException] {
      testApp.service.repository.addRepository(name, C.StorageEngineName.FS, creatorId = Some(creator.persistedId))
    }.getMessage shouldBe C.Msg.Err.TOO_MANY_GRANTS.format(RepositoryService.MAX_GRANTS_PER_ACCOUNT)
    librariesKeyed(NameKey.of(name)) shouldBe empty
    RequestContext.getRepository.persistedId shouldBe library.persistedId
  }

  if (testApp.dataSourceType == C.DbEngineName.POSTGRES)
    test("Two Libraries created at once, each granting the other's creator, lock their accounts in one order and both commit") {

      /**
       * Setup:
       *
       * Two accounts whose new-Library switch is on, the first and the second as the switch's read lists them, and a third with
       * the Create Libraries flag. A transaction holds the first account's row. The third account creates a Library, which waits
       * for that row; then the first account creates one, which waits too, having taken whatever came before it. Then the holding
       * transaction ends.
       *
       * Assertions:
       *
       * Both creates commit and neither fails: each takes the rows of the accounts it grants in one pass, in ID order, so the two
       * cannot wait on each other. In the first Library the first account holds its new-Library Permissions, in the second every
       * Permission, and the second account holds its new-Library Permissions in both.
       */
      val candidates = List.fill(2)(testContext.persistUser(
        Some(testContext.makeUser().copy(grantsNewLibraries = true, newLibraryPermissions = Permissions.of(Permission.Import)))))
      // The order an unordered read of the switch lists them in, which is the order a create took them in when it read them so
      val listed = testApp.txManager.asReadOnly {
        query("SELECT id FROM account WHERE grants_new_libraries = ?", Boolean.box(true)).map(_("id").toString.trim)
      }
      val List(first, second) = candidates.sortBy(account => listed.indexOf(account.persistedId))
      val third = testContext.persistUser(Some(testContext.makeUser().copy(canCreateLibraries = true)))

      val backends = new java.util.concurrent.ConcurrentHashMap[String, AnyRef]()
      def isWaiting(who: String): Boolean =
        Option(backends.get(who)).exists {
          pid =>
            testApp.txManager.asReadOnly {
              query("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid).exists(_("wait_event_type") == "Lock")
            }
        }
      def creating(who: String, creator: User, name: String) =
        onAnotherThread {
          testApp.txManager.withTransaction {
            backends.put(who, query("SELECT pg_backend_pid() AS pid").head("pid"))
            testApp.service.repository.addRepository(name, C.StorageEngineName.FS, creatorId = Some(creator.persistedId)): Unit
          }
        }
      def eventually(condition: => Boolean): Unit =
        org.scalatest.concurrent.Eventually.eventually(
          org.scalatest.concurrent.Eventually.timeout(org.scalatest.time.Span(30, org.scalatest.time.Seconds)),
          org.scalatest.concurrent.Eventually.interval(org.scalatest.time.Span(20, org.scalatest.time.Millis))
        ) {
          condition shouldBe true
        }

      val release = new java.util.concurrent.CountDownLatch(1)
      val holding = new java.util.concurrent.CountDownLatch(1)
      val (holder, _) = onAnotherThread {
        testApp.txManager.withTransaction {
          testApp.DAO.user.lockById(first.persistedId)
          holding.countDown()
          release.await(30, java.util.concurrent.TimeUnit.SECONDS): Unit
        }
      }
      holding.await(30, java.util.concurrent.TimeUnit.SECONDS) shouldBe true

      val (thirdName, firstName) = (s"By the third ${Util.randomStr()}", s"By the first ${Util.randomStr()}")
      val (byThird, thirdFailure) = creating("third", third, thirdName)
      eventually(isWaiting("third"))
      val (byFirst, firstFailure) = creating("first", first, firstName)
      eventually(isWaiting("first"))
      release.countDown()
      List(holder, byThird, byFirst).foreach(_.join())

      thirdFailure.get shouldBe None
      firstFailure.get shouldBe None
      val (thirdLibrary, firstLibrary) = (librariesKeyed(NameKey.of(thirdName)).head, librariesKeyed(NameKey.of(firstName)).head)
      testApp.service.repository.getGrant(first, thirdLibrary.persistedId) shouldBe Some(Permissions.of(Permission.Import))
      testApp.service.repository.getGrant(first, firstLibrary.persistedId) shouldBe Some(Permissions.all)
      for (library <- List(thirdLibrary, firstLibrary))
        testApp.service.repository.getGrant(second, library.persistedId) shouldBe Some(Permissions.of(Permission.Import))
    }
}
