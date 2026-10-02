package altitude.core.util

import java.text.Normalizer
import java.util.Locale

import scala.util.matching.Regex

/**
 * The one word-splitting rule of text search: the stored body of a Search document, every name read at query time and the typed
 * Search text all become words here, so the three cannot disagree about where a word starts.
 *
 * `IMG_1234-beachSunset.final.jpg` is `img 1234 beach sunset final jpg`.
 */
object SearchWords:
  private val CombiningMarks = """\p{M}+""".r

  // Between two words whatever the case: a run of anything that is not a letter or a digit, or a letter next to a digit
  private val CaseFreeBoundary = """[^\p{L}\p{Nd}]+|(?<=\p{L})(?=\p{Nd})|(?<=\p{Nd})(?=\p{L})""".r

  // Those, or a camelCase hump: lower case then upper case, so a run of capitals stays one word
  private val Boundary = ("""(?<=\p{Ll})(?=\p{Lu})|""" + CaseFreeBoundary.regex).r

  /** The words of the text in order: lower case, no diacritics, letters or digits only */
  def of(text: String): Seq[String] = words(unmarked(text), Boundary)

  /**
   * The word sequences the text can be read as, none for text without a word. The first is [[of]]; the second is the same rule
   * without the camelCase hump, present only when the text has one, so that a word written with a hump and the same word written
   * in one case can find each other: `McDonald_beachSunset.jpg` is `mc donald beach sunset jpg` or `mcdonald beachsunset jpg`.
   */
  def variants(text: String): Seq[Seq[String]] =
    val plain = unmarked(text)
    Seq(words(plain, Boundary), words(plain, CaseFreeBoundary)).distinct.filter(_.nonEmpty)

  // Decomposed first, so the marks go before the split (a mark is not a letter and would end a word) and before lower-casing
  // (which needs the case for the humps)
  private def unmarked(text: String): String =
    CombiningMarks.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFD), "")

  private def words(unmarked: String, boundary: Regex): Seq[String] =
    boundary.split(unmarked).toSeq.filter(_.nonEmpty).map(_.toLowerCase(Locale.ROOT))
