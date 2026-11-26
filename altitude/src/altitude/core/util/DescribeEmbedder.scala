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

import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel

/**
 * Embeds a description (words saying what a photo shows) in the space of SigLIP 2's Scene embeddings, so the images nearest it
 * look most like it. Closing it releases the model.
 */
trait DescribeEmbedder extends AutoCloseable:

  /**
   * The description's `SceneModel.Siglip2` embedding, or None where nothing is embedded (a test suite with the model off). Only
   * case and whitespace are normalized; SigLIP 2 is trained on plain captions, so no prompt wraps it.
   */
  def embed(description: String): Option[SceneEmbedding]

object OnnxDescribeEmbedder:

  /** How many of the last-used descriptions' embeddings are kept */
  val CACHE_SIZE = 256

  /** The name of the text tower's one input in its export */
  private val INPUT = "token_embeddings"

  /** The rows of the token-embedding table, one per token ID */
  private val VOCABULARY_SIZE = 256000

  private val DIMENSIONS = SceneModel.Siglip2.dimensions
  private val INPUT_SHAPE: Array[Long] = Array(1L, Siglip2Tokenizer.MAX_TOKENS, DIMENSIONS)

  private val WHITESPACE = "(?U)\\s+".r

  /**
   * The text a description is embedded and cached as: whitespace collapsed and trimmed, lowercased as the tokenizer does. The
   * tokenizer tells tabs, line breaks and doubled spaces apart, so the same words spaced differently would otherwise embed
   * differently, and the cache would answer with whichever spelling came first.
   */
  private def normalized(description: String): String =
    Siglip2Tokenizer.lowercase(WHITESPACE.replaceAllIn(description, " ").strip())

  /** A float16 widened to a float of the same value; the JDK has this from Java 20 on, and the application supports 17 */
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
   * SigLIP 2's text tower: the ONNX Runtime encoder session, whose graph starts after the token-embedding table, and the table
   * itself (256,000 rows of 768 little-endian float16, a token's row at its ID), memory-mapped from a file of its own. A graph
   * holding the table would keep all 790 MB of it as float32 in process memory, though a description reads only 64 rows. The
   * mapped pages are the file's cached pages, so the resident ones grow with token variety (19 MB at the first description, 365
   * of the file's 393 MB after 40,000 different captions), and being clean they are dropped under memory pressure, not swapped.
   *
   * The session is thread-safe and the table is only read, at absolute positions, so any number of threads embed at once.
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
    def open(environment: OrtEnvironment, towerPath: Path, tablePath: Path): TextTower =
      val session = OnnxSessions.open(environment, towerPath)
      try TextTower(environment, session, mapTable(tablePath))
      catch
        case e: Throwable =>
          session.close()
          throw e

    /** The table file as float16 values, read where they are on disk; a file of another size is not this model's table */
    private def mapTable(path: Path): ShortBuffer =
      Using.resource(FileChannel.open(path, StandardOpenOption.READ)) {
        channel =>
          val expected = VOCABULARY_SIZE.toLong * DIMENSIONS * java.lang.Short.BYTES
          if channel.size() != expected then
            throw IllegalStateException(s"The token-embedding table $path is ${channel.size()} bytes, not $expected")
          // The mapping outlives the channel it was made from
          channel.map(FileChannel.MapMode.READ_ONLY, 0, expected).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
      }

/**
 * Embeds a description with SigLIP 2's text tower through ONNX Runtime: normalized, tokenized to 64 tokens, looked up in the
 * token-embedding table, run through the tower and L2-normalized, so it compares with an image's Scene embedding by dot product.
 *
 * Nothing loads until the first description, which loads the tokenizer (about a second) and opens the tower. A server never asked
 * for a description, and every import, pays for neither, so a model file that fails to load fails each description search rather
 * than the start.
 *
 * Measured on the machine of [[ImportParallelism]]'s figures, with both Scene models open: the first description adds about 570
 * MB resident, the session about 430 MB of it (343 MB of weights, kept both raw and pre-packed by the runtime;
 * `session.disable_prepacking` would save about 180 MB for passes 20% slower, so it is not set) and the vocabulary about 30 MB of
 * heap. A pass takes about 91 ms on one thread at any length, the input always being 64 tokens; a cached one takes microseconds.
 *
 * The last `cacheSize` embeddings are kept by normalized text, since paging through results asks for the same description once a
 * page. Two threads embedding one new description at once both run the tower, and the first kept wins.
 */
class OnnxDescribeEmbedder(models: ModelFiles, cacheSize: Int = OnnxDescribeEmbedder.CACHE_SIZE) extends DescribeEmbedder:
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

  /** Loaded by the first description; one that fails to load is tried again by the next */
  private lazy val tokenizer: Siglip2Tokenizer = Siglip2Tokenizer.load(models.path(ModelFile.Siglip2Tokenizer))

  /** The last-used descriptions' embeddings by normalized text, in access order; guarded by its own monitor */
  private val cache = new LinkedHashMap[String, SceneEmbedding](16, 0.75f, true):
    override def removeEldestEntry(eldest: Entry[String, SceneEmbedding]): Boolean = size() > cacheSize

  /** The description's embedding, from the cache or by a pass of the text tower; a closed embedder refuses */
  override def embed(description: String): Option[SceneEmbedding] =
    if closed then throw closedException
    val text = normalized(description)
    val cached = cache.synchronized(Option(cache.get(text)))
    cached.orElse {
      val started = System.nanoTime()
      val embedding = pass(tokenizer.tokenize(text))
      // The description is logged, escaped, by `SearchService.describe`: it is typed text, line breaks and all
      logger.debug(s"Embedded a description by a pass of the text tower in ${(System.nanoTime() - started) / 1000000} ms")
      // Another thread may have embedded the same description meanwhile: the first one kept is everyone's
      Some(cache.synchronized(Option(cache.putIfAbsent(text, embedding)).getOrElse(embedding)))
    }

  /** One pass of the text tower, opening it first if needed */
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

  /** The text tower over its model files; the tests count its calls */
  protected def openTower(): TextTower =
    val started = System.nanoTime()
    val towerPath = models.path(ModelFile.Siglip2Text)
    val tablePath = models.path(ModelFile.Siglip2TokenEmbeddings)
    val opened = TextTower.open(OnnxEnvironment.get, towerPath, tablePath)
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
