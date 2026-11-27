package altitude.core.util

import java.text.Normalizer

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

  /** Lower case, no diacritics, letters or digits only */
  def of(text: String): Seq[String] = words(unmarked(text), Boundary)

  /**
   * Least recently read first out: a search reads the readings of every name of the repository on every request, and an import
   * those of every file name and metadata value it indexes
   */
  private val MEMO_SIZE = 50000

  // The readings of a text are a pure function of it, so a remembered one never goes stale
  private val memo: java.util.Map[String, Seq[Seq[String]]] =
    java.util.Collections.synchronizedMap(new java.util.LinkedHashMap[String, Seq[Seq[String]]](1024, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Seq[Seq[String]]]): Boolean = size > MEMO_SIZE
    })

  /**
   * None for text without a word. The first is [[of]]; the second, only when the text has a camelCase hump, splits without it, so
   * a word written with a hump and in one case find each other: `McDonald_beachSunset.jpg` is `mc donald beach sunset jpg` or
   * `mcdonald beachsunset jpg`.
   */
  def variants(text: String): Seq[Seq[String]] = memo.computeIfAbsent(text, readings)

  private def readings(text: String): Seq[Seq[String]] =
    val plain = unmarked(text)
    Seq(words(plain, Boundary), words(plain, CaseFreeBoundary)).distinct.filter(_.nonEmpty)

  // Decomposed first, so the marks go before the split (a mark is not a letter and would end a word) and before lower-casing
  // (which needs the case for the humps)
  private def unmarked(text: String): String =
    CombiningMarks.replaceAllIn(Normalizer.normalize(text, Normalizer.Form.NFD), "")

  /**
   * Lower case, letter by letter and with no context: each code point becomes the lower case of its capital. A letter with two
   * lower-case forms thus comes out in one, whatever case it was typed in: the final sigma ς and σ (which `String.toLowerCase`
   * writes for a capital sigma ending a word), the micro sign µ and μ, the long s ſ and s, the Greek symbol forms ϐ ϑ ϕ ϖ ϰ ϱ ϵ
   * and their letters, the dotless ı and i. FTS5 folds some of those itself and PostgreSQL's `simple` configuration none, so
   * without this a word would be two words on PostgreSQL. The fold need not match an engine's own: the document, every name and
   * the typed text all go through it.
   */
  private def words(unmarked: String, boundary: Regex): Seq[String] =
    boundary.split(unmarked).toSeq.filter(_.nonEmpty).map(folded)

  private def folded(word: String): String =
    val out = new java.lang.StringBuilder(word.length)
    word.codePoints.forEach(letter => out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(letter)))): Unit
    out.toString
