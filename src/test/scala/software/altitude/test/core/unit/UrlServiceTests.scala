package software.altitude.test.core.unit

import org.mockito.Mockito.{mock, when}
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.{DoNotDiscover, funsuite}
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.service.UrlService
import software.altitude.test.core.TestFocus

import javax.servlet.http.HttpServletRequest

@DoNotDiscover class UrlServiceTests extends funsuite.AnyFunSuite with TestFocus {
  val urlService = new UrlService

  val personId: String = BaseDao.genId
  val repoId = "1"

  test("HTMX person search with sort URL should correctly translate to browser view URL") {
    val request = mock(classOf[HttpServletRequest])

    val tabSelected = "albums"
    // user is on a page with PEOPLE tab chosen
    when(request.getHeader("HX-Current-URL")).thenReturn(s"http://localhost:8080/r/$repoId#$tabSelected")

    val queryParams = "personId=$personId&sort=sort_field"
    when(request.getQueryString).thenReturn(queryParams)

    val url = urlService.getBrowserViewUrl(request)

    // the system forces person view URL with SORT and the PEOPLE tab is still chosen
    url shouldEqual s"/r/$repoId?$queryParams#$tabSelected"
  }
}
