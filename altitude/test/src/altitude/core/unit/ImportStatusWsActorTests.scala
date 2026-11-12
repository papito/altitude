package altitude.core.unit

import altitude.test.TestFocus
import com.typesafe.config.ConfigFactory
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.scalatest.BeforeAndAfterEach
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import altitude.core.Api
import altitude.core.Const
import altitude.core.actors.ImportStatusWsActor
import altitude.core.models.Import
import altitude.core.models.ImportSource
import altitude.core.service.AccountVersions

object ImportStatusWsActorTests {

  /** A socket that keeps what it is sent, in order */
  final class Socket extends castor.Actor[cask.Ws.Event] {
    private val sent = new LinkedBlockingQueue[cask.Ws.Event]()

    override def send(event: cask.Ws.Event)(implicit fileName: sourcecode.FileName, line: sourcecode.Line): Unit =
      sent.add(event)

    override def sendAsync(event: Future[cask.Ws.Event])(implicit fileName: sourcecode.FileName, line: sourcecode.Line): Unit =
      throw new UnsupportedOperationException("The import status actor sends a socket what it has")

    /** The next event the socket was sent, waiting for it; none within the wait fails */
    def next(): cask.Ws.Event =
      Option(sent.poll(10, TimeUnit.SECONDS)).getOrElse(throw new AssertionError("The socket was sent nothing"))

    /** Every event the socket was sent that no call has taken yet */
    def taken(): List[cask.Ws.Event] = {
      val events = new java.util.ArrayList[cask.Ws.Event]()
      sent.drainTo(events)
      events.asScala.toList
    }
  }
}

/**
 * The import status actor on an actor system of its own whose time the test moves (`ExplicitlyTriggeredScheduler`), with sockets
 * that keep what they are sent and a status rendered as a line of the Import's counters.
 */
@DoNotDiscover class ImportStatusWsActorTests extends funsuite.AnyFunSuite with TestFocus with BeforeAndAfterEach {
  import ImportStatusWsActorTests.Socket

  private val INTERVAL: FiniteDuration = 1.second
  private val LIBRARY = "library"
  private val OTHER_LIBRARY = "other-library"

  private var system: ActorSystem[ImportStatusWsActor.Command] = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    system = ActorSystem(
      ImportStatusWsActor(render = status, pushInterval = INTERVAL),
      "import-status-test",
      ConfigFactory
        .parseString("""pekko.scheduler.implementation = "org.apache.pekko.testkit.ExplicitlyTriggeredScheduler"""")
        .withFallback(ConfigFactory.load())
    )

  override def afterEach(): Unit = system.terminate()

  /** What the actor pushes for an Import in these tests: its ID and its counters, and that it is closed */
  private def status(anImport: Import): String =
    s"${anImport.persistedId}: ${anImport.imported} of ${anImport.total}${if (anImport.isInProgress) "" else ", closed"}"

  private def pushed(anImport: Import): cask.Ws.Event = cask.Ws.Text(status(anImport))

  /** An upload's Import in progress, `imported` of its `total` files imported */
  private def anImport(id: String, imported: Int, total: Int): Import =
    Import(id = Some(id), source = ImportSource.Upload, imported = imported, pending = total - imported)

  private def closed(anImport: Import): Import = anImport.copy(isComplete = true, closedAt = Some(LocalDateTime.now()))

  /** A socket of the account on the Library, added to the actor and told so */
  private def socketOf(repositoryId: String, userId: String = "account"): Socket = {
    val socket = new Socket
    system ! ImportStatusWsActor.AddClient(repositoryId, userId, socket)
    socket.next() shouldBe cask.Ws.Text(ImportStatusWsActor.CONNECTED)
    socket
  }

  /**
   * Waits until the actor has handled everything it was sent so far: a socket added now is told so only after that, the actor
   * handling its messages in the order they came
   */
  private def settle(): Unit = socketOf("a-library-with-no-import"): Unit

  /** Lets `duration` pass on the actor system's clock, which sends the timers due by then, and waits for the actor */
  private def after(duration: FiniteDuration): Unit = {
    system.classicSystem.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler].timePasses(duration)
    settle()
  }

  private def changed(repositoryId: String, anImport: Import): Unit =
    system ! ImportStatusWsActor.ImportChanged(repositoryId, anImport)

