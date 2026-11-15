package altitude.core.util

import com.typesafe.config.Config
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
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
enum ModelFile(val fileName: String, val url: URI, val size: Long, val sha256: String):

  /** DINOv2 ViT-B/14, a Scene model */
  case Dinov2
    extends ModelFile(
      "dinov2_vitb14.onnx",
      ModelFile.release("dinov2_vitb14.onnx"),
      346462331L,
      "53feca43cf4c943e056b7c3e2fe72e8a25c97be6ab22cc2df5dc7fd0a23c1ebb")

  /** SigLIP 2's image tower, a Scene model */
  case Siglip2Image
    extends ModelFile(
      "siglip2_b16_256_image.onnx",
      ModelFile.release("siglip2_b16_256_image.onnx"),
      371802977L,
      "e3c6f8acddca4e08cba87284e2aff36d7e8de0d267871f378dd315385307b7af"
    )

  /** SigLIP 2's text tower, the Describe model */
  case Siglip2Text
    extends ModelFile(
      "siglip2_b16_256_text.onnx",
      ModelFile.release("siglip2_b16_256_text.onnx"),
      342852627L,
      "f93c2d65225f7d934e5846db3c7b452707941223101199bef6ed2838f4ab10fd"
    )

  /** The token table the text tower reads its input from */
  case Siglip2TokenEmbeddings
    extends ModelFile(
      "siglip2_token_embeddings.f16",
      ModelFile.release("siglip2_token_embeddings.f16"),
      393216000L,
      "3b559c1353ed1cfbe37db73d684e9a1ca095586e820704346446a51a69f6ecc4"
    )

  /** The text tower's vocabulary */
  case Siglip2Tokenizer
    extends ModelFile(
      "siglip2_tokenizer.tsv",
      ModelFile.release("siglip2_tokenizer.tsv"),
      6166714L,
      "946f9003e78dc5d947b60bdfa9ed6184895f0ff4a378b4f395c27e49ed1d0871")

  /** The face detector */
  case YuNet
    extends ModelFile(
      "face_detection_yunet_2023mar.onnx",
      ModelFile.release("face_detection_yunet_2023mar.onnx"),
      232589L,
      "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
    )

  /** The face recognition net */
  case ArcFace
    extends ModelFile(
      "w600k_r50.onnx",
      URI.create("https://huggingface.co/maze/faceX/resolve/e010b5098c3685fd00b22dd2aec6f37320e3d850/w600k_r50.onnx"),
      174383860L,
      "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43"
    )

  def spec: ModelFileSpec = ModelFileSpec(fileName, url, size, sha256)

object ModelFile:

  /** The release tag of the set, and the name of its directory under `models.dir` */
  final val TAG = "v1"

  private def release(fileName: String): URI =
    URI.create(s"https://github.com/renegade-otter/altitude-models/releases/download/$TAG/$fileName")

/**
 * Where the model files of the pinned set are, under a root (`models.dir`): in the set's own directory, named by its tag, so two
 * sets never mix and an older set's directory is left alone. The lock file is the root's, shared by every set.
 */
final class ModelFiles(root: Path):
  val directory: Path = root.resolve(ModelFile.TAG)
  val lockFile: Path = root.resolve(".lock")

  def path(file: ModelFile): Path = directory.resolve(file.fileName)

object ModelFiles:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * The instance's model files, under `models.dir` or the per-user cache, the missing ones downloaded first. Throws
   * `ModelFilesException`, naming the files missing, their URLs and the directory to put them in, when they cannot be.
   */
  def ensure(config: Config): ModelFiles =
    val models = ModelFiles(root(config.getString(Const.Conf.MODELS_DIR), sys.env.get, System.getProperty("user.home")))
    logger.info(s"Model files: ${models.directory}")
    ModelDownloader().ensure(ModelFile.values.toSeq.map(_.spec), models.directory, models.lockFile)
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
 * Puts a list of files in a directory, downloading the ones not in place: a file is in place when it is there under its name with
 * its pinned size, so a start never reads a file to hash it. A download holds the lock file against every other process and
 * thread, checks the free space against the bytes missing, streams each file to `<name>.part` hashing it as it is written,
 * resumes a `.part` with a `Range` request, retries an I/O error or a server error after each of the retry delays, checks the
 * size and the SHA-256, and moves the file to its name at once. A file whose size or SHA-256 is not its pin fails at once.
 *
 * @param client
 *   the HTTP client: redirects followed (a release host and Hugging Face both redirect to a CDN, of another origin, which
 *   `Redirect.NORMAL` follows; it refuses only HTTPS to HTTP), the JVM's proxy settings (the default proxy selector)
 * @param retryDelays
 *   the waits before each retry of a file, as many retries as there are delays
 * @param sleep
 *   how a wait is waited, which a test records instead
 * @param freeSpace
 *   the bytes free in the file store of a directory
 */
