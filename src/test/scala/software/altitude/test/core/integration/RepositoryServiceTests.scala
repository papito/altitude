package software.altitude.test.core.integration

import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.contain
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Util
import software.altitude.core.models.Repository
import software.altitude.core.{Const => C}
import software.altitude.test.core.IntegrationTestCore

@DoNotDiscover class RepositoryServiceTests(val config: Map[String, Any]) extends IntegrationTestCore {

  test("create repository") {
    val repo: Repository = altitude.service.repository.addRepository(
      name = Util.randomStr(),
      fileStoreType = C.FileStoreType.FS,
      owner = testContext.user)

    repo.fileStoreConfig.keys should contain(C.Repository.Config.PATH)

    val storedRepo: Repository = altitude.service.repository.getById(repo.persistedId)
    storedRepo.name shouldEqual repo.name
    storedRepo.fileStoreConfig.keys should contain(C.Repository.Config.PATH)
    storedRepo.createdAt should not be None
  }
}
