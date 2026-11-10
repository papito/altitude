package altitude.core.integration

import altitude.core
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ should, shouldBe, shouldEqual }

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.ValidationException
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.util.Util

@DoNotDiscover class RepositoryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def addLibrary(name: String, owner: User = testContext.user): Repository =
    testApp.service.repository.addRepository(name = name, fileStoreType = C.StorageEngineName.FS, owner = owner)

  /** The Libraries of the whole instance holding a name key: every bundle's tests add Libraries to one database */
  private def librariesKeyed(nameKey: String): List[Repository] =
    testApp.service.repository.getAll.filter(_.nameLowercase == nameKey)

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

  test("A Library's name is stored trimmed, with its whitespace collapsed and in NFC, beside its lowercased key") {

    /**
     * Setup:
     *
     * A Library added under a name typed with spaces around it, a tab, a newline and a no-break space between its words, and an
     * "é" written as "e" and a combining acute accent.
     *
     * Assertions:
     *
     * The Library is returned and reads back with the name trimmed, each run of whitespace one space, and the "é" precomposed.
     * Its stored name key is that name lowercased.
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
      query(s"SELECT ${FieldConst.Repository.NAME_LC} FROM repository WHERE id = ?", repo.persistedId)
        .head(FieldConst.Repository.NAME_LC)
    }
    storedKey shouldBe s"\u00F6land smith caf\u00E9 ${suffix.toLowerCase}"
  }

  test("A Library name that is empty, too short, too long or holds a control character fails, naming the field") {

    /**
     * Setup:
     *
     * Library names that are empty, only whitespace, one character, one character once composed ("e" and a combining accent), 81
     * characters, and ones holding a control character between words or at the end.
     *
     * Assertions:
     *
     * Each add fails with a `ValidationException` that gives the name field its error, and no Library is added.
     *
     * Edge cases:
     *
     * A name typed longer than 80 characters that is 80 once its whitespace is collapsed is accepted.
     */
    val refused = List(
      "" -> "Enter a name for the library",
      " \t\n " -> "Enter a name for the library",
      "a" -> "Use at least 2 characters",
      "e\u0301" -> "Use at least 2 characters",
      "x" * 81 -> "Use at most 80 characters",
      "Client\u0007Smith" -> "Remove the control characters",
      s"Client ${Util.randomStr()}\u0000" -> "Remove the control characters"
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

    librariesKeyed(original.nameLowercase).map(_.persistedId) shouldBe List(original.persistedId)
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

    val libraries = librariesKeyed(name.toLowerCase)
    libraries.map(_.ownerAccountId) shouldBe List(owner.persistedId)
  }
}
