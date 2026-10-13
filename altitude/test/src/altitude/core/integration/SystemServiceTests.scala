package altitude.core.integration

import altitude.test.TestFocus
import java.util.Base64
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.util.Query

@DoNotDiscover class SystemServiceTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  test("Initialize system") {

    /**
     * Setup:
     *
     * The common setup's repository and its owner, with the request context cleared, then a system initialization with a new
     * admin, a repository named "My Repository" and a password.
     *
     * Assertions:
     *
     * The system metadata and the app both report the system initialized, initialization adds exactly one repository and one user
     * next to the common setup's, and the new admin becomes the request context's account.
     */
    RequestContext.clear()
    RequestContext.account.value shouldBe None

    val userModel = testContext.makeAdminUser()

    testApp.service.system.initializeSystem(repositoryName = "My Repository", adminModel = userModel, password = "password3000")

    val systemMetadata = testApp.service.system.readMetadata

    systemMetadata.isInitialized shouldBe true
    testApp.isInitialized shouldBe true

    val repos = testApp.service.repository.query(new Query())
    val users = testApp.service.user.query(new Query())

    /**
     * !!!! NOTE that since the common test setup creates a repository and its owner (a regular user), we should have 2 records in
     * each table - the one we created in the test setup and the one created by the system initialization test.
     */
    repos.records.size shouldBe 2
    users.records.size shouldBe 2

    val adminUser = RequestContext.account.value
    adminUser.get.email shouldBe userModel.email
    adminUser.get.accountType shouldBe AccountType.Admin
  }

  /** The signing key the system row holds, decoded, if it holds one */
  private def storedSigningKey: Option[Array[Byte]] =
    testApp.txManager.asReadOnly(testApp.DAO.systemMetadata.getSigningKey).map(Base64.getDecoder.decode)

  test("A database without a signing key gets one created and stored") {

    /**
     * Setup:
     *
     * The system row with its signing key cleared, as a database is before its first start with this column, then the signing key
     * asked for.
     *
     * Assertions:
     *
     * The key is 32 bytes, and the system row now holds it.
     */
    testApp.txManager.withTransaction(update("UPDATE system SET signing_key = NULL WHERE id = 1"))
    storedSigningKey shouldBe None

    val key = testApp.service.system.loadOrCreateSigningKey()

    key.length shouldBe 32
    storedSigningKey.value shouldBe key
  }

  test("The signing key is read back as stored, not generated again") {

    /**
     * Setup:
     *
     * The signing key asked for twice.
     *
     * Assertions:
     *
     * Both calls return the same key, the one the system row holds.
     */
    val key = testApp.service.system.loadOrCreateSigningKey()

    testApp.service.system.loadOrCreateSigningKey() shouldBe key
    storedSigningKey.value shouldBe key
  }

  test("A stored signing key is never replaced") {

    /**
     * Setup:
     *
     * A signing key in the system row, then another key written over it through the DAO, as a second instance that lost the race
     * to create the key would.
     *
     * Assertions:
     *
     * The write reports that it wrote nothing, and the system row still holds the first key.
     */
    val key = testApp.service.system.loadOrCreateSigningKey()
    val other = Base64.getEncoder.encodeToString(Array.fill[Byte](32)(7))

    testApp.txManager.withTransaction(testApp.DAO.systemMetadata.setSigningKey(other)) shouldBe false
    storedSigningKey.value shouldBe key
  }

  test("An instance that finds no signing key while another stores one adopts the stored key") {

    /**
     * Setup:
     *
     * The system row with its signing key cleared. One transaction stores a key and holds the row until the signing key, asked
     * for in another transaction, waits for it: on PostgreSQL that one has found no key and waits to store its own, on SQLite it
     * waits for the write connection.
     *
     * Assertions:
     *
     * The waiting transaction gets the key the first one stored, which the system row still holds.
     */
    testApp.txManager.withTransaction(update("UPDATE system SET signing_key = NULL WHERE id = 1"))
    val stored = Array.fill[Byte](32)(7)
    var adopted = Array.emptyByteArray

    contend(
      testApp.DAO.systemMetadata.setSigningKey(Base64.getEncoder.encodeToString(stored)): Unit,
      { adopted = testApp.service.system.loadOrCreateSigningKey() })

    adopted shouldBe stored
    storedSigningKey.value shouldBe stored
  }
}
