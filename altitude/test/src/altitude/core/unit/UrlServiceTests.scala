package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldEqual

import altitude.core.Api
import altitude.core.Const as C
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Repository
import altitude.core.service.UrlService
import altitude.core.util.Util

@DoNotDiscover class UrlServiceTests extends funsuite.AnyFunSuite with TestFocus {
  val urlService = new UrlService

  val personId: String = BaseDao.genId
  val repoId = "1"

  // A browser view URL is scoped to the request's repository, so this suite has to supply one of its own
  RequestContext.repository.value = Some(
    new Repository(
      id = Some(repoId),
      name = "repo name",
      ownerAccountId = Util.randomStr(),
      rootFolderId = "1",
      fileStoreConfig = Map(),
      fileStoreType = C.StorageEngineName.FS))

  test("Browser view URL has the query string in the given order and the fragment") {

    /**
     * Setup:
     *
     * The suite's own repository "1" in the request context, a person ID and a sort as the search parameters, and a browser URL
     * that ends in an "#albums" tab fragment.
     *
     * Assertions:
     *
     * The URL is the repository path with the parameters in the order given, followed by the browser URL's fragment.
     */
    val tabSelected = "albums"
    val sortValue = "sort_field0"
    val url = urlService.getBrowserViewUrl(
      queryParams = Seq(
        Api.Field.Search.PERSON_ID -> personId,
        Api.Field.Search.SORT -> sortValue
      ),
      s"http://localhost:8080/r/$repoId?#$tabSelected")

    url shouldEqual s"/r/$repoId?personId=$personId&sort=$sortValue#$tabSelected"
  }

  test("Browser view URL has no fragment when the browser URL has none") {

    /**
     * Setup:
     *
     * A person ID as the only search parameter and a browser URL with a query string of its own but no fragment.
     *
     * Assertions:
     *
     * The URL carries only the search parameters - none of the browser URL's query - and no fragment.
     */
    val url = urlService.getBrowserViewUrl(
      queryParams = Seq(Api.Field.Search.PERSON_ID -> personId),
      s"http://localhost:8080/r/$repoId?view=triage")

    url shouldEqual s"/r/$repoId?personId=$personId"
  }

  test("Browser view URL has no fragment when the browser URL is not known") {

    /**
     * Setup:
     *
     * A sort as the only search parameter and no browser URL at all (null).
     *
     * Assertions:
     *
     * The URL is built from the parameters alone, with no fragment.
     */
    val url = urlService.getBrowserViewUrl(queryParams = Seq(Api.Field.Search.SORT -> "sort_field0"), browserUrl = null)

    url shouldEqual s"/r/$repoId?sort=sort_field0"
  }

  test("Browser view URL encodes query values") {

    /**
     * Setup:
     *
     * Search text with a space and an ampersand ("cats & dogs").
     *
     * Assertions:
     *
     * The value is form-encoded, so the ampersand cannot split it into a second parameter.
     */
    val url = urlService.getBrowserViewUrl(queryParams = Seq(Api.Field.Search.QUERY_TEXT -> "cats & dogs"), browserUrl = null)

    url shouldEqual s"/r/$repoId?q=cats+%26+dogs"
  }
}
