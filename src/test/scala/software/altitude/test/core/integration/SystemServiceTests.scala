package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.models.AccountType
import software.altitude.core.util.Query
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class SystemServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("Initialize system") {
    // ditch the usual setup and start with a clean slate
    reset()

    // control shot
    RequestContext.account.value should be(None)

    val userModel = testContext.makeAdminUser()

    testApp.service.system.initializeSystem(
      repositoryName = "My Repository",
      adminModel=userModel,
      password = "password3000")

    val systemMetadata = testApp.service.system.readMetadata

    systemMetadata.isInitialized should be(true)
    testApp.isInitialized should be(true)

    val repos = testApp.service.repository.query(new Query())
    repos.records.size should be(1)

    val users = testApp.service.user.query(new Query())
    users.records.size should be(1)

    val adminUser = RequestContext.account.value
    adminUser.get.email should be (userModel.email)
    adminUser.get.accountType should be (AccountType.Admin)
  }
}
