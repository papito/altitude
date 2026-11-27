package altitude.core.unit

import altitude.test.TestContext
import altitude.test.TestFocus
import java.util.Arrays
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import scala.util.Random

import altitude.core.util.NaturalOrder
import altitude.core.util.SortKey

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
     * Names mixing numbers of several lengths with punctuation, accented and other-script letters, a superscript digit (no
     * decimal digit, so no number to the order), Arabic-Indic digits (a number, as any script's decimal digits are), a vulgar
     * fraction, a lone surrogate, the same accented name precomposed and with a combining accent, and names that differ only in
     * case.
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
      "\u0436",
      "\uD800x",
      "E\u0301mile"
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

  test("Names order by their sort key, one order for a list sorted in memory and one sorted by a database") {

    /**
     * Setup:
     *
     * `TestContext.NATURAL_NAMES` shuffled: a symbol, a leading number, a space before a parenthesis, digit runs of one and two
     * digits, an accent and case.
     *
     * Assertions:
     *
     * `NaturalOrder` sorts them in that order, and so do their `SortKey`s, whose bytes a `*_sort` column holds and both engines
     * compare as `memcmp` does.
     *
     * Edge cases:
     *
     * A symbol comes before a number, which comes before a letter, and a space before the other punctuation.
     */
    val names = TestContext.NATURAL_NAMES

    Random.shuffle(names).sorted(using NaturalOrder) shouldBe names
    Random.shuffle(names).sortBy(SortKey.of) shouldBe names
    Random
      .shuffle(names)
      .map(name => SortKey.of(name).bytes -> name)
      .sortWith((a, b) => Arrays.compareUnsigned(a._1, b._1) < 0)
      .map(_._2) shouldBe names
  }

  test("Letters sort with their base letters and any script's digits as numbers, as ICU's root collation has them") {

    /**
     * Setup:
     *
     * Names with letters that have no decomposition (ø, ł, đ, æ, œ), Icelandic thorn, numbers in Arabic-Indic digits, and names
     * alike but for leading zeros, shuffled.
     *
     * Assertions:
     *
     * "Øst", "Łódź" and "Đuro" sort beside "Ost", "Lodz" and "Duro", after them; "Æble" and "Œuvre" as "ae" and "oe" spelled out;
     * "٣" (three) beside "3", before "10"; and of names alike but for leading zeros, the shorter first.
     *
     * Edge cases:
     *
     * Thorn is a letter of its own in the root collation, after "z": an Icelandic list would want it after "t", which needs a
     * tailored collation.
     */
    val expected = List(
      "3",
      "\u0663",
      "10",
      "Aeble",
      "\u00C6ble",
      "Az",
      "Duro",
      "\u0110uro",
      "Dz",
      "File 7",
      "File 07",
      "File 8",
      "Lodz",
      "\u0141\u00F3d\u017A",
      "Lz",
      "Oeuvre",
      "\u0152uvre",
      "Ost",
      "\u00D8st",
      "Oz",
      "Thor",
      "Zz",
      "\u00DE\u00F3r"
    )

    Random.shuffle(expected).sorted(using NaturalOrder) shouldBe expected
  }
}
