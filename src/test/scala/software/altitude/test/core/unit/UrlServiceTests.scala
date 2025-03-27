package software.altitude.test.core.unit

import org.mockito.Mockito.{mock, when}
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.{DoNotDiscover, funsuite}
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.service.UrlService
import software.altitude.test.core.TestFocus
import software.altitude.core.Api

import javax.servlet.http.HttpServletRequest
import scala.jdk.CollectionConverters.MapHasAsJava

@DoNotDiscover class UrlServiceTests extends funsuite.AnyFunSuite with TestFocus {
  val urlService = new UrlService

  val personId: String = BaseDao.genId
  val repoId = "1"

  test("Searching a person should return correct view URL") {
    val request = mock(classOf[HttpServletRequest])

    // user is on a page with PEOPLE tab chosen
    when(request.getHeader("HX-Current-URL")).thenReturn(s"http://localhost:8080/r/$repoId#people")
    // user selects a person to view
    when(request.getParameterMap).thenReturn(Map(Api.Field.Search.PEOPLE_IDS -> Array(personId)).asJava)

    val url = urlService.getBrowserViewUrl(request)

    // the system forces person view URL and the PEOPLE tab is still chosen
    url shouldEqual s"/r/$repoId?view=person&personId=$personId#people"
  }

  test("Sorting should return correct view URL", Focused) {
    val request = mock(classOf[HttpServletRequest])

    // user is on a page with PEOPLE tab chosen
    when(request.getHeader("HX-Current-URL")).thenReturn(s"http://localhost:8080/r/$repoId#people")
    // user selects a person to view
    when(request.getParameterMap).thenReturn(Map(
      Api.Field.Search.PEOPLE_IDS -> Array(personId),
      Api.Field.Search.SORT -> Array("sort_field|1")
    ).asJava)

    val url = urlService.getBrowserViewUrl(request)

    // the system forces person view URL and the PEOPLE tab is still chosen
    url shouldEqual s"/r/$repoId?view=person&personId=$personId&sort=sort_field|1#people"
  }

}
