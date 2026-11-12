package altitude.core.integration

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

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.ImporterUnavailableException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.Asset
import altitude.core.models.Import
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Settings
import altitude.core.util.Query

/** The Import record: an Import and its Import files, their outcomes, the Import's counters and its closing */
@DoNotDiscover class ImportServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

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
  private def stored(file: ImportFile): ImportFile = imports.files(file.importId).find(_.id == file.id).get

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
      imports.files(opened.persistedId) shouldBe empty
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
    imports.list(rpp = 10).total shouldBe 2
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

    val files = imports.files(opened.persistedId)
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
    imports.files(opened.persistedId, Some(ImportOutcome.Pending)) shouldBe files
    imports.files(opened.persistedId, Some(ImportOutcome.Imported)) shouldBe empty

    imports.close(opened.persistedId)
    intercept[IllegalOperationException](imports.addFile(opened.persistedId, "late.jpg", sizeBytes = 10))
    intercept[NotFoundException](imports.addFile("no-such-import", "lost.jpg", sizeBytes = 10))
    imports.files(opened.persistedId) shouldBe files
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
      imports.files(opened.persistedId, Some(outcome)).map(_.id) shouldBe List(file.id)
    imports.files(opened.persistedId, Some(ImportOutcome.Pending)) shouldBe empty

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
     * The two duplicates are settled as duplicates, the first pointing at the asset with "Triage" as its reason, the second with
     * neither. The unsupported type is settled unsupported. Every other cause is settled failed, with the reason the cause has a
     * name for. The Import counts two duplicates, one unsupported and six failed.
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
      (other, Some(DuplicateException()), (ImportOutcome.Duplicate, None, None)),
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
    counters(opened) shouldBe (0, 0, 2, 1, 6)
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

  test("The Import history lists the Imports in progress first, then the newest first, a page at a time") {

    /**
     * Setup:
     *
     * Four Imports of the context repository, opened in order: the first closed, the second left in progress, the third closed,
     * the fourth left in progress. Another repository has an Import of its own.
     *
     * Assertions:
     *
     * Listed two a page, the first page holds the two Imports in progress, the newer first, and the second page the two closed
     * ones, the newer first; each page reports the four Imports of the repository.
     *
     * Edge cases:
     *
     * The other repository's Import is on no page and in no total, and a page past the last is empty.
     */
    val List(firstClosed, firstOpen, secondClosed, secondOpen) = List.fill(4)(imports.open(ImportSource.Upload)): @unchecked
    imports.close(firstClosed.persistedId)
    imports.close(secondClosed.persistedId)

    val otherRepository = testContext.persistRepository()
    switchContextRepo(otherRepository)
    imports.open(ImportSource.Upload)
    switchContextRepo(testContext.repositories.head)

    val firstPage = imports.list(rpp = 2)
    firstPage.records.map(_.id) shouldBe List(secondOpen.id, firstOpen.id)
    firstPage.total shouldBe 4
    val secondPage = imports.list(rpp = 2, page = 2)
    secondPage.records.map(_.id) shouldBe List(secondClosed.id, firstClosed.id)
    secondPage.total shouldBe 4
    imports.list(rpp = 2, page = 3).records shouldBe empty
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

    val files = imports.files(opened.persistedId)
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
     * Every pending file, in both repositories, is failed with the reason "interrupted by a restart", and every Import that was
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
      (read.outcome, read.reason, read.assetId) shouldBe (ImportOutcome.Failed, Some("interrupted by a restart"), None)
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
    stored(theirLost).reason shouldBe Some("interrupted by a restart")
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
    imports.list(rpp = 10).records.map(_.id) shouldBe List(inProgress.id, recent.id)

    switchContextRepo(second)
    imports.list(rpp = 10).records shouldBe empty
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
    imports.list(rpp = 10).records.map(_.id) shouldBe List(recent.id, ancient.id)
    stored(recentFile).outcome shouldBe ImportOutcome.Imported
    stored(ancientFile).outcome shouldBe ImportOutcome.Imported

    withSetting(Settings.IMPORT_HISTORY_DAYS, "30") {
      imports.prune()
    }
    imports.list(rpp = 10).records.map(_.id) shouldBe List(recent.id)
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
     * The pending file of the recent Import is failed, "interrupted by a restart", and its Import is closed, counting it failed.
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
    (failed.outcome, failed.reason) shouldBe (ImportOutcome.Failed, Some("interrupted by a restart"))
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
    intercept[NotFoundException](imports.files(theirs.persistedId))
    intercept[NotFoundException](imports.addFile(theirs.persistedId, "intruder.jpg", sizeBytes = 10))
    intercept[NotFoundException](imports.close(theirs.persistedId))
    intercept[NotFoundException](imports.settle(theirFile.persistedId, ImportOutcome.Failed))
    intercept[NotFoundException](imports.settle(myFile.persistedId, ImportOutcome.Imported, assetId = theirAsset.id))

    testContext.rowsOf(second) shouldEqual before
    stored(myFile).outcome shouldBe ImportOutcome.Pending
    stored(myFile).assetId shouldBe None
    counters(mine) shouldBe (1, 0, 0, 0, 0)
  }
}
