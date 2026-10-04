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
     * Two Persons with ID "1" that differ in every other field, and a Person with ID "2".
     *
     * Assertions:
     *
     * Persons are equal exactly when their IDs are, whatever their other fields, and equal Persons have the same hash code.
     */
    val person = Person(id = Some("1"))
    val sameId = Person(id = Some("1"), name = Some("Ann"), isHidden = true, isNamed = true, numOfFaces = 3)

    assert(person == sameId)
    assert(person.hashCode == sameId.hashCode)
    assert(person != Person(id = Some("2")))
  }
}
