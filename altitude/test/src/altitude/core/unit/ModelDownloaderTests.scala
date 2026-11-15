package altitude.core.unit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Random

import altitude.core.Environment
import altitude.core.ModelFilesException
import altitude.core.util.ModelDownloader
import altitude.core.util.ModelFile
import altitude.core.util.ModelFiles
import altitude.core.util.ModelFileSpec
import altitude.core.util.Sha256

/**
 * The downloader puts a set of files in a directory: what is missing is downloaded, resumed, retried and checked against its
 * pins, and what is in place is never asked for again. Every server here is a local one, so no test touches the network.
 */
@DoNotDiscover class ModelDownloaderTests extends AnyFunSuite with BeforeAndAfterEach {

  /** One request the server saw: its path, its `Range` header and its `User-Agent` */
  private case class Request(path: String, range: Option[String], userAgent: Option[String])

  /**
   * A stand-in for the release host (or its CDN) on localhost: it serves files by path, honouring `Range` with a 206 unless told
   * not to, and can be told to redirect a path, to answer a path with a status a number of times first, to cut the next answer of
   * a path short, to stall the next answer of a path (part of it sent, then nothing, the connection held open until the server
   * stops), or to answer every request after a delay. It declares each body's length unless told not to (a chunked body, which
   * ends where the server stops), and remembers every request and every path whose body it wrote to its end.
   */
  private class FileServer {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private val contents = ConcurrentHashMap[String, Array[Byte]]()
    private val redirects = ConcurrentHashMap[String, String]()
    private val statuses = ConcurrentHashMap[String, (Int, AtomicInteger)]()
    private val cuts = ConcurrentHashMap[String, Integer]()
    private val stalls = ConcurrentHashMap[String, Integer]()
    // What a stalled answer waits for before its handler returns
    private val stopped = CountDownLatch(1)

    val requests: ConcurrentLinkedQueue[Request] = ConcurrentLinkedQueue[Request]()
    val completed: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()
    @volatile var honoursRange: Boolean = true
    @volatile var declaresLength: Boolean = true
    @volatile var delay: FiniteDuration = Duration.Zero

    server.setExecutor(executor)
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        try answer(exchange)
        catch {
          // A cut answer leaves the stream short on purpose
          case _: IOException => ()
        } finally exchange.close()
    )
    server.start()

    def url(path: String): URI = URI.create(s"http://127.0.0.1:${server.getAddress.getPort}$path")

    def serve(path: String, content: Array[Byte]): Unit = contents.put(path, content): Unit
    def redirect(path: String, to: URI): Unit = redirects.put(path, to.toString): Unit
    def answer(path: String, status: Int, times: Int): Unit = statuses.put(path, (status, AtomicInteger(times))): Unit
    def cutNext(path: String, afterBytes: Int): Unit = cuts.put(path, afterBytes): Unit
    def stallNext(path: String, afterBytes: Int): Unit = stalls.put(path, afterBytes): Unit

    def requestsOf(path: String): List[Request] = requests.asScala.toList.filter(_.path == path)

    def stop(): Unit =
      stopped.countDown()
      server.stop(0)
      executor.shutdownNow(): Unit

    private def answer(exchange: HttpExchange): Unit =
      val path = exchange.getRequestURI.getPath
      val range = Option(exchange.getRequestHeaders.getFirst("Range"))
      requests.add(Request(path, range, Option(exchange.getRequestHeaders.getFirst("User-Agent"))))
      if delay > Duration.Zero then Thread.sleep(delay.toMillis)

