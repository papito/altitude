package altitude.core.util

import java.util.Locale

import scala.annotation.tailrec

/**
 * Names in the order a person reads them: case-insensitively, and with each run of ASCII digits compared as a number, so "Client
 * 2" comes before "client 10" and "Client 100". Text is compared lowercased (`Locale.ROOT`, as `Repository.nameLowercase` folds
 * it) by UTF-16 code unit, so a name starting with a digit comes before one starting with a letter. The order is total: numbers
 * equal in value put the one with fewer leading zeros first ("7" before "07"), and names equal but for case fall back to their
 * plain order.
 */
object NaturalOrder extends Ordering[String]:
  // A run of digits, or a run of anything else; `\d` is ASCII only without the UNICODE_CHARACTER_CLASS flag
  private val CHUNK = """\d+|\D+""".r

  override def compare(a: String, b: String): Int =
    val byChunks = compareChunks(chunks(a), chunks(b))
    if byChunks != 0 then byChunks else a.compareTo(b)

  private def chunks(name: String): List[String] = CHUNK.findAllIn(name.toLowerCase(Locale.ROOT)).toList

  @tailrec
  private def compareChunks(as: List[String], bs: List[String]): Int = (as, bs) match
    case (Nil, Nil) => 0
    case (Nil, _) => -1
    case (_, Nil) => 1
    case (a :: restA, b :: restB) =>
      val byChunk = compareChunk(a, b)
      if byChunk != 0 then byChunk else compareChunks(restA, restB)

  private def compareChunk(a: String, b: String): Int =
    if isAsciiDigit(a.head) && isAsciiDigit(b.head) then
      // Compared as text without the leading zeros, a number of any length: the one with more digits is the larger
      val (x, y) = (a.dropWhile(_ == '0'), b.dropWhile(_ == '0'))
      if x.length != y.length then x.length.compare(y.length)
      else if x != y then x.compareTo(y)
      else a.length.compare(b.length)
    else a.compareTo(b)

  // Not `isDigit`, which takes any script's digits, where the chunks are runs of ASCII ones
  private def isAsciiDigit(c: Char): Boolean = c >= '0' && c <= '9'
