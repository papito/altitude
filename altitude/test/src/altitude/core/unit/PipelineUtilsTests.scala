package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.RequestContext
import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineUtils

@DoNotDiscover class PipelineUtilsTests extends funsuite.AnyFunSuite with TestFocus {

  private val repository = Repository(id = Some("repository"), name = "Library", rootFolderId = "root", fileStoreType = "fs")
  private val account =
    User(id = Some("account"), email = "jane@example.com", username = "jane", name = "Jane", accountType = AccountType.User)

  test("A pipeline stage's work runs with the context's Permissions, not the thread's") {

    /**
     * Setup:
     *
     * A thread whose request context holds every Permission, and pipeline contexts of an account holding Import and of no
     * account.
     *
     * Assertions:
     *
     * The work run in the account's context sees Import alone, and in the context of no account no Permission; the thread's own
     * Permissions are back once the work ends.
     */
    RequestContext.permissions.withValue(Permissions.all) {
      val importOnly = Permissions.of(Permission.Import)
      PipelineUtils.withContext(PipelineContext(repository, Some(account), importOnly))(RequestContext.permissions.value) shouldBe
        importOnly
      PipelineUtils.withContext(PipelineContext(repository, None))(RequestContext.permissions.value) shouldBe Permissions.none
      RequestContext.permissions.value shouldBe Permissions.all
    }
  }
}
