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
 * Watch folders: the path rules, the scan and what it remembers, retries, statuses, owner rules and the deletion of imported
 * files. Every directory a test watches is a fresh one under the suite's own temporary directory.
 */
@DoNotDiscover class WatchFolderServiceTests(override val testApp: Altitude) extends IntegrationTestCore {

  private def watchFolders = testApp.service.watchFolder
  private def imports = testApp.service.imports
  private val F = FieldConst.WatchFolder
  private val E = Const.Msg.Err

  private var tempRoot: Path = scala.compiletime.uninitialized

  // The Admin every test acts as
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

  // A bundle's database keeps every test's Libraries, so no Watch folder outlives its test: a scan of every Watch folder then
  // reads the test's own alone
  override def afterEach(): Unit =
    try testApp.txManager.withTransaction(update("DELETE FROM watch_folder"))
    finally super.afterEach()

  private def eventually[T](f: => T): T =
    Eventually.eventually(Eventually.timeout(Span(60, Seconds)), Eventually.interval(Span(100, Millis)))(f)

  private def persistAdmin(): User = testContext.persistUser(Some(testContext.makeAdminUser()))

  /** A fresh, empty directory under the suite's temporary directory */
  private def directory(prefix: String = "watched"): Path = Files.createTempDirectory(tempRoot, prefix).toRealPath()

  /** A random image written at the path under the directory; `size` is its side in pixels */
  private def image(dir: Path, relPath: String, size: Int = 40): Path = {
    val path = dir.resolve(relPath)
    Files.createDirectories(path.getParent)
    Files.write(path, IntegrationTestUtil.generateRandomImagBytesBgr(size))
  }

  /** A file no import supports, written at the path under the directory, its content unique and ending in `text` */
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
    eventually(testContext.isWatchFolderWorking(watchFolder.persistedId) shouldBe false)

  /** One scan, settled, then the rest of the files it has not read yet let pass, so the next scan may read them */
  private def scanAndSettle(watchFolder: WatchFolder): ScanResult = {
    val result = scan(watchFolder)
    settle(watchFolder)
    testContext.letWatchedFilesRest()
    result
  }

  /** Two settled scans a rest apart, as it takes to read a file found for the first time */
  private def scanTwice(watchFolder: WatchFolder): ScanResult = {
    scanAndSettle(watchFolder)
    scanAndSettle(watchFolder)
  }

  /** What the Watch folder remembers: each file's state and attempts, by its path */
  private def memory(watchFolder: WatchFolder): Map[String, (WatchFileState, Int)] =
    testContext.watchFolderMemory(watchFolder.persistedId).map(file => file.relPath -> (file.state, file.attempts)).toMap

  /** The Imports of the Watch folder's scans in the history, the oldest first */
  private def importsOf(watchFolder: WatchFolder): List[Import] =
    testContext.imports.filter(_.watchFolderId == watchFolder.id).sortBy(_.createdAt)

  /** The files of the Watch folder's Imports, the oldest Import's first: path, outcome and reason */
  private def recorded(watchFolder: WatchFolder): List[(String, ImportOutcome, Option[String])] =
    importsOf(watchFolder).flatMap(anImport => testContext.importFiles(anImport.persistedId)).map(recordOf)

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

  /** An import pipeline that runs `before` as the scan offers its first file, then offers to the instance's own queue */
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

  /** An asset imported by a Watch folder of its own, so its stored file has the content its checksum names */
  private def importedAsset(): Asset = {
    val dir = directory("library")
    val file = image(dir, "known.png")
    scanTwice(watch(dir, name = "Library")) shouldBe ScanResult.Scanned(1)
    assetOf(file).get
  }

