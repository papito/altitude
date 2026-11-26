package altitude.core.unit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll
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
 * pins, and what is in place is never asked for again. Every server is local, so no test touches the network.
 */
@DoNotDiscover class ModelDownloaderTests extends AnyFunSuite with BeforeAndAfterAll with BeforeAndAfterEach {

  /**
   * The downloader's loggers, which `logback-test.xml` keeps at INFO so a real first download shows its progress. This suite
   * turns them off while it runs, since its small downloads would print some 300 lines; the unit suites run one at a time, so no
   * real download is silenced.
   */
  private val downloaderLoggers =
    Seq(classOf[ModelDownloader].getName, ModelDownloader.getClass.getName)
      .map(org.slf4j.LoggerFactory.getLogger(_).asInstanceOf[Logger])
  private var downloaderLevels: Seq[Level] = Nil

  override def beforeAll(): Unit =
    downloaderLevels = downloaderLoggers.map(_.getLevel)
    downloaderLoggers.foreach(_.setLevel(Level.OFF))

  override def afterAll(): Unit = downloaderLoggers.zip(downloaderLevels).foreach((logger, level) => logger.setLevel(level))

  /** One request the server saw */
  private case class Request(path: String, range: Option[String], userAgent: Option[String], upgrade: Option[String])

  /**
   * A local stand-in for the release host or its CDN. It serves files by path, honouring `Range` with a 206 unless told not to,
   * and can redirect a path, answer a status first a number of times, cut or stall the next answer of a path partway, and delay
   * every answer. It records every request and every path whose body it wrote in full.
   */
  private class FileServer {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    private val contents = ConcurrentHashMap[String, Array[Byte]]()
    private val redirects = ConcurrentHashMap[String, String]()
    // The answers a path is given before it is served, in order: a status and its headers
    private val statuses = ConcurrentHashMap[String, ConcurrentLinkedQueue[(Int, Map[String, String])]]()
    // A cut answer's byte count, and a check that the client has taken them
    private val cuts = ConcurrentHashMap[String, (Int, () => Boolean)]()
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
    def answer(path: String, status: Int, times: Int, headers: Map[String, String] = Map.empty): Unit =
      val answers = statuses.computeIfAbsent(path, _ => ConcurrentLinkedQueue())
      (1 to times).foreach(_ => answers.add((status, headers)))
    def cutNext(path: String, afterBytes: Int, taken: () => Boolean): Unit = cuts.put(path, (afterBytes, taken)): Unit
    def stallNext(path: String, afterBytes: Int): Unit = stalls.put(path, afterBytes): Unit

    def requestsOf(path: String): List[Request] = requests.asScala.toList.filter(_.path == path)

    def stop(): Unit =
      stopped.countDown()
      server.stop(0)
      executor.shutdownNow(): Unit

    private def answer(exchange: HttpExchange): Unit =
      val path = exchange.getRequestURI.getPath
      val range = Option(exchange.getRequestHeaders.getFirst("Range"))
      val headers = exchange.getRequestHeaders
      requests.add(Request(path, range, Option(headers.getFirst("User-Agent")), Option(headers.getFirst("Upgrade"))))
      if delay > Duration.Zero then Thread.sleep(delay.toMillis)

      val status = Option(statuses.get(path)).flatMap(answers => Option(answers.poll()))
      if redirects.containsKey(path) then
        exchange.getResponseHeaders.add("Location", redirects.get(path))
        exchange.sendResponseHeaders(302, -1)
      else if status.isDefined then
        status.get._2.foreach((name, value) => exchange.getResponseHeaders.add(name, value))
        exchange.sendResponseHeaders(status.get._1, -1)
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
          case (Some((afterBytes, taken)), _) =>
            // Drops the connection only once the client has taken the part, so the cut lands at a byte a test can name
            exchange.getResponseBody.write(body, 0, afterBytes)
            exchange.getResponseBody.flush()
            val deadline = System.nanoTime() + 10.seconds.toNanos
            while !taken() && System.nanoTime() < deadline do Thread.sleep(5)
            throw IOException("cut short")
          case (_, Some(afterBytes)) =>
            // Part of the body, then nothing, the connection held open until the server stops
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

