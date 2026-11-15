package altitude.core.util

import com.typesafe.config.Config
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

import altitude.core.Const
import altitude.core.Environment
import altitude.core.ModelFilesException

/** One file the downloader puts in place: its name in the directory, where it is downloaded from, and its pinned size and SHA-256 */
final case class ModelFileSpec(name: String, url: URI, size: Long, sha256: String)

/**
 * The model files of the set the application pins, release `TAG` of `renegade-otter/altitude-models`, and ArcFace from the
 * revision of `maze/faceX` the face code was tuned with (its licence forbids re-hosting it). The URL, size and SHA-256 here are
 * the trust anchor: a new set is a new tag and new pins.
 */
enum ModelFile(val spec: ModelFileSpec):

  /** DINOv2 ViT-B/14, a Scene model */
  case Dinov2
    extends ModelFile(
      ModelFile.release(
        "dinov2_vitb14.onnx",
        346462331L,
        "53feca43cf4c943e056b7c3e2fe72e8a25c97be6ab22cc2df5dc7fd0a23c1ebb"
      )
    )

  /** SigLIP 2's image tower, a Scene model */
  case Siglip2Image
    extends ModelFile(
      ModelFile.release(
        "siglip2_b16_256_image.onnx",
        371802977L,
        "e3c6f8acddca4e08cba87284e2aff36d7e8de0d267871f378dd315385307b7af"
      )
    )

  /** SigLIP 2's text tower, the Describe model */
  case Siglip2Text
    extends ModelFile(
      ModelFile.release(
        "siglip2_b16_256_text.onnx",
        342852627L,
        "f93c2d65225f7d934e5846db3c7b452707941223101199bef6ed2838f4ab10fd"
      )
    )

  /** The token table the text tower reads its input from */
  case Siglip2TokenEmbeddings
    extends ModelFile(
      ModelFile.release(
        "siglip2_token_embeddings.f16",
        393216000L,
        "3b559c1353ed1cfbe37db73d684e9a1ca095586e820704346446a51a69f6ecc4"
      )
    )

  /** The text tower's vocabulary */
  case Siglip2Tokenizer
    extends ModelFile(
      ModelFile.release(
        "siglip2_tokenizer.tsv",
        6166714L,
        "946f9003e78dc5d947b60bdfa9ed6184895f0ff4a378b4f395c27e49ed1d0871"
      )
    )

  /** The face detector */
  case YuNet
    extends ModelFile(
      ModelFile.release(
        "face_detection_yunet_2023mar.onnx",
        232589L,
        "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
      )
    )

  /** The face recognition net */
  case ArcFace
    extends ModelFile(
      ModelFile.faceX(
        "w600k_r50.onnx",
        174383860L,
        "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
      )
    )

object ModelFile:

  /** The release tag of the set, and the name of its directory under `models.dir` */
  final val TAG = "v1"

  /** The revision of `maze/faceX` on Hugging Face that ArcFace is downloaded from */
  final val FACEX_REVISION = "e010b5098c3685fd00b22dd2aec6f37320e3d850"

  /** An asset of release `TAG` of `altitude-models` */
  private def release(name: String, size: Long, sha256: String): ModelFileSpec =
    ModelFileSpec(
      name,
      URI.create(s"https://github.com/renegade-otter/altitude-models/releases/download/$TAG/$name"),
      size,
      sha256)

  /** A file of `maze/faceX` at `FACEX_REVISION` */
  private def faceX(name: String, size: Long, sha256: String): ModelFileSpec =
    ModelFileSpec(name, URI.create(s"https://huggingface.co/maze/faceX/resolve/$FACEX_REVISION/$name"), size, sha256)

/**
 * Where the model files of the pinned set are, under a root (`models.dir`): in the set's own directory, named by its tag, so two
 * sets never mix and an older set's directory is left alone. The lock file is the root's, shared by every set.
 */
final class ModelFiles(root: Path):
  val directory: Path = root.resolve(ModelFile.TAG)
  val lockFile: Path = root.resolve(".lock")

  def path(file: ModelFile): Path = directory.resolve(file.spec.name)

