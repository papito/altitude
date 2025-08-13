package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import software.altitude.core.models.Person
import software.altitude.test.core.TestFocus

@DoNotDiscover class PersonModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Person model equality") {
    val person = Person(id = Some("1"))
    assert(person == person)
  }
}
