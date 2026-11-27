package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*
import scala.util.Random

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.ForbiddenException
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.ImporterUnavailableException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.SceneEmbedderClosedException
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.ValidationException
import altitude.core.VideoException
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.Asset
import altitude.core.models.Import
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Settings
import altitude.core.models.Stats
import altitude.core.service.ImportPipelineService
import altitude.core.service.ImportService
import altitude.core.util.NaturalOrder
import altitude.core.util.Query

/** The Import record: an Import and its Import files, their outcomes, the Import's counters and its closing */
@DoNotDiscover class ImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore with SearchPlans {

  private def imports = testApp.service.imports

  /** An upload's Import with a pending file per path, in the context repository */
  private def importOf(paths: String*): (Import, List[ImportFile]) = {
    val opened = imports.open(ImportSource.Upload)
    (opened, paths.toList.map(path => imports.addFile(opened.persistedId, path, sizeBytes = 10)))
  }

  /** The Import as stored now */
  private def stored(anImport: Import): Import = imports.getById(anImport.persistedId)

  /** The Import's counters as stored now: pending, imported, duplicate, unsupported, failed */
  private def counters(anImport: Import): (Int, Int, Int, Int, Int) = {
    val now = stored(anImport)
    (now.pending, now.imported, now.duplicate, now.unsupported, now.failed)
  }

  /** The Import file as stored now, among the files of its Import */
  private def stored(file: ImportFile): ImportFile = testContext.importFiles(file.importId).find(_.id == file.id).get

  test("An Import opened with the number of files to expect keeps it") {

    /**
     * Setup:
     *
     * Four Imports opened expecting 24, 3 (through `openUpload`), 0 and no number of files.
     *
     * Assertions:
     *
     * Each keeps its number, read by ID, in the Library's Imports and in the Import history. A file and the close leave it as it
     * is.
     *
     * Edge cases:
     *
     * A negative number is refused and opens nothing.
     */
    val expecting = imports.open(ImportSource.Upload, expectedFiles = Some(24))
    val upload = imports.openUpload(expectedFiles = Some(3))
    val empty = imports.open(ImportSource.Upload, expectedFiles = Some(0))
    val unknowing = imports.open(ImportSource.Upload)
    val opened = List(expecting, upload, empty, unknowing)
    val numbers = List(Some(24), Some(3), Some(0), None)

    opened.map(_.expectedFiles) shouldBe numbers
    opened.map(stored(_).expectedFiles) shouldBe numbers
    val listed = testContext.imports.map(anImport => anImport.persistedId -> anImport.expectedFiles).toMap
    opened.map(anImport => listed(anImport.persistedId)) shouldBe numbers
    val shown = imports.history(shown = 10).entries.map(entry => entry.anImport.persistedId -> entry.anImport.expectedFiles).toMap
    opened.map(anImport => shown(anImport.persistedId)) shouldBe numbers

    val file = imports.addFile(expecting.persistedId, "photo.jpg", sizeBytes = 10)
    imports.settle(file.persistedId, ImportOutcome.Failed, reason = Some("Upload failed"))
    imports.close(expecting.persistedId)
    val closed = stored(expecting)
    (closed.isInProgress, closed.total, closed.expectedFiles) shouldBe (false, 1, Some(24))

    intercept[ValidationException](imports.open(ImportSource.Upload, expectedFiles = Some(-1)))
    testContext.imports.size shouldBe 4
  }

  test("An opened Import is in progress, with the account that delivers it, its name and its destination") {

    /**
     * Setup:
     *
     * Two uploads by the context account: one named "Holiday" into a folder, one with no name or destination.
     *
     * Assertions:
     *
     * Each is stored as an upload of the context account, in progress, with zero counters and no file; the first with its name
     * and destination, the second with neither.
     *
     * Edge cases:
     *
     * A destination that is no folder of the repository is not found, and nothing is opened.
     */
    val folder = testApp.service.folder.add("Sorted")

    val named = imports.open(ImportSource.Upload, name = Some("Holiday"), destFolderId = folder.id)
    val plain = imports.open(ImportSource.Upload)

    for (opened <- List(named, plain)) {
      val read = stored(opened)
      read.source shouldBe ImportSource.Upload
      read.accountId shouldBe Some(testContext.user.persistedId)
      read.watchFolderId shouldBe None
      read.isComplete shouldBe false
      read.closedAt shouldBe None
      read.createdAt should not be empty
      counters(opened) shouldBe (0, 0, 0, 0, 0)
      testContext.importFiles(opened.persistedId) shouldBe empty
    }
    stored(named).name shouldBe Some("Holiday")
    stored(named).destFolderId shouldBe folder.id
    stored(plain).name shouldBe None
    stored(plain).destFolderId shouldBe None

    val otherRepository = testContext.persistRepository()
    switchContextRepo(otherRepository)
    val theirFolder = testApp.service.folder.add("Theirs")
    switchContextRepo(testContext.repositories.head)

    intercept[NotFoundException](imports.open(ImportSource.Upload, destFolderId = theirFolder.id))
    intercept[NotFoundException](imports.open(ImportSource.Upload, destFolderId = Some("no-such-folder")))
    testContext.imports.size shouldBe 2
  }

  test("An Import's files are listed by path in natural order on both engines") {

    /**
     * Setup:
     *
     * An upload's Import with a file under each of `TestContext.NATURAL_NAMES`, in a folder "Trip 9" and in "trip 10", added in a
     * shuffled order.
     *
     * Assertions:
     *
     * The Import's files are listed in natural order of their paths on both engines, the order `NaturalOrder` sorts them in
     * memory: the folders by their numbers, and each folder's files in the order of the names.
     */
    val paths = for (folder <- List("Trip 9", "trip 10"); name <- TestContext.NATURAL_NAMES) yield s"$folder/$name"
    val (opened, _) = importOf(Random.shuffle(paths)*)

    testContext.importFiles(opened.persistedId).map(_.relPath) shouldBe paths
    Random.shuffle(paths).sorted(using NaturalOrder) shouldBe paths
  }

  test("A file added to an Import is pending, and the Import counts it") {

    /**
     * Setup:
     *
     * An upload's Import with two files added, "b/second.jpg" and then "a/first.jpg".
     *
     * Assertions:
     *
     * Both are stored pending with their paths and sizes, the Import counts two pending, and the files list in path order.
     *
     * Edge cases:
     *
     * A file added to an Import marked complete is refused, and to an unknown Import not found; neither is stored or counted.
     */
    val (opened, List(second, first)) = importOf("b/second.jpg", "a/first.jpg"): @unchecked

    val files = testContext.importFiles(opened.persistedId)
    files.map(_.relPath) shouldBe List("a/first.jpg", "b/second.jpg")
    files.map(_.id) shouldBe List(first.id, second.id)
    for (file <- files) {
      file.importId shouldBe opened.persistedId
      file.outcome shouldBe ImportOutcome.Pending
      file.reason shouldBe None
      file.assetId shouldBe None
      file.sizeBytes shouldBe 10
    }
    counters(opened) shouldBe (2, 0, 0, 0, 0)
    testContext.importFiles(opened.persistedId, Some(ImportOutcome.Pending)) shouldBe files
    testContext.importFiles(opened.persistedId, Some(ImportOutcome.Imported)) shouldBe empty

    imports.close(opened.persistedId)
    intercept[IllegalOperationException](imports.addFile(opened.persistedId, "late.jpg", sizeBytes = 10))
    intercept[NotFoundException](imports.addFile("no-such-import", "lost.jpg", sizeBytes = 10))
    testContext.importFiles(opened.persistedId) shouldBe files
    counters(opened) shouldBe (2, 0, 0, 0, 0)
  }

