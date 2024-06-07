package software.altitude.test.core.unit

import org.scalatest.funsuite
import software.altitude.core.models.BaseModel
import software.altitude.core.models.User
import software.altitude.core.util.SearchQuery

class SearchQueryModelTests extends funsuite.AnyFunSuite {

  var user: User = User(id = Some(BaseModel.genId))

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