  /** The set's directory as `ModelFiles` lays it out */
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
     * One ensure downloads each file once and leaves no `.part`; a second sends no request.
     *
     * Edge cases:
     *
     * Every request carries the generic `User-Agent` `Altitude`, and the missing directory is created.
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

  test("The downloader's client speaks HTTP/1.1") {

    /**
     * Setup:
     *
     * A local server, plain HTTP, with one file, and a downloader with its default client.
     *
     * Assertions:
     *
     * The request carries no `Upgrade: h2c`, so the client stays on HTTP/1.1 and a connection whose body was cancelled is closed
     * rather than kept for the retry.
     */
    val server = newServer()
    val spec = served(server, "model.onnx", content(10_000, 28))

    ensure(spec)

    server.requestsOf("/model.onnx").map(_.upgrade) shouldEqual List(None)
  }

  test("The HTTP client is built only for a download, once, and shut down after it") {

    /**
     * Setup:
     *
     * A local server with two files, and downloaders whose client factory counts the clients it builds and keeps them.
     *
     * Assertions:
     *
     * An ensure that downloads both files builds one client and leaves it shut down; one that finds every file in place builds
     * none, so a start with the files in place makes no client and no selector thread.
     */
    val server = newServer()
    val specs = Seq(served(server, "first.onnx", content(30_000, 29)), served(server, "second.onnx", content(20_000, 30)))
    val built = ConcurrentLinkedQueue[java.net.http.HttpClient]()
    def counting(): ModelDownloader =
      ModelDownloader(
        ModelFiles(root),
        newClient = () =>
          val client = ModelDownloader.newClient()
          built.add(client)
          client
        ,
        sleep = delay => slept.add(delay): Unit
      )

    counting().ensure(specs)

    built.size shouldBe 1
    // A shutdown client's selector thread ends on its own time; one never shut down would not end within the 5 seconds
    built.peek().awaitTermination(java.time.Duration.ofSeconds(5)) shouldBe true

    counting().ensure(specs)

    built.size shouldBe 1
  }

  test("A file of the wrong size is downloaded again") {

    /**
     * Setup:
     *
     * A local server with one file, and a file of that name in the directory shorter than its pin.
     *
     * Assertions:
     *
     * The ensure downloads the file again and replaces the short one.
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

  test("A short body that ends cleanly fails at once on its size") {

    /**
     * Setup:
     *
     * A local server that sends 40,000 bytes of a file pinned to 50,000, in a body of no declared length that ends cleanly.
     *
     * Assertions:
     *
     * The ensure fails naming both sizes, and the directory holds neither the file nor its `.part`.
     *
     * Edge cases:
     *
     * A short file is not retried: the server sees one request and the downloader never sleeps.
     */
    val server = newServer()
    server.declaresLength = false
    val bytes = content(50_000, 31)
    server.serve("/model.onnx", bytes.take(40_000))
    val spec = ModelFileSpec("model.onnx", server.url("/model.onnx"), bytes.length, Sha256.hex(bytes))

    val error = intercept[ModelFilesException](ensure(spec))

    error.getMessage should include("came as 40000 bytes")
    error.getMessage should include("not its pinned 50000 bytes")
    Files.exists(fileOf(spec)) shouldBe false
    Files.exists(partOf(spec)) shouldBe false
    server.requestsOf("/model.onnx").size shouldBe 1
    slept shouldBe empty
  }

