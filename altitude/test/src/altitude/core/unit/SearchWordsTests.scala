package altitude.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.util.SearchWords

@DoNotDiscover class SearchWordsTests extends AnyFunSuite {

  test("A file name splits on punctuation, camelCase humps and letter/digit boundaries") {
    SearchWords.of("IMG_1234-beachSunset.final.jpg") shouldBe Seq("img", "1234", "beach", "sunset", "final", "jpg")
  }

  test("Words are lower-cased and a run of capitals is one word") {
    SearchWords.of("Alice SMITH") shouldBe Seq("alice", "smith")
    SearchWords.of("HTMLParser") shouldBe Seq("htmlparser")
  }

  test("Diacritics are stripped, composed or decomposed") {
    SearchWords.of("Zoë Café") shouldBe Seq("zoe", "cafe")
    SearchWords.of("Zoë") shouldBe Seq("zoe")
    // The dotted capital I lower-cases to an i and a combining dot, which must not survive
    SearchWords.of("İstanbul") shouldBe Seq("istanbul")
  }

  test("Letters and digits split from each other in both directions") {
    SearchWords.of("2024trip7b") shouldBe Seq("2024", "trip", "7", "b")
  }

  test("Every character that is not a letter or a digit separates words") {
    SearchWords.of("  new_york's (best)\t\"pizza\"*:&|!<>  ") shouldBe Seq("new", "york", "s", "best", "pizza")
  }

  test("Scripts without case are kept as words") {
    SearchWords.of("東京 Москва") shouldBe Seq("東京", "москва")
  }

  test("Text without a letter or a digit has no words") {
    SearchWords.of("") shouldBe empty
    SearchWords.of(" -_. \"") shouldBe empty
  }

  test("Text with a camelCase hump has two readings: its words, then the same rule without the hump boundary") {
    SearchWords.variants("McDonald_beachSunset.jpg") shouldBe
      Seq(Seq("mc", "donald", "beach", "sunset", "jpg"), Seq("mcdonald", "beachsunset", "jpg"))
    SearchWords.variants("ÉcoleMaternelle") shouldBe Seq(Seq("ecole", "maternelle"), Seq("ecolematernelle"))
  }

  test("Text without a camelCase hump has one reading, its words") {
    SearchWords.variants("IMG_1234-beach.final.jpg") shouldBe Seq(Seq("img", "1234", "beach", "final", "jpg"))
    SearchWords.variants("HTMLParser MCDONALD mcdonald") shouldBe Seq(Seq("htmlparser", "mcdonald", "mcdonald"))
  }

  test("Both readings split letters from digits") {
    SearchWords.variants("iPhone12Pro") shouldBe Seq(Seq("i", "phone", "12", "pro"), Seq("iphone", "12", "pro"))
  }

  test("Text without a letter or a digit has no reading") {
    SearchWords.variants("") shouldBe empty
    SearchWords.variants(" -_. \"") shouldBe empty
  }
}