  /** Runs `work` with the test Library's store at `target`, as on another volume: a symbolic link in the data directory */
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
     * A Watch folder on the first directory is stored with its name trimmed, no destination, deletion off, not paused, no status
     * reason or scan yet, and the Admin as owner. One on the second with a destination and deletion on is stored so. The cards
     * list both by name, watching, with nothing imported or failed.
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
     * Each is refused with its reason on the path's field and adds nothing: no path, a relative or missing path, a file, an
     * unreadable directory and, with deletion on, an unwritable one. A blank name is refused on the name's field, and a
     * destination in the Trash on the destination's.
     *
     * Edge cases:
     *
     * An unwritable directory is taken with deletion off. A name and a path refused together are both named. A destination that
     * is no folder of the Library is not found.
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
      // Root reads and writes whatever the permissions say, so it cannot hit these two rules
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
     * In either Library, a Watch folder on the same directory, its subdirectory or its parent is refused on the path's field,
     * naming "Phone sync" (and, from the second Library, the first Library). The data directory, a directory inside it and its
     * parent are refused as overlapping it. Nothing is added.
     *
     * Edge cases:
     *
     * A path reaching any of these through a symbolic link is refused the same way. A sibling of the watched directory is taken.
     * An edit of "Phone sync" is not refused for its own path.
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
     * A directory "volume" holding "store" and "photos", the test Library's store kept in "store" through a symbolic link in the
     * data directory, and an asset imported so the store holds its file.
     *
     * Assertions:
     *
     * A Watch folder on "store", on "volume" or on the directory of the asset's file is refused as overlapping the data
     * directory, though none is under it. Nothing is added.
     *
     * Edge cases:
     *
     * "photos", beside the store, is taken.
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
     * A directory with a subdirectory. One transaction adds a Watch folder on the directory and stays open while another adds one
     * on the subdirectory.
     *
     * Assertions:
     *
     * The second waits for the first and is then refused as inside it, so the Library has the first alone.
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
     * The first scan hands nothing to the pipeline, remembers both files as seen and opens no Import. The second imports both in
     * one closed Import named after the Watch folder, with the owner as its account and no destination. Both assets are in Triage
     * and owned by the owner, both files are remembered as handled and left in the directory, and the card counts two imported
     * and has a last scan.
     *
     * Edge cases:
     *
     * A file that grew since the last scan is seen again rather than read, and read by the scan after, which finds it unchanged.
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
     * A Watch folder on a directory with one image, scanned back to back, as two manual scans would.
     *
     * Assertions:
     *
     * The first scan remembers the file as seen. The second finds it unchanged but reads nothing, since it was first seen less
     * than the rest ago, and leaves the memory (first-seen time included) as it was and opens no Import. A scan once the rest has
     * passed imports the file.
     *
     * Edge cases:
     *
     * A third scan inside the rest reads nothing either: the rest counts from the first sight of the file, not the last scan.
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
     * the scan's Import at its first offer.
     *
     * Assertions:
     *
     * At that moment the Import has one file and expects three. Once settled, it is closed with three imported and still expects
     * three.
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
     * Further scans read nothing and open no Import. Once the file is overwritten with another image, the next scan sees it as
     * new and the one after imports it in a second Import, as a second asset.
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
     * A Watch folder that deletes imported files, on a directory holding one image, files imports ignore (hidden, `Thumbs.db`, a
     * partial download, an empty file, images in `@eaDir` and in a hidden directory), and symbolic links to an image and to a
     * directory outside it.
     *
     * Assertions:
     *
     * After two scans the one image is imported and deleted, and it is the only file remembered or recorded. Everything else in
     * the directory is as it was, links included, and the outside images are still there and are no assets.
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
     * After two scans the Import's destination is "Archive" and the three assets are out of Triage: the top one in "Archive", the
     * others in the existing "Archive/2026" and in "Archive/2026/Trip", which is created.
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
     * Each of the next three scans records the file failed in an Import of its own; it is to be retried after the first two, and
     * parked after the third. Two more scans read nothing, and the file stays in the directory. With the store repaired, retrying
     * the failed files resets it to seen with no attempts, and the next scan imports it.
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
     * The second scan records the file unsupported; it is remembered as final with no attempt counted and stays in the directory.
     * Further scans read nothing. Retrying the failed files has the next scan record it unsupported again, final.
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
     * A scan through that queue ends at its first file, which is recorded failed in a closed Import; the second is not recorded,
     * and both stay seen with no attempt. A scan through the instance's own queue then imports both.
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
     * A Watch folder on a directory with one seen image. The test holds a transaction that has written the Watch folder's row, so
     * a scan cannot finish remembering what it found, and asks for two scans in a row.
     *
     * Assertions:
     *
     * The second scan reports at once that it did not start, while the first still runs. Once the transaction ends the first scan
     * imports the file once.
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
     * A Watch folder with one image, seen and rested. On PostgreSQL it also remembers 100,000 other files, so reading its memory
     * takes far longer than settling one file, and the memory's table is locked as a second scan offers the image: the image's
     * import waits at its memory write, and a third scan at its memory read. Once released, the third scan's read starts with the
     * image unread, and the image settles meanwhile. On SQLite, whose one write connection never lets a file settle inside a
     * scan's transaction, the scans run without the lock.
     *
     * Assertions:
     *
     * The third scan hands nothing to the pipeline, since the image was in it when the scan looked. It is imported once and
     * remembered as handled.
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

