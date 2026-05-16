package altitude.core.integration

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.util.Query

@DoNotDiscover class SystemServiceTests(override val testApp: Altitude) extends IntegrationTestCore with TestFocus {

  test("Initialize system") {
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
     * !!!! NOTE that since the common test setup creates a repository and an admin user, we should have 2 records in each table -
     * the one we created in the test setup and the one created by the system initialization test.
     */
    repos.records.size shouldBe 2
    users.records.size shouldBe 2

    val adminUser = RequestContext.account.value
    adminUser.get.email shouldBe userModel.email
    adminUser.get.accountType shouldBe AccountType.Admin
  }
}