  test("An Import's status goes to every socket of its Library, and to no other Library's") {

    /**
     * Setup:
     *
     * Two accounts with sockets on one Library, the first with two; the first account also has a socket on another Library. An
     * Import of the first Library changes.
     *
     * Assertions:
     *
     * Each of the three sockets on the Import's Library is sent its status once, whichever account's it is. The socket on the
     * other Library is sent nothing.
     *
     * Edge cases:
     *
     * A socket that was removed is sent nothing of the next change. When the first account's versions are raised, its sockets on
     * both Libraries are closed with 1013 and sent nothing after; the second account's socket is still told.
     */
    val mine = List(socketOf(LIBRARY, "first"), socketOf(LIBRARY, "first"))
    val theirs = socketOf(LIBRARY, "second")
    val elsewhere = socketOf(OTHER_LIBRARY, "first")

    val started = anImport("import", imported = 1, total = 4)
    changed(LIBRARY, started)
    settle()
    for (socket <- theirs :: mine) socket.taken() shouldBe List(pushed(started))
    elsewhere.taken() shouldBe empty

    system ! ImportStatusWsActor.RemoveClient(LIBRARY, "first", mine.head)
    after(INTERVAL * 2)
    val further = anImport("import", imported = 2, total = 4)
    changed(LIBRARY, further)
    settle()
    mine.head.taken() shouldBe empty
    mine.last.taken() shouldBe List(pushed(further))

    system ! ImportStatusWsActor.CloseAllFor("first", AccountVersions.Change.Bumped)
    after(INTERVAL * 2)
    val furthest = anImport("import", imported = 3, total = 4)
    changed(LIBRARY, furthest)
    settle()
    mine.last.taken() shouldBe List(cask.Ws.Close(1013, "Account changed"))
    elsewhere.taken() shouldBe List(cask.Ws.Close(1013, "Account changed"))
    theirs.taken() shouldBe List(pushed(further), pushed(furthest))
  }

  test("A change to a Library's Watch folders is told to every socket of the Library, at once, and to no other Library's") {

    /**
     * Setup:
     *
     * Two sockets on a Library, of two accounts, and one on another Library. The first Library's Watch folders change twice in a
     * row.
     *
     * Assertions:
     *
     * Each socket of the first Library is sent the message that says to read the Watch folders again, twice, with no interval
     * awaited between the two. The other Library's socket is sent nothing.
     */
    val first = socketOf(LIBRARY, "first")
    val second = socketOf(LIBRARY, "second")
    val elsewhere = socketOf(OTHER_LIBRARY)
    val message = cask.Ws.Text(ImportStatusWsActor.WATCH_FOLDERS_CHANGED)

    system ! ImportStatusWsActor.WatchFoldersChanged(LIBRARY)
    system ! ImportStatusWsActor.WatchFoldersChanged(LIBRARY)
    settle()

    first.taken() shouldBe List(message, message)
    second.taken() shouldBe List(message, message)
    elsewhere.taken() shouldBe empty
    ujson.read(ImportStatusWsActor.WATCH_FOLDERS_CHANGED) shouldBe ujson.Obj("watchFolders" -> true)
  }

  test("A burst of changes to an Import is pushed as one message an interval, with the Import as it stands then") {

    /**
     * Setup:
     *
     * A socket on a Library. An Import of it changes, then five more times before an interval has passed, while a second Import
     * of the Library changes once.
     *
     * Assertions:
     *
     * The first change of each Import is pushed at once. Nothing more is pushed until the interval ends; then the first Import is
     * pushed once, as its last change left it, and the second, unchanged since, not at all.
     *
     * Edge cases:
     *
     * Another interval with no change pushes nothing, and the change after it is pushed at once.
     */
    val socket = socketOf(LIBRARY)

    val first = anImport("first", imported = 0, total = 10)
    val second = anImport("second", imported = 0, total = 3)
    changed(LIBRARY, first)
    changed(LIBRARY, second)
    for (imported <- 1 to 5) changed(LIBRARY, anImport("first", imported, total = 10))
    settle()
    socket.taken() shouldBe List(pushed(first), pushed(second))

    after(INTERVAL / 2)
    socket.taken() shouldBe empty

    after(INTERVAL / 2)
    socket.taken() shouldBe List(pushed(anImport("first", imported = 5, total = 10)))

    after(INTERVAL * 2)
    socket.taken() shouldBe empty

    val later = anImport("first", imported = 6, total = 10)
    changed(LIBRARY, later)
    settle()
    socket.taken() shouldBe List(pushed(later))
  }

