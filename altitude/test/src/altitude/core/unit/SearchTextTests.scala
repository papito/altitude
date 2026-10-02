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

  private def bare(words: String*): SearchTerm = SearchTerm(words)
  private def phrase(words: String*): SearchTerm = SearchTerm(words, isPhrase = true)
  private def not(term: SearchTerm): SearchTerm = term.copy(isExcluded = true)

  /** The expression whose groups are the given alternatives, in order */
  private def expression(groups: Seq[SearchTerm]*): Option[SearchExpression] =
    Some(SearchExpression(groups.map(SearchGroup(_))))

  /** The INFO messages `SearchText` logs while the block runs */
  private def infoLogged(block: => Unit): Seq[String] = {
    val logger = LoggerFactory.getLogger(SearchText.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.INFO)
    try block
    finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
    appender.list.asScala.toSeq.filter(_.getLevel == Level.INFO).map(_.getFormattedMessage)
  }

  test("Terms separated by spaces are AND-ed") {
    SearchText.parse("alice  paris\tbeach") shouldBe expression(Seq(bare("alice")), Seq(bare("paris")), Seq(bare("beach")))
  }

  test("A bare term is its words in sequence") {
    SearchText.parse("IMG_1234 beachSunset") shouldBe expression(Seq(bare("img", "1234")), Seq(bare("beach", "sunset")))
  }

  test("OR makes alternatives of its neighbours and binds tighter than AND") {
    SearchText.parse("alice OR bob paris") shouldBe expression(Seq(bare("alice"), bare("bob")), Seq(bare("paris")))
    SearchText.parse("rome alice OR bob OR carol") shouldBe
      expression(Seq(bare("rome")), Seq(bare("alice"), bare("bob"), bare("carol")))
  }

  test("Only an upper-case, bare OR is the operator") {
    SearchText.parse("alice or bob") shouldBe expression(Seq(bare("alice")), Seq(bare("or")), Seq(bare("bob")))
    SearchText.parse("alice \"OR\" bob") shouldBe expression(Seq(bare("alice")), Seq(phrase("or")), Seq(bare("bob")))
    SearchText.parse("alice -OR bob") shouldBe expression(Seq(bare("alice")), Seq(not(bare("or"))), Seq(bare("bob")))
  }

  test("A leading minus excludes a term or a phrase") {
    SearchText.parse("paris -alice -\"new york\"") shouldBe
      expression(Seq(bare("paris")), Seq(not(bare("alice"))), Seq(not(phrase("new", "york"))))
  }

  test("Text made only of exclusions is valid") {
    SearchText.parse("-alice") shouldBe expression(Seq(not(bare("alice"))))
  }

  test("A minus inside a term separates words and does not exclude") {
    SearchText.parse("jean-luc") shouldBe expression(Seq(bare("jean", "luc")))
  }

  test("An exclusion can be an alternative") {
    SearchText.parse("-alice OR bob") shouldBe expression(Seq(not(bare("alice")), bare("bob")))
  }

  test("A quoted phrase is one term of whole words, spaces included") {
    SearchText.parse("\"New York\" pizza") shouldBe expression(Seq(phrase("new", "york")), Seq(bare("pizza")))
    SearchText.parse("\"sun\"") shouldBe expression(Seq(phrase("sun")))
    SearchText.parse("\"alice OR -bob\"") shouldBe expression(Seq(phrase("alice", "or", "bob")))
  }

  test("A quote opens a phrase wherever it is") {
    SearchText.parse("beach\"new york\"pizza") shouldBe
      expression(Seq(bare("beach")), Seq(phrase("new", "york")), Seq(bare("pizza")))
  }

  test("An unbalanced quote is closed at the end of the text") {
    SearchText.parse("pizza \"new york") shouldBe expression(Seq(bare("pizza")), Seq(phrase("new", "york")))
  }

  test("A dangling OR is dropped") {
    SearchText.parse("OR alice") shouldBe expression(Seq(bare("alice")))
    SearchText.parse("alice OR") shouldBe expression(Seq(bare("alice")))
    SearchText.parse("alice OR OR bob") shouldBe expression(Seq(bare("alice"), bare("bob")))
    SearchText.parse("OR") shouldBe None
  }

  test("A lone minus and a term without a word are dropped") {
    SearchText.parse("alice - bob") shouldBe expression(Seq(bare("alice")), Seq(bare("bob")))
    SearchText.parse("alice *** \"\" -\"..\" bob") shouldBe expression(Seq(bare("alice")), Seq(bare("bob")))
  }

  test("Text with no usable term is no text") {
    SearchText.parse("") shouldBe None
    SearchText.parse("   ") shouldBe None
    SearchText.parse("- OR \"\" ...") shouldBe None
  }

  test("Terms past the sixteenth are dropped, and that is logged at INFO") {
    val words = (1 to 18).map(n => "w" + ('a' + n).toChar)
    var parsed = Option.empty[SearchExpression]

    val logged = infoLogged { parsed = SearchText.parse(words.mkString(" ")) }

    parsed shouldBe expression(words.take(16).map(word => Seq(bare(word)))*)
    logged should have size 1
    logged.head should (include("18").and(include("16")))
  }

  test("Alternatives and exclusions count towards the sixteen, and an OR left dangling by the cap is dropped") {
    val words = (1 to 15).map(n => "w" + ('a' + n).toChar)

    SearchText.parse((words ++ Seq("-x", "OR", "y")).mkString(" ")) shouldBe
      expression((words.map(word => Seq(bare(word))) :+ Seq(not(bare("x"))))*)
  }

  test("Sixteen terms are kept and nothing is logged") {
    val words = (1 to 16).map(n => "w" + ('a' + n).toChar)
    var parsed = Option.empty[SearchExpression]

    val logged = infoLogged { parsed = SearchText.parse(words.mkString(" ")) }

    parsed.map(_.groups.size) shouldBe Some(16)
    logged shouldBe empty
  }

  test("A term is in a name that has its words consecutively and in order, the last one as a prefix") {
    val name = Seq("img", "1234", "beach", "sunset")

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
    val name = Seq("golden", "gate", "bridge")

    phrase("golden", "gate").isIn(name) shouldBe true
    phrase("bridge").isIn(name) shouldBe true
    phrase("golden", "gat").isIn(name) shouldBe false
    phrase("gate", "golden").isIn(name) shouldBe false
    phrase("golden", "gate", "bridge", "park").isIn(name) shouldBe false
  }
}