    // Locks the memory's table as the scan offers its file, which then settles only once the lock is released
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
     * The next scan is blocked because the path does not exist, which is stored as the status reason and shown on the Unavailable
     * card; the memory is kept. With a file where the directory was, the reason is that it is not a directory. Once the directory
     * is back, a scan clears the reason, the card says Watching, and nothing is read again.
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
     * A Watch folder on a directory with an image at its top, one in "locked" and one in "open/deep", "locked" then made
     * unreadable to the server.
     *
     * Assertions:
     *
     * Two scans import the top image and the one in "open/deep", walking past "locked". The stored status reason, shown on the
     * Unavailable card, names "locked" and says the rest is scanned, and nothing in "locked" is remembered. Once "locked" is
     * readable again, a scan clears the reason and two scans import its image.
     *
     * Edge cases:
     *
     * The deletion notice's file count, asked meanwhile, counts the two readable files.
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
     * A Watch folder with the destination "Scans", on a directory with one seen image. "Scans" is then recycled.
     *
     * Assertions:
     *
     * A scan is blocked with the destination in the Trash, and a manual scan is refused saying so. The card says Paused with that
     * reason, though the Watch folder is not stored as paused. No Import is opened and the file stays seen and in the directory.
     * Once the folder is restored, a scan imports the file into it, and the card says Watching.
     *
     * Edge cases:
     *
     * Replacing a lost destination resumes the Watch folder too: with "Scans" recycled again and the Watch folder edited to
     * "Other", a second image is imported into "Other".
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
     * A Watch folder with the destination "Scans", on a directory with two seen images. The next scan runs through a pipeline
     * that recycles "Scans" at the scan's first offer.
     *
     * Assertions:
     *
     * The first file is recorded failed for the destination in the Trash, and the second is not recorded, the scan having ended.
     * Both stay seen with no attempt and in the directory, and neither is an asset.
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
     * It is stored as paused and its card says Paused, with no reason. A scan reads nothing, a manual scan is refused, and a scan
     * of every Watch folder leaves it out: the file stays seen and no Import is opened. Resumed, it is watching again, and a scan
     * of every Watch folder imports the file.
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
     * Two scans of every Watch folder, asked for from the first Library, import each image into its Watch folder's Library alone.
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
     * A Watch folder on a directory with one image. The scans are started as a server's start does, with a schedule of the test's
     * own that records each round and its delay and runs none, so the test runs a round when it chooses.
     *
     * Assertions:
     *
     * Starting scans at once: the file is remembered as seen and the Watch folder has a last scan. One round is scheduled, 10
     * minutes on (the Setting's default), and nothing more. Once the file has rested, running that round imports the file and
     * schedules the next, 10 minutes on again.
     *
     * Edge cases:
     *
     * The Setting is read as each round is scheduled: saved as 90 while a round waits, that round keeps its delay and the one it
     * schedules is 90 minutes on.
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
     * Three Admins besides the acting one, each owning a Watch folder in the first Library, the first also one in a second
     * Library. The acting Admin deactivates the first, demotes the second to a User, and deletes the third.
     *
     * Assertions:
     *
     * The deactivated and the demoted Admins' Watch folders are paused, each with its reason, and keep their owners. The deleted
     * Admin's passes to the acting Admin, unpaused. A scan of a paused one reads nothing.
     *
     * Edge cases:
     *
     * A Watch folder whose owner cannot own it, once resumed, is the resuming Admin's, and its files are imported as theirs. A
     * reactivated owner's Watch folder stays paused until resumed, and stays its owner's.
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
     * A Watch folder whose image was imported, and a second directory with an image. Another Admin edits the Watch folder to a
     * new name, the second directory and the destination "Scans".
     *
     * Assertions:
     *
     * The Watch folder is stored as edited, owned by the editor, and remembers nothing of the first directory. Two scans import
     * the second image into "Scans", owned by the editor. Removed, the Watch folder and its memory are gone, both directories
     * keep their files, and both Imports stay in the history, pointing at no Watch folder.
     *
     * Edge cases:
     *
     * Every action on a Watch folder of another Library is not found (a scan is blocked), and it stays as it is.
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
    testContext.imports.size shouldBe 2

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
     * A Library holding an asset, and a Watch folder that deletes imported files, on a directory holding an image at its top, one
     * alone in "2026/Trip", a copy of the existing asset alone in "copies", an image beside a `.DS_Store` in "kept", a text file
     * in "docs", and an empty directory "empty".
     *
     * Assertions:
     *
     * After two scans the three new images are assets and the copy is a duplicate; all four are deleted, as are the directories
     * they left empty, and remembered as handled. What stays: the watched directory, "kept" with its `.DS_Store`, "docs" with the
     * text file (unsupported, final), and "empty", which no deleted file was in.
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
    // The Watch folder that does not delete kept its file
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
     * A Watch folder that deletes imported files, on a directory with one image in a subdirectory, in a Library whose broken file
     * store fails every import.
     *
     * Assertions:
     *
     * Four scans read the file three times, recording it failed each time, and park it; it is still in its subdirectory.
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
     * A Watch folder that deletes imported files, on a directory with one seen image. The next scan runs through a pipeline that
     * overwrites the file as the scan offers it, its copy staged by then.
     *
     * Assertions:
     *
     * The staged image is imported, and the file stays with its new content, which is no asset. The next scan sees it as new, and
     * the one after imports and deletes it.
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
     * A Watch folder that deletes imported files, on a directory with a seen image at its top and one in "album". At the next
     * scan's first offer, a pipeline moves "album" and the top image out of the directory, leaving a symbolic link to each in its
     * place.
     *
     * Assertions:
     *
     * Whatever was staged before the swap is imported, and nothing is deleted: both images are where they were moved to, and both
     * links are still in place.
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
     * A Watch folder that deletes imported files, on a directory "volume" with an image in "photos". The test Library's store is
     * then moved to "volume/store" through a symbolic link in the data directory, and an asset imported, so the store holds its
     * file and its Preview, both files a scan would take.
     *
     * Assertions:
     *
     * Two scans are blocked as overlapping the data directory, the card's reason beside Unavailable. No Import is opened, nothing
     * is remembered, and the stored file, its Preview and the image in "photos" are untouched.
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
     * A Library holding an asset, and a Watch folder that deletes imported files, on a directory on the store's file system
     * holding a hard link to the asset's stored file and a copy of it.
     *
     * Assertions:
     *
     * After two scans both are duplicates. The copy is deleted and remembered as handled. The link and the stored file stay, and
     * the link is remembered as final, so no later scan reads it again.
     */
    val stored = testApp.service.fileStore.assetFile(importedAsset().persistedId).toRealPath()
    // Beside the data directory's parent: on its file system, which a hard link cannot leave, yet outside it
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
     * A Watch folder that deletes imported files, on a directory with one seen image. The next scan runs through a pipeline that
     * pauses the Watch folder as the scan offers the file.
     *
     * Assertions:
     *
     * The file is imported but stays in the directory, remembered as seen, not handled. Once resumed, a scan reads it again,
     * records it as a duplicate, and deletes it.
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
     * A Watch folder that does not delete, whose two images were imported and stay in the directory. The second's asset is then
     * recycled, and the Watch folder edited to delete imported files.
     *
     * Assertions:
     *
     * The edit makes both files ones to read again. The next scan records the first a duplicate and deletes it, and imports the
     * second, whose only copy is in the Trash, again and deletes it. The directory is empty.
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
    testContext.importFiles(importsOf(watchFolder).last.persistedId).map(file => (file.relPath, file.outcome)) shouldBe
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
     * A Watch folder whose two images were imported, the second then removed from the directory. Both files are stamped as last
     * found 100 days ago, and a scan follows. A second Watch folder, last scanned 100 days ago, remembers a file from then too.
     *
     * Assertions:
     *
     * The prune, with the Import history kept 90 days, forgets the absent file and keeps the one the scan found again; the Watch
     * folder not scanned since forgets nothing.
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
    // No rest let pass after the scan, so the last-found times stay the test's own
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
