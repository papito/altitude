package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldEqual

import altitude.core.Api
import altitude.core.dao.jdbc.BaseDao
import altitude.core.service.UrlService

@DoNotDiscover class UrlServiceTests extends funsuite.AnyFunSuite with TestFocus {
  val urlService = new UrlService

  val personId: String = BaseDao.genId
  val repoId = "1"

  test("Browser view URL has the query string in the given order and the fragment") {
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
    val url = urlService.getBrowserViewUrl(
      queryParams = Seq(Api.Field.Search.PERSON_ID -> personId),
      s"http://localhost:8080/r/$repoId?view=triage")

    url shouldEqual s"/r/$repoId?personId=$personId"
  }

  test("Browser view URL has no fragment when the browser URL is not known") {
    val url = urlService.getBrowserViewUrl(queryParams = Seq(Api.Field.Search.SORT -> "sort_field0"), browserUrl = null)

    url shouldEqual s"/r/$repoId?sort=sort_field0"
  }

  test("Browser view URL encodes query values") {
    val url = urlService.getBrowserViewUrl(queryParams = Seq(Api.Field.Search.QUERY_TEXT -> "cats & dogs"), browserUrl = null)

    url shouldEqual s"/r/$repoId?q=cats+%26+dogs"
  }
}
