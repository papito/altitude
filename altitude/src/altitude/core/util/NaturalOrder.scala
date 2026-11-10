package altitude.core.util

import java.text.Collator
import java.util.Locale

import scala.annotation.tailrec

/**
 * Names in the order a person reads them: case-insensitively, with an accented letter beside its base letter, and with each run
 * of ASCII digits compared as a number, so "Client 2" comes before "client 10" and "Client 100", and "Émile" before "Zoo". Text
 * is compared by the root locale's collation at secondary strength: case is ignored, and of two names that differ only in an
 * accent, the unaccented one comes first. Numbers sort, against text, where the collation puts "0": after the punctuation, before
 * the letters. The order is total: numbers equal in value put the one with fewer leading zeros first ("7" before "07"), and names
 * the collation finds equal (equal but for case) fall back to their plain order.
 */
object NaturalOrder extends Ordering[String]:
  // A run of digits, or a run of anything else; `\d` is ASCII only without the UNICODE_CHARACTER_CLASS flag
  private val CHUNK = """\d+|\D+""".r

  // A Collator is not thread-safe, so each thread compares with its own
  private val collator: ThreadLocal[Collator] = ThreadLocal.withInitial {
    () =>
      val collator = Collator.getInstance(Locale.ROOT)
      collator.setStrength(Collator.SECONDARY)
      // A name typed with a combining accent sorts as the same name precomposed
      collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION)
      collator
  }

  override def compare(a: String, b: String): Int =
    val byChunks = compareChunks(chunks(a), chunks(b))
    if byChunks != 0 then byChunks else a.compareTo(b)

  private def chunks(name: String): List[String] = CHUNK.findAllIn(name).toList

  @tailrec
  private def compareChunks(as: List[String], bs: List[String]): Int = (as, bs) match
    case (Nil, Nil) => 0
    case (Nil, _) => -1
    case (_, Nil) => 1
    case (a :: restA, b :: restB) =>
      val byChunk = compareChunk(a, b)
      if byChunk != 0 then byChunk else compareChunks(restA, restB)

  private def compareChunk(a: String, b: String): Int = (isNumber(a), isNumber(b)) match
    case (true, true) =>
      // Compared as text without the leading zeros, a number of any length: the one with more digits is the larger
      val (x, y) = (a.dropWhile(_ == '0'), b.dropWhile(_ == '0'))
      if x.length != y.length then x.length.compare(y.length)
      else if x != y then x.compareTo(y)
      else a.length.compare(b.length)
    case (false, false) => collator.get.compare(a, b)
    // Every number sorts at one place among the text, where "0" does, so a character the collation puts between two digits ("²"
    // between "1" and "9") cannot make the order cyclic
    case (true, false) => -textAgainstNumbers(b)
    case (false, true) => textAgainstNumbers(a)

  // Before every number (-1) or after every number (1)
  private def textAgainstNumbers(text: String): Int = if collator.get.compare(text, "0") < 0 then -1 else 1

  // Not `isDigit`, which takes any script's digits, where the chunks are runs of ASCII ones
  private def isNumber(chunk: String): Boolean = chunk.head >= '0' && chunk.head <= '9'
