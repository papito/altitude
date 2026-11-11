package altitude.core.integration

import altitude.core
import java.sql.SQLException
import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ an, contain, should, shouldBe, shouldEqual, thrownBy }

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.ValidationException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
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
}
