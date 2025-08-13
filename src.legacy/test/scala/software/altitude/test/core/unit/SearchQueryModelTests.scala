package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import software.altitude.core.util.SearchQuery

@DoNotDiscover class SearchQueryModelTests extends funsuite.AnyFunSuite {

  test("Invalid RPP") {
    intercept[IllegalArgumentException] {
      new SearchQuery(rpp = -1)
    }
  }

  test("Invalid page") {
    intercept[IllegalArgumentException] {
      new SearchQuery(page = 0)
    }
    intercept[IllegalArgumentException] {
      new SearchQuery(page = -1)
    }
  }
}