  test("Settling a file gives it its outcome and moves it from pending to that outcome in the Import's counters") {

    /**
     * Setup:
     *
     * An Import of four pending files settled in turn: imported as an asset, a duplicate of it, unsupported, failed.
     *
     * Assertions:
     *
     * Each settle moves one file from pending to its outcome in the counters, and each file keeps its outcome, reason and asset.
     * The Import, not marked complete, stays in progress with nothing pending.
     *
     * Edge cases:
     *
     * Settling a file again changes nothing. An unknown file is not found, and pending is no outcome to settle with.
     */
    val asset = testContext.insertAsset()
    val (opened, List(imported, duplicate, unsupported, failed)) = importOf("1.jpg", "2.jpg", "3.txt", "4.jpg"): @unchecked

    imports.settle(imported.persistedId, ImportOutcome.Imported, assetId = asset.id)
    counters(opened) shouldBe (3, 1, 0, 0, 0)
    imports.settle(duplicate.persistedId, ImportOutcome.Duplicate, reason = Some("Triage"), assetId = asset.id)
    counters(opened) shouldBe (2, 1, 1, 0, 0)
    imports.settle(unsupported.persistedId, ImportOutcome.Unsupported)
    counters(opened) shouldBe (1, 1, 1, 1, 0)
    imports.settle(failed.persistedId, ImportOutcome.Failed, reason = Some("Storage error"))
    counters(opened) shouldBe (0, 1, 1, 1, 1)

    def settled(file: ImportFile) = {
      val read = stored(file)
      (read.outcome, read.reason, read.assetId)
    }
    settled(imported) shouldBe (ImportOutcome.Imported, None, asset.id)
    settled(duplicate) shouldBe (ImportOutcome.Duplicate, Some("Triage"), asset.id)
    settled(unsupported) shouldBe (ImportOutcome.Unsupported, None, None)
    settled(failed) shouldBe (ImportOutcome.Failed, Some("Storage error"), None)
    for ((file, outcome) <- List(imported, duplicate, unsupported, failed).zip(ImportOutcome.values.tail))
      testContext.importFiles(opened.persistedId, Some(outcome)).map(_.id) shouldBe List(file.id)
    testContext.importFiles(opened.persistedId, Some(ImportOutcome.Pending)) shouldBe empty

    stored(opened).isComplete shouldBe false
    stored(opened).closedAt shouldBe None

    imports.settle(imported.persistedId, ImportOutcome.Failed, reason = Some("Too late"))
    settled(imported) shouldBe (ImportOutcome.Imported, None, asset.id)
    counters(opened) shouldBe (0, 1, 1, 1, 1)

    intercept[NotFoundException](imports.settle("no-such-file", ImportOutcome.Failed))
    intercept[IllegalArgumentException](imports.settle(failed.persistedId, ImportOutcome.Pending))
  }

  test("A file the pipeline dropped is settled by the cause it was dropped for") {

    /**
     * Setup:
     *
     * An asset in Triage, and an Import's files each dropped for one cause: a duplicate of that asset, a duplicate of an asset
     * not there, an unsupported type, an undecodable image and Video, a storage error, a gone importer, an unexpected exception
     * whose message names a path, a Scene embedder closed by a stop, and no cause.
     *
     * Assertions:
     *
     * The duplicate points at the asset with "Triage" as reason. The duplicate of nothing is failed, its copy not in the Library.
     * The unsupported type is unsupported, and every other cause failed with its named reason. The closed embedder's file reads
     * as the files a stop leaves pending do after the next start: interrupted by a restart.
     *
     * Edge cases:
     *
     * An unexpected exception is recorded by its class alone, never its message, and no cause as an unknown error.
     */
    val existing = testContext.insertAsset(testContext.makeAsset(isTriaged = true))
    val copy = testContext.makeAsset().copy(checksum = existing.checksum)
    val other = testContext.makeAsset()

    val causes: List[(Asset, Option[Throwable], (ImportOutcome, Option[String], Option[String]))] = List(
      (copy, Some(DuplicateException()), (ImportOutcome.Duplicate, Some("Triage"), existing.id)),
      (other, Some(DuplicateException()), (ImportOutcome.Failed, Some(Const.Msg.Err.COPY_NOT_IN_LIBRARY), None)),
      (other, Some(UnsupportedMediaTypeException(other)), (ImportOutcome.Unsupported, Some("Unsupported media type"), None)),
      (other, Some(ImageException("no reader")), (ImportOutcome.Failed, Some("Cannot decode image"), None)),
      (other, Some(VideoException("no frame")), (ImportOutcome.Failed, Some("Cannot decode video"), None)),
      (other, Some(StorageException("/data/files is full")), (ImportOutcome.Failed, Some("Storage error"), None)),
      (
        other,
        Some(ImporterUnavailableException(Const.Msg.Err.IMPORTER_GONE)),
        (ImportOutcome.Failed, Some(Const.Msg.Err.IMPORTER_GONE), None)),
      (other, Some(new IllegalStateException("/secret/path")), (ImportOutcome.Failed, Some("IllegalStateException"), None)),
      (other, Some(SceneEmbedderClosedException()), (ImportOutcome.Failed, Some("Interrupted by a restart"), None)),
      (other, None, (ImportOutcome.Failed, Some("Unknown error"), None))
    )
    val (opened, files) = importOf(causes.indices.map(index => s"$index.jpg")*)

    for ((file, (asset, cause, _)) <- files.zip(causes)) imports.settleDropped(file.persistedId, asset, cause)

    files.map(stored).map(file => (file.outcome, file.reason, file.assetId)) shouldBe causes.map(_._3)
    counters(opened) shouldBe (0, 0, 1, 1, 8)
  }

  test("An Import closes once it is marked complete and none of its files is pending") {

    /**
     * Setup:
     *
     * Three Imports: one marked complete before its two files settle, one whose file settles before it is marked complete, and
     * one with no file, as a cancelled upload leaves it.
     *
     * Assertions:
     *
     * The first closes when its last file settles, the second and third when marked complete.
     *
     * Edge cases:
     *
     * Marking a closed Import complete again keeps its closing time, and an unknown Import is not found.
     */
    val (first, List(one, two)) = importOf("1.jpg", "2.jpg"): @unchecked
    imports.close(first.persistedId)
    stored(first).isComplete shouldBe true
    stored(first).closedAt shouldBe None
    imports.settle(one.persistedId, ImportOutcome.Imported)
    stored(first).closedAt shouldBe None
    imports.settle(two.persistedId, ImportOutcome.Failed, reason = Some("Storage error"))
    stored(first).closedAt should not be empty
    counters(first) shouldBe (0, 1, 0, 0, 1)

    val (second, List(only)) = importOf("only.jpg"): @unchecked
    imports.settle(only.persistedId, ImportOutcome.Imported)
    stored(second).closedAt shouldBe None
    imports.close(second.persistedId)
    stored(second).isComplete shouldBe true
    stored(second).closedAt should not be empty

    val (third, _) = importOf()
    imports.close(third.persistedId)
    val closedAt = stored(third).closedAt
    closedAt should not be empty

    Thread.sleep(5)
    imports.close(third.persistedId)
    stored(third).closedAt shouldBe closedAt
    intercept[NotFoundException](imports.close("no-such-import"))
  }

  test("Two files of one Import settling at once are both counted, and the second closes the Import") {

    /**
     * Setup:
     *
     * An Import marked complete with two pending files, settled in two transactions, the first held open until the second waits
     * on the Import's row.
     *
     * Assertions:
     *
     * Both settles are counted and the Import is closed.
     *
     * Edge cases:
     *
     * Counters set from a read of their own would lose the first settle on PostgreSQL.
     */
    val (opened, List(one, two)) = importOf("1.jpg", "2.jpg"): @unchecked
    imports.close(opened.persistedId)

    contend(
      first = imports.settle(one.persistedId, ImportOutcome.Imported),
      second = imports.settle(two.persistedId, ImportOutcome.Failed, reason = Some("Storage error"))
    )

    counters(opened) shouldBe (0, 1, 0, 0, 1)
    stored(opened).closedAt should not be empty
    stored(one).outcome shouldBe ImportOutcome.Imported
    stored(two).outcome shouldBe ImportOutcome.Failed
  }

