package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.util.SearchWords

@DoNotDiscover class SearchWordsTests extends AnyFunSuite {

  test("A file name splits on punctuation, camelCase humps and letter/digit boundaries") {

    /**
     * Setup:
     *
     * The file name "IMG_1234-beachSunset.final.jpg".
     *
     * Assertions:
     *
     * It splits into lower-case words at the underscore, the dash and the dots, at the camelCase hump and where letters meet
     * digits.
     */
    SearchWords.of("IMG_1234-beachSunset.final.jpg") shouldBe Seq("img", "1234", "beach", "sunset", "final", "jpg")
  }

  test("Words are lower-cased and a run of capitals is one word") {

    /**
     * Setup:
     *
     * "Alice SMITH" and "HTMLParser".
     *
     * Assertions:
     *
     * Words come out in lower case, and a run of capitals is not split, even where it runs into a capitalized word.
     */
    SearchWords.of("Alice SMITH") shouldBe Seq("alice", "smith")
    SearchWords.of("HTMLParser") shouldBe Seq("htmlparser")
  }

  test("Diacritics are stripped, composed or decomposed") {

    /**
     * Setup:
     *
     * "Zoë Café" with precomposed letters, "Zoë" written with a combining diaeresis, and "İstanbul".
     *
     * Assertions:
     *
     * Accents are removed whether a letter is precomposed or followed by a combining mark.
     *
     * Edge cases:
     *
     * The Turkish dotted capital I, which lower-cases to an i and a combining dot that must not survive.
     */
    SearchWords.of("Zoë Café") shouldBe Seq("zoe", "cafe")
    SearchWords.of("Zoë") shouldBe Seq("zoe")
    // The dotted capital I lower-cases to an i and a combining dot, which must not survive
    SearchWords.of("İstanbul") shouldBe Seq("istanbul")
  }

  test("Letters and digits split from each other in both directions") {

    /**
     * Setup:
     *
     * "2024trip7b".
     *
     * Assertions:
     *
     * A word ends wherever a digit meets a letter or a letter meets a digit.
     */
    SearchWords.of("2024trip7b") shouldBe Seq("2024", "trip", "7", "b")
  }

  test("Every character that is not a letter or a digit separates words") {

    /**
     * Setup:
     *
     * Text padded with spaces and full of separators: an underscore, an apostrophe, brackets, a tab, quotes and query operator
     * characters.
     *
     * Assertions:
     *
     * Each of them separates words and none survives into a word.
     *
     * Edge cases:
     *
     * Leading and trailing whitespace, and characters that are query syntax elsewhere (`*:&|!<>`).
     */
    SearchWords.of("  new_york's (best)\t\"pizza\"*:&|!<>  ") shouldBe Seq("new", "york", "s", "best", "pizza")
  }

  test("Scripts without case are kept as words") {

    /**
     * Setup:
     *
     * Japanese "東京" and Russian "Москва".
     *
     * Assertions:
     *
     * A script without case stays a word as it is, and Cyrillic is lower-cased.
     */
    SearchWords.of("東京 Москва") shouldBe Seq("東京", "москва")
  }

  test("Text without a letter or a digit has no words") {

    /**
     * Setup:
     *
     * An empty string and one made only of separators.
     *
     * Assertions:
     *
     * Neither yields a word.
     */
    SearchWords.of("") shouldBe empty
    SearchWords.of(" -_. \"") shouldBe empty
  }

  test("Text with a camelCase hump has two readings: its words, then the same rule without the hump boundary") {

    /**
     * Setup:
     *
     * "McDonald_beachSunset.jpg" and "ÉcoleMaternelle".
     *
     * Assertions:
     *
     * The first reading splits at the humps and the second is the same split without them, in that order.
     *
     * Edge cases:
     *
     * An accented capital at the start of the humped word.
     */
    SearchWords.variants("McDonald_beachSunset.jpg") shouldBe
      Seq(Seq("mc", "donald", "beach", "sunset", "jpg"), Seq("mcdonald", "beachsunset", "jpg"))
    SearchWords.variants("ÉcoleMaternelle") shouldBe Seq(Seq("ecole", "maternelle"), Seq("ecolematernelle"))
  }

  test("Text without a camelCase hump has one reading, its words") {

    /**
     * Setup:
     *
     * A file name without a hump, and "HTMLParser MCDONALD mcdonald".
     *
     * Assertions:
     *
     * Only one reading comes back, as the reading without the hump boundary would repeat it.
     *
     * Edge cases:
     *
     * A run of capitals and all-caps words, which have no lower-to-upper hump.
     */
    SearchWords.variants("IMG_1234-beach.final.jpg") shouldBe Seq(Seq("img", "1234", "beach", "final", "jpg"))
    SearchWords.variants("HTMLParser MCDONALD mcdonald") shouldBe Seq(Seq("htmlparser", "mcdonald", "mcdonald"))
  }

  test("Both readings split letters from digits") {

    /**
     * Setup:
     *
     * "iPhone12Pro".
     *
     * Assertions:
     *
     * Both readings split at the digits; only the first also splits at the humps.
     */
    SearchWords.variants("iPhone12Pro") shouldBe Seq(Seq("i", "phone", "12", "pro"), Seq("iphone", "12", "pro"))
  }

  test("Text without a letter or a digit has no reading") {

    /**
     * Setup:
     *
     * An empty string and one made only of separators.
     *
     * Assertions:
     *
     * Neither has a reading at all, not even an empty one.
     */
    SearchWords.variants("") shouldBe empty
    SearchWords.variants(" -_. \"") shouldBe empty
  }
}