final class ModelDownloader(
    client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
    retryDelays: Seq[FiniteDuration] = Seq(2.seconds, 4.seconds, 8.seconds),
    sleep: FiniteDuration => Unit = delay => Thread.sleep(delay.toMillis),
    freeSpace: Path => Long = Files.getFileStore(_).getUsableSpace):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Throws `ModelFilesException`, naming the files missing, their URLs and the directory, when they cannot be put in place */
  def ensure(files: Seq[ModelFileSpec], directory: Path, lockFile: Path): Unit =
    if files.forall(inPlace(_, directory)) then logger.debug(s"The model files are in place in $directory")
    else
      Files.createDirectories(directory)
      Files.createDirectories(lockFile.getParent)
      ModelDownloader.locked(lockFile) {
        // Whoever held the lock before may have put them in place meanwhile
        val missing = files.filterNot(inPlace(_, directory))
        if missing.isEmpty then logger.info(s"The model files were put in place in $directory while this one waited")
        else downloadAll(missing, directory)
      }

  private def downloadAll(missing: Seq[ModelFileSpec], directory: Path): Unit =
    // What a `.part` holds is not downloaded again
    val needed = missing.map(spec => spec.size - partSize(spec, directory).min(spec.size)).sum
    logger.info(
      s"Model files missing in $directory: ${missing.map(_.name).mkString(", ")}, " +
        s"${megabytes(missing.map(_.size).sum)} in all, ${megabytes(needed)} to download")

    val free = freeSpace(directory)
    if free < needed then
      throw ModelFilesException(failure(s"$needed bytes are needed in $directory and $free are free", missing, directory))

    try missing.foreach(download(_, directory))
    catch {
      case e @ (_: IOException | _: ModelFilesException) =>
        // An I/O error's message alone may be empty (a refused connection), so it is named with its class
        val reason = e match {
          case _: ModelFilesException => e.getMessage
          case _ => e.toString
        }
        throw ModelFilesException(failure(reason, missing.filterNot(inPlace(_, directory)), directory))
    }

  private def inPlace(spec: ModelFileSpec, directory: Path): Boolean =
    val file = directory.resolve(spec.name)
    Files.isRegularFile(file) && Files.size(file) == spec.size

  private def partOf(spec: ModelFileSpec, directory: Path): Path = directory.resolve(s"${spec.name}.part")

  private def partSize(spec: ModelFileSpec, directory: Path): Long =
    val part = partOf(spec, directory)
    if Files.isRegularFile(part) then Files.size(part) else 0L

  /** Why the files are not in place, and what an instance with no route to them needs to put them in place by hand */
  private def failure(reason: String, missing: Seq[ModelFileSpec], directory: Path): String =
    val files = missing.map(spec => s"  ${spec.name} (${spec.size} bytes, SHA-256 ${spec.sha256}): ${spec.url}")
    (s"The model files could not be put in place: $reason. Download them by hand into $directory:" +: files).mkString("\n")

  /** One file, tried once and then again after each of the retry delays, each try resuming what the one before it wrote */
  private def download(spec: ModelFileSpec, directory: Path): Unit =
    var attempt = 0
    var done = false
    while !done do
      try
        transfer(spec, directory)
        done = true
      catch {
        case e: IOException if attempt < retryDelays.size =>
          val delay = retryDelays(attempt)
          attempt += 1
          logger.warn(s"Downloading ${spec.name} failed ($e): retry $attempt of ${retryDelays.size} in ${delay.toSeconds} s")
          sleep(delay)
      }

  /** One try at a file: the rest of its `.part`, or all of it, then the checks and the move to its name */
  private def transfer(spec: ModelFileSpec, directory: Path): Unit =
    val part = partOf(spec, directory)
    val digest = MessageDigest.getInstance("SHA-256")
    var written = partSize(spec, directory)
    // The bytes already on disk are hashed first, so the digest covers the whole file once the rest is appended
    if written > 0 then
      val existing = Files.newInputStream(part)
      try copy(existing, digest, OutputStream.nullOutputStream())(_ => ())
      finally existing.close()

    // A `.part` already whole (cut off between its last byte and its move) needs no request, which the server would refuse
    if written < spec.size then
      val request = HttpRequest.newBuilder(spec.url).header("User-Agent", "Altitude").timeout(ModelDownloader.HEADERS_TIMEOUT)
      if written > 0 then request.header("Range", s"bytes=$written-")
      val response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
      val body = response.body()
      try
        val status = response.statusCode()
        // A server error is the host's passing trouble, retried as an I/O error is
        if status >= 500 then throw IOException(s"HTTP $status from ${response.uri()}")
        // Anything else that is not the file will not become it by asking again
        if status != 200 && status != 206 then throw ModelFilesException(s"HTTP $status from ${response.uri()}")

        // A 206 is the rest, appended; a 200 is the whole file, which a server that ignores the range sends: it starts over
        val appending = written > 0 && status == 206
        if appending then logger.info(s"Resuming ${spec.name} at ${megabytes(written)} of ${megabytes(spec.size)}: ${spec.url}")
        else
          if written > 0 then logger.info(s"The server sent ${spec.name} whole rather than its rest: starting it over")
          logger.info(s"Downloading ${spec.name}, ${megabytes(spec.size)}: ${spec.url}")
          digest.reset()
          written = 0
        val out =
          if appending then Files.newOutputStream(part, StandardOpenOption.APPEND)
          else Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        val progress = Progress(spec, written)
        try copy(body, digest, out)(progress.add)
        finally out.close()
        written += progress.transferred
        progress.done()
      finally body.close()

    val sha256 = HexFormat.of().formatHex(digest.digest())
    if written != spec.size || sha256 != spec.sha256 then
      Files.delete(part)
      throw ModelFilesException(
        s"${spec.name} came as $written bytes with SHA-256 $sha256, not its pinned ${spec.size} bytes with ${spec.sha256}")
    Files.move(part, directory.resolve(spec.name), StandardCopyOption.ATOMIC_MOVE)

  /** Reads `in` to its end into `digest` and `out`, telling `progress` the length of each chunk */
  private def copy(in: InputStream, digest: MessageDigest, out: OutputStream)(progress: Int => Unit): Unit =
    val chunk = new Array[Byte](64 * 1024)
    var read = in.read(chunk)
    while read != -1 do
      digest.update(chunk, 0, read)
      out.write(chunk, 0, read)
      progress(read)
      read = in.read(chunk)

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

  /** How long a request waits for the answer's headers; the body, which may take minutes, has no limit */
  private val HEADERS_TIMEOUT: Duration = Duration.ofMinutes(1)

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
