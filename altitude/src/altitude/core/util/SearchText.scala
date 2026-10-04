package altitude.core.util

import org.slf4j.LoggerFactory

/**
 * One thing to look for: the [[SearchWords.variants]] of what was typed (never empty, none empty), any one of which must appear
 * consecutively and in order within one name or one Search document. In a phrase every word is a whole word; otherwise the last
 * word matches the start of a word and the ones before it are whole. An excluded term must not match in any source.
 */
case class SearchTerm(variants: Seq[Seq[String]], isPhrase: Boolean = false, isExcluded: Boolean = false):

  /**
   * Whether a name, given as its [[SearchWords.variants]], has the term: the rule a Search document is matched by, applied in
   * memory to each reading of the name on its own
   */
  def isIn(nameVariants: Seq[Seq[String]]): Boolean =
    variants.exists(words => nameVariants.exists(nameWords => isWithin(words, nameWords)))

  private def isWithin(words: Seq[String], nameWords: Seq[String]): Boolean =
    nameWords.sliding(words.size).exists {
      window =>
        // A name shorter than the term still yields one, short, window
        window.size == words.size && window.init == words.init &&
        (if isPhrase then window.last == words.last else window.last.startsWith(words.last))
    }

/** Terms joined by `OR` (never empty): the group is satisfied when any of them is */
case class SearchGroup(alternatives: Seq[SearchTerm])

/** The typed Search text, parsed: every group (never empty) must be satisfied */
case class SearchExpression(groups: Seq[SearchGroup])

/**
 * The grammar of the typed Search text: terms separated by spaces are AND-ed, an upper-case `OR` between two terms makes them
 * alternatives and binds tighter than AND, a leading `-` excludes, and a `"quoted phrase"` is one term of whole words.
 */
object SearchText:
  private val logger = LoggerFactory.getLogger(getClass)

  val MAX_TERMS = 16

  // An optional minus, then a phrase running to its closing quote or to the end of the text, or a run without spaces or quotes
  private val Token = """(-?)(?:"([^"]*)"?|([^\s"]+))""".r

  private val Or = "OR"

  /**
   * Parses the text as typed and never fails: an unbalanced quote is closed at the end of the text, a term without a word (a lone
   * `-`, punctuation, an empty phrase) and a dangling `OR` are dropped, and terms past [[MAX_TERMS]] are dropped. None when no
   * term is left.
   */
  def parse(text: String): Option[SearchExpression] =
    // A term, or None for the OR operator
    val tokens: Seq[Option[SearchTerm]] = Token
      .findAllMatchIn(text)
      .flatMap {
        found =>
          val isExcluded = found.group(1).nonEmpty
          val isPhrase = found.group(2) != null
          val typed = if isPhrase then found.group(2) else found.group(3)

          if !isPhrase && !isExcluded && typed == Or then Some(None)
          else
            Some(SearchWords.variants(typed))
              .filter(_.nonEmpty)
              .map(variants => Some(SearchTerm(variants, isPhrase, isExcluded)))
      }
      .toSeq

    val numOfTerms = tokens.count(_.isDefined)
    if numOfTerms > MAX_TERMS then
      logger.debug(s"Search text has $numOfTerms terms: the ones past the first $MAX_TERMS are dropped")

    // (groups so far, whether an OR precedes the next term, terms kept so far)
    val (groups, _, _) = tokens.foldLeft((Vector.empty[SearchGroup], false, 0)) {
      case ((groups, _, numKept), None) => (groups, true, numKept)
      case (state @ (_, _, MAX_TERMS), Some(_)) => state
      // An OR with a term before it adds the alternative to that term's group
      case ((groups :+ last, true, numKept), Some(term)) =>
        (groups :+ SearchGroup(last.alternatives :+ term), false, numKept + 1)
      case ((groups, _, numKept), Some(term)) => (groups :+ SearchGroup(Seq(term)), false, numKept + 1)
    }

    Option.when(groups.nonEmpty)(SearchExpression(groups))
