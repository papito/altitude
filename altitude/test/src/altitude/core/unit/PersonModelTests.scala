package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite

import altitude.core.models.Person

@DoNotDiscover class PersonModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Person model equality") {
    val person = Person(id = Some("1"))
    assert(person == person)
  }
}
