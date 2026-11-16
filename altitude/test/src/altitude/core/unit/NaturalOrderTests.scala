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
     * They sort as read: case is ignored, numbers compare by value wherever they stand, a leading digit comes before a letter,
     * and a name comes before its own longer form.
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
     * Long numbers compare by value. Of equal numbers, fewer leading zeros come first, and names equal but for case keep a fixed
     * order, so no two different names compare equal.
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

  test("Accented letters sort beside their base letters") {

    /**
     * Setup:
     *
     * Names with accented and unaccented letters in mixed case, some differing only in an accent or in case, shuffled.
     *
     * Assertions:
     *
     * An accented letter sorts with its base letter, not after "Z", and of names that differ only in an accent the unaccented one
     * comes first, whatever the case.
     */
    val expected = List(
      "Eclair",
      "éclair",
      "emile",
      "Émile",
      "Öland",
      "Oz",
      "Zoo"
    )

    Random.shuffle(expected).sorted(using NaturalOrder) shouldBe expected
  }

  test("The order is total and transitive across numbers, punctuation, accents, other scripts and digits of other kinds") {

    /**
     * Setup:
     *
     * Names mixing numbers of several lengths with punctuation, accented and other-script letters, superscript and Arabic-Indic
     * digits (which are not numbers to the order), and names that differ only in case.
     *
     * Assertions:
     *
     * Every pair compares antisymmetrically and is zero only for equal names, and every triple is transitive, so a sort cannot
     * fail or depend on the input order.
     */
    val names = List(
      "9",
      "10",
      "\u00B2",
      "\u00BD",
      "\u0663",
      "(draft)",
      "-a",
      "~x",
      "a",
      "A",
      "\u00C9mile",
      "emile",
      "Client 2",
      "Client \u00B2",
      "Client 10",
      "Client",
      "\u05E9\u05DC\u05D5\u05DD 2",
      "\u0436"
    )

    def sign(a: String, b: String): Int = Integer.signum(NaturalOrder.compare(a, b))

    for (a <- names; b <- names) {
      withClue(s"[$a] [$b]: ") {
        sign(a, b) shouldBe -sign(b, a)
        (sign(a, b) == 0) shouldBe (a == b)
      }
    }
    for (a <- names; b <- names; c <- names if sign(a, b) < 0 && sign(b, c) < 0) {
      withClue(s"[$a] < [$b] < [$c]: ") {
        sign(a, c) shouldBe -1
      }
    }
  }
}