  test("The Import history lists the Imports in progress first, then the newest first, as many as it is asked for") {

    /**
     * Setup:
     *
     * Four Imports opened in turn, alternately closed and left in progress, and an Import of another repository.
     *
     * Assertions:
     *
     * The history lists those in progress first, then the closed ones, newest first within each, cut at the number asked for, and
     * always counts four.
     *
     * Edge cases:
     *
     * The other repository's Import is in no list and in no total.
     */
    val List(firstClosed, firstOpen, secondClosed, secondOpen) = List.fill(4)(imports.open(ImportSource.Upload)): @unchecked
    imports.close(firstClosed.persistedId)
    imports.close(secondClosed.persistedId)

    val otherRepository = testContext.persistRepository()
    switchContextRepo(otherRepository)
    imports.open(ImportSource.Upload)
    switchContextRepo(testContext.repositories.head)

    def top(shown: Int): (List[Option[String]], Int) = {
      val history = imports.history(shown)
      (history.entries.map(_.anImport.id), history.total)
    }

    top(2) shouldBe (List(secondOpen.id, firstOpen.id), 4)
    top(3) shouldBe (List(secondOpen.id, firstOpen.id, secondClosed.id), 4)
    top(10) shouldBe (List(secondOpen.id, firstOpen.id, secondClosed.id, firstClosed.id), 4)
  }

  test("The Import history's statements read its indexes, with no sort of the whole history") {

    /**
     * Setup:
     *
     * 5,000 closed Imports a minute apart and one in progress, analyzed, then rolled back once the plans are read.
     *
     * Assertions:
     *
     * Each of the history's statements reads an index and sorts at most the Imports of one moment: the in-progress statement
     * reads `import_03`, the closed one `import_01`, and the count is read off an index alone.
     */
    val template = imports.open(ImportSource.Upload)
    imports.close(template.persistedId)
    imports.open(ImportSource.Upload)
    val dao = testApp.DAO.imports.asInstanceOf[altitude.core.dao.jdbc.ImportDao]
    val (copyId, createdAt) =
      if (isPostgres) ("lpad(g.n::text, 36, '0')", "created_at - g.n * interval '1 minute'")
      else ("printf('%036d', g.n)", "datetime(created_at, '-' || g.n || ' minutes')")

    val plans = atScale {
      update(
        s"""WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < 5000)
           |INSERT INTO import (id, repository_id, source, account_id, is_complete, closed_at, created_at)
           |SELECT $copyId, repository_id, source, account_id, is_complete, closed_at, $createdAt
           |  FROM import CROSS JOIN g
           | WHERE import.id = ?""".stripMargin,
        template.persistedId
      )
    } {
      // Every page all-visible, as vacuuming leaves a table and seeded rows never are, so the count reads an index alone
      if (isPostgres) {
        update("UPDATE pg_class SET relallvisible = relpages WHERE relname = ?", "import")
        update("SET LOCAL enable_bitmapscan = off")
      }
      dao.listStatements(shown = 20).map((sql, values) => lookupPlanOf(sql, values))
    }

    // A sort of everything below the node; a sort within one moment's Imports reads otherwise
    val sortOfAll = if (isPostgres) """(?m)^\s*(->\s+)?Sort\s+\(""".r else "USE TEMP B-TREE FOR ORDER BY".r
    plans.foreach {
      plan =>
        withClue(plan) {
          plan should include(if (isPostgres) "Index" else "INDEX")
          sortOfAll.findFirstIn(plan) shouldBe None
        }
    }
    // In progress, closed, and the count, which any index holding the repository answers
    val List(inProgress, closed, _) = plans: @unchecked
    withClue(inProgress)(inProgress should include("import_03"))
    withClue(closed)(closed should include("import_01"))
  }

  test("The Import history names who delivered each Import, where its files went and whether a restart interrupted it") {

    /**
     * Setup:
     *
     * A second account's upload into Clients/Wedding left with a pending file, a Watch folder scan closed whose account is gone,
     * then a restart, then the context account's upload in progress. Another repository has an Import.
     *
     * Assertions:
     *
     * The history holds the three Imports, in progress first. The context account's upload names it, with no destination or
     * interruption; the scan names nobody; the folder upload names its account, its destination path, and the restart that
     * interrupted it. Each was opened within the last minute.
     *
     * Edge cases:
     *
     * Once its account is deleted, the folder upload names nobody. Asked for two, the history still counts three.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val uploader = testContext.persistUser()
    val clients = testApp.service.folder.add("Clients")
    val wedding = testApp.service.folder.add("Wedding", parentId = clients.id)

    switchContextUser(uploader)
    val folderUpload = imports.open(ImportSource.Upload, name = Some("Wedding 2026"), destFolderId = wedding.id)
    val List(done, _) = List("done.jpg", "lost.jpg").map(imports.addFile(folderUpload.persistedId, _, sizeBytes = 10)): @unchecked
    imports.settle(done.persistedId, ImportOutcome.Imported)
    switchContextUser(testContext.users.head)

    val scan = imports.open(ImportSource.WatchFolder, name = Some("Phone sync"))
    // Every Import is opened by an account; one whose account was deleted names nobody
    testApp.txManager.withTransaction(update("UPDATE import SET account_id = NULL WHERE id = ?", scan.persistedId))
    val scanned = imports.addFile(scan.persistedId, "IMG_0001.jpg", sizeBytes = 10)
    imports.settle(scanned.persistedId, ImportOutcome.Imported)
    imports.close(scan.persistedId)

    imports.failInterrupted()
    val filesUpload = imports.open(ImportSource.Upload)

    val first = testContext.repositories.head
    switchContextRepo(testContext.persistRepository())
    imports.open(ImportSource.Upload, name = Some("Theirs"))
    switchContextRepo(first)

    val history = imports.history(shown = 10)
    history.total shouldBe 3
    history.entries.map(_.anImport.id) shouldBe List(filesUpload.id, scan.id, folderUpload.id)
    history.inProgress.map(_.anImport.id) shouldBe List(filesUpload.id)
    history.earlier.map(_.anImport.id) shouldBe List(scan.id, folderUpload.id)
    history.entries.map(entry => (entry.deliveredBy, entry.destination, entry.isInterrupted)) shouldBe List(
      (Some(testContext.users.head.name), None, false),
      (None, None, false),
      (Some(uploader.name), Some("Clients › Wedding"), true))
    for (entry <- history.entries)
      java.time.Duration.between(entry.openedAt.value, java.time.Instant.now()).abs.toSeconds should be < 60L

    testApp.service.user.delete(admin.persistedId, uploader.persistedId)
    imports.history(shown = 10).entries.last.deliveredBy shouldBe None

    val top = imports.history(shown = 2)
    top.entries.map(_.anImport.id) shouldBe List(filesUpload.id, scan.id)
    top.total shouldBe 3
  }

  test("An Import's files are read from the first in path order, a number at a time, all of them or those of one outcome") {

    /**
     * Setup:
     *
     * An Import of five files added out of path order, of mixed outcomes, and another repository's Import.
     *
     * Assertions:
     *
     * Each read gives the first files in path order, of all outcomes or one, with the total for that outcome and the Import with
     * its counters.
     *
     * Edge cases:
     *
     * The other repository's Import is not found.
     */
    val (opened, files) = importOf("e.jpg", "c.jpg", "a.jpg", "d.jpg", "b.jpg")
    val byPath = files.map(file => file.relPath -> file.persistedId).toMap
    imports.settle(byPath("a.jpg"), ImportOutcome.Imported)
    imports.settle(byPath("c.jpg"), ImportOutcome.Imported)
    imports.settle(byPath("b.jpg"), ImportOutcome.Failed, reason = Some("Cannot decode image"))

    val first = testContext.repositories.head
    switchContextRepo(testContext.persistRepository())
    val (theirs, _) = importOf("theirs.jpg")
    switchContextRepo(first)

    def shown(outcome: Option[ImportOutcome], count: Int) = {
      val read = imports.filesShown(opened.persistedId, outcome, count)
      read.anImport shouldBe stored(opened)
      (read.files.map(_.relPath), read.total)
    }

    shown(None, 3) shouldBe (List("a.jpg", "b.jpg", "c.jpg"), 5)
    shown(Some(ImportOutcome.Imported), 3) shouldBe (List("a.jpg", "c.jpg"), 2)
    shown(Some(ImportOutcome.Pending), 1) shouldBe (List("d.jpg"), 2)
    shown(Some(ImportOutcome.Duplicate), 3) shouldBe (Nil, 0)

    intercept[NotFoundException](imports.filesShown(theirs.persistedId, None, 3))
  }

