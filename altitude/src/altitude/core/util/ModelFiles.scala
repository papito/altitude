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
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.Try

import altitude.core.Const
import altitude.core.Environment
import altitude.core.ModelFilesException

/** One file the downloader puts in place, with its pinned size and SHA-256 */
final case class ModelFileSpec(name: String, url: URI, size: Long, sha256: String)

/**
 * The pinned model files: release `TAG` of `renegade-otter/altitude-models`, and ArcFace from the revision of `maze/faceX` the
 * face code was tuned with (its licence forbids re-hosting it). These URLs, sizes and SHA-256s are the trust anchor: a new set is
 * a new tag and new pins.
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

  // The enum's cases, URLs included, are built while this object initializes, before a plain `val` here would be set; the two
  // constants are `inline`, so they are never read as null

  /** The release tag of the set, and the name of its directory under `models.dir` */
  inline val TAG = "v1"

  /** The revision of `maze/faceX` on Hugging Face that ArcFace is downloaded from */
  inline val FACEX_REVISION = "e010b5098c3685fd00b22dd2aec6f37320e3d850"

  private def release(name: String, size: Long, sha256: String): ModelFileSpec =
    ModelFileSpec(
      name,
      URI.create(s"https://github.com/renegade-otter/altitude-models/releases/download/$TAG/$name"),
      size,
      sha256)

  private def faceX(name: String, size: Long, sha256: String): ModelFileSpec =
    ModelFileSpec(name, URI.create(s"https://huggingface.co/maze/faceX/resolve/$FACEX_REVISION/$name"), size, sha256)

/**
 * The pinned set's files sit under the root, the Models directory, in a directory named by its tag, so two sets never mix and an
 * older set's directory is left alone. The lock file is the root's, shared by every set.
 */
final class ModelFiles(val root: Path):
  val directory: Path = root.resolve(ModelFile.TAG)
  val lockFile: Path = root.resolve(".lock")

  def path(file: ModelFile): Path = directory.resolve(file.spec.name)

object ModelFiles:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /** The instance's model files, the missing ones downloaded first */
  def ensure(config: Config): ModelFiles =
    val models = ModelFiles(root(config.getString(Const.Conf.MODELS_DIR), sys.env.get, System.getProperty("user.home")))
    logger.info(s"Model files: ${models.directory}")
    ModelDownloader(models).ensure(ModelFile.values.toSeq.map(_.spec))
    models

  /**
   * `models.dir`, with `~` as the home directory and a relative one under the application's root path, as the data directory is.
   * Empty, `models/` in the per-user cache (`UserCache.folder`): `$XDG_CACHE_HOME/altitude/models`, else
   * `~/.cache/altitude/models`.
   *
   * Throws `ModelFilesException` when the root needs the home directory and the JVM has no absolute one (`user.home` is `?` for a
   * user with no passwd entry, as in some containers), rather than resolve under whatever the working directory is.
   */
  def root(configured: String, env: String => Option[String], home: String): Path =
    def unknown(why: String): Nothing =
      throw ModelFilesException(
        s"The model files' directory is unknown: $why, and the home directory (user.home: $home) is not an absolute path. " +
          s"Set ${Const.Conf.MODELS_DIR}, or the ALTITUDE_MODELS_DIR environment variable, to the directory to keep them in.")

    if configured == "~" || configured.startsWith("~/") then
      UserCache.absolute(home).getOrElse(unknown(s"${Const.Conf.MODELS_DIR} is $configured")).resolve(configured.drop(2))
    else if configured.nonEmpty then Path.of(Environment.ROOT_PATH).resolve(configured)
    else
      UserCache
        .folder(env, home)
        .getOrElse(unknown(s"${Const.Conf.MODELS_DIR} is empty and XDG_CACHE_HOME is not an absolute path"))
        .resolve("models")

/**
 * Puts a set's files in place, downloading the missing ones. A file is in place when its name holds its pinned size, so a start
 * never hashes a file. A download holds the lock file against every other process and thread, checks the free space, streams each
 * file to `<name>.part` hashing as it writes, resumes a `.part` with a `Range` request, retries an I/O error, a 5xx, a 429 or a
 * 408 after each retry delay (or the answer's `Retry-After`), checks the size and SHA-256, and moves the file to its name. A size
 * or SHA-256 off the pin fails at once, as does a body declared other than the missing bytes or running past the pin, before a
 * byte past it is written.
 *
 * The client's timeout covers the headers alone, so a connection that stops delivering without closing would hold the start
 * forever. A watchdog therefore closes a body that delivers nothing for `stallTimeout`; the read sees an I/O error, retried and
 * resumed as any other.
 *
 * @param retryDelays
 *   as many retries of a file as there are delays
 * @param sleep
 *   which a test records instead
 */