  test("A body longer than the pin fails without writing past it") {

    /**
     * Setup:
     *
     * A local server that sends the file's 100,000 pinned bytes and 64 MB more, with no declared length, as a proxy or a replaced
     * asset might.
     *
     * Assertions:
     *
     * The ensure fails naming the pinned size as soon as the body runs past it, so the server's write breaks off, and the
     * directory holds neither the file nor its `.part`.
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
     * A file pinned to 100,000 bytes served with 64 MB more and that length declared; and a second file with 30,000 bytes in its
     * `.part` whose served content is 1,000 bytes longer than its pin.
     *
     * Assertions:
     *
     * The first ensure fails on the `Content-Length` before the body, so the server's write breaks off and no `.part` is written.
     * The second fails on the 206's `Content-Range`, whose total is not the pin.
     *
     * Edge cases:
     *
     * Neither is retried, and the second file's `.part` keeps its 30,000 bytes.
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
     * A local server with one file, and a `.part` 1,000 bytes longer than the file's pin.
     *
     * Assertions:
     *
     * The ensure drops the `.part`, asks for the whole file with no `Range`, and the file verifies.
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
     * A local server with one file, and the file's first 100,000 bytes in its `.part`.
     *
     * Assertions:
     *
     * The ensure asks for the rest alone (`Range: bytes=100000-`), appends it to the `.part`, and the whole file verifies.
     *
     * Edge cases:
     *
     * A `.part` that is already whole (cut off before its move) is verified and moved without a request, since the server would
     * refuse a range past its end.
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
     * A local server that ignores `Range` and answers the whole file with a 200, and a `.part` of 100,000 bytes that are not the
     * file's.
     *
     * Assertions:
     *
     * The ensure asks for the rest, takes the 200 as the whole file in place of the `.part`, and the file verifies.
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
     * Two local servers on different ports (different origins, as a release host and its CDN): the first redirects the file to
     * the second. The file's first 60,000 bytes are in its `.part`.
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

  test("A redirected failure names the pinned URL and the host that answered, not its signed path") {

    /**
     * Setup:
     *
     * Two local servers: the first redirects the file to a signed path on the second, which answers a 404.
     *
     * Assertions:
     *
     * The message names the 404, the pinned URL and the host and port that answered, but not the redirected path or query, which
     * carry a CDN's signature.
     */
    val origin = newServer()
    val cdn = newServer()
    origin.redirect("/model.onnx", cdn.url("/blob/model.onnx?X-Amz-Signature=secret"))
    val spec = ModelFileSpec("model.onnx", origin.url("/model.onnx"), 1000, "ab" * 32)

    val message = intercept[ModelFilesException](ensure(spec)).getMessage

    message should include(s"HTTP 404 for ${spec.url}")
    message should include(s"127.0.0.1:${cdn.url("/").getPort}")
    (message should not).include("/blob")
    (message should not).include("secret")
  }

  test("An I/O error mid-file is retried after 2 seconds and resumes where it stopped") {

    /**
     * Setup:
     *
     * A local server whose first answer drops the connection after 80,000 bytes, once the `.part` holds them.
     *
     * Assertions:
     *
     * The downloader sleeps 2 seconds (recorded, not waited), asks for the rest alone (`Range: bytes=80000-`), and the file
     * verifies.
     */
    val server = newServer()
    val bytes = content(300_000, 11)
    val spec = served(server, "model.onnx", bytes)
    server.cutNext(
      "/model.onnx",
      afterBytes = 80_000,
      taken = () => Files.isRegularFile(partOf(spec)) && Files.size(partOf(spec)) >= 80_000)

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").map(_.range) shouldEqual List(None, Some("bytes=80000-"))
    slept.asScala.toList shouldEqual List(2.seconds)
  }

