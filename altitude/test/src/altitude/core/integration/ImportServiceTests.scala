package altitude.core.integration

import altitude.test.IntegrationTestUtil
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

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.ForbiddenException
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.ImporterUnavailableException
import altitude.core.NotFoundException
import altitude.core.RequestContext
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
     * Four Imports opened by the context account: one expecting 24 files, an upload opened expecting 3, one expecting no file at
     * all, and one opened with no number.
     *
     * Assertions:
     *
     * Each is stored with the number it was opened with, or with none, as read by its ID, in the list of the Library's Imports
     * and in the Import history. Files given to the first, and its close, leave the number as it is.
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
     * A folder of the context repository, and two Imports opened by the context account: an upload of a folder named "Holiday"
     * into that folder, and an upload of files with no name and no destination.
     *
     * Assertions:
     *
     * Each is stored as opened: an upload, of the context account, in progress (not complete, not closed), with every counter at
     * zero and no file. The first carries its name and destination, the second neither.
     *
     * Edge cases:
     *
     * An Import whose destination is no folder of the repository (unknown, or another repository's) is not found and nothing is
     * opened.
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

  test("A file added to an Import is pending, and the Import counts it") {

    /**
     * Setup:
     *
     * An upload's Import with two files added, "b/second.jpg" and then "a/first.jpg".
     *
     * Assertions:
     *
     * Both files are stored pending, with their paths and sizes and no reason or asset, the Import counts two pending files, and
     * its files are listed in path order, all of them and by the pending outcome alike; no file has any other outcome.
     *
     * Edge cases:
     *
     * A file added to an Import marked complete is refused, and one added to an Import that does not exist is not found; neither
     * is stored or counted.
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
     * An asset of the repository, and an Import of four pending files, settled one after another: the first imported as that
     * asset, the second a duplicate of it with where it is as the reason, the third unsupported, the fourth failed with a reason.
     *
     * Assertions:
     *
     * After each settle the Import's counters have moved one file from pending to the outcome, and at the end each file carries
     * its outcome, reason and asset, and is the one file listed for its outcome. The Import, not marked complete, is still in
     * progress with nothing pending.
     *
     * Edge cases:
     *
     * Settling a file again leaves its first outcome and the counters as they were. A file that does not exist is not found, and
     * pending is no outcome to settle with.
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
     * An asset of the repository in Triage, and an Import of pending files, each settled as a file the pipeline dropped for one
     * cause: a duplicate of that asset, a duplicate of an asset that is not there (any more), an unsupported media type, an image
     * and a Video that do not decode, a storage error, an importer that is gone, a failure of no expected kind whose message
     * names a path, and no cause at all.
     *
     * Assertions:
     *
     * The duplicate of the asset is settled as a duplicate, pointing at the asset with "Triage" as its reason. The duplicate of
     * an asset that is not there is settled failed, saying that the copy it matched is not in the Library, and points at none:
     * its content is in no Library. The unsupported type is settled unsupported. Every other cause is settled failed, with the
     * reason the cause has a name for. The Import counts one duplicate, one unsupported and seven failed.
     *
     * Edge cases:
     *
     * A failure of no expected kind is recorded by its exception's class alone, never by its message, and a drop with no cause as
     * an unknown error.
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
      (other, None, (ImportOutcome.Failed, Some("Unknown error"), None))
    )
    val (opened, files) = importOf(causes.indices.map(index => s"$index.jpg")*)

    for ((file, (asset, cause, _)) <- files.zip(causes)) imports.settleDropped(file.persistedId, asset, cause)

    files.map(stored).map(file => (file.outcome, file.reason, file.assetId)) shouldBe causes.map(_._3)
    counters(opened) shouldBe (0, 0, 1, 1, 7)
  }

  test("An Import closes once it is marked complete and none of its files is pending") {

    /**
     * Setup:
     *
     * Three Imports. The first has two pending files and is marked complete before either settles, then the files settle one by
     * one. The second has one file, which settles before the Import is marked complete. The third has no file at all, as an
     * upload cancelled before it sent anything.
     *
     * Assertions:
     *
     * The first stays in progress, though complete, until its last file settles, which closes it. The second is in progress after
     * its file settles and closes when it is marked complete. The third closes as it is marked complete.
     *
     * Edge cases:
     *
     * Marking a closed Import complete again leaves the moment it closed at, and an Import that does not exist is not found.
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
     * An Import marked complete with two pending files. Two transactions settle one file each, the first held open until the
     * second waits for it: for the Import's row on PostgreSQL, for the write connection on SQLite.
     *
     * Assertions:
     *
     * Both settles are counted: nothing is pending, one file is imported and one failed, and the Import is closed. A write that
     * set the counters from a read of its own would lose the first settle on PostgreSQL, where the second read the Import before
     * the first committed.
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
     * Four Imports of the context repository, opened in order: the first closed, the second left in progress, the third closed,
     * the fourth left in progress. Another repository has an Import of its own.
     *
     * Assertions:
     *
     * Asked for two, the history holds the two Imports in progress, the newer first; asked for three, the newer of the closed
     * ones after them; asked for more than there are, all four, the closed ones after those in progress, the newer first. Each
     * reports the four Imports of the repository.
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
     * A closed Import seeded into 5,000 closed copies, each opened a minute before the last, and one Import in progress, analyzed
     * and rolled back once the plans are read: a history the planner has rows to choose by, in which the Imports in progress are
     * few.
     *
     * Assertions:
     *
     * The engine's plan for each statement the history's listing runs reads an index and holds no sort of all the rows it reads:
     * at the most the Imports of one moment are put in order. The statement of the Imports in progress names `import_03`, the
     * index of those alone, and the statement of the closed ones `import_01`, the history by age; the count is read off an index
     * alone.
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
      // The table as vacuuming leaves it, every page all-visible, which seeded rows never are: the count is then read off an
      // index alone, as it is in a database that has run for a while
      if (isPostgres) {
        update("UPDATE pg_class SET relallvisible = relpages WHERE relname = ?", "import")
        update("SET LOCAL enable_bitmapscan = off")
      }
      dao.listStatements(shown = 20).map((sql, values) => lookupPlanOf(sql, values))
    }

    // PostgreSQL's node for a sort of everything below it, and SQLite's; a sort within one moment's Imports reads otherwise
    val sortOfAll = if (isPostgres) """(?m)^\s*(->\s+)?Sort\s+\(""".r else "USE TEMP B-TREE FOR ORDER BY".r
    plans.foreach {
      plan =>
        withClue(plan) {
          plan should include(if (isPostgres) "Index" else "INDEX")
          sortOfAll.findFirstIn(plan) shouldBe None
        }
    }
    // The Imports in progress, the closed ones, and the count, which any index that holds the repository answers
    val List(inProgress, closed, _) = plans: @unchecked
    withClue(inProgress)(inProgress should include("import_03"))
    withClue(closed)(closed should include("import_01"))
  }

  test("The Import history names who delivered each Import, where its files went and whether a restart interrupted it") {

    /**
     * Setup:
     *
     * A repository with a folder Clients and its subfolder Wedding, and an Admin. A second account uploads a folder into Wedding
     * and leaves one of its two files pending; a Watch folder's scan, which no account delivers, imports a file to Triage and
     * closes. The restart's step then runs. After it the context account opens an upload of files, left in progress. Another
     * repository has an Import of its own.
     *
     * Assertions:
     *
     * The history holds the repository's three Imports: the one in progress first, then the closed ones, the newest first. The
     * upload of files names the context account, no destination, and no interruption. The scan names nobody, no destination and
     * no interruption. The folder upload names the account that delivered it, its destination by the folder's path, and that a
     * restart interrupted it. Each carries the moment it was opened, as an instant: within the last minute.
     *
     * Edge cases:
     *
     * Once the Admin deletes the second account, the folder upload names nobody. Asked for two Imports, the history holds the
     * first two and still counts three. The other repository's Import is in neither.
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

    val scan = RequestContext.account.withValue(None)(imports.open(ImportSource.WatchFolder, name = Some("Phone sync")))
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
     * An Import with five files, added out of path order: a.jpg and c.jpg imported, b.jpg failed, d.jpg and e.jpg pending.
     * Another repository has an Import with a file.
     *
     * Assertions:
     *
     * Asked for three files of any outcome, the Import gives a.jpg, b.jpg and c.jpg, of five in all. Asked for its imported files
     * it gives a.jpg and c.jpg, of two; asked for one pending file, d.jpg, of two; and for its duplicates none, of none. Each
     * answer carries the Import with its counters.
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
     * A listener of the Import record's changes, kept to the Imports of this test's repository. An Import is opened, given a
     * file, and the file is settled as imported twice: first inside a transaction that then fails, then on its own. The Import is
     * closed.
     *
     * Assertions:
     *
     * The listener hears of the opening with every counter at zero, of the file with one pending, of the settle that committed
     * with one imported and none pending, and of the close with the Import closed, each once, in that order and under the
     * repository's ID. The settle that rolled back is not heard of, and left the file pending.
     *
     * Edge cases:
     *
     * A file that is added and failed in one transaction, as one the page could not send is, is never told as pending: what is
     * heard is the Import as that transaction committed it.
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
     * An Import with two files settled against one asset of the repository, one imported as it and one a duplicate of it. The
     * asset is then recycled and purged.
     *
     * Assertions:
     *
     * Once the purge queue has deleted the asset, both files are still there with their paths, outcomes and reasons, and neither
     * points at an asset. The Import's counters are as they were.
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
     * Four Imports of the context repository as a server that stopped mid-import leaves them: one in progress with one imported
     * and two pending files; one marked complete with a pending file; one in progress with no file; and one closed, its one file
     * imported. A second repository has an Import in progress with a pending file. Then the startup's step runs.
     *
     * Assertions:
     *
     * Every pending file, in both repositories, is failed with the reason "Interrupted by a restart", and every Import that was
     * in progress is complete and closed, counting its pending files as failed and none pending. The context is the first
     * repository again.
     *
     * Edge cases:
     *
     * A file settled before the restart keeps its outcome, and the Import closed before it keeps its counters and the moment it
     * closed at.
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

  /** Rewrites the moment the Import was opened to `days` days and `minutes` minutes ago, in the engine's storage form */
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
     * The Import history Setting at its default of 90 days. In the context repository: a closed Import opened 90 days and a
     * minute ago, a closed one opened a minute short of 90 days ago, and one opened 200 days ago that is still in progress, with
     * a pending file. In a second repository: a closed Import opened 200 days ago. Each has one Import file. Then the prune runs.
     *
     * Assertions:
     *
     * The two closed Imports past 90 days are gone, in both repositories, and so are their Import files. The Import just inside
     * the 90 days is still there with its file. The context is the first repository again.
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
     * Two closed Imports with a file each, opened 10 and 4000 days ago. The prune runs with the Import history Setting saved as
     * 0, and then as 30.
     *
     * Assertions:
     *
     * At 0 the prune deletes nothing: both Imports and their files are still there. At 30 it deletes the one opened 4000 days ago
     * with its file, and keeps the one opened 10 days ago.
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
     * The Import history Setting at its default of 90 days, and four Imports with a file each: one in progress with a pending
     * file; one in progress with a pending file that was opened 200 days ago; a closed one opened 200 days ago; and a closed one
     * opened 10 days ago. Then the Import record's startup step runs, as `App` runs it.
     *
     * Assertions:
     *
     * The pending file of the recent Import is failed, "Interrupted by a restart", and its Import is closed, counting it failed.
     * The closed Import opened 200 days ago is gone with its file, though no daily prune ran. The closed Import opened 10 days
     * ago is as it was.
     *
     * Edge cases:
     *
     * The Import opened 200 days ago that the restart interrupted is closed by the first step and so pruned by the second: it is
     * gone with its file too.
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
     * An Import in progress with a pending file, and a closed Import opened 200 days ago whose row the database refuses to
     * delete, as a failing statement would. Then the Import record's startup step runs.
     *
     * Assertions:
     *
     * The prune on its own fails with the database's refusal. The startup step does not: it returns, having failed the pending
     * file and closed its Import, and the Import the prune could not delete is still there with its file.
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
     * The Import names the account that delivered it until the account is deleted, and no account after. It is still there, with
     * its file pending and counted.
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
     * Two repositories. The second has an asset and an Import with one pending file; the first has an Import with one pending
     * file. The context is the first repository.
     *
     * Assertions:
     *
     * From the first repository, the second's Import is not found to read, to list its files, to add a file to or to close, and
     * its file is not found to settle. Settling the first repository's own file as the second repository's asset is not found
     * either, and leaves the file pending. Every row of the second repository is as it was, and the first repository's Import
     * still counts its one pending file.
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

  /** A random image staged as an upload's request stages it */
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
     * Opening an Import with that folder as its destination is refused, saying that the destination folder is in the Trash, and
     * opens nothing.
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
     * A folder of the context repository, and a folder of another repository. An account holding Import alone, and one holding
     * Import and Organize.
     *
     * Assertions:
     *
     * The account with Import alone opens an upload's Import to Triage, under the name it gives, and is refused one into the
     * folder, which opens nothing. The account with Organize too opens one into the folder.
     *
     * Edge cases:
     *
     * Another repository's folder is not found for the account with Import alone, rather than refused: the folder is read in the
     * repository before the Permission is checked.
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
     * An account holding Import and Organize opens two uploads: one into a folder, one to Triage. Its Organize is then taken
     * away, so it holds Import alone, as its requests do from the next one on.
     *
     * Assertions:
     *
     * The upload into the folder takes nothing more from the account: asking whether it takes a file, sending one, whose staged
     * file is deleted, failing one for a lost destination and recording one the page could not send are each refused as
     * forbidden, naming Organize, and the Import has no file. The account still closes it, so the Import does not stay open.
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
        intercept[ForbiddenException](imports.failForLostDestination(sorting.persistedId, "a.jpg", sizeBytes = 10)),
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

  test("An upload's name and paths are logged with their control characters escaped, so none starts a log line") {

    /**
     * Setup:
     *
     * The Import record's log captured at DEBUG. An upload opened under a name that holds a line break followed by what reads as
     * a log line of its own, and three files of it whose paths hold a line break or a carriage return: one recorded as the page
     * could not send it, then settled a second time; and one sent to an import queue that is shut down, which refuses it.
     *
     * Assertions:
     *
     * No line logged holds a control character. The lines that name the Import or a path, at INFO, WARN and DEBUG, hold the break
     * as its escape: the Import's opening, a file added, failed on arrival, settled, settled already, and failed before the
     * pipeline.
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
      List(
        "Opened Import",
        "Added Import file",
        "is failed on arrival",
        "is failed",
        "was settled already",
        "failed before the pipeline").foreach {
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
     * An upload's Import with the folder Clients as its destination, and two staged images uploaded to it: one under the path
     * Ceremony/vows.jpg and one under its bare name.
     *
     * Assertions:
     *
     * Each upload returns its Import file, pending, under the path it was uploaded with and with the staged file's size, and the
     * Import counts it. The import queue then imports both: each file is settled imported, the first as an asset named vows.jpg
     * in Clients/Ceremony, the second in Clients itself, and the Import, once closed, counts two imported.
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

  test("An uploaded file the import queue refuses is recorded failed") {

    /**
     * Setup:
     *
     * An upload's Import, and an import queue of the test's own, shut down. A staged image is uploaded to the Import through that
     * queue.
     *
     * Assertions:
     *
     * The upload returns the Import file as failed, saying that the import queue is unavailable, and it is stored so: the Import
     * counts one failed and none pending. The staged file is gone.
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
     * The upload returns the Import file as failed, with the failure's class as its reason, and it is stored so: the Import
     * counts one failed and none pending. What was staged is gone.
     */
    val opened = imports.openUpload()
    val unreadable = Files.createDirectories(testApp.service.staging.dir.resolve(java.util.UUID.randomUUID.toString))

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
     * An upload's Import, and a staged image for each path: one with a name starting with a dot, as a file and as a folder, the
     * operating systems' and the NAS's own names in any case, the names a download in progress leaves, a path with "..", and
     * paths with an empty name (empty, a leading, a doubled and a trailing separator). And an empty staged file under a fine
     * name.
     *
     * Assertions:
     *
     * Every one is refused as a bad request, and its staged file is deleted. The Import has no file and counts nothing.
     *
     * Edge cases:
     *
     * A name that only holds an ignored name (a dot inside it, "thumbs.db.jpg", "apart") is fine: the file is added, pending.
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
     * The upload to the complete Import is refused as an illegal operation and the one to the unknown Import is not found. Each
     * staged file is deleted, and the complete Import has no file.
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
     * An upload's Import into the folder Sorted, opened by one account, and a Watch folder's Import. A second account of the
     * repository is then the context account.
     *
     * Assertions:
     *
     * From the second account, a file uploaded to the first account's Import, a failed file recorded for it and its close are
     * each refused as forbidden: the Import has no file and stays in progress, and the staged file is deleted. The same goes, for
     * either account, for the Watch folder's Import, which is no upload.
     *
     * Edge cases:
     *
     * The account that opened the upload still uploads to it and closes it.
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
     * An upload's Import into the folder Sorted. While Sorted is live, a file is announced to it by its path and size, before its
     * content is read. Sorted is then deleted, which recycles it; a second file is announced the same way, and a third is
     * uploaded, staged, through an import queue of the test's own that is shut down.
     *
     * Assertions:
     *
     * With the destination live nothing is recorded for the announced file: its content is to be staged and uploaded. Once the
     * destination is in the Trash, the announced file is recorded failed at once, under its path and size, saying that the
     * destination folder is in the Trash. The uploaded one is recorded failed with that reason too, not with the queue's refusal,
     * so it was never offered to the queue, and its staged file is deleted. The Import counts two failed and none pending, and
     * the repository holds no asset.
     *
     * Edge cases:
     *
     * An upload to Triage has no destination to lose. A path the uploads refuse is refused here too, and records nothing.
     */
    val sorted = testApp.service.folder.add("Sorted")
    val opened = imports.openUpload(destFolderId = sorted.id)
    val stopped = ImportPipelineService(testApp)
    stopped.shutdown()

    imports.failForLostDestination(opened.persistedId, "early.jpg", sizeBytes = 2048) shouldBe None
    testContext.importFiles(opened.persistedId) shouldBe empty

    testApp.service.library.deleteFolderById(sorted.persistedId)
    val announced = imports.failForLostDestination(opened.persistedId, "Beach/sea.jpg", sizeBytes = 2048)
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

    imports.failForLostDestination(imports.openUpload().persistedId, "photo.jpg", sizeBytes = 10) shouldBe None
    intercept[ValidationException](imports.failForLostDestination(opened.persistedId, ".hidden.jpg", sizeBytes = 10))
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
     * Recording a file that was not received adds it to the Import as failed, under its path and size, saying that the upload
     * failed, and the Import counts it failed, with its one file still pending.
     *
     * Edge cases:
     *
     * A path the uploads refuse is refused here too, and a complete Import takes no such file either; neither records anything.
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
     * An asset persisted by an import that has not completed it, and never will: one whose discard failed, or that another stream
     * is importing. A second copy of it dropped as a duplicate in an Import whose destination is the folder Wedding under
     * Clients.
     *
     * Assertions:
     *
     * The Import file is failed, saying that the copy it matched is not in the Library, and points at no asset: only a completed
     * asset makes a file a duplicate. The Import counts it as failed.
     *
     * Edge cases:
     *
     * Once the copy's import completes, the next file with its content is a duplicate of it, naming where it is: Triage.
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
