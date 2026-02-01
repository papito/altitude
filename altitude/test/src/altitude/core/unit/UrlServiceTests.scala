package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import altitude.core.Api
import altitude.core.dao.jdbc.BaseDao
import altitude.core.service.UrlService
import altitude.test.TestFocus
import org.scalatest.matchers.should.Matchers.shouldEqual

@DoNotDiscover class UrlServiceTests extends funsuite.AnyFunSuite with TestFocus {
  val urlService = new UrlService

  val personId: String = BaseDao.genId
  val repoId = "1"

  test("Browser view URL has the combined query string and the fragment") {
    val tabSelected = "albums"
    val sortValue = "sort_field0"
    val url = urlService.getBrowserViewUrl(
      combinedQueryParams=Map(
        Api.Field.Search.PERSON_ID -> personId,
        Api.Field.Search.SORT -> sortValue
      ), s"http://localhost:8080/r/$repoId?#$tabSelected")

    url shouldEqual s"/r/$repoId?personId=$personId&sort=$sortValue#$tabSelected"
  }
}
