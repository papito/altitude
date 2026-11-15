package altitude.core.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.LinkedHashMap
import java.util.Map.Entry
import java.util.concurrent.locks.ReentrantReadWriteLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.util.Using

import altitude.core.Environment
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel

/**
 * What embeds a description, the words that say what a photo shows, in the space of SigLIP 2's Scene embeddings: the still images
 * whose Scene embedding is nearest to it are the ones that look most like the description. Closing it releases what the model
 * holds.
 */
trait DescribeEmbedder extends AutoCloseable:

  /**
   * The description's embedding, a [[SceneEmbedding]] of `SceneModel.Siglip2`, or none where this embedder embeds nothing (a test
   * instance whose suite has not turned the model on). The description is embedded as it is written but for its case and its
   * whitespace, which say nothing of a photo: SigLIP 2 is trained on plain captions, so there is no prompt around it.
   */
  def embed(description: String): Option[SceneEmbedding]

object OnnxDescribeEmbedder:

  /** How many descriptions' embeddings are kept, the ones used last */
  val CACHE_SIZE = 256

  private val TEXT_TOWER = "/onnx/siglip2_b16_256_text.onnx"
  private val TOKEN_EMBEDDINGS = "/onnx/siglip2_token_embeddings.f16"

  /** The name of the text tower's one input in its export */
  private val INPUT = "token_embeddings"

  /** The rows of the token-embedding table, one per token ID */
  private val VOCABULARY_SIZE = 256000

  private val DIMENSIONS = SceneModel.Siglip2.dimensions
  private val INPUT_SHAPE: Array[Long] = Array(1L, Siglip2Tokenizer.MAX_TOKENS, DIMENSIONS)

  private val WHITESPACE = "(?U)\\s+".r

  /**
   * A description as it is embedded, and so what its embedding is cached by: each run of whitespace one space and none at the
   * ends, in lowercase as the tokenizer lowercases it. The tokenizer tells a tab, a line break and a doubled space from a space,
   * so the same words spaced differently would be different inputs to the model; embedding the one text the key is makes a
   * description's embedding the same whichever way of writing it was asked first.
   */
  private def normalized(description: String): String =
    Siglip2Tokenizer.lowercase(WHITESPACE.replaceAllIn(description, " ").strip())

  /**
   * A float16 widened to the float of the same value: the sign, five bits of exponent and ten of mantissa spread into a float's
   * one, eight and twenty-three. The JDK has this conversion from Java 20 on, and the application runs on 17.
   */
  private[core] def halfToFloat(half: Short): Float =
    val sign = (half & 0x8000) << 16
    val exponent = (half >>> 10) & 0x1f
    val mantissa = half & 0x3ff
    exponent match
      // A zero or a subnormal, which has no implicit leading one: the mantissa counts 2^-24s, each exactly a float
      case 0 => Math.scalb(if sign == 0 then mantissa.toFloat else -mantissa.toFloat, -24)
      // An infinity or a NaN keeps every exponent bit set
      case 0x1f => java.lang.Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13))
      // The exponent's bias goes from 15 to 127
      case _ => java.lang.Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13))

  /**
   * SigLIP 2's text tower as it is loaded: the ONNX Runtime session of the encoder, and the token-embedding table mapped into
   * memory, 256,000 rows of 768 little-endian float16 with a token's row at its ID. The session's graph starts after the table,
   * which is why the table is a file of its own: a description reads 64 of its rows, where a graph holding the table would keep
   * all 790 MB of it as float32 in the process's own memory. The mapping's pages are the file's: the system maps the cached pages
   * around each row read, so the resident ones grow with the variety of the tokens used (19 MB at the first description, 365 MB
   * of the file's 393 after 40,000 different captions), and being clean they are dropped under memory pressure rather than
   * swapped.
   *
   * A session is thread-safe and the table is only read, at absolute positions, so any number of threads embed at once.
   */
  private[core] class TextTower(environment: OrtEnvironment, session: OrtSession, table: ShortBuffer) extends AutoCloseable:

    /** One pass of the text tower over the 64 token IDs of a description: its embedding, L2-normalized */
    def embed(ids: IArray[Int]): SceneEmbedding =
      val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(tokenEmbeddings(ids)), INPUT_SHAPE)
      try OnnxSessions.embed(session, INPUT, tensor, SceneModel.Siglip2)
      finally tensor.close()

    /** The tower's input: each token's row of the table, widened from float16, one after another */
    private def tokenEmbeddings(ids: IArray[Int]): Array[Float] =
      val values = new Array[Float](ids.length * DIMENSIONS)
      val row = new Array[Short](DIMENSIONS)
      var token = 0
      while token < ids.length do
        table.get(ids(token) * DIMENSIONS, row)
        val start = token * DIMENSIONS
        var i = 0
        while i < DIMENSIONS do
          values(start + i) = halfToFloat(row(i))
          i += 1
        token += 1
      values

    /** Closes the session. The mapping has no close of its own: it is released when the buffer is collected */
    override def close(): Unit = session.close()

  private[core] object TextTower:

    /** Opens the session and maps the table; a table that fails to map leaves no session open */
    def open(environment: OrtEnvironment, towerPath: String, tablePath: String): TextTower =
      val session = OnnxSessions.open(environment, towerPath)
      try TextTower(environment, session, mapTable(tablePath))
      catch
        case e: Throwable =>
          session.close()
          throw e

    /** The table file as float16 values, read where they are on disk; a file of another size is not this model's table */
    private def mapTable(path: String): ShortBuffer =
      Using.resource(FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
        channel =>
          val expected = VOCABULARY_SIZE.toLong * DIMENSIONS * java.lang.Short.BYTES
          if channel.size() != expected then
            throw IllegalStateException(s"The token-embedding table $path is ${channel.size()} bytes, not $expected")
          // The mapping outlives the channel it was made from
          channel.map(FileChannel.MapMode.READ_ONLY, 0, expected).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
      }

