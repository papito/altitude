package altitude.core.util

import java.text.Normalizer
import java.util.Locale

/**
 * The one word-splitting rule of text search: the stored body of a Search document, every name read at query time and the typed
 * Search text all become words here, so the three cannot disagree about where a word starts.
 *
 * `IMG_1234-beachSunset.final.jpg` is `img 1234 beach sunset final jpg`.
 */
object SearchWords:
  private val CombiningMarks = """\p{M}+""".r

  // Between two words: a run of anything that is not a letter or a digit, a camelCase hump (lower case then upper case, so a run
  // of capitals stays one word), or a letter next to a digit
  private val Boundary = """[^\p{L}\p{Nd}]+|(?<=\p{Ll})(?=\p{Lu})|(?<=\p{L})(?=\p{Nd})|(?<=\p{Nd})(?=\p{L})"""

  /** The words of the text in order: lower case, no diacritics, letters or digits only */
  def of(text: String): Seq[String] =
    // Decomposed first, so the marks go before the split (a mark is not a letter and would end a word) and before lower-casing
    // (which needs the case for the humps)
    val unmarked = CombiningMarks.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFD), "")
    unmarked.split(Boundary).toSeq.filter(_.nonEmpty).map(_.toLowerCase(Locale.ROOT))
