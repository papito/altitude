package altitude.core.util

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * SigLIP 2's text tokenizer: a description as the 64 token IDs the checkpoint's reference processor gives it
 * (`padding="max_length"`, `max_length=64`, `truncation=True`) after lowercasing, which the model was trained on and the
 * reference `GemmaTokenizer` leaves to its caller. The steps of its byte-fallback BPE of 256,000 pieces:
 *
 *   1. Lowercase, as Python does (`lowercase`).
 *   1. Take out the pieces matched whole (the special tokens, runs of line feeds, a few HTML tags), the leftmost first and the
 *      longest there. Each is its own token, and the stretches between them are tokenized apart, so no merge crosses one.
 *   1. In a stretch, replace every space with U+2581. There is no dummy prefix and no other normalization.
 *   1. Split the stretch into code points; one that is no piece becomes the pieces of its UTF-8 bytes (`<0xNN>`).
 *   1. While two adjacent pieces concatenate to a piece, merge the pair that comes first by `Candidate`'s order.
 *   1. Keep the first 63 tokens, add the EOS, and pad to 64. The reference adds no BOS.
 *
 * The loaded vocabulary takes about 30 MB of heap; `tokenize` only reads it, so any number of threads tokenize at once.
 */
object Siglip2Tokenizer:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The length of the text tower's input */
  val MAX_TOKENS = 64

  /** What a space is written as in a piece */
  private val SPACE_MARK = '\u2581'

  private val EOS = "<eos>"
  private val PAD = "<pad>"

  private val CAPITAL_SIGMA = '\u03a3'
  private val SMALL_SIGMA = '\u03c3'
  private val FINAL_SIGMA = '\u03c2'

  /**
   * The punctuation Unicode counts as case-ignorable beside the marks, format characters, modifier letters and modifier symbols:
   * the code points whose Word_Break is MidLetter, MidNumLet or Single_Quote (an apostrophe, a full stop, a colon and their
   * like), as of Unicode 15
   */
  private val CASE_IGNORABLE_PUNCTUATION: Set[Int] = Set(0x0027, 0x002e, 0x003a, 0x00b7, 0x0387, 0x055f, 0x05f4, 0x2018, 0x2019,
    0x2024, 0x2027, 0xfe13, 0xfe52, 0xfe55, 0xff07, 0xff0e, 0xff1a)

  /** A piece's token ID, and the score that ranks the merges that build it: the higher merges first */
  private case class Piece(id: Int, score: Float)

  /**
   * The two symbols starting at `position` concatenate to `piece`. The order rebuilds the reference's merge list, as the export
   * script proves: the piece's score, higher first; then the left and the right symbol's length in code points, longer first;
   * then the piece's ID, lower first; then the leftmost position.
   */
  private case class Candidate(position: Int, piece: Piece, leftPoints: Int, rightPoints: Int)

  /** A priority queue gives its greatest element first, so the candidate to merge first is the greatest */
  private given Ordering[Candidate] with
    def compare(a: Candidate, b: Candidate): Int =
      var order = java.lang.Float.compare(a.piece.score, b.piece.score)
      if order == 0 then order = Integer.compare(a.leftPoints, b.leftPoints)
      if order == 0 then order = Integer.compare(a.rightPoints, b.rightPoints)
      if order == 0 then order = Integer.compare(b.piece.id, a.piece.id)
      if order == 0 then order = Integer.compare(b.position, a.position)
      order

  /** A line per piece, `<id> <score> <whole> <piece>` between tabs, the piece escaped. Reading it takes about a second. */
  def load(path: Path): Siglip2Tokenizer =
    // The model files are put in place at the start, so a file missing now was taken away since
    if !Files.isRegularFile(path) then
      throw IllegalStateException(s"SigLIP 2's tokenizer file is missing at $path: a restart downloads it again")
    val pieces = HashMap.newBuilder[String, Piece]
    val whole = mutable.ArrayBuffer.empty[String]
    Using.resource(Files.lines(path, UTF_8)) {
      lines =>
        lines.iterator().asScala.foreach {
          line =>
            line.split("\t", 4) match
              case Array(id, score, isWhole, escaped) =>
                val piece = unescaped(escaped)
                pieces += piece -> Piece(id.toInt, score.toFloat)
                if isWhole == "1" then whole += piece
              case _ => throw IllegalStateException(s"A line of $path is not an ID, a score, a flag and a piece: $line")
        }
    }
    val tokenizer =
      Siglip2Tokenizer(pieces.result(), whole.toSeq.groupBy(_.charAt(0)).view.mapValues(_.sortBy(-_.length)).toMap)
    logger.info(s"Loaded SigLIP 2's vocabulary from $path: ${tokenizer.pieceCount} pieces, ${whole.size} matched whole")
    tokenizer

  /** A backslash, a tab, a line feed and a carriage return are written `\\`, `\t`, `\n`, `\r` */
  private def unescaped(escaped: String): String =
    if escaped.indexOf('\\') < 0 then escaped
    else
      val text = new StringBuilder(escaped.length)
      var i = 0
      while i < escaped.length do
        val char = escaped.charAt(i)
        if char == '\\' && i + 1 < escaped.length then
          i += 1
          text += (escaped.charAt(i) match
            case 't' => '\t'
            case 'n' => '\n'
            case 'r' => '\r'
            case other => other)
        else text += char
        i += 1
      text.toString

  /**
   * Unicode's default lowercasing, as the reference's `str.lower`. That is `toLowerCase(Locale.ROOT)` but for a capital sigma at
   * the end of a word: Unicode reads the nearest character that is not case-ignorable on each side, where the JDK looks for a
   * cased letter anywhere in the word, so the two disagree beside a digit ("ΣΑΣ1a" ends in a final sigma by Unicode only). Each
   * capital sigma is therefore lowercased here first. Idempotent, so a caller that keys by the lowercase text
   * (`OnnxDescribeEmbedder.normalized`) can hand that text to [[tokenize]].
   */
  private[util] def lowercase(text: String): String =
    if text.indexOf(CAPITAL_SIGMA) < 0 then text.toLowerCase(Locale.ROOT)
    else
      val chars = text.toCharArray
      chars.indices.foreach {
        i => if chars(i) == CAPITAL_SIGMA then chars(i) = if isFinalSigma(text, i) then FINAL_SIGMA else SMALL_SIGMA
      }
      String(chars).toLowerCase(Locale.ROOT)

  /**
   * Unicode's Final_Sigma condition for the capital sigma at the index: the nearest code point before it that is not
   * case-ignorable is cased, and the nearest one after it is not (or there is none)
   */
  private def isFinalSigma(text: String, index: Int): Boolean =
    var before = index
    while before > 0 && isCaseIgnorable(text.codePointBefore(before)) do
      before -= Character.charCount(text.codePointBefore(before))
    var after = index + 1
    while after < text.length && isCaseIgnorable(text.codePointAt(after)) do after += Character.charCount(text.codePointAt(after))
    before > 0 && isCased(text.codePointBefore(before)) && !(after < text.length && isCased(text.codePointAt(after)))

  /** Unicode's Cased property: a lowercase, uppercase or titlecase letter */
  private def isCased(point: Int): Boolean =
    Character.isLowerCase(point) || Character.isUpperCase(point) || Character.isTitleCase(point)

  /** Unicode's Case_Ignorable property: what a word may hold between its letters without ending */
  private def isCaseIgnorable(point: Int): Boolean =
    Character.getType(point) match
      case Character.NON_SPACING_MARK | Character.ENCLOSING_MARK | Character.FORMAT | Character.MODIFIER_LETTER |
          Character.MODIFIER_SYMBOL =>
        true
      case _ => CASE_IGNORABLE_PUNCTUATION.contains(point)