  test("An Import's close is pushed at once, and nothing of the Import after it") {

    /**
     * Setup:
     *
     * A socket on a Library. An Import of it changes twice and closes, all before an interval has passed.
     *
     * Assertions:
     *
     * The first change is pushed at once, the second is held, and the close is pushed at once in its place: the socket's last
     * message of the Import says it is closed. The interval's end pushes nothing more.
     *
     * Edge cases:
     *
     * A change told late, with the Import as it was before a state the actor already has, is not pushed: neither one in progress
     * after the close, nor one with fewer files settled than the last one pushed.
     */
    val socket = socketOf(LIBRARY)

    val started = anImport("import", imported = 1, total = 3)
    val done = closed(anImport("import", imported = 3, total = 3))
    changed(LIBRARY, started)
    changed(LIBRARY, anImport("import", imported = 2, total = 3))
    changed(LIBRARY, done)
    settle()
    socket.taken() shouldBe List(pushed(started), pushed(done))

    after(INTERVAL)
    socket.taken() shouldBe empty

    changed(LIBRARY, anImport("import", imported = 2, total = 3))
    after(INTERVAL * 2)
    socket.taken() shouldBe empty

    val ahead = anImport("other", imported = 2, total = 3)
    changed(LIBRARY, ahead)
    after(INTERVAL * 2)
    changed(LIBRARY, anImport("other", imported = 1, total = 3))
    after(INTERVAL * 2)
    socket.taken() shouldBe List(pushed(ahead))
  }

  test("An Import that expects more files than it has is pushed in the order of its changes") {

    /**
     * Setup:
     *
     * A socket on a Library, and an upload's Import there that expects 10 files, told three times: with 1 file, with 2 files of
     * which 1 is settled, and closed with those 2.
     *
     * Assertions:
     *
     * The first change is pushed at once, the second once the interval ends, and the close at once: how far along an Import is
     * goes by the files it has and what became of them, never by the number it expects.
     *
     * Edge cases:
     *
     * The first state told again after the second, late, is not pushed.
     */
    val socket = socketOf(LIBRARY)
    val first = anImport("import", imported = 0, total = 1).copy(expectedFiles = Some(10))
    val second = anImport("import", imported = 1, total = 2).copy(expectedFiles = Some(10))

    changed(LIBRARY, first)
    changed(LIBRARY, second)
    changed(LIBRARY, first)
    settle()
    socket.taken() shouldBe List(pushed(first))

    after(INTERVAL)
    socket.taken() shouldBe List(pushed(second))

    val done = closed(second)
    changed(LIBRARY, done)
    settle()
    socket.taken() shouldBe List(pushed(done))
  }

  test("An Import's status message carries its status element with its name escaped") {

    /**
     * Setup:
     *
     * An upload's Import in progress whose name, an uploaded folder's, holds markup, with files of every outcome.
     *
     * Assertions:
     *
     * Its message is JSON for `hx-ws`: the status element of the Import's ID as the content, to replace the element of that ID on
     * the page, with the Import's ID and that it is not closed. The content holds the counts, the duplicates under "already in
     * the Library", and the Import's title to swap out of band, in which the name's markup is escaped.
     *
     * Edge cases:
     *
     * A closed Import's message says so, and its content names no files remaining.
     */
    val named = Import(
      id = Some("import"),
      source = ImportSource.Upload,
      name = Some("""<img src=x onerror="alert(1)">"""),
      pending = 1200,
      imported = 3281,
      duplicate = 308,
      unsupported = 45,
      failed = 9
    )

    val message = ujson.read(ImportStatusWsActor.statusMessage(named))
    message(Api.Field.ImportStatus.TARGET).str shouldBe s"#${Const.ImportHistory.statusId("import")}"
    message(Api.Field.ImportStatus.SWAP).str shouldBe "outerHTML"
    message(Api.Field.ImportStatus.IMPORT_ID).str shouldBe "import"
    message(Api.Field.ImportStatus.IS_CLOSED).bool shouldBe false

    val content = message(Api.Field.ImportStatus.CONTENT).str
    content should include(s"""id="${Const.ImportHistory.statusId("import")}"""")
    for (count <- List("3,281 imported", "308 already in the Library", "45 unsupported", "9 failed", "1,200 remaining"))
      content should include(count)
    content should include(s"""id="${Const.ImportHistory.titleId("import")}"""")
    content should include("hx-swap-oob")
    content should include("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;")
    (content should not).include("<img")

    val finished = ujson.read(ImportStatusWsActor.statusMessage(closed(named.copy(pending = 0))))
    finished(Api.Field.ImportStatus.IS_CLOSED).bool shouldBe true
    (finished(Api.Field.ImportStatus.CONTENT).str should not).include("remaining")
  }
}
