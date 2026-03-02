package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import altitude.core.models.Person
import altitude.test.TestFocus

@DoNotDiscover class PersonModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Person model equality") {
    val person = Person(id = Some("1"))
    assert(person == person)
  }
}
