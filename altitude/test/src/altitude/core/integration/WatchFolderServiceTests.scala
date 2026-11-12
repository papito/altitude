package altitude.core.integration

import altitude.test.IntegrationTestUtil
import altitude.test.TestContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.io.FileUtils
import org.apache.pekko.actor.Cancellable
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.ValidationException
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Import
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.Settings
import altitude.core.models.User
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolder
import altitude.core.models.WatchFolderSpec
import altitude.core.models.WatchFolderStatus
import altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import altitude.core.service.ImportPipelineService
import altitude.core.service.WatchFolderService.ScanResult
import altitude.core.util.Sha256

/**
 * The Watch folders: the path rules, the scan and what it remembers, the retries, the statuses, the owner rules, and the deletion
 * of imported files. Every directory a test watches is a fresh one under the suite's own temporary directory.
 */
@DoNotDiscover class WatchFolderServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def watchFolders = testApp.service.watchFolder
  private def imports = testApp.service.imports
  private val F = FieldConst.WatchFolder
  private val E = Const.Msg.Err

  // The suite's own temporary directory, which holds every directory a test watches and goes with the suite
  private var tempRoot: Path = scala.compiletime.uninitialized

  // The Admin every test acts as, who owns the Watch folders it adds
  private var admin: User = scala.compiletime.uninitialized

  override def beforeAll(): Unit = {
    super.beforeAll()
    tempRoot = Files.createTempDirectory("altitude-watch-folders")
  }

  override def afterAll(): Unit =
    try FileUtils.deleteDirectory(tempRoot.toFile)
    finally super.afterAll()

  override def beforeEach(): Unit = {
    super.beforeEach()
    admin = persistAdmin()
    switchContextUser(admin)
  }

  // A bundle's database keeps every test's Libraries: no Watch folder outlives its test, so a scan of every Watch folder reads
  // the test's own alone
  override def afterEach(): Unit =
    try testApp.txManager.withTransaction(update("DELETE FROM watch_folder"))
    finally super.afterEach()

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  private def persistAdmin(): User = testContext.persistUser(Some(testContext.makeAdminUser()))

  /** A fresh, empty directory under the suite's temporary directory */
  private def directory(prefix: String = "watched"): Path = Files.createTempDirectory(tempRoot, prefix).toRealPath()

  /** A random image written at the path under the directory, its directories created; `size` is the image's side, in pixels */
  private def image(dir: Path, relPath: String, size: Int = 40): Path = {
    val path = dir.resolve(relPath)
    Files.createDirectories(path.getParent)
    Files.write(path, IntegrationTestUtil.generateRandomImagBytesBgr(size))
  }

  /** A file no import supports, written at the path under the directory with the text after what every such file says */
  private def textFile(dir: Path, relPath: String, text: String = ""): Path = {
    val path = dir.resolve(relPath)
    Files.createDirectories(path.getParent)
    Files.write(path, s"not an image ${java.util.UUID.randomUUID} $text".getBytes)
  }

  /** A Watch folder of the context repository on the directory, added by the context account */
  private def watch(
      dir: Path,
      name: String = "Phone sync",
      destination: Option[Folder] = None,
      deletesImported: Boolean = false): WatchFolder =
    watchFolders.add(WatchFolderSpec(name, dir.toString, destination.flatMap(_.id), deletesImported))

  /** The errors a refused add or edit names, by field */
  private def refusal(write: => Any): Map[String, String] =
    intercept[ValidationException](write).errors.toMap

  /** One scan of the Watch folder, up to its last file's offer to the import queue */
  private def scan(watchFolder: WatchFolder, pipeline: ImportPipelineService = testApp.service.importPipeline): ScanResult =
    Await.result(watchFolders.scan(watchFolder.persistedId, pipeline), 60.seconds)

  /** Waits until nothing of the Watch folder is being scanned or is in the import pipeline */
  private def settle(watchFolder: WatchFolder): Unit =
    eventually(watchFolders.isWorking(watchFolder.persistedId) shouldBe false)

  /**
   * One scan of the Watch folder, with everything it handed to the pipeline settled, and then the rest of the files it has not
   * read yet let pass, as the interval before the next scan does: the next scan may read them
   */
  private def scanAndSettle(watchFolder: WatchFolder): ScanResult = {
    val result = scan(watchFolder)
    settle(watchFolder)
    testContext.letWatchedFilesRest()
    result
  }

  /** Two scans an interval apart, as it takes to read a file found for the first time, with everything settled */
  private def scanTwice(watchFolder: WatchFolder): ScanResult = {
    scanAndSettle(watchFolder)
    scanAndSettle(watchFolder)
  }

  /** What the Watch folder remembers: each file's state and attempts, by its path */
  private def memory(watchFolder: WatchFolder): Map[String, (WatchFileState, Int)] =
    watchFolders.memory(watchFolder.persistedId).map(file => file.relPath -> (file.state, file.attempts)).toMap

  /** The Imports of the Watch folder's scans in the history, the oldest first */
  private def importsOf(watchFolder: WatchFolder): List[Import] =
    imports.list(rpp = 100).records.filter(_.watchFolderId == watchFolder.id).sortBy(_.createdAt)

  /** The files of the Watch folder's Imports, the oldest Import's first: path, outcome and reason */
  private def recorded(watchFolder: WatchFolder): List[(String, ImportOutcome, Option[String])] =
    importsOf(watchFolder).flatMap(anImport => imports.files(anImport.persistedId)).map(recordOf)

  private def recordOf(file: ImportFile): (String, ImportOutcome, Option[String]) = (file.relPath, file.outcome, file.reason)

  /** The live asset of the Library that has the file's content, if any */
  private def assetOf(file: Path): Option[Asset] = testApp.service.asset.getByChecksum(Sha256.hex(file))

  /** The names of the folders the asset is in, below the root */
  private def placeOf(asset: Asset): List[String] = testApp.service.folder.getPath(asset.folderId).map(_.name)

  /** Every file and directory under the directory, by its path under it, directories with a trailing "/" */
  private def tree(dir: Path): Set[String] = {
    val walk = Files.walk(dir)
    try
      walk
        .iterator()
        .asScala
        .filter(_ != dir)
        .map(path => dir.relativize(path).toString + (if (Files.isDirectory(path)) "/" else ""))
        .toSet
    finally walk.close()
  }

  /** An import pipeline that runs `before` once, as the scan offers its first file, and then offers to the instance's own queue */
  private def pipelineThat(before: => Unit): ImportPipelineService = new ImportPipelineService(testApp) {
    private val isDone = new AtomicBoolean(false)

    override def addToQueue(asset: TDataAssetWithContext): Future[Unit] = {
      if (isDone.compareAndSet(false, true)) before
      testApp.service.importPipeline.addToQueue(asset)
    }
  }

  /** Runs the work in the test's repository as the test's Admin, on whatever thread calls it */
  private def asAdmin[T](repository: Repository)(work: => T): T =
    RequestContext.repository.withValue(Some(repository)) {
      RequestContext.account.withValue(Some(admin)) {
        RequestContext.permissions.withValue(Permissions.all)(work)
      }
    }

  private def addLibrary(): Repository =
    testApp.service.repository.addRepository(name = TestContext.nextRepositoryName(), fileStoreType = Const.StorageEngineName.FS)

  /** An asset of the Library imported from a file, by a Watch folder of its own, so its stored file has the content it names */
  private def importedAsset(): Asset = {
    val dir = directory("library")
    val file = image(dir, "known.png")
    scanTwice(watch(dir, name = "Library")) shouldBe ScanResult.Scanned(1)
    assetOf(file).get
  }

  /**
   * Keeps the store of the test's Library outside the data directory while `work` runs, as a store on another volume is kept: its
   * directory in the data directory is a symbolic link to `target`
   */
  private def withStoreAt[T](target: Path)(work: => T): T = {
    val inData = Path.of(testApp.dataPath, Const.DataStore.REPOSITORIES, testContext.repository.persistedId)
    FileUtils.deleteDirectory(inData.toFile)
    Files.createDirectories(inData.getParent)
    Files.createSymbolicLink(inData, target)
    try work
    finally Files.delete(inData)
  }

  test("A Watch folder is added for its Library with the account that adds it as its owner") {

    /**
     * Setup:
     *
     * An Admin, a folder of the Library, and two empty directories on the server.
     *
     * Assertions:
     *
     * A Watch folder added on the first directory is stored with its name trimmed, its path, no destination, deletion off, not
     * paused, with no status reason and no scan yet, and the Admin as its owner. One added on the second with a destination and
     * deletion on is stored so. The Library's cards list both by name, each watching, with nothing imported or failed.
     *
     * Edge cases:
     *
     * A path written with a "." and a ".." in it is stored without them.
     */
    val folder = testApp.service.folder.add("Scans")
    val first = directory()
    val second = directory()

    val plain = watch(first, name = "  Phone sync ")
    val sorted = watchFolders.add(
      WatchFolderSpec(
        "Scanner",
        s"${second.getParent}/./${second.getFileName}/../${second.getFileName}",
        folder.id,
        deletesImported = true))

    val stored = watchFolders.getById(plain.persistedId)
    (stored.name, stored.path, stored.destFolderId, stored.deletesImported) shouldBe ("Phone sync", first.toString, None, false)
    (stored.isPaused, stored.statusReason, stored.lastScanAt) shouldBe (false, None, None)
    stored.accountId shouldBe admin.persistedId

    val storedSorted = watchFolders.getById(sorted.persistedId)
    (storedSorted.path, storedSorted.destFolderId, storedSorted.deletesImported) shouldBe (second.toString, folder.id, true)

    val cards = watchFolders.cards()
    cards.map(_.watchFolder.name) shouldBe List("Phone sync", "Scanner")
    cards.map(_.status).toSet shouldBe Set(WatchFolderStatus.Watching)
    cards.map(card => (card.destination, card.imported, card.failed, card.isScanning)) shouldBe
      List((None, 0, 0, false), (Some("Scans"), 0, 0, false))
  }

  test("A Watch folder's name, path and destination are held to their rules") {

    /**
     * Setup:
     *
     * An Admin, a directory on the server with a file in it and a directory in it that the server cannot read, and a folder of
     * the Library that is in the Trash.
     *
     * Assertions:
     *
     * Each of these is refused with an error on the path's field that says why, and adds nothing: no path, a relative path, a
     * path that does not exist, a path that is a file, a directory the server cannot read, and, with deletion on, a directory it
     * cannot write to. A blank name is refused on the name's field, and a destination in the Trash on the destination's.
     *
     * Edge cases:
     *
     * A directory the server cannot write to is taken with deletion off. A name and a path refused together are both named. A
     * destination that is no folder of the Library is not found.
     */
    val dir = directory()
    val file = image(dir, "photo.png")
    val unreadable = Files.createDirectory(dir.resolve("locked"))
    val readOnly = Files.createDirectory(dir.resolve("read-only"))
    val recycled = testApp.service.folder.add("Gone")
    testApp.service.library.deleteFolderById(recycled.persistedId)

    def add(path: String, name: String = "Watched", destination: Option[String] = None, deletes: Boolean = false) =
      watchFolders.add(WatchFolderSpec(name, path, destination, deletes))

    try {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"))
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
      // An account that reads and writes whatever the permissions say (root) cannot run into these two rules
      assume(!Files.isReadable(unreadable) && !Files.isWritable(readOnly))

      refusal(add("  ")) shouldBe Map(F.PATH -> E.VALUE_REQUIRED)
      refusal(add("relative/path")) shouldBe Map(F.PATH -> E.WATCH_PATH_RELATIVE)
      refusal(add(dir.resolve("missing").toString)) shouldBe Map(F.PATH -> E.WATCH_PATH_MISSING)
      refusal(add(file.toString)) shouldBe Map(F.PATH -> E.WATCH_PATH_NOT_DIRECTORY)
      refusal(add(unreadable.toString)) shouldBe Map(F.PATH -> E.WATCH_PATH_UNREADABLE)
      refusal(add(readOnly.toString, deletes = true)) shouldBe Map(F.PATH -> E.WATCH_PATH_UNWRITABLE)
      refusal(add(dir.toString, name = " ")) shouldBe Map(F.NAME -> E.VALUE_REQUIRED)
      refusal(add(dir.toString, destination = recycled.id)) shouldBe Map(F.DEST_FOLDER_ID -> E.DESTINATION_IN_TRASH)
      refusal(add("relative", name = "")) shouldBe Map(F.NAME -> E.VALUE_REQUIRED, F.PATH -> E.WATCH_PATH_RELATIVE)
      intercept[NotFoundException](add(dir.toString, destination = Some(java.util.UUID.randomUUID.toString)))

      watchFolders.cards() shouldBe empty

      add(readOnly.toString).deletesImported shouldBe false
    } finally {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwxr-xr-x"))
      Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"))
    }
  }

  test("No two Watch folders overlap, in any Library, nor does one overlap the app's data directory") {

    /**
     * Setup:
     *
     * A Watch folder "Phone sync" on a directory that has a subdirectory, in the first Library, and a second Library.
     *
     * Assertions:
     *
     * In either Library, a Watch folder on the same directory, on its subdirectory and on its parent is refused, on the path's
     * field, naming "Phone sync" and, from the second Library, the first Library too. The app's data directory, a directory
     * inside it and the directory that holds it are refused as overlapping the data directory. Nothing is added.
     *
     * Edge cases:
     *
     * A path that reaches the watched directory, its subdirectory or the data directory through a symbolic link is refused the
     * same way. A sibling of the watched directory is taken. An edit of "Phone sync" is not refused for its own path.
     */
    val parent = directory("parent")
    val watched = Files.createDirectory(parent.resolve("watched"))
    val inside = Files.createDirectories(watched.resolve("2026/trip"))
    val sibling = Files.createDirectory(parent.resolve("sibling"))
    val linkToWatched = Files.createSymbolicLink(parent.resolve("link-to-watched"), watched)
    val linkInside = Files.createSymbolicLink(parent.resolve("link-inside"), inside)
    val data = Path.of(testApp.dataPath).toRealPath()
    val linkToData = Files.createSymbolicLink(parent.resolve("link-to-data"), data)
    val firstLibrary = testContext.repository
    val phoneSync = watch(watched)

    def refusedAs(path: Path, expected: String): Unit =
      refusal(watch(path, name = "Another")) shouldBe Map(F.PATH -> expected)

    def refusedEverywhere(library: String): Unit = {
      refusedAs(watched, E.WATCH_PATH_SAME.format("Phone sync", library))
      refusedAs(linkToWatched, E.WATCH_PATH_SAME.format("Phone sync", library))
      refusedAs(inside, E.WATCH_PATH_INSIDE.format("Phone sync", watched, library))
      refusedAs(linkInside, E.WATCH_PATH_INSIDE.format("Phone sync", watched, library))
      refusedAs(parent, E.WATCH_PATH_CONTAINS.format("Phone sync", watched, library))
      refusedAs(data, E.WATCH_PATH_OVERLAPS_DATA)
      refusedAs(linkToData, E.WATCH_PATH_OVERLAPS_DATA)
      refusedAs(Files.createDirectories(data.resolve("staging")), E.WATCH_PATH_OVERLAPS_DATA)
      refusedAs(data.getParent, E.WATCH_PATH_OVERLAPS_DATA)
    }

    refusedEverywhere(library = "")
    watchFolders.update(phoneSync.persistedId, WatchFolderSpec("Phone sync 2", watched.toString)).name shouldBe "Phone sync 2"
    watchFolders.update(phoneSync.persistedId, WatchFolderSpec("Phone sync", watched.toString)): Unit
    watchFolders.cards().map(_.watchFolder.path) shouldBe List(watched.toString)

    switchContextRepo(addLibrary())
    refusedEverywhere(library = E.WATCH_PATH_OTHER_LIBRARY.format(firstLibrary.name))
    watchFolders.cards() shouldBe empty
    watch(sibling, name = "Sibling").path shouldBe sibling.toString
    switchContextRepo(firstLibrary)
  }

  test(
    "A Watch folder is refused where a symbolic link in the data directory leads: on the store kept there, around it, inside it") {

    /**
     * Setup:
     *
     * A temporary directory "volume" holding "store" and "photos". The store of the test's Library is kept in "store": its
     * directory in the data directory is a symbolic link to it. An asset imported into the Library, so the store holds its file.
     *
     * Assertions:
     *
     * A Watch folder on "store", on "volume", which holds it, and on the directory of the Library's files inside it is refused,
     * on the path's field, as overlapping the app's data directory, although none of the three paths is under the data directory.
     * Nothing is added.
     *
     * Edge cases:
     *
     * "photos", which lies beside the store, is taken.
     */
    val volume = directory("volume")
    val store = Files.createDirectory(volume.resolve("store"))
    val photos = Files.createDirectory(volume.resolve("photos"))

    withStoreAt(store) {
      val stored = testApp.service.fileStore.assetFile(testContext.persistAsset(user = Some(admin)).persistedId).toRealPath()
      stored.startsWith(store) shouldBe true

      List(store, volume, stored.getParent.getParent).foreach {
        path => refusal(watch(path)) shouldBe Map(F.PATH -> E.WATCH_PATH_OVERLAPS_DATA)
      }
      watchFolders.cards() shouldBe empty

      watch(photos).path shouldBe photos.toString
    }
  }

  test("Of two Watch folders added at once on a directory and on one inside it, the second is refused") {

    /**
     * Setup:
     *
     * A directory with a subdirectory. Two transactions run at once: the first adds a Watch folder on the directory and stays
     * open; the second adds one on the subdirectory.
     *
     * Assertions:
     *
     * The second waits for the first and is then refused as inside the first's Watch folder, so the Library has the first alone.
     */
    val watched = directory()
    val inside = Files.createDirectory(watched.resolve("inside"))
    val repository = testContext.repository
    var refused: Option[ValidationException] = None

    contend(
      asAdmin(repository)(watch(watched, name = "Outer"): Unit),
      asAdmin(repository) {
        try watch(inside, name = "Inner"): Unit
        catch { case ex: ValidationException => refused = Some(ex) }
      }
    )

    refused.map(_.errors.toMap) shouldBe Some(Map(F.PATH -> E.WATCH_PATH_INSIDE.format("Outer", watched, "")))
    watchFolders.cards().map(_.watchFolder.name) shouldBe List("Outer")
  }

  test("A scan reads a file once two scans in a row find it the same, and catches up on the files present before the first") {

    /**
     * Setup:
     *
     * A Watch folder with no destination on a directory that holds two images already, one of them in a subdirectory.
     *
     * Assertions:
     *
     * The first scan hands nothing to the pipeline and remembers both files as seen; no Import is opened. The second imports
     * both: one Import of the Watch folder, named after it, with no account's upload (the owner as its account) and no
     * destination, closed with two imported files under their paths; both assets are in Triage and owned by the Watch folder's
     * owner; both files are remembered as handled, and both are still in the directory, where nothing else appeared. The card
     * counts two imported and has a last scan.
     *
     * Edge cases:
     *
     * A file still growing is not read: written before a scan, and longer by the next scan, it is seen again rather than read,
     * and read by the scan after, which finds it as the one before did.
     */
    val dir = directory()
    val first = image(dir, "a.png")
    val second = image(dir, "2026/b.png")
    val watchFolder = watch(dir)

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0), "2026/b.png" -> (WatchFileState.Seen, 0))
    importsOf(watchFolder) shouldBe empty

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(2)

    val List(scanImport) = importsOf(watchFolder)
    (scanImport.source, scanImport.name, scanImport.destFolderId) shouldBe (ImportSource.WatchFolder, Some("Phone sync"), None)
    scanImport.accountId shouldBe Some(admin.persistedId)
    scanImport.isInProgress shouldBe false
    (scanImport.imported, scanImport.total) shouldBe (2, 2)
    recorded(watchFolder) shouldBe List(("2026/b.png", ImportOutcome.Imported, None), ("a.png", ImportOutcome.Imported, None))
    for (file <- List(first, second)) {
      val asset = assetOf(file).value
      (asset.isTriaged, asset.ownerUserId) shouldBe (true, admin.persistedId)
    }
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0), "2026/b.png" -> (WatchFileState.Handled, 0))
    tree(dir) shouldBe Set("a.png", "2026/", "2026/b.png")
    val card = watchFolders.cards().head
    (card.imported, card.failed, card.lastScanAt.isDefined) shouldBe (2, 0, true)

    // A file a copy is still writing
    val growing = image(dir, "growing.png")
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    Files.write(growing, IntegrationTestUtil.generateRandomImagBytesBgr(60))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder)("growing.png") shouldBe (WatchFileState.Seen, 0)
    assetOf(growing) shouldBe None

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    memory(watchFolder)("growing.png") shouldBe (WatchFileState.Handled, 0)
    assetOf(growing) should not be empty
  }

  test("A file is read only once it has rested: two scans back to back do not read it, a scan past the rest does") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image, scanned twice with nothing between the two scans, as two presses of "Scan
     * now" would.
     *
     * Assertions:
     *
     * The first scan remembers the file as seen. The second, which finds it with the same size and modified time, reads nothing:
     * the file was first seen less than the rest ago. It leaves what the Watch folder remembers as it was, the moment the file
     * was first seen included, and opens no Import. A scan once the rest has passed, five seconds on, reads the file, which is
     * imported.
     *
     * Edge cases:
     *
     * A third scan, still inside the rest, reads nothing either: the rest is counted from the first sight of the file, not from
     * the scan before.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir)
    def firstSeen: List[AnyRef] = testApp.txManager.asReadOnly {
      query("SELECT last_seen_at FROM watch_folder_file WHERE watch_folder_id = ?", watchFolder.persistedId).map(
        _("last_seen_at"))
    }

    scan(watchFolder) shouldBe ScanResult.Scanned(0)
    val seenAt = firstSeen
    seenAt should have size 1

    scan(watchFolder) shouldBe ScanResult.Scanned(0)
    scan(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    firstSeen shouldBe seenAt
    importsOf(watchFolder) shouldBe empty
    assetOf(file) shouldBe None

    // The rest itself, in real time: the one test that waits for it
    Thread.sleep(Const.WatchFolder.REST_SECONDS * 1000L + 250)
    scan(watchFolder) shouldBe ScanResult.Scanned(1)
    settle(watchFolder)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0))
    assetOf(file) should not be empty
  }

  test("A scan's Import expects the files the scan is about to hand to the pipeline") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with three images, all seen by a first scan. The next scan runs through a pipeline that reads
     * the scan's Import as the scan offers its first file.
     *
     * Assertions:
     *
     * At that moment the Import has one file and expects three, so two more are to come. Once the scan has settled, the Import is
     * closed with three imported files, and still says it expected three.
     */
    val dir = directory()
    for (name <- List("a.png", "b.png", "c.png")) image(dir, name)
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder)
    val repository = testContext.repository
    var atFirstOffer: Option[Import] = None
    val watching = pipelineThat(asAdmin(repository) { atFirstOffer = importsOf(watchFolder).headOption })

    try {
      scan(watchFolder, watching) shouldBe ScanResult.Scanned(3)
      settle(watchFolder)
    } finally watching.shutdown()

    atFirstOffer.map(anImport => (anImport.total, anImport.expectedFiles, anImport.isInProgress)) shouldBe Some(
      (1, Some(3), true))
    importsOf(watchFolder).map(anImport => (anImport.imported, anImport.expectedFiles, anImport.isInProgress)) shouldBe
      List((3, Some(3), false))
  }

  test("A handled file is not read again, and a file that changed is a new file") {

    /**
     * Setup:
     *
     * A Watch folder whose two scans imported its one image.
     *
     * Assertions:
     *
     * Further scans hand nothing to the pipeline and open no Import. Once the file is overwritten with another image, the next
     * scan sees it as a new file and the one after imports it: a second Import, a second asset, and the file handled again.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir)
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    val original = assetOf(file).value

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    importsOf(watchFolder) should have size 1

    Files.write(file, IntegrationTestUtil.generateRandomImagBytesBgr(60))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)

    importsOf(watchFolder) should have size 2
    assetOf(file).value.id should not be original.id
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0))
  }

  test("A scan leaves out the files imports ignore and never follows or reads a symbolic link") {

    /**
     * Setup:
     *
     * A directory outside the Watch folder's holding an image and a subdirectory with an image. The Watch folder, which deletes
     * imported files, is on a directory holding one image, a hidden file, a `Thumbs.db`, a partial download, an empty file, an
     * image in an `@eaDir` directory and one in a hidden directory, a symbolic link to the outside image and a symbolic link to
     * the outside directory.
     *
     * Assertions:
     *
     * After two scans the one image is imported and deleted, and it is the only file the Watch folder remembers and the only file
     * of its Import. Everything else in the directory is as it was, the two links still links, and both outside images are still
     * there and are no assets of the Library.
     */
    val outside = directory("outside")
    val outsideImage = image(outside, "private.png")
    val outsideNested = image(outside, "album/nested.png")
    val dir = directory()
    image(dir, "photo.png")
    image(dir, ".hidden.png")
    image(dir, "Thumbs.db")
    image(dir, "movie.mp4.part")
    Files.createFile(dir.resolve("empty.png"))
    image(dir, "@eaDir/thumb.png")
    image(dir, ".trash/old.png")
    Files.createSymbolicLink(dir.resolve("linked.png"), outsideImage)
    Files.createSymbolicLink(dir.resolve("linked-album"), outside.resolve("album"))
    val watchFolder = watch(dir, deletesImported = true)
    val before = tree(dir)

    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    eventually(Files.exists(dir.resolve("photo.png")) shouldBe false)

    memory(watchFolder).keySet shouldBe Set("photo.png")
    recorded(watchFolder) shouldBe List(("photo.png", ImportOutcome.Imported, None))
    tree(dir) shouldBe before - "photo.png"
    Files.isSymbolicLink(dir.resolve("linked.png")) shouldBe true
    Files.isSymbolicLink(dir.resolve("linked-album")) shouldBe true
    for (file <- List(outsideImage, outsideNested)) {
      Files.exists(file) shouldBe true
      assetOf(file) shouldBe None
    }
  }

  test("A Watch folder with a destination mirrors its directory's tree under it") {

    /**
     * Setup:
     *
     * A Watch folder whose destination is the folder "Archive", on a directory with an image at its top, one in "2026" and one in
     * "2026/Trip". "Archive" holds a folder "2026" already.
     *
     * Assertions:
     *
     * After two scans, the Import's destination is "Archive", and the three assets are sorted, out of Triage: the top one in
     * "Archive" itself, the others in "Archive/2026", the folder that was there, and in "Archive/2026/Trip", which is created.
     */
    val archive = testApp.service.folder.add("Archive")
    val existing = testApp.service.folder.add("2026", archive.id)
    val dir = directory()
    val top = image(dir, "top.png")
    val year = image(dir, "2026/year.png")
    val trip = image(dir, "2026/Trip/trip.png")
    val watchFolder = watch(dir, destination = Some(archive))

    scanTwice(watchFolder) shouldBe ScanResult.Scanned(3)

    importsOf(watchFolder).map(_.destFolderId) shouldBe List(archive.id)
    assetOf(top).map(asset => (asset.isTriaged, placeOf(asset))) shouldBe Some((false, List("Archive")))
    assetOf(year).map(asset => (asset.folderId, placeOf(asset))) shouldBe Some((existing.persistedId, List("Archive", "2026")))
    assetOf(trip).map(placeOf) shouldBe Some(List("Archive", "2026", "Trip"))
    watchFolders.cards().head.destination shouldBe Some("Archive")
  }

  test("A file whose import fails is read by three scans in all and then parked, until an Admin retries the failed files") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image, in a Library whose file store is broken, so every import fails as it is
     * stored. The first scan has seen the file.
     *
     * Assertions:
     *
     * Each of the next three scans reads the file and records it failed, "Storage error", in an Import of its own; the file is to
     * be retried after the first and the second, with one and two attempts, and parked after the third. Two more scans read
     * nothing. The file is still in the directory. With the store repaired, "Retry failed" makes the file one to read again,
     * never tried, and the next scan imports it.
     *
     * Edge cases:
     *
     * The card counts the three failed files of the Imports, and one imported after the retry.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder)
    breakFileStore(testContext.repository)

    try {
      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
      memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Retry, 1))
      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
      memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Retry, 2))
      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
      memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Parked, 3))

      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
      recorded(watchFolder) shouldBe List.fill(3)(("a.png", ImportOutcome.Failed, Some("Storage error")))
      Files.exists(file) shouldBe true
      watchFolders.cards().map(card => (card.imported, card.failed)) shouldBe List((0, 3))
    } finally restoreFileStore(testContext.repository)

    watchFolders.retryFailed(watchFolder.persistedId) shouldBe 1
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)

    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0))
    assetOf(file) should not be empty
    watchFolders.cards().map(card => (card.imported, card.failed)) shouldBe List((1, 3))
  }

  test("An unsupported file is final until it changes or an Admin retries the failed files") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a directory with one text file, which no import supports.
     *
     * Assertions:
     *
     * The second scan reads the file and records it unsupported; it is remembered as final, with no attempt counted, and stays in
     * the directory. Further scans read nothing. "Retry failed" makes it a file to read again, and the next scan records it
     * unsupported once more, final again.
     *
     * Edge cases:
     *
     * Rewritten, the file is a new file: seen by one scan, read by the next.
     */
    val dir = directory()
    val file = textFile(dir, "notes.txt")
    val watchFolder = watch(dir, deletesImported = true)

    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    memory(watchFolder) shouldBe Map("notes.txt" -> (WatchFileState.Final, 0))
    recorded(watchFolder).map(file => (file._1, file._2)) shouldBe List(("notes.txt", ImportOutcome.Unsupported))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    Files.exists(file) shouldBe true

    watchFolders.retryFailed(watchFolder.persistedId) shouldBe 1
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    memory(watchFolder) shouldBe Map("notes.txt" -> (WatchFileState.Final, 0))
    importsOf(watchFolder) should have size 2

    textFile(dir, "notes.txt", "and longer")
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder) shouldBe Map("notes.txt" -> (WatchFileState.Seen, 0))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    Files.exists(file) shouldBe true
  }

  test("A file the import queue refuses is recorded failed, is no attempt of the file's, and is read by a later scan") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with two images, both seen by a first scan, and an import queue of the test's own, shut down.
     *
     * Assertions:
     *
     * A scan through that queue ends at its first file: the file is recorded failed, "Import queue unavailable", in an Import
     * that is closed, the second file is not recorded at all, and both are remembered as seen with no attempt, as before. A scan
     * through the instance's own queue then imports both.
     */
    val dir = directory()
    val first = image(dir, "a.png")
    val second = image(dir, "b.png")
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder)
    val closedQueue = ImportPipelineService(testApp)
    closedQueue.shutdown()

    scan(watchFolder, closedQueue) shouldBe ScanResult.Scanned(0)
    settle(watchFolder)

    recorded(watchFolder) shouldBe List(("a.png", ImportOutcome.Failed, Some("Import queue unavailable")))
    importsOf(watchFolder).map(_.isInProgress) shouldBe List(false)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0), "b.png" -> (WatchFileState.Seen, 0))

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(2)
    assetOf(first) should not be empty
    assetOf(second) should not be empty
  }

  test("One scan of a Watch folder runs at a time") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image, seen by a first scan. The test holds a transaction that has written the Watch
     * folder's row, so a scan cannot finish remembering what it found, and asks for two scans one after the other.
     *
     * Assertions:
     *
     * The second scan does nothing and says so at once, while the first is still running. Once the transaction ends the first
     * scan reads the file, which is imported once: one Import, with one file.
     */
    val dir = directory()
    image(dir, "a.png")
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder)

    val first = testApp.txManager.withTransaction {
      update("UPDATE watch_folder SET name = name WHERE id = ?", watchFolder.persistedId)
      val first = watchFolders.scan(watchFolder.persistedId)
      Await.result(watchFolders.scan(watchFolder.persistedId), 10.seconds) shouldBe ScanResult.NotStarted
      first.isCompleted shouldBe false
      watchFolders.cards().map(_.isScanning) shouldBe List(true)
      first
    }

    Await.result(first, 60.seconds) shouldBe ScanResult.Scanned(1)
    settle(watchFolder)
    recorded(watchFolder) shouldBe List(("a.png", ImportOutcome.Imported, None))
    watchFolders.cards().map(_.isScanning) shouldBe List(false)
  }

  test("A file that settles while the next scan reads the Watch folder's memory is not handed to the pipeline again") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image, seen by a first scan and rested. On PostgreSQL the Watch folder also
     * remembers a hundred thousand other files, so reading its memory takes far longer than settling one file, and the test holds
     * the memory's table locked from the moment a second scan offers the image: the image's import completes and waits at the
     * write of its memory, in the pipeline still, and a third scan, started then, waits at its read of the memory. The lock is
     * released, so the read begins with the image remembered as not read yet and the image settles while the rows are read. On
     * SQLite, whose one write connection never lets a file settle inside a scan's transaction, the scans run without the lock.
     *
     * Assertions:
     *
     * The third scan hands nothing to the pipeline: the image was in the pipeline when the scan looked. It is imported once, in
     * one Import, and remembered as handled.
     */
    val isPostgres = testApp.dataSourceType == Const.DbEngineName.POSTGRES
    val dir = directory()
    image(dir, "only.png")
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)

    val locked = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    def waitingForLocks: Int = testApp.txManager.asReadOnly {
      query("SELECT count(*) AS n FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
        .head("n")
        .toString
        .toInt
    }

    if (isPostgres) testApp.txManager.withTransaction {
      update(
        "INSERT INTO watch_folder_file (id, repository_id, watch_folder_id, rel_path, size_bytes, modified_at, state, last_seen_at) " +
          "SELECT lpad(n::text, 36, '0'), ?, ?, 'elsewhere/' || n, 1, 1, 'handled', now() FROM generate_series(1, 100000) AS n",
        testContext.repository.persistedId,
        watchFolder.persistedId
      )
    }

    // Locks the memory's table as the scan offers its file, which is then in the pipeline and settles only once it is released
    val locking = pipelineThat {
      if (isPostgres) {
        onAnotherThread {
          testApp.txManager.withTransaction {
            update("LOCK TABLE watch_folder_file IN ACCESS EXCLUSIVE MODE")
            locked.countDown()
            release.await(60, TimeUnit.SECONDS): Unit
          }
        }
        locked.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      scan(watchFolder, locking) shouldBe ScanResult.Scanned(1)
      // The image's import waits at its memory's write
      if (isPostgres) eventually(waitingForLocks shouldBe 1)

      val next = watchFolders.scan(watchFolder.persistedId)
      // And the next scan at its read of the memory
      if (isPostgres) eventually(waitingForLocks shouldBe 2)
      release.countDown()

      Await.result(next, 60.seconds) shouldBe ScanResult.Scanned(0)
      settle(watchFolder)
    } finally {
      release.countDown()
      locking.shutdown()
    }

    recorded(watchFolder) shouldBe List(("only.png", ImportOutcome.Imported, None))
    importsOf(watchFolder) should have size 1
    memory(watchFolder)("only.png") shouldBe (WatchFileState.Handled, 0)
  }

  test("A Watch folder whose directory is unavailable says why, keeps what it remembers, and scans again once it is back") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with two images, both imported by two scans. The directory is then moved away.
     *
     * Assertions:
     *
     * The next scan walks nothing: it is blocked, the path does not exist, which is the Watch folder's stored status reason, and
     * its card says Unavailable with that reason. What it remembers is as it was. With a file put where the directory was, the
     * reason becomes that the path is not a directory. Once the directory is moved back, a scan finds it available: no reason is
     * stored, the card says Watching, and nothing is read again.
     *
     * Edge cases:
     *
     * A Watch folder that deletes imported files is unavailable while the server cannot write to its directory.
     */
    val dir = directory()
    image(dir, "a.png")
    image(dir, "b.png")
    val watchFolder = watch(dir)
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(2)
    val remembered = memory(watchFolder)
    val away = tempRoot.resolve(s"away-${java.util.UUID.randomUUID}")

    def card = watchFolders.cards().head

    Files.move(dir, away)
    scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.PATH_MISSING)
    watchFolders.getById(watchFolder.persistedId).statusReason shouldBe Some(Const.WatchFolder.PATH_MISSING)
    (card.status, card.reason) shouldBe (WatchFolderStatus.Unavailable, Some(Const.WatchFolder.PATH_MISSING))
    memory(watchFolder) shouldBe remembered

    Files.createFile(dir)
    scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.PATH_NOT_DIRECTORY)
    (card.status, card.reason) shouldBe (WatchFolderStatus.Unavailable, Some(Const.WatchFolder.PATH_NOT_DIRECTORY))
    memory(watchFolder) shouldBe remembered

    Files.delete(dir)
    Files.move(away, dir)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    watchFolders.getById(watchFolder.persistedId).statusReason shouldBe None
    (card.status, card.reason) shouldBe (WatchFolderStatus.Watching, None)
    memory(watchFolder) shouldBe remembered
    importsOf(watchFolder) should have size 1

    watchFolders.update(watchFolder.persistedId, WatchFolderSpec("Phone sync", dir.toString, deletesImported = true)): Unit
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
      assume(!Files.isWritable(dir))
      scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.PATH_UNWRITABLE)
      (card.status, card.reason) shouldBe (WatchFolderStatus.Unavailable, Some(Const.WatchFolder.PATH_UNWRITABLE))
    } finally Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
  }

  test("A directory of a Watch folder that cannot be read is named on its card, and the rest of the tree is imported") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with an image at its top, one in "locked" and one in "open/deep". The server is then denied
     * "locked": it can neither list it nor enter it.
     *
     * Assertions:
     *
     * Two scans import the top image and the one in "open/deep": the walk goes on past "locked". The Watch folder's card says
     * Unavailable with a reason that names "locked" by its path and says the rest is scanned, which is its stored status reason.
     * Nothing is remembered of the image in "locked". Once the server may read "locked" again, a scan clears the reason, the card
     * says Watching, and two scans import its image.
     *
     * Edge cases:
     *
     * The deletion notice's file count, asked while "locked" is denied, counts the two files that can be read.
     */
    val dir = directory()
    image(dir, "top.png")
    val hidden = image(dir, "locked/hidden.png")
    image(dir, "open/deep/deep.png")
    val locked = hidden.getParent
    val watchFolder = watch(dir)
    val reason = Const.WatchFolder.DIRECTORY_UNREADABLE.format(locked)

    def card = watchFolders.cards().head

    try {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
      assume(!Files.isReadable(locked))

      scanTwice(watchFolder) shouldBe ScanResult.Scanned(2)
      recorded(watchFolder).toSet shouldBe
        Set(("top.png", ImportOutcome.Imported, None), ("open/deep/deep.png", ImportOutcome.Imported, None))
      watchFolders.getById(watchFolder.persistedId).statusReason shouldBe Some(reason)
      (card.status, card.reason) shouldBe (WatchFolderStatus.Unavailable, Some(reason))
      memory(watchFolder).keySet shouldBe Set("top.png", "open/deep/deep.png")
      watchFolders.fileCount(dir.toString, watchFolder.id) shouldBe Some(2)
    } finally Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    watchFolders.getById(watchFolder.persistedId).statusReason shouldBe None
    (card.status, card.reason) shouldBe (WatchFolderStatus.Watching, None)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    assetOf(hidden) shouldBe defined
  }

  test("A Watch folder whose destination folder is in the Trash is paused, its files untouched, until the folder is back") {

    /**
     * Setup:
     *
     * A Watch folder whose destination is the folder "Scans", on a directory with one image that a first scan has seen. "Scans"
     * is then deleted, which recycles it.
     *
     * Assertions:
     *
     * A scan reads nothing: it is blocked, the destination folder is in the Trash, and "Scan now" is refused saying so. The card
     * says Paused with that reason, while the Watch folder itself is not stored as paused. No Import is opened, no file is
     * failed, the file is remembered as seen, as before, and is still in the directory. Once the folder is restored, a scan
     * imports the file into it, and the card says Watching.
     *
     * Edge cases:
     *
     * A lost destination that is replaced resumes the Watch folder too: with "Scans" in the Trash again and the Watch folder
     * edited to the folder "Other", a second image is imported into "Other".
     */
    val scans = testApp.service.folder.add("Scans")
    val other = testApp.service.folder.add("Other")
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir, destination = Some(scans))
    scanAndSettle(watchFolder)

    def card = watchFolders.cards().head
    def recycleScans(): Unit = testApp.service.library.deleteFolderById(scans.persistedId)

    recycleScans()
    scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.DESTINATION_LOST)
    intercept[IllegalOperationException](watchFolders.scanNow(watchFolder.persistedId)).getMessage shouldBe E.DESTINATION_IN_TRASH
    (card.status, card.reason) shouldBe (WatchFolderStatus.Paused, Some(Const.WatchFolder.DESTINATION_LOST))
    watchFolders.getById(watchFolder.persistedId).isPaused shouldBe false
    importsOf(watchFolder) shouldBe empty
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    Files.exists(file) shouldBe true

    testApp.service.folder.setRecycledProp(testApp.service.folder.getById(scans.persistedId), isRecycled = false)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    assetOf(file).map(placeOf) shouldBe Some(List("Scans"))
    (card.status, card.reason) shouldBe (WatchFolderStatus.Watching, None)

    recycleScans()
    val second = image(dir, "b.png")
    scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.DESTINATION_LOST)
    watchFolders.update(watchFolder.persistedId, WatchFolderSpec("Phone sync", dir.toString, other.id)): Unit
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    assetOf(second).map(placeOf) shouldBe Some(List("Other"))
  }

  test("A destination folder recycled during a scan ends the scan, and its file in the pipeline is no attempt of the file's") {

    /**
     * Setup:
     *
     * A Watch folder whose destination is the folder "Scans", on a directory with two images, both seen by a first scan. The next
     * scan runs through a pipeline that deletes "Scans" as the scan offers its first file.
     *
     * Assertions:
     *
     * The first file is recorded failed, the destination folder being in the Trash, and the second is not recorded: the scan
     * ended before it. Both are remembered as seen, with no attempt, and both are still in the directory. Neither is an asset.
     */
    val scans = testApp.service.folder.add("Scans")
    val dir = directory()
    val first = image(dir, "a.png")
    val second = image(dir, "b.png")
    val watchFolder = watch(dir, destination = Some(scans))
    scanAndSettle(watchFolder)
    val repository = testContext.repository
    val recycling = pipelineThat(asAdmin(repository)(testApp.service.library.deleteFolderById(scans.persistedId)))

    try {
      scan(watchFolder, recycling) shouldBe ScanResult.Scanned(1)
      settle(watchFolder)
    } finally recycling.shutdown()

    recorded(watchFolder) shouldBe List(("a.png", ImportOutcome.Failed, Some(E.DESTINATION_IN_TRASH)))
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0), "b.png" -> (WatchFileState.Seen, 0))
    for (file <- List(first, second)) {
      Files.exists(file) shouldBe true
      assetOf(file) shouldBe None
    }
  }

  test("Pausing a Watch folder stops its scans until it is resumed") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image, seen by a first scan, then paused.
     *
     * Assertions:
     *
     * It is stored as paused and its card says Paused, with no reason. A scan reads nothing, "Scan now" is refused as paused, and
     * a scan of every Watch folder leaves it out: the file stays seen and no Import is opened. Resumed, it is watching again, and
     * a scan of every Watch folder imports the file.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir)
    scanAndSettle(watchFolder)

    watchFolders.pause(watchFolder.persistedId)

    watchFolders.getById(watchFolder.persistedId).isPaused shouldBe true
    watchFolders.cards().map(card => (card.status, card.reason)) shouldBe List((WatchFolderStatus.Paused, None))
    scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(WatchFolderStatus.Paused.label)
    intercept[IllegalOperationException](watchFolders.scanNow(watchFolder.persistedId)).getMessage shouldBe E.WATCH_FOLDER_PAUSED
    watchFolders.scanAll()
    settle(watchFolder)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    importsOf(watchFolder) shouldBe empty

    watchFolders.resume(watchFolder.persistedId)

    watchFolders.cards().map(card => (card.status, card.reason)) shouldBe List((WatchFolderStatus.Watching, None))
    watchFolders.scanAll()
    eventually(assetOf(file) should not be empty)
    settle(watchFolder)
    watchFolders.getById(watchFolder.persistedId).accountId shouldBe admin.persistedId
  }

  test("A scan of every Watch folder reads the Watch folders of every Library") {

    /**
     * Setup:
     *
     * Two Libraries, each with a Watch folder on a directory of its own holding one image.
     *
     * Assertions:
     *
     * Two scans of every Watch folder, asked for from the first Library, import each image into the Library of its Watch folder,
     * and into no other.
     */
    val firstLibrary = testContext.repository
    val firstDir = directory("first")
    val firstFile = image(firstDir, "a.png")
    val firstWatch = watch(firstDir, name = "First")
    val secondLibrary = addLibrary()
    switchContextRepo(secondLibrary)
    val secondDir = directory("second")
    val secondFile = image(secondDir, "b.png")
    val secondWatch = watch(secondDir, name = "Second")
    switchContextRepo(firstLibrary)

    def settleBoth(): Unit = {
      settle(firstWatch)
      settle(secondWatch)
      testContext.letWatchedFilesRest()
    }

    watchFolders.scanAll()
    settleBoth()
    watchFolders.scanAll()
    eventually {
      assetOf(firstFile) should not be empty
      asAdmin(secondLibrary)(assetOf(secondFile)) should not be empty
    }
    settleBoth()

    assetOf(secondFile) shouldBe None
    asAdmin(secondLibrary)(assetOf(firstFile)) shouldBe None
  }

  test("Scanning starts with a scan of every Watch folder, and each round schedules the next by the Setting's minutes") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with one image. The scans are started, as a server's start does, with a schedule of the
     * test's own in place of the actor system's: it keeps each round it is handed with the delay it was asked for, and runs none
     * of them, so the test runs a round when it chooses to.
     *
     * Assertions:
     *
     * Starting scans at once, with nobody asking: the file is remembered as seen and the Watch folder has a last scan. One round
     * is scheduled, 10 minutes on, the Setting's default, and nothing more while it waits. Once the file has rested, running that
     * round reads the file, which becomes an asset of the Library, and schedules the round after it, 10 minutes on again.
     *
     * Edge cases:
     *
     * The Setting is read as each round is scheduled: saved as 90 while a round waits, the round keeps the delay it was scheduled
     * with, and the one it schedules when it runs is 90 minutes on.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir)
    val rounds = scala.collection.mutable.ListBuffer[(FiniteDuration, () => Unit)]()
    def schedule(delay: FiniteDuration, round: () => Unit): Cancellable = {
      rounds += ((delay, round))
      Cancellable.alreadyCancelled
    }

    watchFolders.start(schedule)
    settle(watchFolder)

    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    watchFolders.getById(watchFolder.persistedId).lastScanAt should not be empty
    assetOf(file) shouldBe None
    rounds.map(_._1).toList shouldBe List(10.minutes)

    testContext.letWatchedFilesRest()
    rounds.head._2()
    eventually(assetOf(file) should not be empty)
    settle(watchFolder)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0))
    rounds.map(_._1).toList shouldBe List(10.minutes, 10.minutes)

    withSetting(Settings.WATCH_FOLDER_SCAN_INTERVAL, "90") {
      rounds(1)._2()
      settle(watchFolder)
    }
    rounds.map(_._1).toList shouldBe List(10.minutes, 10.minutes, 90.minutes)
  }

  test("A deactivated or demoted Admin's Watch folders pause, and a deleted Admin's pass to the Admin who deletes it") {

    /**
     * Setup:
     *
     * Three Admins besides the one who acts, each owning a Watch folder in the first Library, and the first of them another in a
     * second Library. The acting Admin deactivates the first, changes the second to a User, and deletes the third.
     *
     * Assertions:
     *
     * Both Watch folders of the deactivated Admin are paused, saying its owner was deactivated, and the demoted Admin's is
     * paused, saying its owner is no longer an Admin; each still has its owner. The deleted Admin's is owned by the acting Admin
     * and is not paused. A scan of a paused one reads nothing.
     *
     * Edge cases:
     *
     * Resumed by the acting Admin, a Watch folder whose owner cannot own it is the acting Admin's from there on, and its files
     * are imported as that Admin's. A reactivated owner's Watch folder stays paused until it is resumed, and stays its owner's.
     */
    val users = testApp.service.user
    val firstLibrary = testContext.repository
    val deactivated = persistAdmin()
    val demoted = persistAdmin()
    val deleted = persistAdmin()

    def watchAs(owner: User, name: String): (WatchFolder, Path) = {
      switchContextUser(owner)
      val dir = directory()
      val added = watch(dir, name = name)
      switchContextUser(admin)
      (added, dir)
    }

    val (ofDeactivated, deactivatedDir) = watchAs(deactivated, "Deactivated")
    val (ofDemoted, _) = watchAs(demoted, "Demoted")
    val (ofDeleted, _) = watchAs(deleted, "Deleted")
    val secondLibrary = addLibrary()
    switchContextRepo(secondLibrary)
    val (elsewhere, _) = watchAs(deactivated, "Elsewhere")
    switchContextRepo(firstLibrary)
    val file = image(deactivatedDir, "a.png")
    scanAndSettle(ofDeactivated)

    users.deactivate(admin.persistedId, deactivated.persistedId)
    users.update(
      admin.persistedId,
      users.getById(demoted.persistedId).copy(accountType = AccountType.User),
      Map(firstLibrary.persistedId -> Permissions.all),
      users.openForEdit(demoted.persistedId).state
    )
    users.delete(admin.persistedId, deleted.persistedId)

    def stored(watchFolder: WatchFolder): (Boolean, Option[String], String) = {
      val now = watchFolders.getById(watchFolder.persistedId)
      (now.isPaused, now.statusReason, now.accountId)
    }

    stored(ofDeactivated) shouldBe (true, Some(Const.WatchFolder.OWNER_DEACTIVATED), deactivated.persistedId)
    asAdmin(secondLibrary)(stored(elsewhere)) shouldBe (true, Some(Const.WatchFolder.OWNER_DEACTIVATED), deactivated.persistedId)
    stored(ofDemoted) shouldBe (true, Some(Const.WatchFolder.OWNER_DEMOTED), demoted.persistedId)
    stored(ofDeleted) shouldBe (false, None, admin.persistedId)
    scanAndSettle(ofDeactivated) shouldBe ScanResult.Blocked(Const.WatchFolder.OWNER_DEACTIVATED)
    memory(ofDeactivated) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))

    watchFolders.resume(ofDeactivated.persistedId)
    stored(ofDeactivated) shouldBe (false, None, admin.persistedId)
    scanAndSettle(ofDeactivated) shouldBe ScanResult.Scanned(1)
    assetOf(file).map(_.ownerUserId) shouldBe Some(admin.persistedId)

    users.reactivate(admin.persistedId, deactivated.persistedId)
    asAdmin(secondLibrary) {
      stored(elsewhere) shouldBe (true, Some(Const.WatchFolder.OWNER_DEACTIVATED), deactivated.persistedId)
      watchFolders.resume(elsewhere.persistedId)
      stored(elsewhere) shouldBe (false, None, deactivated.persistedId)
    }
  }

  test("An edit makes the editor the owner, a new path forgets the old one's files, and a removal leaves files and Imports") {

    /**
     * Setup:
     *
     * A Watch folder added by one Admin on a directory with one image, imported by two scans, and a second directory with an
     * image of its own. Another Admin edits the Watch folder: a new name, the second directory, the folder "Scans" as its
     * destination.
     *
     * Assertions:
     *
     * The Watch folder is stored as edited and owned by the editor, and remembers nothing of the first directory's file. Two
     * scans import the second directory's image into "Scans", owned by the editor. Removed, the Watch folder is gone from the
     * cards and is not found, and what it remembered is gone with it; both directories still hold their files, and both Imports
     * of its scans are still in the history, pointing at no Watch folder.
     *
     * Edge cases:
     *
     * A Watch folder of another Library is not found, to edit, pause, resume, retry, scan or remove, and stays as it is.
     */
    val scans = testApp.service.folder.add("Scans")
    val firstDir = directory("first")
    val firstFile = image(firstDir, "a.png")
    val secondDir = directory("second")
    val secondFile = image(secondDir, "b.png")
    val watchFolder = watch(firstDir)
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    val editor = persistAdmin()

    switchContextUser(editor)
    watchFolders.update(watchFolder.persistedId, WatchFolderSpec("Renamed", secondDir.toString, scans.id)): Unit
    switchContextUser(admin)

    val edited = watchFolders.getById(watchFolder.persistedId)
    (edited.name, edited.path, edited.destFolderId, edited.accountId) shouldBe
      ("Renamed", secondDir.toString, scans.id, editor.persistedId)
    memory(watchFolder) shouldBe empty
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(1)
    assetOf(secondFile).map(asset => (placeOf(asset), asset.ownerUserId)) shouldBe Some((List("Scans"), editor.persistedId))
    val scanImports = importsOf(watchFolder).map(_.persistedId)
    scanImports should have size 2

    watchFolders.remove(watchFolder.persistedId)

    watchFolders.cards() shouldBe empty
    intercept[NotFoundException](watchFolders.getById(watchFolder.persistedId))
    testApp.txManager.asReadOnly(query("SELECT id FROM watch_folder_file")) shouldBe empty
    Files.exists(firstFile) shouldBe true
    Files.exists(secondFile) shouldBe true
    scanImports.map(imports.getById).map(_.watchFolderId) shouldBe List(None, None)
    imports.list(rpp = 10).total shouldBe 2

    val theirs = watch(firstDir, name = "Theirs")
    switchContextRepo(addLibrary())
    val id = theirs.persistedId
    intercept[NotFoundException](watchFolders.update(id, WatchFolderSpec("Mine", secondDir.toString)))
    intercept[NotFoundException](watchFolders.pause(id))
    intercept[NotFoundException](watchFolders.resume(id))
    intercept[NotFoundException](watchFolders.retryFailed(id))
    intercept[NotFoundException](watchFolders.scanNow(id))
    intercept[NotFoundException](watchFolders.remove(id))
    intercept[NotFoundException](imports.open(ImportSource.WatchFolder, watchFolderId = Some(id)))
    Await.result(watchFolders.scan(id), 60.seconds) shouldBe a[ScanResult.Blocked]
    switchContextRepo(testContext.repository)
    val kept = watchFolders.getById(id)
    (kept.name, kept.path, kept.isPaused) shouldBe ("Theirs", firstDir.toString, false)
  }

  test("A Watch folder that deletes imported files deletes imported and duplicate files and the directories they leave empty") {

    /**
     * Setup:
     *
     * A Library that holds an asset already. A Watch folder that deletes imported files, on a directory holding: an image at its
     * top; an image in "2026/Trip", alone there; a copy of the asset the Library holds in "copies", alone there; an image beside
     * a `.DS_Store` in "kept"; a text file, which no import supports, in "docs"; and an empty directory, "empty".
     *
     * Assertions:
     *
     * After two scans the three new images are assets and the copy is recorded as already in the Library; all four are deleted
     * from the directory, and so are "2026/Trip", "2026" and "copies", which they left empty. What stays: the watched directory
     * itself, "kept" with its `.DS_Store`, "docs" with the text file, which is recorded unsupported and remembered as final, and
     * "empty", which no deleted file was in. The four deleted files are remembered as handled.
     *
     * Edge cases:
     *
     * A Watch folder whose only file is imported and deleted keeps its directory, empty.
     */
    val existing = directory("existing")
    val known = image(existing, "known.png")
    val library = watch(existing, name = "Library")
    scanTwice(library) shouldBe ScanResult.Scanned(1)

    val dir = directory()
    val top = image(dir, "top.png")
    val trip = image(dir, "2026/Trip/trip.png")
    val copy = Files.copy(known, Files.createDirectories(dir.resolve("copies")).resolve("copy.png"))
    val beside = image(dir, "kept/beside.png")
    Files.write(dir.resolve("kept/.DS_Store"), "finder".getBytes)
    textFile(dir, "docs/notes.txt")
    Files.createDirectory(dir.resolve("empty"))
    val watchFolder = watch(dir, deletesImported = true)
    val checksums = List(top, trip, beside).map(Sha256.hex)

    scanTwice(watchFolder) shouldBe ScanResult.Scanned(5)

    recorded(watchFolder).map(file => (file._1, file._2)).toSet shouldBe Set(
      ("top.png", ImportOutcome.Imported),
      ("2026/Trip/trip.png", ImportOutcome.Imported),
      ("kept/beside.png", ImportOutcome.Imported),
      ("copies/copy.png", ImportOutcome.Duplicate),
      ("docs/notes.txt", ImportOutcome.Unsupported)
    )
    checksums.map(testApp.service.asset.getByChecksum(_).isDefined) shouldBe List(true, true, true)
    tree(dir) shouldBe Set("kept/", "kept/.DS_Store", "docs/", "docs/notes.txt", "empty/")
    Files.isDirectory(dir) shouldBe true
    Files.exists(copy) shouldBe false
    memory(watchFolder) shouldBe Map(
      "top.png" -> (WatchFileState.Handled, 0),
      "2026/Trip/trip.png" -> (WatchFileState.Handled, 0),
      "kept/beside.png" -> (WatchFileState.Handled, 0),
      "copies/copy.png" -> (WatchFileState.Handled, 0),
      "docs/notes.txt" -> (WatchFileState.Final, 0)
    )
    // The Watch folder that only reads kept its file
    Files.exists(known) shouldBe true

    val single = directory("single")
    image(single, "only.png")
    val emptied = watch(single, name = "Single", deletesImported = true)
    scanTwice(emptied) shouldBe ScanResult.Scanned(1)
    Files.isDirectory(single) shouldBe true
    tree(single) shouldBe empty
  }

  test("A Watch folder that deletes imported files never touches a file whose import failed") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a directory with one image in a subdirectory, in a Library whose file store
     * is broken, so every import fails as it is stored.
     *
     * Assertions:
     *
     * Four scans read the file three times and park it; it is recorded failed each time, and it is still in its subdirectory.
     */
    val dir = directory()
    val file = image(dir, "2026/a.png")
    val watchFolder = watch(dir, deletesImported = true)
    breakFileStore(testContext.repository)

    try for (_ <- 1 to 4) scanAndSettle(watchFolder)
    finally restoreFileStore(testContext.repository)

    memory(watchFolder) shouldBe Map("2026/a.png" -> (WatchFileState.Parked, 3))
    recorded(watchFolder).map(_._2) shouldBe List.fill(3)(ImportOutcome.Failed)
    tree(dir) shouldBe Set("2026/", "2026/a.png")
    Files.exists(file) shouldBe true
  }

  test("A file that changed after its copy was staged is not deleted") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a directory with one image, seen by a first scan. The next scan runs through
     * a pipeline that overwrites the file with another image as the scan offers it, its copy staged by then.
     *
     * Assertions:
     *
     * The image as it was staged is imported, and the file is still in the directory, with its new content, which is no asset.
     * The next scan sees it as a new file, and the one after imports it and deletes it.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val staged = Sha256.hex(file)
    val watchFolder = watch(dir, deletesImported = true)
    scanAndSettle(watchFolder)
    val overwriting = pipelineThat(Files.write(file, IntegrationTestUtil.generateRandomImagBytesBgr(60)): Unit)

    try {
      scan(watchFolder, overwriting) shouldBe ScanResult.Scanned(1)
      settle(watchFolder)
    } finally overwriting.shutdown()

    testApp.service.asset.getByChecksum(staged) should not be empty
    Files.exists(file) shouldBe true
    val changed = Sha256.hex(file)
    changed should not be staged
    testApp.service.asset.getByChecksum(changed) shouldBe None

    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)
    testApp.service.asset.getByChecksum(changed) should not be empty
    Files.exists(file) shouldBe false
  }

  test("Nothing is deleted through a symbolic link put in the place of a file or of its directory") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a directory with an image at its top and one in "album", both seen by a
     * first scan. The next scan runs through a pipeline that, as the scan offers its first file: moves "album" out of the watched
     * directory and puts a symbolic link to it in its place, so the image is reached through the link, as it was; and moves the
     * top image out and puts a symbolic link to it in its place.
     *
     * Assertions:
     *
     * Whatever was staged before the swap is imported, and nothing is deleted: both images are where they were moved to, outside
     * the watched directory, and both links are still in it.
     */
    val outside = directory("outside")
    val dir = directory()
    val nested = image(dir, "album/nested.png")
    val top = image(dir, "top.png")
    val watchFolder = watch(dir, deletesImported = true)
    scanAndSettle(watchFolder)
    val movedAlbum = outside.resolve("album")
    val movedTop = outside.resolve("top.png")
    val swapping = pipelineThat {
      Files.move(dir.resolve("album"), movedAlbum)
      Files.createSymbolicLink(dir.resolve("album"), movedAlbum)
      Files.move(top, movedTop)
      Files.createSymbolicLink(top, movedTop): Unit
    }

    try {
      scan(watchFolder, swapping)
      settle(watchFolder)
    } finally swapping.shutdown()

    // The first file, in path order, was staged before the swap
    recorded(watchFolder).map(file => (file._1, file._2)) should contain(("album/nested.png", ImportOutcome.Imported))
    Files.exists(movedAlbum.resolve("nested.png")) shouldBe true
    Files.exists(movedTop) shouldBe true
    Files.isSymbolicLink(dir.resolve("album")) shouldBe true
    Files.isSymbolicLink(top) shouldBe true
    Files.exists(nested) shouldBe true
  }

  test(
    "A Watch folder whose directory has come to hold the app's store is unavailable: nothing of the store is read or deleted") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a temporary directory "volume" with an image in "photos". The store of the
     * test's Library is then moved into "volume": its directory in the data directory becomes a symbolic link to "volume/store".
     * An asset is imported into the Library, by another Watch folder, so "volume/store" holds its file and its Preview, both
     * files a scan would take: the file a duplicate of the asset, the Preview a new image.
     *
     * Assertions:
     *
     * Two scans are both blocked, the path overlapping the app's data directory, which the card says beside Unavailable. No
     * Import is opened, nothing is remembered, and the asset's stored file and its Preview are where they were, as is the image
     * in "photos".
     *
     * Edge cases:
     *
     * The deletion notice counts no files for the directory.
     */
    val volume = directory("volume")
    val photo = image(volume, "photos/new.png")
    val store = Files.createDirectory(volume.resolve("store"))
    val watchFolder = watch(volume, deletesImported = true)

    withStoreAt(store) {
      val stored = testApp.service.fileStore.assetFile(importedAsset().persistedId).toRealPath()
      stored.startsWith(store) shouldBe true
      val before = tree(volume)
      before.count(_.endsWith(".webp")) shouldBe 1

      scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.PATH_OVERLAPS_DATA)
      scanAndSettle(watchFolder) shouldBe ScanResult.Blocked(Const.WatchFolder.PATH_OVERLAPS_DATA)

      val card = watchFolders.cards().find(_.watchFolder.id == watchFolder.id).get
      (card.status, card.reason) shouldBe (WatchFolderStatus.Unavailable, Some(Const.WatchFolder.PATH_OVERLAPS_DATA))
      importsOf(watchFolder) shouldBe empty
      memory(watchFolder) shouldBe empty
      tree(volume) shouldBe before
      Files.exists(stored) shouldBe true
      Files.exists(photo) shouldBe true
      watchFolders.fileCount(volume.toString, watchFolder.id) shouldBe None
    }
  }

  test("A file that is the stored file of the asset it duplicates is not deleted, nor remembered as handled") {

    /**
     * Setup:
     *
     * A Library holding an asset, imported by another Watch folder. A Watch folder that deletes imported files, on a temporary
     * directory on the store's file system, beside the test's data directory, holding two files with the asset's content: a hard
     * link to the asset's stored file, which is that file under a second path, and a copy of it.
     *
     * Assertions:
     *
     * After two scans both are recorded as already in the Library. The copy is deleted and remembered as handled. The link is
     * still in the directory, and the asset's stored file in the store; the link is remembered as final, so no later scan reads
     * it again.
     */
    val stored = testApp.service.fileStore.assetFile(importedAsset().persistedId).toRealPath()
    // Beside the directory that holds the data directory: on its file system, which a hard link cannot leave, and outside it
    val dir = Files.createTempDirectory(Path.of(testApp.dataPath).toRealPath().getParent.getParent, "watched-links")

    try {
      val link = Files.createLink(dir.resolve("link.png"), stored)
      val copy = Files.copy(stored, dir.resolve("copy.png"))
      val watchFolder = watch(dir, deletesImported = true)

      scanTwice(watchFolder) shouldBe ScanResult.Scanned(2)

      recorded(watchFolder).map(file => (file._1, file._2)).toSet shouldBe
        Set(("copy.png", ImportOutcome.Duplicate), ("link.png", ImportOutcome.Duplicate))
      Files.exists(copy) shouldBe false
      Files.exists(link) shouldBe true
      Files.exists(stored) shouldBe true
      memory(watchFolder) shouldBe Map("copy.png" -> (WatchFileState.Handled, 0), "link.png" -> (WatchFileState.Final, 0))

      scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(0)
    } finally FileUtils.deleteDirectory(dir.toFile)
  }

  test("A paused Watch folder deletes nothing, and reads the file again once it is resumed") {

    /**
     * Setup:
     *
     * A Watch folder that deletes imported files, on a directory with one image, seen by a first scan. The next scan runs through
     * a pipeline that pauses the Watch folder as the scan offers the file.
     *
     * Assertions:
     *
     * The file is imported, is still in the directory, and is remembered as seen, not as handled. Once the Watch folder is
     * resumed, a scan reads the file again, records it as already in the Library, and deletes it.
     */
    val dir = directory()
    val file = image(dir, "a.png")
    val watchFolder = watch(dir, deletesImported = true)
    scanAndSettle(watchFolder)
    val repository = testContext.repository
    val pausing = pipelineThat(asAdmin(repository)(watchFolders.pause(watchFolder.persistedId)))

    try {
      scan(watchFolder, pausing) shouldBe ScanResult.Scanned(1)
      settle(watchFolder)
    } finally pausing.shutdown()

    assetOf(file) should not be empty
    Files.exists(file) shouldBe true
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0))

    watchFolders.resume(watchFolder.persistedId)
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(1)

    recorded(watchFolder).map(_._2) shouldBe List(ImportOutcome.Imported, ImportOutcome.Duplicate)
    Files.exists(file) shouldBe false
    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Handled, 0))
  }

  test("Switching the deletion of imported files on reads the imported files again and deletes them as duplicates") {

    /**
     * Setup:
     *
     * A Watch folder that does not delete imported files, on a directory with two images, both imported by two scans and still in
     * the directory. The asset of the second is then recycled. The Watch folder is edited to delete imported files.
     *
     * Assertions:
     *
     * The edit makes both files ones to read again. The next scan reads both: the first is recorded as already in the Library and
     * deleted; the second, whose only copy is in the Trash, is imported again and deleted. The directory is empty.
     *
     * Edge cases:
     *
     * Switching the deletion off again reads nothing again.
     */
    val dir = directory()
    val kept = image(dir, "a.png")
    val recycled = image(dir, "b.png")
    val watchFolder = watch(dir)
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(2)
    tree(dir) shouldBe Set("a.png", "b.png")
    testApp.service.library.recycleAssets(Set(assetOf(recycled).value.persistedId))

    watchFolders.update(watchFolder.persistedId, WatchFolderSpec("Phone sync", dir.toString, deletesImported = true)): Unit

    memory(watchFolder) shouldBe Map("a.png" -> (WatchFileState.Seen, 0), "b.png" -> (WatchFileState.Seen, 0))
    scanAndSettle(watchFolder) shouldBe ScanResult.Scanned(2)
    imports.files(importsOf(watchFolder).last.persistedId).map(file => (file.relPath, file.outcome)) shouldBe
      List(("a.png", ImportOutcome.Duplicate), ("b.png", ImportOutcome.Imported))
    tree(dir) shouldBe empty
    Files.exists(kept) shouldBe false

    watchFolders.update(watchFolder.persistedId, WatchFolderSpec("Phone sync", dir.toString)): Unit
    memory(watchFolder).values.toSet shouldBe Set((WatchFileState.Handled, 0))
  }

  test("The prune forgets the files absent from a Watch folder's directory for the retention period") {

    /**
     * Setup:
     *
     * A Watch folder on a directory with two images, both imported by two scans; the second is then removed from the directory.
     * Both files' memory is stamped as last found 100 days ago, and a scan follows. A second Watch folder, never scanned since,
     * has a file remembered from 100 days ago too, and its last scan was then.
     *
     * Assertions:
     *
     * The scan stamps the file it finds as found now. The prune, with the Import history kept 90 days, forgets the absent file
     * and keeps the one in the directory; the Watch folder that was not scanned since forgets nothing.
     *
     * Edge cases:
     *
     * With the Import history kept forever, an absent file is forgotten after 30 days: one last found 40 days ago goes, one last
     * found 20 days ago stays.
     */
    val dir = directory()
    image(dir, "present.png")
    val absent = image(dir, "absent.png")
    val watchFolder = watch(dir)
    scanTwice(watchFolder) shouldBe ScanResult.Scanned(2)
    val idleDir = directory("idle")
    image(idleDir, "idle.png")
    val idle = watch(idleDir, name = "Idle")
    scanAndSettle(idle)

    def daysAgo(days: Int): Any =
      getSqlDateTime(java.sql.Timestamp.from(java.time.Instant.now.minus(java.time.Duration.ofDays(days))))
    def lastSeen(relPath: String, days: Int): Unit = testApp.txManager.withTransaction {
      update("UPDATE watch_folder_file SET last_seen_at = ? WHERE rel_path = ?", daysAgo(days), relPath)
    }

    Files.delete(absent)
    lastSeen("present.png", 100)
    lastSeen("absent.png", 100)
    lastSeen("idle.png", 100)
    testApp.txManager.withTransaction {
      update("UPDATE watch_folder SET last_scan_at = ? WHERE id = ?", daysAgo(100), idle.persistedId)
    }
    // A scan alone, with no rest let pass after it: the moments the files were last found are the test's own
    scan(watchFolder)
    settle(watchFolder)

    imports.prune()

    memory(watchFolder).keySet shouldBe Set("present.png")
    memory(idle).keySet shouldBe Set("idle.png")

    val older = image(dir, "older.png")
    val newer = image(dir, "newer.png")
    scanAndSettle(watchFolder)
    Files.delete(older)
    Files.delete(newer)
    lastSeen("older.png", 40)
    lastSeen("newer.png", 20)
    scan(watchFolder)
    settle(watchFolder)

    withSetting(Settings.IMPORT_HISTORY_DAYS, "0")(imports.prune())

    memory(watchFolder).keySet shouldBe Set("present.png", "newer.png")
  }
}