  test("A change to an Import is told once it has committed, with the Import as committed, and never when it rolls back") {

    /**
     * Setup:
     *
     * A listener of the repository's Import changes. An Import is opened and given a file, which is settled inside a transaction
     * that fails, then on its own; then the Import is closed.
     *
     * Assertions:
     *
     * The listener hears the opening, the file, the committed settle and the close, each once and in order, with the counters as
     * committed. The rolled-back settle is not heard, and left the file pending.
     *
     * Edge cases:
     *
     * A file added and failed in one transaction is never heard as pending.
     */
    val repositoryId = testContext.repository.persistedId
    val heard = new java.util.concurrent.CopyOnWriteArrayList[Import]()
    imports.onChange((inRepository, changed) => if (inRepository == repositoryId) heard.add(changed): Unit)
    def counts = heard.asScala.toList.map(told => (told.pending, told.imported, told.failed, told.closedAt.isDefined))

    val (opened, List(file)) = importOf("photo.jpg"): @unchecked
    counts shouldBe List((0, 0, 0, false), (1, 0, 0, false))
    heard.asScala.map(_.id).toSet shouldBe Set(opened.id)

    intercept[IllegalStateException] {
      testApp.txManager.withTransaction {
        imports.settle(file.persistedId, ImportOutcome.Imported)
        throw new IllegalStateException("the completion fails after its settle")
      }
    }
    counts should have size 2
    stored(file).outcome shouldBe ImportOutcome.Pending

    imports.settle(file.persistedId, ImportOutcome.Imported)
    counts.drop(2) shouldBe List((0, 1, 0, false))

    imports.close(opened.persistedId)
    counts.drop(3) shouldBe List((0, 1, 0, true))
    heard.asScala.last shouldBe stored(opened)

    heard.clear()
    val unsent = imports.open(ImportSource.Upload)
    imports.addFailedFile(unsent.persistedId, "unsent.jpg", sizeBytes = 10)
    counts.drop(1).distinct shouldBe List((0, 0, 1, false))
  }

