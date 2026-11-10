package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.util.Random

import altitude.core.util.NaturalOrder

@DoNotDiscover class NaturalOrderTests extends funsuite.AnyFunSuite with TestFocus {

  test("Names sort case-insensitively, with each run of digits compared as a number") {

    /**
     * Setup:
     *
     * Names in mixed case, some ending in numbers of one, two and three digits, some with a number between words, shuffled.
     *
     * Assertions:
     *
     * They sort as read: case is ignored, "Client 2" comes before "client 10" and "Client 100", "Trip 9 day 10" before "trip 10
     * day 2", a name that starts with a digit before one that starts with a letter, and a name before its own longer form.
     */
    val expected = List(
      "2020 Archive",
      "Alpha",
      "beta",
      "Client",
      "Client 2",
      "client 10",
      "Client 100",
      "Trip 9 day 10",
      "trip 10 day 2",
      "Zeta"
    )

    Random.shuffle(expected).sorted(using NaturalOrder) shouldBe expected
  }

  test("Numbers of any length compare by value, and the order stays total") {

    /**
     * Setup:
     *
     * Names whose numbers are longer than a 64-bit integer holds, names whose numbers differ only in leading zeros, and two names
     * that differ only in case.
     *
     * Assertions:
     *
     * The longer numbers compare by value. Equal numbers put the one with fewer leading zeros first, and names equal but for case
     * keep a fixed order, so no two different names compare as equal.
     */
    NaturalOrder.compare("Roll 99999999999999999999", "Roll 100000000000000000000") shouldBe -1
    NaturalOrder.compare("Roll 100000000000000000000", "Roll 99999999999999999999") shouldBe 1

    val zeros = List("File 7", "File 07", "File 007", "File 8")
    Random.shuffle(zeros).sorted(using NaturalOrder) shouldBe zeros

    Math.signum(NaturalOrder.compare("Smith", "smith").toFloat) shouldBe -Math.signum(
      NaturalOrder.compare("smith", "Smith").toFloat)
    (NaturalOrder.compare("Smith", "smith") != 0) shouldBe true
    NaturalOrder.compare("Same 1", "Same 1") shouldBe 0
  }
}