final class ModelDownloader(
    models: ModelFiles,
    newClient: () => HttpClient = ModelDownloader.newClient,
    retryDelays: Seq[FiniteDuration] = Seq(2.seconds, 4.seconds, 8.seconds),
    sleep: FiniteDuration => Unit = delay => Thread.sleep(delay.toMillis),
    freeSpace: Path => Long = Files.getFileStore(_).getUsableSpace,
    stallTimeout: FiniteDuration = 60.seconds):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  private val directory = models.directory

  // Built at the first request and shut down after the downloads, so an ensure that finds every file in place (every start but
  // the first) builds no client, nor the selector thread it starts
  private var client: Option[HttpClient] = None

  private def http: HttpClient =
    client.getOrElse {
      val built = newClient()
      client = Some(built)
      built
    }

  /**
   * Throws `ModelFilesException`, naming the missing files, their URLs and the directory, when they cannot be put in place; an
   * I/O error anywhere on the way is its cause.
   */
  def ensure(files: Seq[ModelFileSpec]): Unit =
    if files.forall(inPlace) then logger.debug(s"The model files are in place in $directory")
    else
      try
        Files.createDirectories(directory)
        Files.createDirectories(models.lockFile.getParent)
        LockFile.locked(models.lockFile, "putting the model files in place") {
          // Whoever held the lock before may have put them in place meanwhile
          val missing = files.filterNot(inPlace)
          if missing.isEmpty then logger.info(s"The model files were put in place in $directory while this one waited")
          else downloadAll(missing)
        }
      catch {
        case e: IOException => throw ModelFilesException(failure(ModelDownloader.describe(e), files.filterNot(inPlace)), Some(e))
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
    } finally
      // Every body is closed by now, so nothing is cut short; `close` would wait on whatever the client still holds
      client.foreach(_.shutdownNow())
      client = None

  private def inPlace(spec: ModelFileSpec): Boolean =
    val file = fileOf(spec)
    Files.isRegularFile(file) && Files.size(file) == spec.size

  private def fileOf(spec: ModelFileSpec): Path = directory.resolve(spec.name)
  private def partOf(spec: ModelFileSpec): Path = directory.resolve(s"${spec.name}.part")

  private def partSize(spec: ModelFileSpec): Long =
    val part = partOf(spec)
    if Files.isRegularFile(part) then Files.size(part) else 0L

  /** None of a `.part` longer than the pin, which `transfer` starts over */
  private def resumable(spec: ModelFileSpec): Long =
    val held = partSize(spec)
    if held > spec.size then 0L else held

  /** With what an instance with no route to the files needs to put them in place by hand */
  private def failure(reason: String, missing: Seq[ModelFileSpec]): String =
    val files = missing.map(spec => s"  ${spec.name} (${spec.size} bytes, SHA-256 ${spec.sha256}): ${spec.url}")
    (s"The model files could not be put in place: $reason. Download them by hand into $directory:" +: files).mkString("\n")

  /**
   * Retried after each retry delay, or after the wait a `Retry-After` names, each try resuming what the last one wrote. An I/O
   * error after the last retry becomes a `ModelFilesException`.
   */
  private def download(spec: ModelFileSpec, attempt: Int = 0): Unit =
    try transfer(spec)
    catch {
      case e: IOException =>
        val reason = s"${ModelDownloader.describe(e)}, downloading ${spec.name} from ${spec.url.getHost}"
        if attempt == retryDelays.size then throw ModelFilesException(reason, Some(e))
        val delay = e match {
          case unavailable: ModelDownloader.Unavailable => unavailable.retryAfter.getOrElse(retryDelays(attempt))
          case _ => retryDelays(attempt)
        }
        logger.warn(s"$reason: retry ${attempt + 1} of ${retryDelays.size} in ${delay.toSeconds} s")
        sleep(delay)
        download(spec, attempt + 1)
    }

  private def transfer(spec: ModelFileSpec): Unit =
    // A `.part` longer than the pin holds no file to resume, whatever left it
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
   * A 206 is the rest, appended to the `held` bytes that `digest` covers; a 200 is the whole file from a server that ignores the
   * range, so the `.part` starts over with a new digest. Returns the `.part`'s digest and size.
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

  /** Any status but 200 and 206 fails here */
  private def send(spec: ModelFileSpec, from: Long): HttpResponse[InputStream] =
    val request = HttpRequest.newBuilder(spec.url).header("User-Agent", "Altitude").timeout(ModelDownloader.HEADERS_TIMEOUT)
    if from > 0 then request.header("Range", s"bytes=$from-")
    val response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    val status = response.statusCode()
    if status != 200 && status != 206 then
      response.body().close()
      // The URL a redirect ended at carries a CDN's signature in its path and query, so only its host is named
      val answered = response.uri()
      val by =
        if answered == spec.url then ""
        else s" (answered by ${answered.getHost}${Option(answered.getPort).filter(_ != -1).fold("")(p => s":$p")})"
      val reason = s"HTTP $status for ${spec.url}$by"
      // A server error, a rate limit (429) or a request timeout (408) is the host's passing trouble, retried as an I/O error is
      if status >= 500 || status == 429 || status == 408 then
        throw ModelDownloader.Unavailable(reason, ModelDownloader.retryAfter(response))
      // Anything else that is not the file will not become it by asking again
      throw ModelFilesException(reason)
    response

  /**
   * A body declared other than the file's bytes from `from` on fails before it is read. A header the answer leaves out is not
   * checked (an HTTP/2 answer may declare no length); `write` bounds the body by the pin anyway.
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
   * Appends past the `.part`'s first `from` bytes, or writes it anew at 0, under the stall watchdog; returns the bytes written. A
   * body running past the pin fails with no byte past it written: a CDN or a proxy streaming more would otherwise fill the disk.
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

  /** Its `.part` holds no file to resume, so it is deleted */
  private def rejected(spec: ModelFileSpec, reason: String): ModelFilesException =
    Files.deleteIfExists(partOf(spec))
    ModelFilesException(reason)

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

  /**
   * HTTP/1.1, so a body cancelled mid-file (a stall, a body past its pin) closes its connection rather than staying pooled, dead,
   * for the retry. Redirects are followed: the release host and Hugging Face both redirect to a CDN of another origin, and
   * `Redirect.NORMAL` refuses only HTTPS to HTTP.
   */
  def newClient(): HttpClient =
    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL).build()

  /** How long a request waits for the answer's headers; the body, which may take minutes, has a watchdog instead (`Watchdog`) */
  private val HEADERS_TIMEOUT: Duration = Duration.ofMinutes(1)

  /** The longest wait a `Retry-After` is followed for: a host that asks for more is asked again after this */
  private val MAX_RETRY_AFTER: FiniteDuration = 60.seconds

  /** The host's passing trouble (a 5xx, a 429, a 408), with the wait it asks for, if any */
  final private class Unavailable(message: String, val retryAfter: Option[FiniteDuration]) extends IOException(message)

  /** In seconds or until an HTTP-date, at most `MAX_RETRY_AFTER`; none when the header is absent or reads as neither */
  private def retryAfter(response: HttpResponse[?]): Option[FiniteDuration] =
    val header = Option(response.headers().firstValue("Retry-After").orElse(null)).map(_.trim)
    val wait = header.flatMap {
      value =>
        value.toLongOption.filter(_ >= 0).map(_.seconds).orElse {
          Try(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toOption
            .map(date => Duration.between(Instant.now(), date.toInstant).toMillis.max(0).millis)
        }
    }
    wait.map(_.min(MAX_RETRY_AFTER))

  /**
   * The error and the root of its cause chain. The JDK's client reports a refused connection or an unresolved name as a
   * `ConnectException` with no message, what went wrong only in the root it wraps, so the error alone would say nothing.
   */
  private def describe(e: Throwable): String =
    def named(t: Throwable) =
      Option(t.getMessage).filter(_.nonEmpty).fold(t.getClass.getSimpleName)(m => s"${t.getClass.getSimpleName}: $m")
    val root = Iterator.iterate(e)(_.getCause).takeWhile(_ != null).toSeq.last
    if root eq e then named(e) else s"${named(e)} (${named(root)})"

  /** A daemon, so it never keeps the process up; a cancelled check leaves its queue at once */
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
   * Checked ten times a stall timeout, it closes the stream once no byte has come for the stall timeout, which fails the read
   * blocked on it with an I/O error.
   */
  private class Watchdog(stream: InputStream, stallTimeout: FiniteDuration):
    @volatile private var lastDelivery = System.nanoTime()

    /** Whether the watchdog closed the stream */
    @volatile var fired = false

    private val period = (stallTimeout / 10).toNanos.max(1)
    private val check = watchdogs.scheduleWithFixedDelay(() => checkStall(), period, period, TimeUnit.NANOSECONDS)

    def delivered(): Unit = lastDelivery = System.nanoTime()

    def cancel(): Unit = check.cancel(false): Unit

    private def checkStall(): Unit =
      if !fired && System.nanoTime() - lastDelivery >= stallTimeout.toNanos then
        fired = true
        // A check that throws is never run again, and the stream is the reader's to close as well
        try stream.close()
        catch { case e: IOException => logger.debug(s"Closing a stalled body failed: $e") }
