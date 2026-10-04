package altitude.core.integration

import altitude.core
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.must.Matchers.not
import org.scalatest.matchers.should.Matchers.{ should, shouldEqual }

import altitude.core.Altitude
import altitude.core.Const as C
import altitude.core.models.Repository
import altitude.core.util.Util

@DoNotDiscover class RepositoryServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

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
    val repo: Repository = testApp.service.repository.addRepository(
      name = Util.randomStr(),
      fileStoreType = C.StorageEngineName.FS,
      owner = testContext.user)

    val storedRepo: Repository = testApp.service.repository.getById(repo.persistedId)
    storedRepo.name shouldEqual repo.name
    storedRepo.createdAt should not be None
    storedRepo.updatedAt should be(None)
  }
}