/**
 * `whole` holds the pieces matched whole by their first character, the longest first, so the first one the text continues with is
 * the longest match there
 */
final class Siglip2Tokenizer private (pieces: HashMap[String, Siglip2Tokenizer.Piece], whole: Map[Char, Seq[String]]):
  import Siglip2Tokenizer.*

  private val eos: Int = id(EOS)
  private val pad: Int = id(PAD)

  private def pieceCount: Int = pieces.size

  private def id(piece: String): Int =
    pieces.getOrElse(piece, throw IllegalStateException(s"The SigLIP 2 vocabulary has no piece $piece")).id

  /** The longest whole piece the text holds at the index */
  private def wholeAt(text: String, index: Int): Option[String] =
    whole.get(text.charAt(index)).flatMap(_.find(text.startsWith(_, index)))

  def tokenize(description: String): IArray[Int] =
    val text = lowercase(description)
    val tokens = mutable.ArrayBuffer.empty[Int]
    // The start of the stretch read so far that holds no whole piece
    var stretch = 0
    var index = 0
    while index < text.length do
      wholeAt(text, index) match
        case Some(piece) =>
          merge(text.substring(stretch, index), tokens)
          tokens += id(piece)
          index += piece.length
          stretch = index
        case None => index += 1
    merge(text.substring(stretch), tokens)

    val ids = Array.fill(MAX_TOKENS)(pad)
    val kept = Math.min(tokens.length, MAX_TOKENS - 1)
    tokens.copyToArray(ids, 0, kept)
    ids(kept) = eos
    IArray.unsafeFromArray(ids)

  /**
   * The symbols are a linked list over arrays, so a merge is constant work, and the candidates wait in a priority queue, so a
   * stretch of n symbols takes about n log n steps.
   */
  private def merge(stretch: String, tokens: mutable.ArrayBuffer[Int]): Unit =
    val symbols = mutable.ArrayBuffer.empty[String]
    stretch.replace(' ', SPACE_MARK).codePoints().forEach {
      point =>
        val character = Character.toString(point)
        if pieces.contains(character) then symbols += character
        // A lone surrogate, which no decoded text holds, has no UTF-8 form and encodes as a question mark's byte
        else character.getBytes(UTF_8).foreach(byte => symbols += f"<0x${byte & 0xff}%02X>")
    }

    // A symbol's text (null once it is merged into the one before it), its ID, its length in code points and its neighbors
    val count = symbols.length
    val texts = symbols.toArray
    val ids = texts.map(id)
    val points = texts.map(text => text.codePointCount(0, text.length))
    val next = Array.tabulate(count)(i => if i + 1 < count then i + 1 else -1)
    val previous = Array.tabulate(count)(_ - 1)

    val candidates = mutable.PriorityQueue.empty[Candidate]

    def offer(position: Int): Unit =
      val right = next(position)
      if right >= 0 then
        pieces
          .get(texts(position) + texts(right))
          .foreach(piece => candidates.enqueue(Candidate(position, piece, points(position), points(right))))

    (0 until count).foreach(offer)
    while candidates.nonEmpty do
      val best = candidates.dequeue()
      val left = best.position
      val right = if texts(left) == null then -1 else next(left)
      // A candidate goes stale when a merge takes one of its symbols: it stands only if the two there now still make its piece
      if right >= 0 && pieces.get(texts(left) + texts(right)).exists(_.id == best.piece.id) then
        texts(left) = texts(left) + texts(right)
        ids(left) = best.piece.id
        points(left) += points(right)
        texts(right) = null
        next(left) = next(right)
        if next(left) >= 0 then previous(next(left)) = left
        // The merged symbol has new pairs with both its neighbors
        if previous(left) >= 0 then offer(previous(left))
        offer(left)

    var symbol = if count == 0 then -1 else 0
    while symbol >= 0 do
      tokens += ids(symbol)
      symbol = next(symbol)