      val status = Option(statuses.get(path)).filter(_._2.getAndDecrement() > 0).map(_._1)
      if redirects.containsKey(path) then
        exchange.getResponseHeaders.add("Location", redirects.get(path))
        exchange.sendResponseHeaders(302, -1)
      else if status.isDefined then exchange.sendResponseHeaders(status.get, -1)
      else if !contents.containsKey(path) then exchange.sendResponseHeaders(404, -1)
      else
        val content = contents.get(path)
        val from = range.filter(_ => honoursRange).map(_.stripPrefix("bytes=").stripSuffix("-").toInt)
        val body = content.drop(from.getOrElse(0))
        // A length of 0 is a chunked body to `HttpServer`
        val length = if declaresLength then body.length else 0
        from match {
          case Some(start) =>
            exchange.getResponseHeaders.add("Content-Range", s"bytes $start-${content.length - 1}/${content.length}")
            exchange.sendResponseHeaders(206, length)
          case None =>
            exchange.sendResponseHeaders(200, length)
        }
        (Option(cuts.remove(path)), Option(stalls.remove(path))) match {
          case (Some(afterBytes), _) =>
            // Part of the body, a pause for the client to read it, then the connection dropped before the rest
            exchange.getResponseBody.write(body, 0, afterBytes)
            exchange.getResponseBody.flush()
            Thread.sleep(200)
            throw IOException("cut short")
          case (_, Some(afterBytes)) =>
            // Part of the body, then nothing more, the connection neither closed nor sent to until the server stops
            exchange.getResponseBody.write(body, 0, afterBytes)
            exchange.getResponseBody.flush()
            stopped.await()
          case _ =>
            exchange.getResponseBody.write(body)
            exchange.getResponseBody.flush()
            completed.add(path): Unit
        }
  }

  private var root: Path = scala.compiletime.uninitialized
  private var servers: List[FileServer] = Nil

  /** The delays the downloader slept, so a retry is seen without being waited for */
  private val slept = ConcurrentLinkedQueue[FiniteDuration]()

  override def beforeEach(): Unit =
    root = Files.createTempDirectory("model-downloader")
    slept.clear()

  override def afterEach(): Unit =
    servers.foreach(_.stop())
    servers = Nil
    FileUtils.deleteDirectory(root.toFile)

  private def newServer(): FileServer =
    val server = FileServer()
    servers = server :: servers
    server

  /** The set's directory, laid out as `ModelFiles` lays it out */
  private def directory: Path = ModelFiles(root).directory

  private def downloader(
      freeSpace: Path => Long = _ => Long.MaxValue,
      stallTimeout: FiniteDuration = 60.seconds): ModelDownloader =
    ModelDownloader(ModelFiles(root), sleep = delay => slept.add(delay): Unit, freeSpace = freeSpace, stallTimeout = stallTimeout)

  private def ensure(files: ModelFileSpec*): Unit = downloader().ensure(files)

  private def content(size: Int, seed: Int): Array[Byte] =
    val bytes = new Array[Byte](size)
    new Random(seed).nextBytes(bytes)
    bytes

  /** A file the server serves at `/<name>`, pinned to the content's size and SHA-256 */
  private def served(server: FileServer, name: String, bytes: Array[Byte]): ModelFileSpec =
    server.serve(s"/$name", bytes)
    ModelFileSpec(name, server.url(s"/$name"), bytes.length, Sha256.hex(bytes))

  private def fileOf(spec: ModelFileSpec): Path = directory.resolve(spec.name)
  private def partOf(spec: ModelFileSpec): Path = directory.resolve(s"${spec.name}.part")

  private def writePart(spec: ModelFileSpec, bytes: Array[Byte]): Unit =
    Files.createDirectories(directory)
    Files.write(partOf(spec), bytes): Unit

  test("Missing files are downloaded and verified, and a second ensure sends no request") {

    /**
     * Setup:
     *
     * A local server with two files, neither in the empty directory.
     *
     * Assertions:
     *
     * One ensure downloads each file once, under its name and with its content, and leaves no `.part`; a second ensure sends no
     * request at all.
     *
     * Edge cases:
     *
     * Every request names the generic `User-Agent`, `Altitude`, and the directory is created when it does not exist.
     */
    val server = newServer()
    val first = content(300_000, 1)
    val second = content(70_000, 2)
    val specs = Seq(served(server, "first.onnx", first), served(server, "second.f16", second))

    ensure(specs*)

    Files.readAllBytes(fileOf(specs(0))) shouldEqual first
    Files.readAllBytes(fileOf(specs(1))) shouldEqual second
    specs.foreach(spec => Files.exists(partOf(spec)) shouldBe false)
    server.requests.asScala.toList.map(_.path) shouldEqual List("/first.onnx", "/second.f16")
    server.requests.asScala.toList.flatMap(_.userAgent).distinct shouldEqual List("Altitude")

    ensure(specs*)

    server.requests.size shouldBe 2
  }

  test("A file of the wrong size is downloaded again") {

    /**
     * Setup:
     *
     * A local server with one file, and a file under its name in the directory that is shorter than its pinned size.
     *
     * Assertions:
     *
     * The ensure downloads the file and replaces the short one with the served content.
     */
    val server = newServer()
    val bytes = content(120_000, 3)
    val spec = served(server, "model.onnx", bytes)
    Files.createDirectories(directory)
    Files.write(fileOf(spec), bytes.take(1000))

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").size shouldBe 1
  }

  test("A hash mismatch fails at once and leaves neither the file nor its .part") {

    /**
     * Setup:
     *
     * A local server serving a file of the pinned size whose SHA-256 is not the pinned one.
     *
     * Assertions:
     *
     * The ensure fails naming the mismatch, and the directory holds neither the file nor its `.part`.
     *
     * Edge cases:
     *
     * A mismatch is not retried: the server sees one request and the downloader never sleeps.
     */
    val server = newServer()
    val spec = served(server, "model.onnx", content(50_000, 4)).copy(sha256 = Sha256.hex(content(50_000, 5)))

    val error = intercept[ModelFilesException](ensure(spec))

    error.getMessage should include("SHA-256")
    Files.exists(fileOf(spec)) shouldBe false
    Files.exists(partOf(spec)) shouldBe false
    server.requestsOf("/model.onnx").size shouldBe 1
    slept shouldBe empty
  }

  test("A body longer than the pin fails without writing past it") {

    /**
     * Setup:
     *
     * A local server that sends the file's 100,000 pinned bytes and 64 MB more after them, in a body of no declared length, as a
     * proxy or a replaced asset might.
     *
     * Assertions:
     *
     * The ensure fails naming the pinned size as soon as the body runs past it: the server's write of the body breaks off before
     * its end, and the directory holds neither the file nor its `.part`.
     *
     * Edge cases:
     *
     * An over-long body is not retried: the server sees one request and the downloader never sleeps.
     */
    val server = newServer()
    server.declaresLength = false
    val bytes = content(100_000, 21)
    server.serve("/model.onnx", bytes ++ new Array[Byte](64 * 1024 * 1024))
    val spec = ModelFileSpec("model.onnx", server.url("/model.onnx"), bytes.length, Sha256.hex(bytes))

    val error = intercept[ModelFilesException](ensure(spec))

    error.getMessage should include("longer than its pinned 100000 bytes")
    server.completed shouldBe empty
    Files.exists(fileOf(spec)) shouldBe false
    Files.exists(partOf(spec)) shouldBe false
    server.requestsOf("/model.onnx").size shouldBe 1
    slept shouldBe empty
  }

  test("A declared length that is not the pin fails before the body is read") {

    /**
     * Setup:
     *
     * A local server that serves 64 MB under the file's URL, with that length declared, for a file pinned to its first 100,000
     * bytes; and a second file with 30,000 of its bytes in its `.part` whose served content is 1,000 bytes longer than its pin.
     *
     * Assertions:
     *
     * The first ensure fails naming the `Content-Length` and the size expected, before the body: the server's write breaks off
     * before its end and no `.part` is written. The second fails naming the 206's `Content-Range`, whose total is not the pin.
     *
     * Edge cases:
     *
     * Neither is retried: the downloader never sleeps, and the second file's `.part` keeps the 30,000 bytes it held.
     */
    val server = newServer()
    val bytes = content(100_000, 22)
    server.serve("/model.onnx", bytes ++ new Array[Byte](64 * 1024 * 1024))
    val spec = ModelFileSpec("model.onnx", server.url("/model.onnx"), bytes.length, Sha256.hex(bytes))

    val error = intercept[ModelFilesException](ensure(spec))

    error.getMessage should include(s"Content-Length ${bytes.length + 64 * 1024 * 1024}, not the 100000 bytes expected")
    server.completed shouldBe empty
    Files.exists(partOf(spec)) shouldBe false

    val resumed = content(50_000, 23)
    server.serve("/resumed.onnx", resumed ++ new Array[Byte](1000))
    val resumedSpec = ModelFileSpec("resumed.onnx", server.url("/resumed.onnx"), resumed.length, Sha256.hex(resumed))
    writePart(resumedSpec, resumed.take(30_000))

    val rangeError = intercept[ModelFilesException](ensure(resumedSpec))

    rangeError.getMessage should include("Content-Range bytes 30000-50999/51000, not bytes 30000-49999/50000")
    Files.size(partOf(resumedSpec)) shouldBe 30_000
    slept shouldBe empty
  }

  test("A .part longer than the pin is dropped and the file downloaded from zero") {

    /**
     * Setup:
     *
     * A local server with one file, and a `.part` 1,000 bytes longer than the file's pin, as a body that ran past its pin before
     * the start was killed might leave it.
     *
     * Assertions:
     *
     * The ensure drops the `.part`, asks for the whole file (no `Range`), and the file verifies; nothing fails.
     */
    val server = newServer()
    val bytes = content(80_000, 24)
    val spec = served(server, "model.onnx", bytes)
    writePart(spec, bytes ++ new Array[Byte](1000))

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(None)
    slept shouldBe empty
  }

  test("A .part left by an interrupted start is resumed with a Range request") {

    /**
     * Setup:
     *
     * A local server with one file, and the file's first 100,000 bytes in its `.part`, as a start cut off mid-download leaves it.
     *
     * Assertions:
     *
     * The ensure asks for the rest alone (`Range: bytes=100000-`), appends the 206 answer to the `.part`, and the file verifies
     * against its pins, which covers the bytes already on disk.
     *
     * Edge cases:
     *
     * A `.part` that is already whole (cut off between its last byte and its move) is verified and moved without a request, which
     * a range past its end would only have the server refuse.
     */
    val server = newServer()
    val bytes = content(250_000, 6)
    val spec = served(server, "model.onnx", bytes)
    writePart(spec, bytes.take(100_000))
    val wholeBytes = content(40_000, 7)
    val whole = served(server, "whole.tsv", wholeBytes)
    writePart(whole, wholeBytes)

    ensure(spec, whole)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(Some("bytes=100000-"))
    Files.exists(partOf(spec)) shouldBe false
    Sha256.hex(fileOf(whole)) shouldEqual Sha256.hex(wholeBytes)
    server.requestsOf("/whole.tsv") shouldBe empty
  }

  test("A 200 answer to a Range request restarts the file from zero") {

    /**
     * Setup:
     *
     * A local server that ignores `Range` and always answers the whole file with a 200, and a `.part` of 100,000 bytes that are
     * not the file's.
     *
     * Assertions:
     *
     * The ensure asks for the rest, takes the 200 as the whole file, drops what the `.part` held, and the file verifies.
     */
    val server = newServer()
    server.honoursRange = false
    val bytes = content(250_000, 8)
    val spec = served(server, "model.onnx", bytes)
    writePart(spec, content(100_000, 9))

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(Some("bytes=100000-"))
  }

  test("A redirect to another host is followed, the Range request with it") {

    /**
     * Setup:
     *
     * Two local servers on different ports, so of different origins, as a release host and its CDN are: the first redirects the
     * file's URL to the second, which serves it. The file's first 60,000 bytes are in its `.part`.
     *
     * Assertions:
     *
     * The ensure follows the redirect, asks the second server for the rest with the same `Range`, and the file verifies.
     */
    val origin = newServer()
    val cdn = newServer()
    val bytes = content(200_000, 10)
    cdn.serve("/blob/model.onnx", bytes)
    origin.redirect("/model.onnx", cdn.url("/blob/model.onnx"))
    val spec = ModelFileSpec("model.onnx", origin.url("/model.onnx"), bytes.length, Sha256.hex(bytes))
    writePart(spec, bytes.take(60_000))

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    origin.requestsOf("/model.onnx").map(_.range) shouldEqual List(Some("bytes=60000-"))
    cdn.requestsOf("/blob/model.onnx").map(_.range) shouldEqual List(Some("bytes=60000-"))
  }

  test("An I/O error mid-file is retried after 2 seconds and resumes where it stopped") {

    /**
     * Setup:
     *
     * A local server whose first answer for the file drops the connection after 80,000 of its bytes.
     *
     * Assertions:
     *
     * The downloader sleeps 2 seconds (recorded, not waited), asks again for the rest alone (`Range: bytes=80000-`), and the file
     * verifies.
     */
    val server = newServer()
    val bytes = content(300_000, 11)
    val spec = served(server, "model.onnx", bytes)
    server.cutNext("/model.onnx", afterBytes = 80_000)

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(None, Some("bytes=80000-"))
    slept.asScala.toList shouldEqual List(2.seconds)
  }

  test("A body that stops arriving mid-file is closed after the stall timeout, retried and resumed") {

    /**
     * Setup:
     *
     * A local server whose first answer for the file sends 80,000 of its bytes and then nothing more, holding the connection
     * open, and a downloader whose stall timeout is 500 ms rather than 60 seconds.
     *
     * Assertions:
     *
     * The stalled body is closed once no byte has come for the stall timeout, which is an I/O error: the downloader sleeps 2
     * seconds (recorded, not waited), asks again for the rest alone (`Range: bytes=80000-`), and the file verifies.
     *
     * Edge cases:
     *
     * The client's own timeout covers the response headers alone, which came at once, so without the watchdog the read would wait
     * for as long as the server held the connection. The watchdog's checks run on one thread for every download, a daemon, so it
     * never keeps a process up.
     */
    val server = newServer()
    val bytes = content(300_000, 20)
    val spec = served(server, "model.onnx", bytes)
    server.stallNext("/model.onnx", afterBytes = 80_000)

    downloader(stallTimeout = 500.millis).ensure(Seq(spec))

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(None, Some("bytes=80000-"))
    slept.asScala.toList shouldEqual List(2.seconds)
    val watchdogs = Thread.getAllStackTraces.keySet.asScala.filter(_.getName == "model-download-watchdog")
    watchdogs.map(_.isDaemon) shouldEqual Set(true)
    watchdogs.size shouldBe 1
  }

  test("A 503 is retried 2, 4 and 8 seconds apart, then served") {

    /**
     * Setup:
     *
     * A local server that answers the file with a 503 three times before it serves it.
     *
     * Assertions:
     *
     * The downloader sleeps 2, 4 and 8 seconds (recorded, not waited), its fourth request is served, and the file verifies.
     */
    val server = newServer()
    val bytes = content(90_000, 12)
    val spec = served(server, "model.onnx", bytes)
    server.answer("/model.onnx", 503, times = 3)

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").size shouldBe 4
    slept.asScala.toList shouldEqual List(2.seconds, 4.seconds, 8.seconds)
  }

  test("A failed download names the files missing, their URLs and the directory to put them in") {

    /**
     * Setup:
     *
     * A local server that serves the first of three files and answers the second with a 404; the third is never reached.
     *
     * Assertions:
     *
     * The ensure fails with a message that names the 404 and, for the second and third files, their names, URLs, sizes and
     * SHA-256, and the directory to put them in by hand; the first file is in place and not named.
     *
     * Edge cases:
     *
     * A 404 is not retried: the server sees one request for the second file and the downloader never sleeps.
     */
    val server = newServer()
    val first = served(server, "first.onnx", content(30_000, 13))
    val second = ModelFileSpec("second.onnx", server.url("/second.onnx"), 1234, "ab" * 32)
    val third = served(server, "third.f16", content(20_000, 14))

    val message = intercept[ModelFilesException](ensure(first, second, third)).getMessage

    message should include("404")
    message should include(directory.toString)
    Seq(second, third).foreach {
      spec =>
        message should include(spec.name)
        message should include(spec.url.toString)
        message should include(spec.size.toString)
        message should include(spec.sha256)
    }
    (message should not).include(first.url.toString)
    Files.exists(fileOf(first)) shouldBe true
    server.requestsOf("/second.onnx").size shouldBe 1
    server.requestsOf("/third.f16") shouldBe empty
    slept shouldBe empty
  }

  test("A file still failing after the third retry fails the ensure") {

    /**
     * Setup:
     *
     * A local server that answers the file with a 503 four times.
     *
     * Assertions:
     *
     * The downloader sleeps 2, 4 and 8 seconds, gives up after its fourth request, and fails with the files missing, the last
     * error among them; the file is not in place.
     */
    val server = newServer()
    val spec = served(server, "model.onnx", content(10_000, 15))
    server.answer("/model.onnx", 503, times = 4)

    val message = intercept[ModelFilesException](ensure(spec)).getMessage

    message should include("503")
    message should include(spec.url.toString)
    server.requestsOf("/model.onnx").size shouldBe 4
    slept.asScala.toList shouldEqual List(2.seconds, 4.seconds, 8.seconds)
    Files.exists(fileOf(spec)) shouldBe false
  }

  test("Two threads ensuring the same files at once download each file once") {

    /**
     * Setup:
     *
     * A local server with two files that answers every request after 300 ms, so a download is still running when the second
     * thread arrives, and two threads that start ensuring them at the same moment, each with a downloader of its own, as two test
     * bundles' instances in one JVM would.
     *
     * Assertions:
     *
     * Both ensures succeed, each file is requested once, and both files verify: the thread that waited checked again once it had
     * the lock and found them in place.
     *
     * Edge cases:
     *
     * Both threads take the same lock file, which one JVM cannot hold twice: the process-wide lock keeps the second from trying.
     */
    val server = newServer()
    server.delay = 300.millis
    val first = content(60_000, 16)
    val second = content(40_000, 17)
    val specs = Seq(served(server, "first.onnx", first), served(server, "second.tsv", second))
    val start = CountDownLatch(1)
    val errors = ConcurrentLinkedQueue[Throwable]()
    val threads = (1 to 2).map {
      _ =>
        val thread = Thread(
          () =>
            start.await()
            try downloader().ensure(specs)
            catch { case e: Throwable => errors.add(e): Unit })
        thread.start()
        thread
    }

    start.countDown()
    threads.foreach(_.join(TimeUnit.SECONDS.toMillis(30)))

    errors shouldBe empty
    server.requestsOf("/first.onnx").size shouldBe 1
    server.requestsOf("/second.tsv").size shouldBe 1
    Sha256.hex(fileOf(specs(0))) shouldEqual Sha256.hex(first)
    Sha256.hex(fileOf(specs(1))) shouldEqual Sha256.hex(second)
  }

  test("Too little free space for the bytes missing fails before any request") {

    /**
     * Setup:
     *
     * A local server with two files of 50,000 bytes, the first with 20,000 bytes already in its `.part`, and a directory whose
     * file store reports 79,999 bytes free, one short of the 80,000 still to download.
     *
     * Assertions:
     *
     * The ensure fails naming the space needed and free and the files missing, and the server sees no request.
     *
     * Edge cases:
     *
     * With exactly 80,000 bytes free, the bytes a `.part` already holds not counted again, the same files download.
     */
    val server = newServer()
    val first = content(50_000, 18)
    val specs = Seq(served(server, "first.onnx", first), served(server, "second.onnx", content(50_000, 19)))
    writePart(specs(0), first.take(20_000))

    val message =
      intercept[ModelFilesException](downloader(freeSpace = _ => 79_999).ensure(specs)).getMessage

    message should include("80000")
    message should include("79999")
    specs.foreach(spec => message should include(spec.url.toString))
    server.requests shouldBe empty

    downloader(freeSpace = _ => 80_000).ensure(specs)

    specs.foreach(spec => Files.exists(fileOf(spec)) shouldBe true)
  }

  test("The set lives in models.dir, else in the per-user cache, in a directory named by its tag") {

    /**
     * Setup:
     *
     * `models.dir` as configured, empty, or relative, with `XDG_CACHE_HOME` set, unset, empty or relative, and a home directory.
     *
     * Assertions:
     *
     * An absolute `models.dir` is the root as it is; an empty one is `$XDG_CACHE_HOME/altitude/models`, else
     * `~/.cache/altitude/models`. The set's files are under the root in the directory named by its tag, `v1`, by their names.
     *
     * Edge cases:
     *
     * A relative `models.dir` is under the application's root path, as the data directory is; an empty or relative
     * `XDG_CACHE_HOME` is ignored, as the XDG spec says.
     */
    val noEnv: String => Option[String] = _ => None
    def xdg(value: String): String => Option[String] = Map("XDG_CACHE_HOME" -> value).get

    ModelFiles.root("/srv/models", xdg("/var/cache"), "/home/user") shouldEqual Path.of("/srv/models")
    ModelFiles.root("models", noEnv, "/home/user") shouldEqual Path.of(Environment.ROOT_PATH, "models")
    ModelFiles.root("", xdg("/var/cache"), "/home/user") shouldEqual Path.of("/var/cache/altitude/models")
    ModelFiles.root("", noEnv, "/home/user") shouldEqual Path.of("/home/user/.cache/altitude/models")
    ModelFiles.root("", xdg(""), "/home/user") shouldEqual Path.of("/home/user/.cache/altitude/models")
    ModelFiles.root("", xdg("cache"), "/home/user") shouldEqual Path.of("/home/user/.cache/altitude/models")

    val models = ModelFiles(Path.of("/srv/models"))
    models.directory shouldEqual Path.of("/srv/models/v1")
    models.path(ModelFile.Dinov2) shouldEqual Path.of("/srv/models/v1/dinov2_vitb14.onnx")
  }
}