  test("An Import file keeps its path and outcome when its asset is purged, and points at no asset after") {

    /**
     * Setup:
     *
     * An Import with two files settled against one asset, imported and duplicate. The asset is then purged.
     *
     * Assertions:
     *
     * Both files keep their paths, outcomes and reasons but point at no asset, and the counters are unchanged.
     */
    val asset: Asset = testContext.insertAsset()
    val (opened, List(imported, duplicate)) = importOf("kept.jpg", "copy.jpg"): @unchecked
    imports.settle(imported.persistedId, ImportOutcome.Imported, assetId = asset.id)
    imports.settle(duplicate.persistedId, ImportOutcome.Duplicate, reason = Some("Triage"), assetId = asset.id)

    testApp.service.library.recycleAssets(Set(asset.persistedId))
    testApp.service.library.purgeSelectedAssets(Set(asset.persistedId))
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis))) {
      testApp.service.asset.queryAll(new Query()).total shouldBe 0
    }

    val files = testContext.importFiles(opened.persistedId)
    files.map(file => (file.relPath, file.outcome, file.reason, file.assetId)) shouldBe List(
      ("copy.jpg", ImportOutcome.Duplicate, Some("Triage"), None),
      ("kept.jpg", ImportOutcome.Imported, None, None))
    counters(opened) shouldBe (0, 1, 1, 0, 0)
  }

  test("A restart fails the Import files still pending and closes their Imports") {

    /**
     * Setup:
     *
     * Imports as a server stopped mid-import leaves them, in progress, marked complete, empty or closed, in two repositories.
     * Then the restart's step runs.
     *
     * Assertions:
     *
     * Every pending file, in both repositories, is failed as "Interrupted by a restart", and every Import in progress is closed,
     * counting them failed. The context is the first repository again.
     *
     * Edge cases:
     *
     * Files settled earlier, and the Import closed earlier, are unchanged.
     */
    val first = testContext.repository
    val (interrupted, List(done, lostOne, lostTwo)) = importOf("done.jpg", "lost-1.jpg", "lost-2.jpg"): @unchecked
    imports.settle(done.persistedId, ImportOutcome.Imported)
    val (sent, List(lostThree)) = importOf("lost-3.jpg"): @unchecked
    imports.close(sent.persistedId)
    val (untouched, _) = importOf()
    val (finished, List(kept)) = importOf("kept.jpg"): @unchecked
    imports.settle(kept.persistedId, ImportOutcome.Imported)
    imports.close(finished.persistedId)
    val finishedBefore = stored(finished)

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val (theirs, List(theirLost)) = importOf("theirs.jpg"): @unchecked
    switchContextRepo(first)

    imports.failInterrupted()

    RequestContext.getRepository.persistedId shouldBe first.persistedId
    for (lost <- List(lostOne, lostTwo, lostThree)) {
      val read = stored(lost)
      (read.outcome, read.reason, read.assetId) shouldBe (ImportOutcome.Failed, Some("Interrupted by a restart"), None)
    }
    stored(done).outcome shouldBe ImportOutcome.Imported
    counters(interrupted) shouldBe (0, 1, 0, 0, 2)
    counters(sent) shouldBe (0, 0, 0, 0, 1)
    counters(untouched) shouldBe (0, 0, 0, 0, 0)
    for (closed <- List(interrupted, sent, untouched)) {
      stored(closed).isComplete shouldBe true
      stored(closed).closedAt should not be empty
    }
    stored(finished) shouldBe finishedBefore
    stored(kept).outcome shouldBe ImportOutcome.Imported

    switchContextRepo(second)
    stored(theirLost).outcome shouldBe ImportOutcome.Failed
    stored(theirLost).reason shouldBe Some("Interrupted by a restart")
    counters(theirs) shouldBe (0, 0, 0, 0, 1)
    stored(theirs).closedAt should not be empty
  }

  /** Sets the Import's opening to `days` days and `minutes` minutes ago */
  private def age(anImport: Import, days: Int, minutes: Int = 0): Unit = {
    val createdAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).minusMinutes(minutes).truncatedTo(ChronoUnit.MICROS)
    val value: Any = testApp.dataSourceType match {
      case Const.DbEngineName.SQLITE => createdAt.toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)
      case _ => createdAt
    }
    testApp.txManager.withTransaction(update("UPDATE import SET created_at = ? WHERE id = ?", value, anImport.persistedId))
  }

  /** A closed Import of one imported file, opened `days` days and `minutes` minutes ago */
  private def closedImport(days: Int, minutes: Int = 0): (Import, ImportFile) = {
    val (opened, List(file)) = importOf("file.jpg"): @unchecked
    imports.settle(file.persistedId, ImportOutcome.Imported)
    imports.close(opened.persistedId)
    age(opened, days, minutes)
    (opened, file)
  }

  /** How many Import files the Import has, read past the service, which does not find a deleted Import */
  private def fileRows(anImport: Import): Int = testApp.txManager.asReadOnly {
    query("SELECT count(*) AS n FROM import_file WHERE import_id = ?", anImport.persistedId).head("n").toString.toInt
  }

  test("The prune deletes the closed Imports older than the Import history Setting, with their Import files") {

    /**
     * Setup:
     *
     * The Import history Setting at 90 days. Closed Imports a minute past and a minute short of 90 days, one in progress opened
     * 200 days ago, and a closed one 200 days old in a second repository, each with a file. Then the prune runs.
     *
     * Assertions:
     *
     * The closed Imports past 90 days are gone with their files, in both repositories; the one just inside stays. The context is
     * the first repository again.
     *
     * Edge cases:
     *
     * The Import in progress stays, however old, with its pending file: only a closed Import is history.
     */
    testApp.settings.current.importHistoryDays shouldBe 90
    val first = testContext.repository
    val (expired, _) = closedImport(days = 90, minutes = 1)
    val (recent, recentFile) = closedImport(days = 89, minutes = 24 * 60 - 1)
    val (inProgress, List(pendingFile)) = importOf("pending.jpg"): @unchecked
    age(inProgress, days = 200)

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val (theirs, _) = closedImport(days = 200)
    switchContextRepo(first)

    imports.prune()

    RequestContext.getRepository.persistedId shouldBe first.persistedId
    intercept[NotFoundException](imports.getById(expired.persistedId))
    fileRows(expired) shouldBe 0
    stored(recentFile).outcome shouldBe ImportOutcome.Imported
    stored(pendingFile).outcome shouldBe ImportOutcome.Pending
    testContext.imports.map(_.id) shouldBe List(inProgress.id, recent.id)

    switchContextRepo(second)
    testContext.imports shouldBe empty
    fileRows(theirs) shouldBe 0
  }

  test("The prune follows the Import history Setting as saved, and keeps every Import when it is 0") {

    /**
     * Setup:
     *
     * Closed Imports opened 10 and 4000 days ago, pruned with the Setting saved as 0, then as 30.
     *
     * Assertions:
     *
     * At 0 nothing is deleted; at 30 the 4000-day-old Import goes with its file.
     */
    val (recent, recentFile) = closedImport(days = 10)
    val (ancient, ancientFile) = closedImport(days = 4000)

    withSetting(Settings.IMPORT_HISTORY_DAYS, "0") {
      imports.prune()
    }
    testContext.imports.map(_.id) shouldBe List(recent.id, ancient.id)
    stored(recentFile).outcome shouldBe ImportOutcome.Imported
    stored(ancientFile).outcome shouldBe ImportOutcome.Imported

    withSetting(Settings.IMPORT_HISTORY_DAYS, "30") {
      imports.prune()
    }
    testContext.imports.map(_.id) shouldBe List(recent.id)
    fileRows(ancient) shouldBe 0
    stored(recentFile).outcome shouldBe ImportOutcome.Imported
  }

  test("A startup fails what a restart interrupted, then prunes the Import history") {

    /**
     * Setup:
     *
     * The Setting at 90 days, and Imports with a file each: in progress, in progress and 200 days old, closed and 200 days old,
     * and closed 10 days ago. Then the startup step runs.
     *
     * Assertions:
     *
     * The recent pending file is failed and its Import closed. The closed 200-day-old Import is pruned with its file; the
     * 10-day-old one is unchanged.
     *
     * Edge cases:
     *
     * The 200-day-old Import in progress is closed by the first step and so pruned by the second.
     */
    val (interrupted, List(lost)) = importOf("lost.jpg"): @unchecked
    val (stale, _) = importOf("stale.jpg")
    age(stale, days = 200)
    val (expired, _) = closedImport(days = 200)
    val (recent, recentFile) = closedImport(days = 10)
    val recentBefore = stored(recent)

    imports.startup()

    val failed = stored(lost)
    (failed.outcome, failed.reason) shouldBe (ImportOutcome.Failed, Some("Interrupted by a restart"))
    counters(interrupted) shouldBe (0, 0, 0, 0, 1)
    stored(interrupted).closedAt should not be empty
    for (gone <- List(expired, stale)) {
      intercept[NotFoundException](imports.getById(gone.persistedId))
      fileRows(gone) shouldBe 0
    }
    stored(recent) shouldBe recentBefore
    stored(recentFile).outcome shouldBe ImportOutcome.Imported
  }

  test("A prune that fails at startup does not stop the startup") {

    /**
     * Setup:
     *
     * An Import in progress with a pending file, and a 200-day-old closed Import whose delete the database refuses. Then the
     * startup step runs.
     *
     * Assertions:
     *
     * The prune alone fails, but the startup returns, having closed the interrupted Import; the undeletable one stays.
     */
    val (interrupted, List(lost)) = importOf("lost.jpg"): @unchecked
    val (expired, expiredFile) = closedImport(days = 200)
    val allowDeleting = refuse("DELETE", "import", expired.persistedId)

    try {
      intercept[SQLException](imports.prune())

      imports.startup()
    } finally allowDeleting()

    stored(lost).outcome shouldBe ImportOutcome.Failed
    stored(interrupted).closedAt should not be empty
    stored(expired).closedAt should not be empty
    stored(expiredFile).outcome shouldBe ImportOutcome.Imported
  }

  test("An Import outlives the account that delivered it") {

    /**
     * Setup:
     *
     * An Admin, and a second account that opens an Import with one pending file. The Admin then deletes that account.
     *
     * Assertions:
     *
     * The Import names its account until the account is deleted, and none after; it stays, its file pending and counted.
     */
    val admin = testContext.persistUser(Some(testContext.makeAdminUser()))
    val uploader = testContext.persistUser()
    switchContextUser(uploader)
    val (opened, List(file)) = importOf("photo.jpg"): @unchecked
    switchContextUser(testContext.users.head)
    stored(opened).accountId shouldBe uploader.id

    testApp.service.user.delete(admin.persistedId, uploader.persistedId)

    stored(opened).accountId shouldBe None
    stored(file).outcome shouldBe ImportOutcome.Pending
    counters(opened) shouldBe (1, 0, 0, 0, 0)
  }

  test("Another repository's Import and Import files are neither read nor changed, and its asset settles no file") {

    /**
     * Setup:
     *
     * Two repositories, each with an Import of one pending file, the second with an asset too. The context is the first.
     *
     * Assertions:
     *
     * The second's Import and file are not found to read, list, add to, close or settle, and settling the first's own file as the
     * second's asset is not found either. Nothing changes in either repository.
     */
    val first = testContext.repository
    val (mine, List(myFile)) = importOf("mine.jpg"): @unchecked

    val second = testContext.persistRepository()
    switchContextRepo(second)
    val theirAsset = testContext.insertAsset(testContext.makeAsset(repository = Some(second)))
    val (theirs, List(theirFile)) = importOf("theirs.jpg"): @unchecked
    switchContextRepo(first)
    val before = testContext.rowsOf(second)

    intercept[NotFoundException](imports.getById(theirs.persistedId))
    intercept[NotFoundException](testContext.importFiles(theirs.persistedId))
    intercept[NotFoundException](imports.addFile(theirs.persistedId, "intruder.jpg", sizeBytes = 10))
    intercept[NotFoundException](imports.close(theirs.persistedId))
    intercept[NotFoundException](imports.settle(theirFile.persistedId, ImportOutcome.Failed))
    intercept[NotFoundException](imports.settle(myFile.persistedId, ImportOutcome.Imported, assetId = theirAsset.id))

    testContext.rowsOf(second) shouldEqual before
    stored(myFile).outcome shouldBe ImportOutcome.Pending
    stored(myFile).assetId shouldBe None
    counters(mine) shouldBe (1, 0, 0, 0, 0)
  }

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  /** A random image staged as an upload stages it */
  private def stagedImage(): Path =
    testApp.service.staging.stage(IntegrationTestUtil.generateRandomImagBytesBgr(dimensions = 150))

  test("An Import is not opened into a folder in the Trash") {

    /**
     * Setup:
     *
     * A folder of the context repository, recycled by a delete.
     *
     * Assertions:
     *
     * Opening an Import into it is refused, saying the destination is in the Trash, and opens nothing.
     */
    val folder = testApp.service.folder.add("Gone")
    testApp.service.library.deleteFolderById(folder.persistedId)

    val refused = intercept[IllegalOperationException](imports.open(ImportSource.Upload, destFolderId = folder.id))

    refused.getMessage shouldBe Const.Msg.Err.DESTINATION_IN_TRASH
    testContext.imports.size shouldBe 0
  }

  test("An upload's Import with a destination takes Organize as well as Import") {

    /**
     * Setup:
     *
     * A folder here and one in another repository; an account holding Import alone, and one holding Import and Organize.
     *
     * Assertions:
     *
     * Import alone opens an upload to Triage but is refused one into the folder; with Organize too, the folder is allowed.
     *
     * Edge cases:
     *
     * Another repository's folder is not found rather than refused: the folder is read before the Permission is checked.
     */
    val folder = testApp.service.folder.add("Sorted")
    val otherRepository = testContext.persistRepository()
    switchContextRepo(otherRepository)
    val theirFolder = testApp.service.folder.add("Theirs")
    switchContextRepo(testContext.repositories.head)

    RequestContext.permissions.withValue(Permissions.of(Permission.Import)) {
      val toTriage = imports.openUpload(name = Some("Holiday"))
      (stored(toTriage).source, stored(toTriage).name, stored(toTriage).destFolderId) shouldBe (
        ImportSource.Upload,
        Some("Holiday"),
        None)

      intercept[ForbiddenException](imports.openUpload(destFolderId = folder.id))
      intercept[NotFoundException](imports.openUpload(destFolderId = theirFolder.id))
      testContext.imports.size shouldBe 1
    }

    RequestContext.permissions.withValue(Permissions.of(Permission.Import, Permission.Organize)) {
      stored(imports.openUpload(destFolderId = folder.id)).destFolderId shouldBe folder.id
    }
  }

  test("An upload into a folder takes no more files once its account holds Organize no longer, and can still be closed") {

    /**
     * Setup:
     *
     * An account with Import and Organize opens an upload into a folder and one to Triage, then loses Organize.
     *
     * Assertions:
     *
     * Every way of adding a file to the upload into the folder is forbidden, naming Organize, and its staged file is deleted; the
     * account can still close it.
     *
     * Edge cases:
     *
     * The upload to Triage, which sorts nothing, takes its file as before.
     */
    val folder = testApp.service.folder.add("Sorted")
    val (sorting, toTriage) = RequestContext.permissions.withValue(Permissions.of(Permission.Import, Permission.Organize)) {
      (imports.openUpload(destFolderId = folder.id), imports.openUpload())
    }

    RequestContext.permissions.withValue(Permissions.of(Permission.Import)) {
      val staged = stagedImage()
      val refusals = List(
        intercept[ForbiddenException](imports.requireTakes(sorting.persistedId, "a.jpg")),
        intercept[ForbiddenException](imports.upload(sorting.persistedId, "New folder/a.jpg", staged)),
        intercept[ForbiddenException](imports.failOnArrival(sorting.persistedId, "a.jpg", sizeBytes = 10)),
        intercept[ForbiddenException](imports.addFailedFile(sorting.persistedId, "a.jpg", sizeBytes = 10))
      )
      refusals.map(_.getMessage).toSet shouldBe Set(Const.Msg.Err.FORBIDDEN_PERMISSION.format(Permission.Organize.label))
      Files.exists(staged) shouldBe false
      testContext.importFiles(sorting.persistedId) shouldBe empty

      imports.closeUpload(sorting.persistedId)
      stored(sorting).isInProgress shouldBe false

      imports.requireTakes(toTriage.persistedId, "b.jpg")
      imports.addFailedFile(toTriage.persistedId, "b.jpg", sizeBytes = 10).outcome shouldBe ImportOutcome.Failed
    }
  }

  test("An upload's file whose path holds a NUL is recorded failed under its path made printable, and its name is shown clean") {

    /**
     * Setup:
     *
     * An account that holds Import. An upload named with a NUL, a line break, a tab and a right-to-left override, and files of it
     * whose paths hold a NUL: one announced before its body, one staged, one the page could not send.
     *
     * Assertions:
     *
     * The upload opens, its name stored with each of those characters replaced by U+FFFD. Each file is recorded failed under its
     * path with the NUL escaped as `\u0000`, the staged one discarded: the two that arrived for the NUL, the unsent one as an
     * upload that failed. The Import holds those three files alone.
     *
     * Edge cases:
     *
     * The check before the body takes a path with a NUL, and a path with a tab, a line break or a C1 control is taken as any
     * other.
     */
    val opened = imports.openUpload(name = Some("Trip\u0000\n2026\tday \u202eone"))
    imports.getById(opened.persistedId).name shouldBe Some("Trip\ufffd\ufffd2026\ufffdday \ufffdone")
    val id = opened.persistedId
    val R = Const.Msg.Err.PATH_HOLDS_NUL

    imports.requireTakes(id, "a\u0000.jpg")
    imports.failOnArrival(id, "a\u0000.jpg", sizeBytes = 10).map(file => (file.outcome, file.reason)) shouldBe
      Some((ImportOutcome.Failed, Some(R)))
    val staged = stagedImage()
    val uploaded = imports.upload(id, "Day 1/b\u0000.jpg", staged)
    (uploaded.outcome, uploaded.reason) shouldBe (ImportOutcome.Failed, Some(R))
    Files.exists(staged) shouldBe false
    imports.addFailedFile(id, "c\u0000.jpg", sizeBytes = 10).outcome shouldBe ImportOutcome.Failed
    for (path <- List("Day\t1/d.jpg", "Day 1/e\n.jpg", "f\u0085.jpg")) withClue(s"[${path.map(_.toInt)}]: ") {
      imports.requireTakes(id, path)
      imports.failOnArrival(id, path, sizeBytes = 10) shouldBe None
    }

    testContext.importFiles(id).map(file => (file.relPath, file.outcome, file.reason)).toSet shouldBe Set(
      ("a\\u0000.jpg", ImportOutcome.Failed, Some(R)),
      ("Day 1/b\\u0000.jpg", ImportOutcome.Failed, Some(R)),
      ("c\\u0000.jpg", ImportOutcome.Failed, Some("Upload failed"))
    )
  }

  test("An upload's name and paths are logged with their control characters escaped, so none starts a log line") {

    /**
     * Setup:
     *
     * The Import log captured at DEBUG. An upload named with a line break and a forged log line, and files whose paths hold line
     * breaks or carriage returns: one failed on arrival then settled again, one refused by a shut-down queue.
     *
     * Assertions:
     *
     * No logged line holds a control character, and every line naming a path, at every level, holds the escape. The Import's name
     * is logged as stored, the line break replaced.
     */
    val logger = LoggerFactory.getLogger(classOf[ImportService]).asInstanceOf[Logger]
    val capture = ListAppender[ILoggingEvent]()
    // Test logging is off, so the level is raised for the test
    val level = logger.getLevel
    capture.start()
    logger.addAppender(capture)
    logger.setLevel(Level.DEBUG)
    val refusing = ImportPipelineService(testApp)
    refusing.shutdown()

    try {
      val opened = imports.openUpload(name = Some("Trip\n2026-11-12 WARN  Failed login attempt for [admin] from [10.0.0.5]"))
      val unsent = imports.addFailedFile(opened.persistedId, "Day 1\r/a\n.jpg", sizeBytes = 10)
      imports.settle(unsent.persistedId, ImportOutcome.Imported)
      imports.upload(opened.persistedId, "b\n.jpg", stagedImage(), refusing).outcome shouldBe ImportOutcome.Failed

      val lines = capture.list.asScala.map(_.getFormattedMessage).toList
      lines.filter(_.exists(_.isControl)) shouldBe empty
      lines.exists(line => line.contains("Opened Import") && line.contains("Trip\ufffd2026-11-12 WARN")) shouldBe true
      List("Added Import file", "is failed on arrival", "is failed", "was settled already", "failed before the pipeline").foreach {
        said => withClue(said)(lines.exists(line => line.contains(said) && line.contains("\\u000a")) shouldBe true)
      }
    } finally {
      logger.detachAppender(capture)
      logger.setLevel(level)
    }
  }

  test("An uploaded file is added to its Import and imported by the queue into the folder its path names") {

    /**
     * Setup:
     *
     * An upload into Clients of two staged images, one at Ceremony/vows.jpg and one at the top.
     *
     * Assertions:
     *
     * Each upload returns its pending Import file with its path and size. The queue imports both, into Clients/Ceremony and
     * Clients, and the closed Import counts two imported.
     */
    val clients = testApp.service.folder.add("Clients")
    val opened = imports.openUpload(name = Some("Wedding"), destFolderId = clients.id)
    val (nested, top) = (stagedImage(), stagedImage())
    val nestedSize = Files.size(nested)

    val nestedFile = imports.upload(opened.persistedId, "Ceremony/vows.jpg", nested)
    val topFile = imports.upload(opened.persistedId, "top.jpg", top)
    imports.close(opened.persistedId)

    (nestedFile.relPath, nestedFile.sizeBytes, nestedFile.outcome) shouldBe (
      "Ceremony/vows.jpg",
      nestedSize,
      ImportOutcome.Pending)
    topFile.relPath shouldBe "top.jpg"

    eventually(stored(opened).closedAt should not be empty)
    counters(opened) shouldBe (0, 2, 0, 0, 0)
    val vows = testApp.service.asset.getById(stored(nestedFile).assetId.get)
    vows.fileName shouldBe "vows.jpg"
    testApp.service.folder.getPath(vows.folderId).map(_.name) shouldBe List("Clients", "Ceremony")
    testApp.service.asset.getById(stored(topFile).assetId.get).folderId shouldBe clients.persistedId
  }

  test("An uploaded image measured once the Scene embedder is closed fails as interrupted, and nothing of it is kept") {

    /**
     * Setup:
     *
     * An upload of one staged image, imported by the queue while the Scene embedder refuses every image as a closed one does: a
     * stop whose import drain ran out closes the embedder under the files still in the pipeline.
     *
     * Assertions:
     *
     * The file is failed, "Interrupted by a restart", and points at no asset: the import did not go on to store the image as one
     * the models could not embed, which no later import would put right. The Library holds no asset of it, so the same image
     * uploads again and is imported.
     */
    val image = IntegrationTestUtil.generateRandomImagBytesBgr(dimensions = 150)
    def uploaded(): (Import, ImportFile) = {
      val opened = imports.openUpload(name = None, destFolderId = None)
      val file = imports.upload(opened.persistedId, "late.jpg", testApp.service.staging.stage(image))
      imports.close(opened.persistedId)
      eventually(stored(opened).closedAt should not be empty)
      (opened, stored(file))
    }

    val (cutOff, failed) = withSceneEmbedderClosed(uploaded())
    (failed.outcome, failed.reason, failed.assetId) shouldBe (ImportOutcome.Failed, Some("Interrupted by a restart"), None)
    counters(cutOff) shouldBe (0, 0, 0, 0, 1)

    val (again, imported) = uploaded()
    imported.outcome shouldBe ImportOutcome.Imported
    counters(again) shouldBe (0, 1, 0, 0, 0)
  }

  test("An uploaded file the import queue refuses is recorded failed") {

    /**
     * Setup:
     *
     * A staged image uploaded through a shut-down import queue.
     *
     * Assertions:
     *
     * The Import file is stored failed, the queue unavailable, the Import counts it failed, and the staged file is gone.
     */
    val opened = imports.openUpload()
    val pipeline = ImportPipelineService(testApp)
    pipeline.shutdown()
    val staged = stagedImage()

    val file = imports.upload(opened.persistedId, "photo.jpg", staged, pipeline)

    (file.outcome, file.reason) shouldBe (ImportOutcome.Failed, Some("Import queue unavailable"))
    (stored(file).outcome, stored(file).reason) shouldBe (ImportOutcome.Failed, Some("Import queue unavailable"))
    counters(opened) shouldBe (0, 0, 0, 0, 1)
    Files.exists(staged) shouldBe false
  }

  test("An uploaded file that fails before it is offered to the import queue is recorded failed") {

    /**
     * Setup:
     *
     * An upload's Import, and a directory in staging in place of a staged file, which has a size but cannot be read as a file.
     *
     * Assertions:
     *
     * The Import file is stored failed with the exception's class as reason, the Import counts it failed, and the staged
     * directory is gone.
     */
    val opened = imports.openUpload()
    val unreadable = Files.createDirectories(testApp.service.staging.dir.resolve(java.util.UUID.randomUUID.toString))
    // NTFS gives a directory no size, so on Windows it is refused as an empty file before anything reads it
    assume(Files.size(unreadable) > 0, "a directory has a size on this file system")

    val file = imports.upload(opened.persistedId, "photo.jpg", unreadable)

    file.outcome shouldBe ImportOutcome.Failed
    stored(file).outcome shouldBe ImportOutcome.Failed
    stored(file).reason shouldBe Some("IOException")
    counters(opened) shouldBe (0, 0, 0, 0, 1)
    Files.exists(unreadable) shouldBe false
  }

  test("An upload refuses a path with an ignored name, a \"..\" or an empty name, and an empty file") {

    /**
     * Setup:
     *
     * Uploads at refused paths (dot names, system and NAS names in any case, partial-download names, "..", empty names), and an
     * empty file at a fine path.
     *
     * Assertions:
     *
     * Each is refused and its staged file deleted; the Import has no file.
     *
     * Edge cases:
     *
     * Names that merely contain an ignored name ("my.photo.jpg", "thumbs.db.jpg", "apart") are added, pending.
     */
    val opened = imports.openUpload()
    val refusedPaths = List(
      ".hidden.jpg",
      ".git/photo.jpg",
      "Thumbs.db",
      "DESKTOP.INI",
      "Album/@eaDir/photo.jpg",
      "#recycle/photo.jpg",
      "$RECYCLE.BIN/photo.jpg",
      "photo.jpg.part",
      "photo.TMP",
      "photo.jpg.crdownload",
      "../photo.jpg",
      "Album/../photo.jpg",
      "",
      "/photo.jpg",
      "Album//photo.jpg",
      "Album/"
    )

    for (path <- refusedPaths) withClue(s"[$path]: ") {
      val staged = stagedImage()
      intercept[ValidationException](imports.upload(opened.persistedId, path, staged))
      Files.exists(staged) shouldBe false
    }

    val emptyFile = testApp.service.staging.stage(Array.emptyByteArray)
    intercept[ValidationException](
      imports.upload(opened.persistedId, "photo.jpg", emptyFile)).message shouldBe Const.Msg.Err.EMPTY_FILE
    Files.exists(emptyFile) shouldBe false

    testContext.importFiles(opened.persistedId) shouldBe empty
    counters(opened) shouldBe (0, 0, 0, 0, 0)

    val fine = List("my.photo.jpg", "thumbs.db.jpg", "apart", "Album.tmp.d/photo.jpg")
    fine.foreach(path => imports.upload(opened.persistedId, path, stagedImage()).outcome shouldBe ImportOutcome.Pending)
    testContext.importFiles(opened.persistedId).map(_.relPath).toSet shouldBe fine.toSet
  }

  test("An upload to an Import that takes no more files, or that is not the repository's, stores nothing") {

    /**
     * Setup:
     *
     * An upload's Import marked complete, and a staged image for it and for an Import that does not exist.
     *
     * Assertions:
     *
     * The first is an illegal operation and the second not found. Each staged file is deleted.
     */
    val opened = imports.openUpload()
    imports.close(opened.persistedId)
    val (late, lost) = (stagedImage(), stagedImage())

    intercept[IllegalOperationException](imports.upload(opened.persistedId, "late.jpg", late))
    intercept[NotFoundException](imports.upload("no-such-import", "lost.jpg", lost))

    Files.exists(late) shouldBe false
    Files.exists(lost) shouldBe false
    testContext.importFiles(opened.persistedId) shouldBe empty
  }

  test("An upload's Import takes files from, and is closed by, the account that opened it alone") {

    /**
     * Setup:
     *
     * An upload opened by one account, and a Watch folder's Import. A second account is then the context account.
     *
     * Assertions:
     *
     * The second account cannot add files to or close the upload, and neither account can for the Watch folder's Import; nothing
     * is stored and staged files are deleted.
     *
     * Edge cases:
     *
     * The opening account still uploads to it and closes it.
     */
    val sorted = testApp.service.folder.add("Sorted")
    val opener = testContext.user
    val upload = imports.openUpload(destFolderId = sorted.id)
    val scan = imports.open(ImportSource.WatchFolder, name = Some("Phone sync"))
    val other = testContext.persistUser()

    def refusedFor(anImport: Import): Unit = {
      val staged = stagedImage()
      intercept[ForbiddenException](imports.requireTakes(anImport.persistedId, "photo.jpg"))
      intercept[ForbiddenException](imports.upload(anImport.persistedId, "photo.jpg", staged))
      intercept[ForbiddenException](imports.addFailedFile(anImport.persistedId, "photo.jpg", sizeBytes = 10))
      intercept[ForbiddenException](imports.closeUpload(anImport.persistedId))
      Files.exists(staged) shouldBe false
      testContext.importFiles(anImport.persistedId) shouldBe empty
      stored(anImport).isComplete shouldBe false
    }

    switchContextUser(other)
    refusedFor(upload)
    refusedFor(scan)

    switchContextUser(opener)
    refusedFor(scan)
    imports.upload(upload.persistedId, "photo.jpg", stagedImage()).outcome shouldBe ImportOutcome.Pending
    imports.closeUpload(upload.persistedId)
    stored(upload).isComplete shouldBe true
  }

  test(
    "A file sent to an upload whose destination is in the Trash is recorded failed at once, and is neither staged nor queued") {

    /**
     * Setup:
     *
     * An upload into Sorted. A file is announced by path and size while Sorted is live; then Sorted is deleted, another file is
     * announced, and a third uploaded through a shut-down queue.
     *
     * Assertions:
     *
     * While the destination is live nothing is recorded. Once it is in the Trash, the announced file and the uploaded one are
     * recorded failed for it, the latter never reaching the queue, whose refusal would say otherwise. No asset is left.
     *
     * Edge cases:
     *
     * An upload to Triage has no destination to lose, and a refused path records nothing.
     */
    val sorted = testApp.service.folder.add("Sorted")
    val opened = imports.openUpload(destFolderId = sorted.id)
    val stopped = ImportPipelineService(testApp)
    stopped.shutdown()

    imports.failOnArrival(opened.persistedId, "early.jpg", sizeBytes = 2048) shouldBe None
    testContext.importFiles(opened.persistedId) shouldBe empty

    testApp.service.library.deleteFolderById(sorted.persistedId)
    val announced = imports.failOnArrival(opened.persistedId, "Beach/sea.jpg", sizeBytes = 2048)
    val staged = stagedImage()
    val uploaded = imports.upload(opened.persistedId, "sand.jpg", staged, stopped)

    val lost = Some(Const.Msg.Err.DESTINATION_IN_TRASH)
    announced.map(file => (file.relPath, file.sizeBytes, file.outcome, file.reason)) shouldBe Some(
      ("Beach/sea.jpg", 2048, ImportOutcome.Failed, lost))
    (uploaded.outcome, uploaded.reason) shouldBe (ImportOutcome.Failed, lost)
    Files.exists(staged) shouldBe false
    testContext
      .importFiles(opened.persistedId)
      .map(file => (file.relPath, file.outcome, file.reason, file.assetId)) shouldBe List(
      ("Beach/sea.jpg", ImportOutcome.Failed, lost, None),
      ("sand.jpg", ImportOutcome.Failed, lost, None))
    counters(opened) shouldBe (0, 0, 0, 0, 2)
    testApp.service.asset.getDanglingAssets shouldBe empty
    testApp.service.stats.getStats.getStatValue(Stats.SORTED_ASSETS) shouldBe 0

    imports.failOnArrival(imports.openUpload().persistedId, "photo.jpg", sizeBytes = 10) shouldBe None
    intercept[ValidationException](imports.failOnArrival(opened.persistedId, ".hidden.jpg", sizeBytes = 10))
    testContext.importFiles(opened.persistedId).size shouldBe 2
  }

  test("A file the page could not send is recorded failed") {

    /**
     * Setup:
     *
     * An upload's Import with one pending file.
     *
     * Assertions:
     *
     * The unsent file is added failed under its path and size, saying the upload failed, and counted.
     *
     * Edge cases:
     *
     * A refused path, or a complete Import, records nothing.
     */
    val (opened, _) = importOf("sent.jpg")

    val file = imports.addFailedFile(opened.persistedId, "Ceremony/lost.jpg", sizeBytes = 2048)

    val read = stored(file)
    (read.relPath, read.sizeBytes, read.outcome, read.reason, read.assetId) shouldBe (
      "Ceremony/lost.jpg",
      2048,
      ImportOutcome.Failed,
      Some("Upload failed"),
      None)
    counters(opened) shouldBe (1, 0, 0, 0, 1)

    intercept[ValidationException](imports.addFailedFile(opened.persistedId, ".hidden/lost.jpg", sizeBytes = 1))
    imports.close(opened.persistedId)
    intercept[IllegalOperationException](imports.addFailedFile(opened.persistedId, "late.jpg", sizeBytes = 1))
    testContext.importFiles(opened.persistedId).size shouldBe 2
  }

  test("A duplicate of a copy whose import is not complete is failed, saying the copy is not in the Library") {

    /**
     * Setup:
     *
     * An asset whose import is not complete (its discard failed, or another stream is importing it), and a copy of it dropped as
     * a duplicate in an Import into Clients/Wedding.
     *
     * Assertions:
     *
     * The Import file is failed, its copy not in the Library, with no asset: only a completed asset makes a duplicate.
     *
     * Edge cases:
     *
     * Once the copy completes, the next copy is a duplicate of it, in Triage.
     */
    val clients = testApp.service.folder.add("Clients")
    val wedding = testApp.service.folder.add("Wedding", clients.id)
    val unfinished = testApp.service.library.persistAndIndex(testContext.makeAsset(isTriaged = true))
    val copy = testContext.makeAsset(isTriaged = true).copy(checksum = unfinished.checksum)

    val sorting = imports.open(ImportSource.Upload, destFolderId = wedding.id)
    val early = imports.addFile(sorting.persistedId, "early.jpg", sizeBytes = 10)
    val late = imports.addFile(sorting.persistedId, "late.jpg", sizeBytes = 10)

    imports.settleDropped(early.persistedId, copy, Some(DuplicateException()))

    (stored(early).outcome, stored(early).reason, stored(early).assetId) shouldBe (
      ImportOutcome.Failed,
      Some(Const.Msg.Err.COPY_NOT_IN_LIBRARY),
      None)
    counters(sorting) shouldBe (1, 0, 0, 0, 1)

    testApp.service.library.completeImport(unfinished): Unit
    imports.settleDropped(late.persistedId, copy, Some(DuplicateException()))

    (stored(late).outcome, stored(late).reason, stored(late).assetId) shouldBe (
      ImportOutcome.Duplicate,
      Some("Triage"),
      unfinished.id)
  }
}