object ModelFiles:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The instance's model files, under `models.dir` or the per-user cache, the missing ones downloaded first. Throws
   * `ModelFilesException`, naming the files missing, their URLs and the directory to put them in, when they cannot be.
   */
  def ensure(config: Config): ModelFiles =
    val models = ModelFiles(root(config.getString(Const.Conf.MODELS_DIR), sys.env.get, System.getProperty("user.home")))
    logger.info(s"Model files: ${models.directory}")
    ModelDownloader(models).ensure(ModelFile.values.toSeq.map(_.spec))
    models

  /**
   * The root of the model files: `models.dir` as configured, a relative one under the application's root path as the data
   * directory is; empty, the per-user cache, `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`. The XDG spec
   * says an empty or relative `XDG_CACHE_HOME` is ignored.
   */
  def root(configured: String, env: String => Option[String], home: String): Path =
    if configured.nonEmpty then Path.of(Environment.ROOT_PATH).resolve(configured)
    else
      val cache = env("XDG_CACHE_HOME").map(Path.of(_)).filter(_.isAbsolute).getOrElse(Path.of(home, ".cache"))
      cache.resolve("altitude").resolve("models")

/**
 * Puts a list of files in the directory of a set of model files, downloading the ones not in place: a file is in place when it is
 * there under its name with its pinned size, so a start never reads a file to hash it. A download holds the lock file against
 * every other process and thread, checks the free space against the bytes missing, streams each file to `<name>.part` hashing it
 * as it is written, resumes a `.part` with a `Range` request, retries an I/O error or a server error after each of the retry
 * delays, checks the size and the SHA-256, and moves the file to its name at once. A file whose size or SHA-256 is not its pin
 * fails at once, as does a body declared other than the bytes missing or running past the pin, before a byte past it is written.
 *
 * The client's timeout covers the answer's headers alone: a body streamed after them has none, and a connection that stops
 * delivering without closing would hold the start forever. A watchdog therefore closes a body that has delivered no byte for the
 * stall timeout, which the read sees as an I/O error, retried and resumed as any other.
 *
 * @param models
 *   where the files go: the set's directory, and the lock file
 * @param client
 *   the HTTP client: redirects followed (a release host and Hugging Face both redirect to a CDN, of another origin, which
 *   `Redirect.NORMAL` follows; it refuses only HTTPS to HTTP), the JVM's proxy settings (the default proxy selector)
 * @param retryDelays
 *   the waits before each retry of a file, as many retries as there are delays
 * @param sleep
 *   how a wait is waited, which a test records instead
 * @param freeSpace
 *   the bytes free in the file store of a directory
 * @param stallTimeout
 *   how long a body may deliver no byte before the watchdog closes it
 */
