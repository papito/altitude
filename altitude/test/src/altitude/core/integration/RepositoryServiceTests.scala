package altitude.core.integration

import altitude.core
import java.util.Locale
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ should, shouldBe, shouldEqual }

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.util.NameKey
import altitude.core.util.Util

@DoNotDiscover class RepositoryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addLibrary(name: String, owner: User = testContext.user): Repository =
    testApp.service.repository.addRepository(name = name, fileStoreType = C.StorageEngineName.FS, owner = owner)

  /** The Libraries of the whole instance holding a name key: every bundle's tests add Libraries to one database */
  private def librariesKeyed(nameKey: String): List[Repository] =
    testApp.service.repository.getAll.filter(_.nameKey == nameKey)

  private def persistAdmin(): User = testContext.persistUser(Some(testContext.makeAdminUser()))

  test("create repository") {

    /**
     * Setup:
     *
     * A second repository with a random name on the file-system store, owned by the common setup's user.
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
     * Its stored name key is that name in lower case.
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
     * characters, ones holding a control character between words or at the end, ones holding an invisible format character (a
     * zero-width space, a right-to-left override, a directional isolate pair, a byte order mark, a soft hyphen), and ones holding
     * an unpaired surrogate, high or low.
     *
     * Assertions:
     *
     * Each add fails with a `ValidationException` that gives the name field its error, the control characters' for the format
     * characters and the unpaired surrogates too, and no Library is added.
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

  test("A second Library whose name differs only in case, spacing or Unicode composition is a duplicate, for any owner") {

    /**
     * Setup:
     *
     * An Admin's Library named with a precomposed "é", and a second Admin.
     *
     * Assertions:
     *
     * Adding a Library under the same name in other capitals for the same Admin, and under it with doubled spaces and a
     * decomposed "é" for the other Admin, each fails with a `DuplicateException` saying a library of that name, as normalized,
     * already exists. The instance keeps one Library of that name.
     */
    val owner = persistAdmin()
    val otherAdmin = persistAdmin()
    val suffix = Util.randomStr()
    val original = addLibrary(s"Caf\u00E9 Smith $suffix", owner)

    val attempts = List(
      (s"CAF\u00C9 SMITH ${suffix.toUpperCase}", owner, s"CAF\u00C9 SMITH ${suffix.toUpperCase}"),
      (s"  Cafe\u0301   Smith $suffix", otherAdmin, s"Caf\u00E9 Smith $suffix")
    )

    for ((name, attemptOwner, normalized) <- attempts) {
      withClue(s"[$name]: ") {
        val ex = intercept[DuplicateException](addLibrary(name, attemptOwner))
        ex.message shouldBe Some(s"A library named \u201C$normalized\u201D already exists")
      }
    }

    librariesKeyed(original.nameKey).map(_.persistedId) shouldBe List(original.persistedId)
  }

  test("A second Library whose name differs only by case fold or compatibility form is a duplicate") {

    /**
     * Setup:
     *
     * An Admin's Library named "Weiß", under a random suffix.
     *
     * Assertions:
     *
     * Adding a Library named "WEISS" (the case fold of "ß") or "Ｗeiß" with a full-width "W" (the compatibility form), under the
     * same suffix, fails with a `DuplicateException` saying a library of that name, as typed, already exists. The instance keeps
     * one Library of that name.
     */
    val owner = persistAdmin()
    val suffix = Util.randomStr()
    val original = addLibrary(s"Wei\u00DF $suffix", owner)

    for (name <- List(s"WEISS $suffix", s"\uFF37ei\u00DF $suffix")) {
      withClue(s"[$name]: ") {
        val ex = intercept[DuplicateException](addLibrary(name, owner))
        ex.message shouldBe Some(s"A library named \u201C$name\u201D already exists")
      }
    }

    librariesKeyed(original.nameKey).map(_.persistedId) shouldBe List(original.persistedId)
  }

  test("Two transactions adding one Library name at once leave one Library") {

    /**
     * Setup:
     *
     * Two Admins adding a Library under one name, in other capitals, from two transactions: the first holds its transaction open
     * until the second waits for it.
     *
     * Assertions:
     *
     * The second add fails with a `DuplicateException` once the first commits, and the instance holds one Library of that name,
     * the first's. The database decides it: on PostgreSQL both transactions insert before either commits.
     */
    val owner = persistAdmin()
    val otherAdmin = persistAdmin()
    val name = s"Race ${Util.randomStr()}"

    intercept[DuplicateException] {
      contend(
        first = addLibrary(name, owner),
        second = addLibrary(name.toUpperCase, otherAdmin)
      )
    }

    val libraries = librariesKeyed(NameKey.of(name))
    libraries.map(_.ownerAccountId) shouldBe List(owner.persistedId)
  }

  test("An account's Libraries are listed alone, by name: case-insensitively and naturally") {

    /**
     * Setup:
     *
     * An Admin's four Libraries, added out of order and named under one random prefix "client 10", "Client 2", "alpha" and
     * "Client 100", and another Admin's Library under the same prefix.
     *
     * Assertions:
     *
     * The Admin's Libraries are listed, and only those, as "alpha", "Client 2", "client 10", "Client 100".
     */
    val owner = persistAdmin()
    val otherAdmin = persistAdmin()
    val prefix = Util.randomStr()
    for (name <- List("client 10", "Client 2", "alpha", "Client 100")) addLibrary(s"$prefix $name", owner)
    addLibrary(s"$prefix Client 3", otherAdmin)

    testApp.service.repository.getOwnedBy(owner).map(_.name) shouldBe
      List("alpha", "Client 2", "client 10", "Client 100").map(name => s"$prefix $name")
  }

  test("A user lands in their last active Library") {

    /**
     * Setup:
     *
     * An Admin's three Libraries, the last active one being neither the first by name nor the newest.
     *
     * Assertions:
     *
     * The landing Library is the last active one, which stays recorded.
     */
    val owner = persistAdmin()
    val prefix = Util.randomStr()
    addLibrary(s"$prefix A", owner)
    val lastActive = addLibrary(s"$prefix B", owner)
    addLibrary(s"$prefix C", owner)
    testApp.service.user.rememberLastActiveRepository(owner, lastActive.persistedId)

    testApp.service.repository.landingRepositoryOf(owner).map(_.persistedId) shouldBe Some(lastActive.persistedId)
    testApp.service.user.getById(owner.persistedId).lastActiveRepoId shouldBe Some(lastActive.persistedId)
  }

  test("A last active Library that is unset, missing or another account's falls back to the first owned by name, recorded") {

    /**
     * Setup:
     *
     * An Admin's Libraries "Client 10" and "Client 2" (under one random prefix), the second added last, and another Admin's
     * Library.
     *
     * Assertions:
     *
     * With no last active Library, with one that names no Library, and with the other Admin's, the landing Library is "Client 2",
     * and it is recorded as the last active one.
     */
    val owner = persistAdmin()
    val otherAdmin = persistAdmin()
    val prefix = Util.randomStr()
    addLibrary(s"$prefix Client 10", owner)
    val firstByName = addLibrary(s"$prefix Client 2", owner)
    val foreign = addLibrary(s"$prefix Client 1", otherAdmin)

    def landsInFirstByName(): Unit = {
      testApp.service.repository.landingRepositoryOf(owner).map(_.persistedId) shouldBe Some(firstByName.persistedId)
      testApp.service.user.getById(owner.persistedId).lastActiveRepoId shouldBe Some(firstByName.persistedId)
    }

    testApp.service.user.getById(owner.persistedId).lastActiveRepoId shouldBe None
    landsInFirstByName()

    for (lastActiveId <- List(BaseDao.genId, foreign.persistedId)) {
      withClue(s"[$lastActiveId]: ") {
        testApp.service.user.rememberLastActiveRepository(owner, lastActiveId)
        landsInFirstByName()
      }
    }
  }

  test("A user who owns no Library has none to land in") {

    /**
     * Setup:
     *
     * A User who owns no Library, with no last active one.
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
