package altitude.core.unit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.util.SearchExpression
import altitude.core.util.SearchGroup
import altitude.core.util.SearchTerm
import altitude.core.util.SearchText

@DoNotDiscover class SearchTextTests extends AnyFunSuite {

  private def bare(words: String*): SearchTerm = SearchTerm(Seq(words))
  private def phrase(words: String*): SearchTerm = SearchTerm(Seq(words), isPhrase = true)
  private def not(term: SearchTerm): SearchTerm = term.copy(isExcluded = true)

  /** The expression whose groups are the given alternatives, in order */
  private def expression(groups: Seq[SearchTerm]*): Option[SearchExpression] =
    Some(SearchExpression(groups.map(SearchGroup(_))))

  /** The DEBUG messages `SearchText` logs while the block runs */
  private def debugLogged(block: => Unit): Seq[String] = {
    val logger = LoggerFactory.getLogger(SearchText.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    try block
    finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
    appender.list.asScala.toSeq.filter(_.getLevel == Level.DEBUG).map(_.getFormattedMessage)
  }

  test("Terms separated by spaces are AND-ed") {

    /**
     * Setup:
     *
     * The terms alice, paris and beach, separated by a double space and a tab.
     *
     * Assertions:
     *
     * Each term is a group of its own, so all three must match.
     *
     * Edge cases:
     *
     * A run of spaces and a tab as separators.
     */
    SearchText.parse("alice  paris\tbeach") shouldBe expression(Seq(bare("alice")), Seq(bare("paris")), Seq(bare("beach")))
  }

  test("A bare term is its words in sequence") {

    /**
     * Setup:
     *
     * "IMG_1234 beach.sunset".
     *
     * Assertions:
     *
     * Each space-separated term holds the words its punctuation splits it into, in order.
     */
    SearchText.parse("IMG_1234 beach.sunset") shouldBe expression(Seq(bare("img", "1234")), Seq(bare("beach", "sunset")))
  }

  test("A term with a camelCase hump, bare or a phrase, is both of its readings") {

    /**
     * Setup:
     *
     * The bare term beachSunset and the phrase "McDonald farm".
     *
     * Assertions:
     *
     * Both terms carry the reading split at the hump and the reading without it, and the phrase stays a phrase.
     */
    SearchText.parse("beachSunset \"McDonald farm\"") shouldBe expression(
      Seq(SearchTerm(Seq(Seq("beach", "sunset"), Seq("beachsunset")))),
      Seq(SearchTerm(Seq(Seq("mc", "donald", "farm"), Seq("mcdonald", "farm")), isPhrase = true))
    )
  }

  test("OR makes alternatives of its neighbours and binds tighter than AND") {

    /**
     * Setup:
     *
     * "alice OR bob paris" and "rome alice OR bob OR carol".
     *
     * Assertions:
     *
     * OR joins the terms on either side into one group of alternatives, and the other terms stay AND-ed groups.
     *
     * Edge cases:
     *
     * A chain of two ORs makes one group of three.
     */
    SearchText.parse("alice OR bob paris") shouldBe expression(Seq(bare("alice"), bare("bob")), Seq(bare("paris")))
    SearchText.parse("rome alice OR bob OR carol") shouldBe
      expression(Seq(bare("rome")), Seq(bare("alice"), bare("bob"), bare("carol")))
  }

  test("Only an upper-case, bare OR is the operator") {

    /**
     * Setup:
     *
     * "or" in lower case, a quoted "OR" and an excluded -OR, each between two terms.
     *
     * Assertions:
     *
     * None of them is the operator: each is an ordinary term of the word "or" - bare, a phrase or excluded.
     */
    SearchText.parse("alice or bob") shouldBe expression(Seq(bare("alice")), Seq(bare("or")), Seq(bare("bob")))
    SearchText.parse("alice \"OR\" bob") shouldBe expression(Seq(bare("alice")), Seq(phrase("or")), Seq(bare("bob")))
    SearchText.parse("alice -OR bob") shouldBe expression(Seq(bare("alice")), Seq(not(bare("or"))), Seq(bare("bob")))
  }

  test("A leading minus excludes a term or a phrase") {

    /**
     * Setup:
     *
     * The text paris -alice -"new york".
     *
     * Assertions:
     *
     * The minus excludes both the bare term and the phrase, and the first term stays positive.
     */
    SearchText.parse("paris -alice -\"new york\"") shouldBe
      expression(Seq(bare("paris")), Seq(not(bare("alice"))), Seq(not(phrase("new", "york"))))
  }

  test("Text made only of exclusions is valid") {

    /**
     * Setup:
     *
     * "-alice".
     *
     * Assertions:
     *
     * It parses to one excluded term, not to no text.
     */
    SearchText.parse("-alice") shouldBe expression(Seq(not(bare("alice"))))
  }

  test("A minus inside a term separates words and does not exclude") {

    /**
     * Setup:
     *
     * "jean-luc".
     *
     * Assertions:
     *
     * The dash splits the term into two words, and the term is not excluded.
     */
    SearchText.parse("jean-luc") shouldBe expression(Seq(bare("jean", "luc")))
  }

  test("An exclusion can be an alternative") {

    /**
     * Setup:
     *
     * "-alice OR bob".
     *
     * Assertions:
     *
     * The excluded term and the positive one form one group of alternatives.
     */
    SearchText.parse("-alice OR bob") shouldBe expression(Seq(not(bare("alice")), bare("bob")))
  }

  test("A quoted phrase is one term of whole words, spaces included") {

    /**
     * Setup:
     *
     * The phrases "New York" (followed by a bare term), "sun", and "alice OR -bob" with operators inside the quotes.
     *
     * Assertions:
     *
     * Each quoted run is one phrase term of its words.
     *
     * Edge cases:
     *
     * Inside quotes, OR and a minus are plain words, not operators.
     */
    SearchText.parse("\"New York\" pizza") shouldBe expression(Seq(phrase("new", "york")), Seq(bare("pizza")))
    SearchText.parse("\"sun\"") shouldBe expression(Seq(phrase("sun")))
    SearchText.parse("\"alice OR -bob\"") shouldBe expression(Seq(phrase("alice", "or", "bob")))
  }

  test("A quote opens a phrase wherever it is") {

    /**
     * Setup:
     *
     * beach"new york"pizza, with no spaces around the quotes.
     *
     * Assertions:
     *
     * The quoted run is a phrase between two bare terms.
     */
    SearchText.parse("beach\"new york\"pizza") shouldBe
      expression(Seq(bare("beach")), Seq(phrase("new", "york")), Seq(bare("pizza")))
  }

  test("An unbalanced quote is closed at the end of the text") {

    /**
     * Setup:
     *
     * pizza "new york, with no closing quote.
     *
     * Assertions:
     *
     * The open phrase runs to the end of the text.
     */
    SearchText.parse("pizza \"new york") shouldBe expression(Seq(bare("pizza")), Seq(phrase("new", "york")))
  }

  test("A dangling OR is dropped") {

    /**
     * Setup:
     *
     * An OR at the start, at the end, doubled, and on its own.
     *
     * Assertions:
     *
     * An OR without a term on both sides is ignored, a doubled one still joins its neighbours, and a lone OR leaves no text.
     */
    SearchText.parse("OR alice") shouldBe expression(Seq(bare("alice")))
    SearchText.parse("alice OR") shouldBe expression(Seq(bare("alice")))
    SearchText.parse("alice OR OR bob") shouldBe expression(Seq(bare("alice"), bare("bob")))
    SearchText.parse("OR") shouldBe None
  }

  test("A lone minus and a term without a word are dropped") {

    /**
     * Setup:
     *
     * A lone minus, punctuation, an empty phrase and an excluded phrase of dots between "alice" and "bob".
     *
     * Assertions:
     *
     * Only "alice" and "bob" are left as terms.
     */
    SearchText.parse("alice - bob") shouldBe expression(Seq(bare("alice")), Seq(bare("bob")))
    SearchText.parse("alice *** \"\" -\"..\" bob") shouldBe expression(Seq(bare("alice")), Seq(bare("bob")))
  }

  test("Text with no usable term is no text") {

    /**
     * Setup:
     *
     * An empty string, whitespace, and text of only operators, an empty phrase and punctuation.
     *
     * Assertions:
     *
     * None of them parses to an expression.
     */
    SearchText.parse("") shouldBe None
    SearchText.parse("   ") shouldBe None
    SearchText.parse("- OR \"\" ...") shouldBe None
  }

  test("Terms past the sixteenth are dropped, and that is logged at DEBUG") {

    /**
     * Setup:
     *
     * Eighteen one-word terms, parsed while the DEBUG messages of `SearchText` are captured.
     *
     * Assertions:
     *
     * Only the first sixteen terms are kept, and one DEBUG message names both the eighteen typed and the cap of sixteen.
     */
    val words = (1 to 18).map(n => "w" + ('a' + n).toChar)
    var parsed = Option.empty[SearchExpression]

    val logged = debugLogged { parsed = SearchText.parse(words.mkString(" ")) }

    parsed shouldBe expression(words.take(16).map(word => Seq(bare(word)))*)
    logged should have size 1
    logged.head should (include("18").and(include("16")))
  }

  test("Alternatives and exclusions count towards the sixteen, and an OR left dangling by the cap is dropped") {

    /**
     * Setup:
     *
     * Fifteen bare terms, then -x, OR and y.
     *
     * Assertions:
     *
     * The exclusion is kept as the sixteenth term; y would be the seventeenth, so it is dropped rather than joined to the
     * exclusion as an alternative, and the OR before it goes with it.
     *
     * Edge cases:
     *
     * An OR whose right-hand term falls past the cap.
     */
    val words = (1 to 15).map(n => "w" + ('a' + n).toChar)

    SearchText.parse((words ++ Seq("-x", "OR", "y")).mkString(" ")) shouldBe
      expression((words.map(word => Seq(bare(word))) :+ Seq(not(bare("x"))))*)
  }

  test("Sixteen terms are kept and nothing is logged") {

    /**
     * Setup:
     *
     * Exactly sixteen one-word terms, parsed while DEBUG messages are captured.
     *
     * Assertions:
     *
     * All sixteen are kept and nothing is logged.
     *
     * Edge cases:
     *
     * A term count exactly at the cap.
     */
    val words = (1 to 16).map(n => "w" + ('a' + n).toChar)
    var parsed = Option.empty[SearchExpression]

    val logged = debugLogged { parsed = SearchText.parse(words.mkString(" ")) }

    parsed.map(_.groups.size) shouldBe Some(16)
    logged shouldBe empty
  }

  test("A term is in a name that has its words consecutively and in order, the last one as a prefix") {

    /**
     * Setup:
     *
     * The name "img 1234 beach sunset" as its one reading, and bare terms matched against it in memory.
     *
     * Assertions:
     *
     * A term is in the name when its words appear consecutively and in order, all of them whole but the last, which may be a
     * prefix.
     *
     * Edge cases:
     *
     * A prefix in a word that is not the last, words that are not adjacent or are out of order, a suffix ("unset"), a term
     * running past the end of the name, and a name with no reading.
     */
    val name = Seq(Seq("img", "1234", "beach", "sunset"))

    bare("beach").isIn(name) shouldBe true
    bare("sun").isIn(name) shouldBe true
    bare("1234", "bea").isIn(name) shouldBe true
    // Only the last word is a prefix, and the words are consecutive
    bare("im", "1234").isIn(name) shouldBe false
    bare("img", "beach").isIn(name) shouldBe false
    bare("beach", "1234").isIn(name) shouldBe false
    bare("unset").isIn(name) shouldBe false
    bare("sunset", "strip").isIn(name) shouldBe false
    bare("beach").isIn(Seq()) shouldBe false
  }

  test("A phrase is in a name that has its words whole") {

    /**
     * Setup:
     *
     * The name "golden gate bridge" and phrases matched against it in memory.
     *
     * Assertions:
     *
     * A phrase is in the name only when all its words, the last included, appear whole, consecutively and in order.
     *
     * Edge cases:
     *
     * A partial last word, reversed words, and a phrase longer than the name.
     */
    val name = Seq(Seq("golden", "gate", "bridge"))

    phrase("golden", "gate").isIn(name) shouldBe true
    phrase("bridge").isIn(name) shouldBe true
    phrase("golden", "gat").isIn(name) shouldBe false
    phrase("gate", "golden").isIn(name) shouldBe false
    phrase("golden", "gate", "bridge", "park").isIn(name) shouldBe false
  }

  test("A term is in a name when any of its readings is within one reading of the name") {

    /**
     * Setup:
     *
     * A name given as its two readings, "la guardia airport" and "laguardia airport", and terms with one or two readings.
     *
     * Assertions:
     *
     * A term matches when any of its readings is within any single reading of the name.
     *
     * Edge cases:
     *
     * A phrase that would match only by running from the end of one reading into the start of the other.
     */
    val name = Seq(Seq("la", "guardia", "airport"), Seq("laguardia", "airport"))

    bare("laguar").isIn(name) shouldBe true
    bare("guardia", "air").isIn(name) shouldBe true
    phrase("laguardia", "airport").isIn(name) shouldBe true
    SearchTerm(Seq(Seq("mc", "donald"), Seq("mcdonald"))).isIn(Seq(Seq("mcdonald", "farm"))) shouldBe true
    // The readings of a name are not one run of words
    phrase("airport", "laguardia").isIn(name) shouldBe false
  }
}