final class ModelDownloader(
    models: ModelFiles,
    client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
    retryDelays: Seq[FiniteDuration] = Seq(2.seconds, 4.seconds, 8.seconds),
    sleep: FiniteDuration => Unit = delay => Thread.sleep(delay.toMillis),
    freeSpace: Path => Long = Files.getFileStore(_).getUsableSpace,
    stallTimeout: FiniteDuration = 60.seconds):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val directory = models.directory

  /** Throws `ModelFilesException`, naming the files missing, their URLs and the directory, when they cannot be put in place */
  def ensure(files: Seq[ModelFileSpec]): Unit =
    if files.forall(inPlace) then logger.debug(s"The model files are in place in $directory")
    else
      Files.createDirectories(directory)
      Files.createDirectories(models.lockFile.getParent)
      ModelDownloader.locked(models.lockFile) {
        // Whoever held the lock before may have put them in place meanwhile
        val missing = files.filterNot(inPlace)
        if missing.isEmpty then logger.info(s"The model files were put in place in $directory while this one waited")
        else downloadAll(missing)
      }

  private def downloadAll(missing: Seq[ModelFileSpec]): Unit =
    // What a `.part` holds is not downloaded again
    val needed = missing.map(spec => spec.size - resumable(spec)).sum
    logger.info(
      s"Model files missing in $directory: ${missing.map(_.name).mkString(", ")}, " +
        s"${megabytes(missing.map(_.size).sum)} in all, ${megabytes(needed)} to download")

    val free = freeSpace(directory)
    if free < needed then
      throw ModelFilesException(failure(s"$needed bytes are needed in $directory and $free are free", missing))

    try missing.foreach(download(_))
    catch {
      case e: ModelFilesException => throw ModelFilesException(failure(e.getMessage, missing.filterNot(inPlace)), e.cause)
    }

  private def inPlace(spec: ModelFileSpec): Boolean =
    val file = fileOf(spec)
    Files.isRegularFile(file) && Files.size(file) == spec.size

  private def fileOf(spec: ModelFileSpec): Path = directory.resolve(spec.name)
  private def partOf(spec: ModelFileSpec): Path = directory.resolve(s"${spec.name}.part")

  private def partSize(spec: ModelFileSpec): Long =
    val part = partOf(spec)
    if Files.isRegularFile(part) then Files.size(part) else 0L

  /** The bytes of the `.part` a download keeps: none of one longer than the pin, which `transfer` starts over */
  private def resumable(spec: ModelFileSpec): Long =
    val held = partSize(spec)
    if held > spec.size then 0L else held

  /** Why the files are not in place, and what an instance with no route to them needs to put them in place by hand */
  private def failure(reason: String, missing: Seq[ModelFileSpec]): String =
    val files = missing.map(spec => s"  ${spec.name} (${spec.size} bytes, SHA-256 ${spec.sha256}): ${spec.url}")
    (s"The model files could not be put in place: $reason. Download them by hand into $directory:" +: files).mkString("\n")

  /**
   * One file, tried once and then again after each of the retry delays, each try resuming what the one before it wrote. An I/O
   * error still failing it after the last retry is a `ModelFilesException`, with the I/O error as its cause.
   */
  private def download(spec: ModelFileSpec, attempt: Int = 0): Unit =
    try transfer(spec)
    catch {
      case e: IOException =>
        val reason = s"${ModelDownloader.describe(e)}, downloading ${spec.name} from ${spec.url.getHost}"
        if attempt == retryDelays.size then throw ModelFilesException(reason, Some(e))
        val delay = retryDelays(attempt)
        logger.warn(s"$reason: retry ${attempt + 1} of ${retryDelays.size} in ${delay.toSeconds} s")
        sleep(delay)
        download(spec, attempt + 1)
    }

  /** One try at a file: the rest of its `.part`, or all of it, then the checks and the move to its name */
  private def transfer(spec: ModelFileSpec): Unit =
    // A `.part` longer than the pin holds no file to resume, whatever left it: it is dropped and the file starts over
    if partSize(spec) > spec.size then
      logger.info(s"${spec.name}.part holds ${partSize(spec)} bytes, more than its pinned ${spec.size}: starting it over")
      Files.delete(partOf(spec))
    val held = partSize(spec)
    // The bytes already on disk are hashed first, so the digest covers the whole file once the rest is appended
    val digest = Sha256.Digest()
    if held > 0 then digest.update(partOf(spec))
    // A `.part` already whole (cut off between its last byte and its move) needs no request, which the server would refuse
    val (fileDigest, size) = if held < spec.size then receive(spec, digest, held) else (digest, held)
    verify(spec, size, fileDigest.hex())
    Files.move(partOf(spec), fileOf(spec), StandardCopyOption.ATOMIC_MOVE)

  /**
   * Asks for the file from byte `held` on and writes what comes to its `.part`. A 206 is the rest, appended to the `held` bytes
   * that `digest` covers; a 200 is the whole file, which a server that ignores the range sends: the `.part` starts over, with a
   * new digest. Returns the digest of the `.part` and its size.
   */
  private def receive(spec: ModelFileSpec, digest: Sha256.Digest, held: Long): (Sha256.Digest, Long) =
    val response = send(spec, held)
    val body = response.body()
    try
      val appending = held > 0 && response.statusCode() == 206
      val (partDigest, from) = if appending then (digest, held) else (Sha256.Digest(), 0L)
      checkDeclared(spec, response, from)
      if appending then logger.info(s"Resuming ${spec.name} at ${megabytes(held)} of ${megabytes(spec.size)}: ${spec.url}")
      else
        if held > 0 then logger.info(s"The server sent ${spec.name} whole rather than its rest: starting it over")
        logger.info(s"Downloading ${spec.name}, ${megabytes(spec.size)}: ${spec.url}")
      (partDigest, from + write(spec, body, partDigest, from))
    finally body.close()

  /** The request for the file from byte `from` on, answered with the file or a part of it; any other status fails here */
  private def send(spec: ModelFileSpec, from: Long): HttpResponse[InputStream] =
    val request = HttpRequest.newBuilder(spec.url).header("User-Agent", "Altitude").timeout(ModelDownloader.HEADERS_TIMEOUT)
    if from > 0 then request.header("Range", s"bytes=$from-")
    val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    val status = response.statusCode()
    if status != 200 && status != 206 then
      response.body().close()
      // A server error is the host's passing trouble, retried as an I/O error is
      if status >= 500 then throw IOException(s"HTTP $status from ${response.uri()}")
      // Anything else that is not the file will not become it by asking again
      throw ModelFilesException(s"HTTP $status from ${response.uri()}")
    response

  /**
   * A body declared other than the file's bytes from `from` on will not become the file: it fails before it is read. A header the
   * answer leaves out is not checked (an HTTP/2 answer may declare no length), and `write` bounds the body by the pin anyway.
   */
  private def checkDeclared(spec: ModelFileSpec, response: HttpResponse[?], from: Long): Unit =
    val headers = response.headers()
    if response.statusCode() == 206 then
      val expected = s"bytes $from-${spec.size - 1}/${spec.size}"
      val range = headers.firstValue("Content-Range")
      // A total of `*` is a server that does not say it, which the pin then decides
      if range.isPresent && range.get.trim != expected && range.get.trim != s"bytes $from-${spec.size - 1}/*" then
        throw ModelFilesException(s"${spec.name} was answered with Content-Range ${range.get}, not $expected")
    val length = headers.firstValueAsLong("Content-Length")
    if length.isPresent && length.getAsLong != spec.size - from then
      throw ModelFilesException(
        s"${spec.name} was answered with Content-Length ${length.getAsLong}, not the ${spec.size - from} bytes expected")

  /**
   * Reads the body to its end into the `.part`, appended past its first `from` bytes or, when `from` is 0, from its start, and
   * into `digest`, under the stall watchdog. Returns the bytes written. A body that runs past the pin is read no further and
   * fails, no byte past the pin written: a CDN or a proxy streaming more than the file would otherwise fill the disk.
   */
  private def write(spec: ModelFileSpec, body: InputStream, digest: Sha256.Digest, from: Long): Long =
    val part = partOf(spec)
    val out =
      if from > 0 then FileChannel.open(part, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
      else FileChannel.open(part, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    val progress = Progress(spec, from)
    val watchdog = ModelDownloader.Watchdog(body, stallTimeout)
    var overlong = false
    try
      val chunk = new Array[Byte](64 * 1024)
      var read = body.read(chunk)
      while read != -1 && !overlong do
        watchdog.delivered()
        overlong = from + progress.transferred + read > spec.size
        if !overlong then
          digest.update(chunk, 0, read)
          val buffer = ByteBuffer.wrap(chunk, 0, read)
          while buffer.hasRemaining do out.write(buffer)
          progress.add(read)
          read = body.read(chunk)
      // The whole body on the disk before the move gives the file its name, so a crash cannot leave a short file named whole
      if !overlong then out.force(true)
    catch {
      // The read failed because the watchdog closed the stream: the stall is the cause, not the closed stream, which is left out
      case _: IOException if watchdog.fired => throw IOException(s"No byte of ${spec.name} came for $stallTimeout")
    } finally
      watchdog.cancel()
      out.close()
    if overlong then throw rejected(spec, s"${spec.name} came longer than its pinned ${spec.size} bytes")
    progress.done()
    progress.transferred

  /** A file whose size or SHA-256 is not its pin will not become it by asking again */
  private def verify(spec: ModelFileSpec, size: Long, sha256: String): Unit =
    if size != spec.size || sha256 != spec.sha256 then
      throw rejected(
        spec,
        s"${spec.name} came as $size bytes with SHA-256 $sha256, not its pinned ${spec.size} bytes with ${spec.sha256}")

  /** The failure of a file that came as something other than its pin: its `.part` holds no file to resume, and is deleted */
  private def rejected(spec: ModelFileSpec, reason: String): ModelFilesException =
    Files.deleteIfExists(partOf(spec))
    ModelFilesException(reason)

  /** A file's download as it goes: logged at every tenth of the file, and at its end with its time and rate */
  private class Progress(spec: ModelFileSpec, from: Long):
    private val started = System.nanoTime()
    var transferred = 0L
    // The next tenth to log; a resumed file starts past the tenths it already holds
    private var tenth = (from * 10 / spec.size).toInt + 1

    def add(bytes: Int): Unit =
      transferred += bytes
      while tenth < 10 && (from + transferred) * 10 >= spec.size * tenth do
        logger.info(s"${spec.name}: ${tenth * 10}%")
        tenth += 1

    def done(): Unit =
      val seconds = (System.nanoTime() - started) / 1e9
      val rate = if seconds > 0 then transferred / seconds else 0d
      logger.info(f"Downloaded ${spec.name}: ${megabytes(transferred)} in $seconds%.1f s, ${megabytes(rate.toLong)}/s")

  private def megabytes(bytes: Long): String = f"${bytes / 1e6}%.1f MB"

object ModelDownloader:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /** How long a request waits for the answer's headers; the body, which may take minutes, has a watchdog instead (`Watchdog`) */
  private val HEADERS_TIMEOUT: Duration = Duration.ofMinutes(1)

  /**
   * An error as a reason: itself and the root of its cause chain, each by its class and its message. The JDK's client reports a
   * refused connection or a name that does not resolve as a `ConnectException` of no message, what went wrong only in the root it
   * wraps (a `ClosedChannelException`, an `UnresolvedAddressException`), so the error alone would say nothing.
   */
  private def describe(e: Throwable): String =
    def named(t: Throwable) =
      Option(t.getMessage).filter(_.nonEmpty).fold(t.getClass.getSimpleName)(m => s"${t.getClass.getSimpleName}: $m")
    val root = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).toSeq.last
    if root eq e then named(e) else s"${named(e)} (${named(root)})"

  /**
   * The one thread every download's watchdog runs its checks on. A daemon, so it never keeps the process up, and a check that is
   * cancelled is taken out of its queue at once.
   */
  private lazy val watchdogs: ScheduledExecutorService =
    val executor = ScheduledThreadPoolExecutor(
      1,
      runnable =>
        val thread = Thread(runnable, "model-download-watchdog")
        thread.setDaemon(true)
        thread
    )
    executor.setRemoveOnCancelPolicy(true)
    executor

  /**
   * Watches a body as it is read: checked ten times a stall timeout, it closes the stream once no byte has been delivered for the
   * stall timeout, which fails the read blocked on it with an I/O error. Cancelled once the body is read, or its read has failed.
   */
  private class Watchdog(stream: InputStream, stallTimeout: FiniteDuration):
    @volatile private var lastDelivery = System.nanoTime()

    /** Whether the watchdog closed the stream */
    @volatile var fired = false

    private val period = (stallTimeout / 10).toNanos.max(1)
    private val check = watchdogs.scheduleWithFixedDelay(() => checkStall(), period, period, TimeUnit.NANOSECONDS)

    /** The body delivered bytes now */
    def delivered(): Unit = lastDelivery = System.nanoTime()

    def cancel(): Unit = check.cancel(false): Unit

    private def checkStall(): Unit =
      if !fired && System.nanoTime() - lastDelivery >= stallTimeout.toNanos then
        fired = true
        // A check that throws is never run again, and the stream is the reader's to close as well
        try stream.close()
        catch { case e: IOException => logger.debug(s"Closing a stalled body failed: $e") }

  /**
   * One lock per lock file for the whole JVM. A file lock is held by the process, not the thread: a second thread locking the
   * same file would get an `OverlappingFileLockException` rather than wait, so the threads of one JVM queue here first.
   */
  private val processLocks = ConcurrentHashMap[Path, ReentrantLock]()

  /**
   * Runs `body` holding the lock file, against every other process and thread putting files in place under it. A wait for either
   * lock is logged, since a download can take minutes.
   */
  private def locked[A](lockFile: Path)(body: => A): A =
    // The same file reached by two spellings is one lock
    val key = lockFile.getParent.toRealPath().resolve(lockFile.getFileName)
    val processLock = processLocks.computeIfAbsent(key, _ => ReentrantLock())
    if !processLock.tryLock() then
      logger.info(s"Another thread is putting the model files in place: waiting for it to finish ($key)")
      processLock.lock()
    try
      val channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      try
        val fileLock = Option(channel.tryLock()).getOrElse {
          logger.info(s"Another process is putting the model files in place: waiting for it to finish ($key)")
          channel.lock()
        }
        try body
        finally fileLock.release()
      finally channel.close()
    finally processLock.unlock()