/**
 * A description's embedding by SigLIP 2's text tower through ONNX Runtime: the description is normalized (`normalized`: its case
 * and its whitespace) and tokenized (`Siglip2Tokenizer`), its 64 tokens' rows are looked up in the token-embedding table, the
 * tower runs over them, and the output row is L2-normalized, so it compares with an image's SigLIP 2 Scene embedding by their dot
 * product.
 *
 * Nothing is loaded when this is built. The first description opens the text tower (`TextTower`) and the tokenizer loads its
 * vocabulary, which takes about a second, and both stay loaded until `close`. A server that is never asked for a description
 * never pays for either, and neither does an import, which embeds images alone. A missing model file therefore fails the first
 * search with a description, and each one after it, rather than the start.
 *
 * Measured on the machine of [[ImportParallelism]]'s figures, in a JVM with the two Scene models open: the first description adds
 * about 570 MB resident, of which the session is about 430 MB once opened (343 MB of weights, which the runtime pre-packs and
 * keeps both copies of, as it does the Scene models'; `session.disable_prepacking` would save about 180 MB for passes 20% slower,
 * and is not set) and the vocabulary about 30 MB of heap. A pass takes about 91 ms on one thread whatever the description's
 * length, the input being 64 tokens always; a cached description is answered in microseconds.
 *
 * The embeddings of the descriptions used last are kept, `cacheSize` of them, by the normalized text they are of: paging through
 * a search's results asks for the same description once a page. Two threads asking for one new description at once both run the
 * tower, and the first to finish is the embedding kept.
 */
class OnnxDescribeEmbedder(cacheSize: Int = OnnxDescribeEmbedder.CACHE_SIZE) extends DescribeEmbedder:
  import OnnxDescribeEmbedder.*

  private val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Guards the tower's life: a pass holds the read lock, so passes run together, and opening and closing hold the write lock, so
   * the tower is opened once and never closed under a pass
   */
  private val lifecycle = ReentrantReadWriteLock()

  // Both written under the write lock; read without it only to choose a path that then checks again under a lock
  @volatile private var tower: Option[TextTower] = None
  @volatile private var closed = false

  /** The embeddings of the descriptions used last, by their normalized text, in the order of their use; guarded by its own monitor */
  private val cache = new LinkedHashMap[String, SceneEmbedding](16, 0.75f, true):
    override def removeEldestEntry(eldest: Entry[String, SceneEmbedding]): Boolean = size() > cacheSize

  /** The description's embedding, from the cache or by a pass of the text tower; a closed embedder refuses */
  override def embed(description: String): Option[SceneEmbedding] =
    if closed then throw closedException
    val text = normalized(description)
    val cached = cache.synchronized(Option(cache.get(text)))
    cached.orElse {
      val started = System.nanoTime()
      val embedding = pass(Siglip2Tokenizer.tokenize(text))
      // The description itself is in the search's own line (`SearchService.describe`), escaped: typed text, line breaks and all
      logger.debug(s"Embedded a description by a pass of the text tower in ${(System.nanoTime() - started) / 1000000} ms")
      // Another thread may have embedded the same description meanwhile: the first one kept is everyone's
      Some(cache.synchronized(Option(cache.putIfAbsent(text, embedding)).getOrElse(embedding)))
    }

  /** One pass of the text tower, opened first if this is the first */
  private def pass(ids: IArray[Int]): SceneEmbedding =
    if tower.isEmpty then open()
    val reading = lifecycle.readLock()
    reading.lock()
    // None here is a close since the check above
    try tower.getOrElse(throw closedException).embed(ids)
    finally reading.unlock()

  /** Opens the text tower unless another thread has; a tower that fails to open is tried again by the next description */
  private def open(): Unit =
    val writing = lifecycle.writeLock()
    writing.lock()
    try
      if closed then throw closedException
      if tower.isEmpty then tower = Some(openTower())
    finally writing.unlock()

  /** The text tower over the model files of the classpath; the tests count its calls */
  protected def openTower(): TextTower =
    val started = System.nanoTime()
    val towerPath = Environment.resolveResourcePath(TEXT_TOWER)
    val tablePath = Environment.resolveResourcePath(TOKEN_EMBEDDINGS)
    val opened = TextTower.open(OrtEnvironment.getEnvironment, towerPath, tablePath)
    logger.info(
      s"Opened SigLIP 2's text tower from $towerPath and mapped its token embeddings from $tablePath in " +
        s"${(System.nanoTime() - started) / 1000000} ms")
    opened

  private def closedException = IllegalStateException("The Describe embedder is closed")

  /**
   * Closes the text tower once the passes under way have finished, and forgets the cached embeddings; every description after it
   * is refused. A second close does nothing. The environment is the process's and stays.
   */
  override def close(): Unit =
    val writing = lifecycle.writeLock()
    writing.lock()
    try
      closed = true
      tower.foreach(_.close())
      tower = None
    finally writing.unlock()
    cache.synchronized(cache.clear())
