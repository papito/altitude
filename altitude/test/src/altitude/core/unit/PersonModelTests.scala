package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite

import altitude.core.models.Person

@DoNotDiscover class PersonModelTests extends funsuite.AnyFunSuite with TestFocus {

  test("Person model equality") {

    /**
     * Setup:
     *
     * A Person with ID "1".
     *
     * Assertions:
     *
     * The Person equals itself.
     */
    val person = Person(id = Some("1"))
    assert(person == person)
  }
}
