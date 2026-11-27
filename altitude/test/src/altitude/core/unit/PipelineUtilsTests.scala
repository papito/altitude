package altitude.core.unit

import altitude.test.TestFocus
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.include
import org.scalatest.matchers.should.Matchers.should
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

  test("A pipeline stage's work runs with the context's grants, not the thread's") {

    /**
     * Setup:
     *
     * A thread whose request context holds a grant in another Library, and pipeline contexts of a User holding Import, of an
     * Admin, and of no account.
     *
     * Assertions:
     *
     * The User's work sees one grant, Import in the context's Library; the Admin's and the account-less work see none, an Admin
     * holding every Permission by type. The thread's own grants are back once the work ends.
     */
    val elsewhere = Map("another-library" -> Permissions.all)
    RequestContext.grants.withValue(elsewhere) {
      val importOnly = Permissions.of(Permission.Import)
      val admin = account.copy(accountType = AccountType.Admin)
      def grantsIn(ctx: PipelineContext) = PipelineUtils.withContext(ctx)(RequestContext.grants.value)

      grantsIn(PipelineContext(repository, Some(account), importOnly)) shouldBe Map("repository" -> importOnly)
      grantsIn(PipelineContext(repository, Some(admin), Permissions.all)) shouldBe Map.empty
      grantsIn(PipelineContext(repository, None)) shouldBe Map.empty
      RequestContext.grants.value shouldBe elsewhere
    }
  }

  test("A pooled thread started under a request's context does not work under it") {

    /**
     * Setup:
     *
     * A thread whose request context holds a Library, an account, every Permission and a grant, and a pool of one thread, started
     * by work handed to it from there.
     *
     * Assertions:
     *
     * Work on the pool's thread that binds nothing sees no Library, no account, no Permission and no grant: reading the Library
     * throws instead of answering the request's, so work that forgets to bind its context fails loudly rather than reach a
     * Library the request left behind.
     */
    val pool = Executors.newFixedThreadPool(1)
    try {
      val seen =
        RequestContext.repository.withValue(Some(repository)) {
          RequestContext.account.withValue(Some(account)) {
            RequestContext.permissions.withValue(Permissions.all) {
              RequestContext.grants.withValue(Map("repository" -> Permissions.all)) {
                // The pool starts its thread here, for this first task
                pool
                  .submit(new Callable[(Any, Any, Any, Any, String)] {
                    def call() = (
                      RequestContext.repository.value,
                      RequestContext.account.value,
                      RequestContext.permissions.value,
                      RequestContext.grants.value,
                      scala.util.Try(RequestContext.getRepository).failed.map(_.getMessage).getOrElse(""))
                  })
                  .get(10, TimeUnit.SECONDS)
              }
            }
          }
        }

      val (seenRepository, seenAccount, seenPermissions, seenGrants, refusal) = seen
      (seenRepository, seenAccount, seenPermissions, seenGrants) shouldBe (None, None, Permissions.none, Map.empty)
      refusal should include("No repository in context")
    } finally pool.shutdownNow(): Unit
  }
}