  test("A body that stops arriving mid-file is closed after the stall timeout, retried and resumed") {

    /**
     * Setup:
     *
     * A local server whose first answer sends 80,000 bytes and then nothing, holding the connection open, and a downloader with a
     * 500 ms stall timeout.
     *
     * Assertions:
     *
     * The stalled body is closed after the stall timeout as an I/O error: the downloader sleeps 2 seconds (recorded, not waited),
     * asks for the rest alone, and the file verifies.
     *
     * Edge cases:
     *
     * The client's own timeout covers only the response headers, so without the watchdog the read would wait as long as the
     * server held on. The watchdog is one daemon thread for every download, so it never keeps a process up.
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
     * A local server that answers a 503 three times before serving the file.
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

  test("A 429 is retried after its Retry-After, capped at a minute, then served") {

    /**
     * Setup:
     *
     * A local server that answers a 429 three times, with `Retry-After: 3`, `Retry-After: 3600` and an HTTP-date 30 seconds
     * ahead, before serving the file.
     *
     * Assertions:
     *
     * The downloader waits what each answer asks instead of its own backoff (recorded, not waited): 3 seconds, then the
     * one-minute cap, then the time to the date; the fourth request is served and the file verifies.
     *
     * Edge cases:
     *
     * The date has whole seconds, so its wait is a little under 30 seconds by the time it is read.
     */
    val server = newServer()
    val bytes = content(40_000, 26)
    val spec = served(server, "model.onnx", bytes)
    val date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
      .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30))
    server.answer("/model.onnx", 429, times = 1, Map("Retry-After" -> "3"))
    server.answer("/model.onnx", 429, times = 1, Map("Retry-After" -> "3600"))
    server.answer("/model.onnx", 429, times = 1, Map("Retry-After" -> date))

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").size shouldBe 4
    val waits = slept.asScala.toList
    waits.take(2) shouldEqual List(3.seconds, 60.seconds)
    waits(2) should ((be > 28.seconds).and(be <= 30.seconds))
  }

  test("A 408 is retried after 2 seconds, then served") {

    /**
     * Setup:
     *
     * A local server that answers a 408 with no `Retry-After` once before serving the file.
     *
     * Assertions:
     *
     * The downloader sleeps its own 2 seconds (recorded, not waited), its second request is served, and the file verifies.
     */
    val server = newServer()
    val bytes = content(20_000, 27)
    val spec = served(server, "model.onnx", bytes)
    server.answer("/model.onnx", 408, times = 1)

    ensure(spec)

    Sha256.hex(fileOf(spec)) shouldEqual Sha256.hex(bytes)
    server.requestsOf("/model.onnx").size shouldBe 2
    slept.asScala.toList shouldEqual List(2.seconds)
  }

  test("A failed download names the files missing, their URLs and the directory to put them in") {

    /**
     * Setup:
     *
     * A local server that serves the first of three files and answers the second with a 404; the third is never reached.
     *
     * Assertions:
     *
     * The message names the 404, the second and third files' names, URLs, sizes and SHA-256, and the directory to put them in by
     * hand; the first file is in place and not named.
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
     * A local server that answers a 503 four times.
     *
     * Assertions:
     *
     * The downloader sleeps 2, 4 and 8 seconds, gives up after its fourth request, and fails naming the last error and the file
     * missing; the file is not in place.
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

  test("A refused connection names its root cause and the host, and keeps it as the cause") {

    /**
     * Setup:
     *
     * A file whose URL is a port on 127.0.0.1 with nothing listening on it.
     *
     * Assertions:
     *
     * After three retries the message names the I/O error, the root of its cause chain and the host, and the exception keeps the
     * I/O error as its cause.
     *
     * Edge cases:
     *
     * The JDK reports a refused connection as a `ConnectException` with no message, the detail only at the root of its chain, so
     * the error alone would say nothing.
     */
    val port =
      val socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
      try socket.getLocalPort
      finally socket.close()
    val spec = ModelFileSpec("model.onnx", URI.create(s"http://127.0.0.1:$port/model.onnx"), 1000, "ab" * 32)

    val error = intercept[ModelFilesException](ensure(spec))

    error.getCause shouldBe a[java.net.ConnectException]
    val root = Iterator.iterate(error.getCause)(_.getCause).takeWhile(_ != null).toSeq.last
    root should not be theSameInstanceAs(error.getCause)
    error.getMessage should include(s"ConnectException (${root.getClass.getSimpleName}), downloading model.onnx from 127.0.0.1")
    slept.asScala.toList shouldEqual List(2.seconds, 4.seconds, 8.seconds)
  }

  test("A directory that cannot be made fails naming the files and the directory") {

    /**
     * Setup:
     *
     * A regular file where the set's directory should be, and a file missing from it.
     *
     * Assertions:
     *
     * The ensure fails with a `ModelFilesException` naming the I/O error, the missing file with its URL and the directory, with
     * the I/O error as its cause; the server sees no request.
     */
    val server = newServer()
    val spec = served(server, "model.onnx", content(10_000, 25))
    Files.write(directory, Array[Byte](1))

    val error = intercept[ModelFilesException](ensure(spec))

    error.getMessage should include("FileAlreadyExistsException")
    error.getMessage should include(directory.toString)
    error.getMessage should include(spec.name)
    error.getMessage should include(spec.url.toString)
    error.getCause shouldBe a[java.nio.file.FileAlreadyExistsException]
    server.requests shouldBe empty
  }

  test("Two threads ensuring the same files at once download each file once") {

    /**
     * Setup:
     *
     * A local server with two files that answers after 300 ms, and two threads, each with its own downloader, ensuring them at
     * the same moment, as two test bundles in one JVM would.
     *
     * Assertions:
     *
     * Both ensures succeed and each file is requested once: the thread that waited checked again under the lock and found them in
     * place.
     *
     * Edge cases:
     *
     * One JVM cannot hold the same lock file twice, so a process-wide lock keeps the second thread from trying.
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
     * Two files of 50,000 bytes, the first with 20,000 already in its `.part`, and 79,999 bytes free, one short of the 80,000
     * still to download.
     *
     * Assertions:
     *
     * The ensure fails naming the space needed and free and the missing files, before any request.
     *
     * Edge cases:
     *
     * With exactly 80,000 bytes free the files download: a `.part`'s bytes are not counted again.
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

  test("Each model file is pinned to its release asset or its Hugging Face revision, with a size and a SHA-256") {

    /**
     * Setup:
     *
     * The seven files of the set, read offline from `ModelFile`.
     *
     * Assertions:
     *
     * Six are release assets at `.../releases/download/v1/<name>` and ArcFace is at a pinned revision of `maze/faceX`; every name
     * is distinct, every size positive, and every SHA-256 64 lowercase hex characters.
     *
     * Edge cases:
     *
     * The URLs are built along with the enum's cases, so a tag or revision not yet initialized then would show as `null`.
     */
    val release = "https://github.com/renegade-otter/altitude-models/releases/download/v1"
    ModelFile.values.map(file => file -> file.spec.url.toString).toMap shouldEqual Map(
      ModelFile.Dinov2 -> s"$release/dinov2_vitb14.onnx",
      ModelFile.Siglip2Image -> s"$release/siglip2_b16_256_image.onnx",
      ModelFile.Siglip2Text -> s"$release/siglip2_b16_256_text.onnx",
      ModelFile.Siglip2TokenEmbeddings -> s"$release/siglip2_token_embeddings.f16",
      ModelFile.Siglip2Tokenizer -> s"$release/siglip2_tokenizer.tsv",
      ModelFile.YuNet -> s"$release/face_detection_yunet_2023mar.onnx",
      ModelFile.ArcFace ->
        "https://huggingface.co/maze/faceX/resolve/e010b5098c3685fd00b22dd2aec6f37320e3d850/w600k_r50.onnx"
    )
    ModelFile.values.map(_.spec.name).distinct.length shouldBe ModelFile.values.length
    ModelFile.values.foreach {
      file =>
        withClue(s"$file: ") {
          file.spec.size should be > 0L
          (file.spec.sha256 should fullyMatch).regex("[0-9a-f]{64}")
        }
    }
  }

  /**
   * An absolute path on this system, written as one on Linux: `/srv/models` is `D:\srv\models` on Windows, where a path from `/`
   * is no absolute one
   */
  private def abs(posix: String): String = Path.of("").toAbsolutePath.getRoot.resolve(posix.stripPrefix("/")).toString

  test("The set lives in models.dir, else in the per-user cache, in a directory named by its tag") {

    /**
     * Setup:
     *
     * `models.dir` as configured, empty, or relative, with `XDG_CACHE_HOME` set, unset, empty or relative, and a home directory.
     *
     * Assertions:
     *
     * An absolute `models.dir` is the root; an empty one is `$XDG_CACHE_HOME/altitude/models`, else `~/.cache/altitude/models`.
     * The files are in the root's `v1` directory, by name.
     *
     * Edge cases:
     *
     * A relative `models.dir` is under the application's root path; an empty or relative `XDG_CACHE_HOME` is ignored, as the XDG
     * spec says.
     */
    val noEnv: String => Option[String] = _ => None
    def xdg(value: String): String => Option[String] = Map("XDG_CACHE_HOME" -> value).get

    ModelFiles.root(abs("/srv/models"), xdg(abs("/var/cache")), abs("/home/user")) shouldEqual Path.of(abs("/srv/models"))
    ModelFiles.root("models", noEnv, abs("/home/user")) shouldEqual Path.of(Environment.ROOT_PATH, "models")
    ModelFiles.root("", xdg(abs("/var/cache")), abs("/home/user")) shouldEqual Path.of(abs("/var/cache/altitude/models"))
    ModelFiles.root("", noEnv, abs("/home/user")) shouldEqual Path.of(abs("/home/user/.cache/altitude/models"))
    ModelFiles.root("", xdg(""), abs("/home/user")) shouldEqual Path.of(abs("/home/user/.cache/altitude/models"))
    ModelFiles.root("", xdg("cache"), abs("/home/user")) shouldEqual Path.of(abs("/home/user/.cache/altitude/models"))

    val models = ModelFiles(Path.of(abs("/srv/models")))
    models.directory shouldEqual Path.of(abs("/srv/models/v1"))
    models.path(ModelFile.Dinov2) shouldEqual Path.of(abs("/srv/models/v1/dinov2_vitb14.onnx"))
  }

  test("A models.dir under ~ is under the home, and no absolute home fails asking for models.dir") {

    /**
     * Setup:
     *
     * `models.dir` as `~`, `~/models` and empty, with a home directory that is absolute, `?` (the JVM's `user.home` for a user
     * with no passwd entry, and no path at all on Windows), one with a NUL (no path on any system), or unset.
     *
     * Assertions:
     *
     * `~` and a leading `~/` are the home directory. With no absolute home, an empty `models.dir` (and no absolute
     * `XDG_CACHE_HOME`) or one using `~` fails asking for `models.dir` or `ALTITUDE_MODELS_DIR`, rather than resolving against
     * the working directory.
     *
     * Edge cases:
     *
     * An absolute `XDG_CACHE_HOME` needs no home, and `~user` is a plain name under the application's root path.
     */
    val noEnv: String => Option[String] = _ => None

    ModelFiles.root("~", noEnv, abs("/home/user")) shouldEqual Path.of(abs("/home/user"))
    ModelFiles.root("~/models", noEnv, abs("/home/user")) shouldEqual Path.of(abs("/home/user/models"))
    ModelFiles.root("~user", noEnv, abs("/home/user")) shouldEqual Path.of(Environment.ROOT_PATH, "~user")
    ModelFiles.root("", Map("XDG_CACHE_HOME" -> abs("/var/cache")).get, "?") shouldEqual Path.of(
      abs("/var/cache/altitude/models"))

    Seq(("", "?"), ("", null), ("~/models", "?"), ("~", null), ("~", "/home/\u0000user")).foreach {
      (configured, home) =>
        val message = intercept[ModelFilesException](ModelFiles.root(configured, noEnv, home)).getMessage
        message should include("models.dir")
        message should include("ALTITUDE_MODELS_DIR")
    }
  }
}
