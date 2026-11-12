package altitude.core.service

import java.io.IOException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.apache.pekko.actor.Cancellable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Using
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.FieldConst
import altitude.core.IllegalOperationException
import altitude.core.ImageException
import altitude.core.ImportDestinationException
import altitude.core.ImporterUnavailableException
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.UnsupportedMediaTypeException
import altitude.core.ValidationException
import altitude.core.Validators
import altitude.core.VideoException
import altitude.core.dao.WatchFolderDao
import altitude.core.dao.WatchFolderFileDao
import altitude.core.models.AccountType
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Import
import altitude.core.models.ImportOrigin
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.NamedPath
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolder
import altitude.core.models.WatchFolderCard
import altitude.core.models.WatchFolderFile
import altitude.core.models.WatchFolderSpec
import altitude.core.models.WatchFolderStatus
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import altitude.core.pipeline.PipelineUtils
import altitude.core.transactions.TransactionManager
import altitude.core.util.IgnoredFiles
import altitude.core.util.RelPath
import altitude.core.util.Util

object WatchFolderService:
  /** How a scan of a Watch folder ended */
  enum ScanResult:
    /** Its directory was walked, and this many of its files were handed to the import pipeline */
    case Scanned(offered: Int)

    /** A scan of it was running already, or the server is stopping: nothing was done */
    case NotStarted

    /** It is not scanned as it stands, for the reason: paused, its destination lost, its directory unavailable, or removed */
    case Blocked(reason: String)

  /**
   * Why a path cannot be a Watch folder's, or cannot be scanned: `error` as the Watch folder dialog says it under the path's
   * field, `reason` as the card says it beside the chip of a Watch folder that became unavailable
   */
  case class PathProblem(error: String, reason: String)

  // A file of a Watch folder's directory as a walk finds it: its path under the directory, and where it is
  private case class Found(relPath: String, path: Path, sizeBytes: Long, modifiedAt: FileTime)

  // What a walk of a Watch folder's directory came to: its files in path order, the directories it could not read, which it
  // went on past, and the directory of the app's own that it met, if it met one, at which it ended
  private case class Walked(files: List[Found], unreadable: List[Path], own: Option[Path]):
    // What the Watch folder's card says of the directories that could not be read, if there are any
    def problem: Option[String] =
      unreadable match
        case Nil => None
        case List(directory) => Some(Const.WatchFolder.DIRECTORY_UNREADABLE.format(directory))
        case first :: _ => Some(Const.WatchFolder.DIRECTORIES_UNREADABLE.format(unreadable.size, first))

  /**
   * The directories the app stores into (`paths`, each where it really is, symbolic links resolved), which no Watch folder may
   * read or delete from. A directory is told to be one of them by where it is and by what it is: its file key, the file system's
   * identity of it (`Files.isSameFile` where the file system gives no key), which a second path to it shares that no symbolic
   * link explains, such as a volume mounted twice.
   */
  private class OwnDirectories(val paths: List[Path]):
    private val keys: Set[AnyRef] = paths.flatMap(path => attributesOf(path).flatMap(read => Option(read.fileKey))).toSet

    private def attributesOf(path: Path): Option[BasicFileAttributes] =
      try Some(Files.readAttributes(path, classOf[BasicFileAttributes]))
      catch case _: IOException => None

    /** Whether the directory, read with these attributes, is one of them */
    def has(directory: Path, attributes: BasicFileAttributes): Boolean =
      Option(attributes.fileKey) match
        case Some(key) => keys.contains(key)
        case None =>
          paths.exists {
            path =>
              try Files.isSameFile(directory, path)
              catch case _: IOException => false
          }

    /** The first of them that is the path or a directory above it, if any */
    def at(path: Path): Option[Path] =
      Iterator.iterate(path)(_.getParent).takeWhile(_ != null).find(step => attributesOf(step).exists(has(step, _)))

    /**
     * Whether the directory at the real path is one of them, lies inside one or holds one: by the paths, and by what the
     * directory and each directory above it is, and what each directory above one of them is
     */
    def overlaps(real: Path): Boolean =
      paths.exists(path => real.startsWith(path) || path.startsWith(real)) || at(real).isDefined || {
        val candidate = OwnDirectories(List(real))
        paths.exists(candidate.at(_).isDefined)
      }

  // A file of a Watch folder that is in the import pipeline, as it was when its copy was staged
  private case class InFlight(watchFolderId: String, relPath: String, source: Path, sizeBytes: Long, modifiedAt: FileTime)

  // One scan past its walk: the Watch folder as the scan read it, its owner, its directory's real path, the files to hand to
  // the pipeline, in path order, and the Import the scan opened, once it has
  private class Run(
      val repository: Repository,
      val watchFolder: WatchFolder,
      val owner: User,
      val root: Path,
      val files: List[Found],
      val pipeline: ImportPipelineService):
    val opened: AtomicReference[Option[Import]] = AtomicReference[Option[Import]](None)

  // What handing one file to the pipeline came to
  private enum Taken:
    // Staged and added to the scan's Import: to offer to the queue
    case Staged(element: TDataAssetWithContext, importFileId: String)
    // Left for a later scan, or recorded as failed: the scan goes on
    case Skipped
    // The Watch folder is not scanned any more as it was: the scan ends
    case Stop

  // What a file's memory does with what became of the file in the pipeline
  private enum Settled:
    case Handled, Final, Failed, Untouched

  // A scan stamps a remembered file as found again only once its stamp is this old, so a scan that finds nothing new writes
  // nothing; what the forgetting of absent files allows for
  private val LAST_SEEN_STEP = java.time.Duration.ofDays(1)

  // How long a file rests before it is read: a scan reads a file only when a scan saw it with the same size and modified time
  // at least this long before, whoever asked for the two scans. Far shorter than the scan interval, so two scheduled scans in
  // a row always span it, while two presses of "Scan now" do not shorten it
  private val REST = java.time.Duration.ofSeconds(Const.WatchFolder.REST_SECONDS)

  private val STOPPING = "the server is stopping"
  private val REMOVED = "the Watch folder was removed"

/**
 * The Watch folders: directories on the server that the app scans, each for one Library, importing the files that arrive in them.
 * An Admin adds, edits, pauses, resumes and removes one; its path is held to the path rules, and its files are only ever copied
 * out of its directory, and deleted from it when it deletes imported files.
 *
 * A scan walks the directory, never following a symbolic link nor reading one, leaving out what imports ignore (`IgnoredFiles`)
 * and empty files, and remembers each file by its path under the directory, its size and its modified time (`watch_folder_file`).
 * A file is read once a scan finds it as a scan found it at least the rest before (5 seconds, so as the scheduled scan before
 * did, unless the two were asked for in quick succession): a copy is staged and offered to the import queue, under the queue's
 * backpressure, in path order, as a file of the scan's Import, which is opened with the scan's first file, expecting the files
 * the scan is about to offer, and closed once its last file settles. What becomes of a file in the pipeline is written to its
 * memory by the pipeline's last working stage ([[settled]]), which is also where the file is deleted.
 */
class WatchFolderService(val app: Altitude):
  import WatchFolderService.*

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: WatchFolderDao = app.DAO.watchFolder
  private val fileDao: WatchFolderFileDao = app.DAO.watchFolderFile
  private val txManager: TransactionManager = app.txManager
  private val F = FieldConst.WatchFolder

  // A scan's own work runs on the import dispatcher, as the pipeline's stages do; nothing waits there for the queue
  private given ExecutionContext = app.importDispatcher

  // The Watch folders a scan of which is running: one scan per Watch folder at a time
  private val scanning = ConcurrentHashMap.newKeySet[String]()

  // The files in the import pipeline, by their Import file: a scan does not offer one again, and the pipeline's end finds what
  // it settles here. In memory, as the pipeline is: nothing is in flight when the server starts.
  private val inFlight = ConcurrentHashMap[String, InFlight]()

  // Why each Watch folder was last not scanned, so the log says it once per change
  private val notScanned = ConcurrentHashMap[String, String]()

  private val listeners = CopyOnWriteArrayList[String => Unit]()

  // The scheduled scan to come, once scanning has started
  private val nextScan = AtomicReference[Option[Cancellable]](None)
  private val isStopping = AtomicBoolean(false)

  /**
   * Adds a Watch folder to the context repository, owned by the context account. Its name is required; its path must be absolute
   * and an existing directory the server can read, and write to when imported files are deleted, that overlaps neither one of the
   * app's own directories ([[ownDirectories]]) nor another Watch folder of any Library (the same directory, or one inside the
   * other), each compared by real path, symbolic links resolved; its destination, if it has one, must be a live folder of the
   * repository. A refusal is a `ValidationException` naming every field refused; a destination that is no folder of the
   * repository is not found.
   */
  def add(spec: WatchFolderSpec): WatchFolder =
    txManager.withTransaction {
      dao.lockPaths()
      val valid = validated(spec, self = None)
      val added = dao.add(
        WatchFolder(
          name = valid.name,
          path = valid.path,
          destFolderId = valid.destFolderId,
          deletesImported = valid.deletesImported,
          accountId = RequestContext.getAccount.persistedId
        ))
      logger.info(
        s"Added Watch folder [${added.persistedId}] \"${added.name}\" at ${added.path}${deletion(added)}. " +
          s"Repo: ${RequestContext.getRepository.persistedId}")
      changed()
      added
    }

  private def deletion(watchFolder: WatchFolder): String =
    if watchFolder.deletesImported then ", deleting imported files" else ""

  /**
   * An Admin's edit of a Watch folder, held to the rules of [[add]]; the context account owns it from here on. A changed path is
   * another directory, so what was remembered of the old one's files is forgotten. Switching the deletion of imported files on
   * makes the files it had imported files to read again: each is then found to be in the Library, or imported once more if its
   * asset is gone, and deleted, so nothing is deleted on the word of the memory alone. What the last scan found wrong with the
   * directory is cleared, for the next scan to find again; a Watch folder paused for its owner stays paused, with no reason.
   */
  def update(id: String, spec: WatchFolderSpec): WatchFolder =
    txManager.withTransaction {
      dao.lockPaths()
      val stored = dao.getById(id)
      val valid = validated(spec, Some(stored))
      val edited = stored.copy(
        name = valid.name,
        path = valid.path,
        destFolderId = valid.destFolderId,
        deletesImported = valid.deletesImported,
        accountId = RequestContext.getAccount.persistedId
      )
      BaseService.requireFound(dao.tableName, id, dao.update(edited)): Unit
      if stored.isPaused then dao.setPaused(id, isPaused = true, reason = None): Unit
      else dao.setStatusReason(id, None): Unit

      if edited.path != stored.path then
        val forgotten = fileDao.forgetAll(id)
        logger.info(s"Watch folder [$id] moved from ${stored.path} to ${edited.path}: forgot $forgotten files of the old path")
      else if edited.deletesImported && !stored.deletesImported then
        val reread = fileDao.reread(id, Set(WatchFileState.Handled))
        logger.info(s"Watch folder [$id] deletes imported files from now on: $reread files it had imported are read again")

      logger.info(s"Edited Watch folder [$id] \"${edited.name}\" at ${edited.path}${deletion(edited)}")
      changed()
      edited.copy(statusReason = None)
    }

  /**
   * Removes a Watch folder with what it remembers. The files of its directory stay where they are, and its Imports stay in the
   * Import history. A file of it still in the import pipeline is imported, and not deleted.
   */
  def remove(id: String): Unit =
    txManager.withTransaction {
      val stored = dao.getById(id)
      BaseService.requireFound(dao.tableName, id, dao.deleteById(id)): Unit
      notScanned.remove(id): Unit
      logger.info(s"Removed Watch folder [$id] \"${stored.name}\" at ${stored.path}; its files and its Imports stay")
      changed()
    }

  /** Pauses a Watch folder: no scan of it starts, and one that is running hands no more files to the pipeline */
  def pause(id: String): Unit =
    txManager.withTransaction {
      BaseService.requireFound(dao.tableName, id, dao.setPaused(id, isPaused = true, reason = None)): Unit
      logger.info(s"Paused Watch folder [$id]")
      changed()
    }

  /**
   * Resumes a paused Watch folder; the next scan reads it. One whose owner is no active Admin any more, which is what paused it,
   * is owned by the context account from here on, since its assets need an owner.
   */
  def resume(id: String): Unit =
    txManager.withTransaction {
      val stored = dao.getById(id)
      if !owns(app.DAO.user.getById(stored.accountId)) then
        val owner = RequestContext.getAccount
        dao.setOwner(id, owner.persistedId): Unit
        logger.info(s"Watch folder [$id] is owned by account [${owner.persistedId}] from here on, who resumed it")
      dao.setPaused(id, isPaused = false, reason = None): Unit
      logger.info(s"Resumed Watch folder [$id]")
      changed()
    }

  /**
   * Makes the files of a Watch folder that failed files to read again, never tried: those of an unsupported type or that could
   * not be decoded, those to be retried and those parked. How many.
   */
  def retryFailed(id: String): Int =
    txManager.withTransaction {
      dao.getById(id): Unit
      val retried = fileDao.reread(id, Set(WatchFileState.Final, WatchFileState.Retry, WatchFileState.Parked))
      logger.info(s"Watch folder [$id]: $retried failed files are read again")
      retried
    }

  /** Whether a scan of the Watch folder is running, or a file one handed to the import pipeline is in it still */
  def isWorking(id: String): Boolean = scanning.contains(id) || inFlight.values.asScala.exists(_.watchFolderId == id)

  def getById(id: String): WatchFolder =
    txManager.asReadOnly {
      dao.getById(id)
    }

  /** What the Watch folder remembers of the files of its directory */
  def memory(id: String): List[WatchFolderFile] =
    txManager.asReadOnly {
      fileDao.ofWatchFolder(id)
    }

  /** The context repository's Watch folders as the Import page's cards show them, by name */
  def cards(): List[WatchFolderCard] =
    txManager.asReadOnly {
      val counts = app.DAO.imports.countsByWatchFolder()
      dao.all().map {
        watchFolder =>
          val destination = watchFolder.destFolderId.map(app.service.folder.getById)
          val (status, reason) = statusOf(watchFolder, destination)
          val (imported, failed) = counts.getOrElse(watchFolder.persistedId, (0, 0))
          WatchFolderCard(
            watchFolder,
            status,
            reason,
            destination = destination.map(folder => app.service.imports.pathOf(folder.persistedId, NamedPath.SEPARATOR)),
            lastScanAt = watchFolder.lastScanAt.map(dao.instantOf),
            isScanning = scanning.contains(watchFolder.persistedId),
            imported = imported,
            failed = failed
          )
      }
    }

  /**
   * What a Watch folder is doing, with the reason beside it: paused, by an Admin or for its owner; paused while its destination
   * folder is in the Trash, which nothing stores, so it resumes as the folder is restored or replaced; unavailable, as its last
   * scan found its directory, or directories of it that it could not read while it scanned the rest; or watching.
   */
  private def statusOf(watchFolder: WatchFolder, destination: Option[Folder]): (WatchFolderStatus, Option[String]) =
    if watchFolder.isPaused then (WatchFolderStatus.Paused, watchFolder.statusReason)
    else if destination.exists(_.isRecycled) then (WatchFolderStatus.Paused, Some(Const.WatchFolder.DESTINATION_LOST))
    else
      watchFolder.statusReason.fold((WatchFolderStatus.Watching, None))(reason => (WatchFolderStatus.Unavailable, Some(reason)))

  /** The context repository's Watch folders whose destination is the folder or a folder below it, by name */
  def targeting(folderId: String): List[WatchFolder] =
    txManager.asReadOnly {
      dao.targeting(app.service.folder.getChildrenRecursive(folderId).map(_.persistedId).toSet + folderId)
    }

  /**
   * How many files a scan would find in the directory at the path now, for the Watch folder dialog to name before imported files
   * are deleted; none for a path that cannot be a Watch folder's as it stands (`self` being the Watch folder edited)
   */
  def fileCount(path: String, self: Option[String] = None): Option[Int] =
    val others = txManager.asReadOnly(dao.everywhere().filterNot(placed => self.contains(placed.watchFolder.persistedId)))
    val own = ownDirectories()
    parsed(path).toOption
      .filter(pathProblem(_, deletesImported = false, others, own).isEmpty)
      .map(path => walk(path.toRealPath(), own))
      .filter(_.own.isEmpty)
      .map(_.files.size)

  /** Pauses every Watch folder the account owns, in every Library, in the transaction that deactivates or demotes it */
  def pauseOwnedBy(accountId: String, reason: String): Unit =
    txManager.withTransaction {
      val paused = dao.pauseOwnedBy(accountId, reason)
      if paused > 0 then logger.info(s"Paused $paused Watch folders of account [$accountId]: $reason")
    }

  /** Gives every Watch folder of a deleted account, in every Library, to the Admin who deletes it, in that transaction */
  def passOn(fromAccountId: String, toAccountId: String): Unit =
    txManager.withTransaction {
      val passed = dao.reassignOwner(fromAccountId, toAccountId)
      if passed > 0 then logger.info(s"$passed Watch folders of account [$fromAccountId] pass to account [$toAccountId]")
    }

  /**
   * Forgets, in every repository, the files a Watch folder remembers that have been absent from its directory for `days`: last
   * found that long ago, and not by the Watch folder's last scan. A Watch folder that is not scanned forgets nothing.
   */
  def forgetAbsent(days: Int): Unit =
    val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days)
    app.service.library.forEachRepository {
      repository =>
        val forgotten = txManager.withTransaction {
          dao
            .all()
            .map {
              watchFolder =>
                watchFolder.lastScanAt.fold(0) {
                  lastScanAt =>
                    // A file the last scan found is stamped within a step of it
                    val absentAtLastScan = dao.instantOf(lastScanAt).atOffset(ZoneOffset.UTC).minus(LAST_SEEN_STEP)
                    val before = if absentAtLastScan.isBefore(cutoff) then absentAtLastScan else cutoff
                    fileDao.forgetLastSeenBefore(watchFolder.persistedId, before)
                }
            }
            .sum
        }
        if forgotten > 0 then logger.info(s"Forgot $forgotten Watch folder files absent for $days days. Repo: ${repository.name}")
    }

  /**
   * Tells `listener` of every change to what a Library's Watch folders show from now on, with the repository's ID: one added,
   * edited, paused, resumed or removed, and a scan of one started or ended
   */
  def onChange(listener: String => Unit): Unit = listeners.add(listener): Unit

  // Tells the listeners once the transaction that changes a Watch folder has committed
  private def changed(): Unit =
    val repositoryId = RequestContext.getRepository.persistedId
    txManager.afterCommit(tell(repositoryId))

  private def tell(repositoryId: String): Unit = listeners.forEach(_(repositoryId))

  // -- The path rules --------------------------------------------------------------------------------------------------------

  // What an Admin entered, as it is stored, or a `ValidationException` with the error of every field refused
  private def validated(spec: WatchFolderSpec, self: Option[WatchFolder]): WatchFolderSpec =
    val refused = ValidationException()

    val name = spec.name.trim
    if name.isEmpty then refused.errors += F.NAME -> Const.Msg.Err.VALUE_REQUIRED
    else
      try Validators.validName(name, F.NAME, Api.Constraints.MAX_WATCH_FOLDER_NAME_LENGTH): Unit
      catch case tooLong: ValidationException => refused.errors ++= tooLong.errors

    val path = parsed(spec.path) match
      case Left(error) =>
        refused.errors += F.PATH -> error
        None
      case Right(path) =>
        val others = dao.everywhere().filterNot(placed => self.exists(_.id == placed.watchFolder.id))
        pathProblem(path, spec.deletesImported, others, ownDirectories()).foreach {
          problem => refused.errors += F.PATH -> problem.error
        }
        Some(path)

    // Not found, for a folder that is not the repository's
    spec.destFolderId.map(app.service.folder.getById).filter(_.isRecycled).foreach {
      _ => refused.errors += F.DEST_FOLDER_ID -> Const.Msg.Err.DESTINATION_IN_TRASH
    }

    refused.trigger()
    spec.copy(name = name, path = path.get.toString)

  // The path as typed: absolute, with no "." or ".." left in it
  private def parsed(text: String): Either[String, Path] =
    if text.trim.isEmpty then Left(Const.Msg.Err.VALUE_REQUIRED)
    else
      try
        val path = Path.of(text.trim)
        if path.isAbsolute then Right(path.normalize()) else Left(Const.Msg.Err.WATCH_PATH_RELATIVE)
      catch case _: InvalidPathException => Left(Const.Msg.Err.WATCH_PATH_INVALID)

  // A path with its symbolic links resolved, or as it is written where it does not exist
  private def realOrWritten(path: Path): Path =
    try path.toRealPath()
    catch case _: IOException => path.toAbsolutePath.normalize()

  /**
   * The directories the app stores into, each where it really is: the data directory, staging, the SQLite database's directory,
   * and the file store, down to each Library's directory and the directories in it (its files, Previews and faces). Any of them
   * may be a symbolic link that leads out of the data directory, a store kept on another volume, so each is resolved on its own
   * rather than taken to lie under the data directory's real path. One that is not there is left out, but for the data directory,
   * which is then taken as it is written.
   */
  private def ownDirectories(): OwnDirectories =
    val data = Path.of(app.dataPath)
    val store = data.resolve(Const.DataStore.REPOSITORIES)
    val libraries = directoriesIn(store)
    val parts = app.service.staging.dir :: store :: libraries ::: libraries.flatMap(directoriesIn) ::: app.sqliteDir.toList

    OwnDirectories((realOrWritten(data) :: parts.filter(Files.isDirectory(_)).map(realOrWritten)).distinct)

  // The directories in a directory, a symbolic link to one included; none for a directory that cannot be listed
  private def directoriesIn(directory: Path): List[Path] =
    try Using.resource(Files.newDirectoryStream(directory))(_.asScala.filter(Files.isDirectory(_)).toList)
    catch case _: IOException => Nil

  /**
   * What keeps the directory at the path from being a Watch folder's, if anything: it is missing, no directory, not readable, not
   * writable when imported files are deleted, or it overlaps one of the app's own directories (`own`) or one of `others`, the
   * other Watch folders of the instance: the same directory, or one inside the other, by real path, and for the app's own
   * directories by what the directories are as well (`OwnDirectories.overlaps`)
   */
  private def pathProblem(
      path: Path,
      deletesImported: Boolean,
      others: List[WatchFolderDao.Placed],
      own: OwnDirectories): Option[PathProblem] =
    val E = Const.Msg.Err
    val R = Const.WatchFolder

    if !Files.exists(path) then Some(PathProblem(E.WATCH_PATH_MISSING, R.PATH_MISSING))
    else if !Files.isDirectory(path) then Some(PathProblem(E.WATCH_PATH_NOT_DIRECTORY, R.PATH_NOT_DIRECTORY))
    else
      val real = realOrWritten(path)
      if !isListable(real) then Some(PathProblem(E.WATCH_PATH_UNREADABLE, R.PATH_UNREADABLE))
      else if deletesImported && !Files.isWritable(real) then Some(PathProblem(E.WATCH_PATH_UNWRITABLE, R.PATH_UNWRITABLE))
      else if own.overlaps(real) then Some(PathProblem(E.WATCH_PATH_OVERLAPS_DATA, R.PATH_OVERLAPS_DATA))
      else
        others.iterator
          .flatMap {
            placed =>
              val other = placed.watchFolder
              val theirs = realOrWritten(Path.of(other.path))
              val library =
                if placed.repositoryId == RequestContext.getRepository.persistedId then ""
                else E.WATCH_PATH_OTHER_LIBRARY.format(placed.repositoryName)
              val error =
                if real == theirs then Some(E.WATCH_PATH_SAME.format(other.name, library))
                else if real.startsWith(theirs) then Some(E.WATCH_PATH_INSIDE.format(other.name, other.path, library))
                else if theirs.startsWith(real) then Some(E.WATCH_PATH_CONTAINS.format(other.name, other.path, library))
                else None
              error.map(PathProblem(_, R.PATH_OVERLAPS.format(other.name)))
          }
          .nextOption()

  // Whether the server can list the directory, which takes more than its permission bits say on some file systems
  private def isListable(directory: Path): Boolean =
    Files.isReadable(directory) && Files.isExecutable(directory) &&
      (try Using.resource(Files.newDirectoryStream(directory))(_ => true)
      catch case _: IOException => false)

  // -- Scanning --------------------------------------------------------------------------------------------------------------

  /**
   * Starts the scans: every Watch folder of every Library is scanned now, the startup scan, which catches up on what arrived
   * while the server was down, and again every `watch_folder.scan_interval` minutes (a Live Setting, read as each round is
   * scheduled, so a change takes effect from the round scheduled next). `App` calls it once the Imports a stop interrupted are
   * failed and the Import history is pruned. `schedule` puts a round off by a delay: the actor system's scheduler, unless a
   * caller drives the rounds itself.
   */
  def start(schedule: (FiniteDuration, () => Unit) => Cancellable = onActorSystem): Unit =
    logger.info(
      s"Scanning the Watch folders, now and then every ${Util.minutesText(app.settings.current.watchFolderScanInterval)} " +
        "(watch_folder.scan_interval)")
    tick(schedule)

  // One round of scans, and the next one scheduled. A round that fails is logged, and the next one still comes
  private def tick(schedule: (FiniteDuration, () => Unit) => Cancellable): Unit =
    if !isStopping.get then
      try scanAll()
      catch case NonFatal(e) => logger.error("Scanning the Watch folders failed", e)

      val interval = app.settings.current.watchFolderScanInterval.minutes
      nextScan.set(Some(schedule(interval, () => tick(schedule))))

  // A round put off on the actor system's scheduler, to run on the import dispatcher
  private def onActorSystem(delay: FiniteDuration, round: () => Unit): Cancellable =
    app.actorSystem.scheduler.scheduleOnce(delay, () => round())

  /** No scan starts from here on, and a scan that is running hands no more files to the pipeline: the server is stopping */
  def stop(): Unit =
    isStopping.set(true)
    nextScan.getAndSet(None).foreach(_.cancel())

  /** Scans every Watch folder of every Library that is not paused; the scans run on, each on its own */
  def scanAll(): Unit =
    app.service.library.forEachRepository {
      _ => txManager.asReadOnly(dao.all()).filterNot(_.isPaused).foreach(watchFolder => scan(watchFolder.persistedId))
    }

  /**
   * "Scan now": a scan of the Watch folder, started at once ([[scan]]), unless it is paused, or its destination folder is in the
   * Trash, either of which is an `IllegalOperationException` saying so. One that is not the context repository's is not found.
   */
  def scanNow(id: String): Future[ScanResult] =
    txManager.asReadOnly {
      val watchFolder = dao.getById(id)
      if watchFolder.isPaused then throw IllegalOperationException(Const.Msg.Err.WATCH_FOLDER_PAUSED)
      if watchFolder.destFolderId.map(app.service.folder.getById).exists(_.isRecycled) then
        throw IllegalOperationException(Const.Msg.Err.DESTINATION_IN_TRASH)
    }
    scan(id)

  /**
   * Scans a Watch folder of the context repository, one scan of it at a time: a scan asked for while one is running does nothing.
   * The future ends when the scan has handed its last file to the import queue (`pipeline`), or found nothing to hand it; the
   * files then settle in the pipeline, and the scan's Import closes with the last of them.
   *
   * A Watch folder that is paused, whose owner is no active Admin (which pauses it), or whose destination folder is in the Trash
   * is not scanned, and neither is one whose directory is unavailable, by the path rules, which is stored as its status reason
   * and logged at WARN when it changes; each is tried again by the next scan, and none touches what the Watch folder remembers.
   *
   * Otherwise the directory is walked ([[walk]]) and each file found is remembered ([[remember]]). A file found as a scan found
   * it at least the rest before, by its size and modified time, and not read yet, or one to be read again, is staged and offered
   * ([[take]]), unless it is in the pipeline still. The offers wait for the queue one after another without holding a thread of
   * the import dispatcher, which the pipeline's stages need to make room in the queue.
   */
  def scan(id: String, pipeline: ImportPipelineService = app.service.importPipeline): Future[ScanResult] =
    val repository = RequestContext.getRepository
    if isStopping.get || !scanning.add(id) then return Future.successful(ScanResult.NotStarted)
    tell(repository.persistedId)

    Future(prepare(repository, id, pipeline))
      .flatMap {
        case Left(blocked) => Future.successful(blocked)
        case Right(run) => offerAll(run)
      }
      .andThen {
        case result =>
          scanning.remove(id)
          result.failed.foreach(e => logger.error(s"The scan of Watch folder [$id] failed", e))
          tell(repository.persistedId)
      }

  // The repository as the context of a scan's work that is nobody's yet
  private def inRepository[A](repository: Repository)(work: => A): A =
    PipelineUtils.withContext(PipelineContext(repository, account = None))(work)

  // The repository and the Watch folder's owner, with an Admin's Permissions, as the context of a scan's imports
  private def asOwner[A](run: Run)(work: => A): A =
    PipelineUtils.withContext(PipelineContext(run.repository, Some(run.owner), Permissions.all))(work)

  // Whether the account may own a Watch folder, and so its assets
  private def owns(account: User): Boolean = account.isActive && account.accountType == AccountType.Admin

  // A scan up to its walk: why the Watch folder is not scanned, or the scan with the files to hand to the pipeline
  private def prepare(repository: Repository, id: String, pipeline: ImportPipelineService): Either[ScanResult, Run] =
    inRepository(repository) {
      val read = txManager.asReadOnly {
        find(id).map {
          watchFolder =>
            (
              watchFolder,
              app.DAO.user.getById(watchFolder.accountId),
              watchFolder.destFolderId.map(app.service.folder.getById),
              dao.everywhere().filterNot(_.watchFolder.id == watchFolder.id))
        }
      }

      read match
        case None => Left(ScanResult.Blocked(REMOVED))
        case Some((watchFolder, owner, destination, others)) =>
          val heldBack =
            if watchFolder.isPaused then Some(watchFolder.statusReason.getOrElse(WatchFolderStatus.Paused.label))
            else if !owns(owner) then Some(pauseFor(watchFolder, owner))
            else if destination.exists(_.isRecycled) then Some(Const.WatchFolder.DESTINATION_LOST)
            else None

          heldBack match
            case Some(reason) =>
              if notScanned.put(id, reason) != reason then
                logger.info(s"Watch folder [$id] \"${watchFolder.name}\" is not scanned: $reason")
              Left(ScanResult.Blocked(reason))
            case None =>
              if notScanned.remove(id) != null then logger.info(s"Watch folder [$id] \"${watchFolder.name}\" is scanned again")
              val path = Path.of(watchFolder.path)
              val own = ownDirectories()

              def unavailable(reason: String): Either[ScanResult, Run] =
                available(watchFolder, Some(reason))
                Left(ScanResult.Blocked(reason))

              pathProblem(path, watchFolder.deletesImported, others, own) match
                case Some(problem) => unavailable(problem.reason)
                case None =>
                  val root = path.toRealPath()
                  // Before the walk: no file of it is looked at earlier than this
                  val walkStarted = Instant.now()
                  walk(root, own) match
                    // Nothing of such a directory is remembered or read, the files beside the app's own included
                    case Walked(_, _, Some(met)) => unavailable(Const.WatchFolder.PATH_HOLDS_DATA.format(met))
                    case walked @ Walked(found, _, None) =>
                      val files = txManager.withTransaction(remember(watchFolder, found, walkStarted, walked.problem))
                      logger.debug(
                        s"Watch folder [$id] \"${watchFolder.name}\": ${found.size} files found, ${files.size} to read")
                      Right(Run(repository, watchFolder, owner, root, files, pipeline))
    }

  // The Watch folder, unless it was removed
  private def find(id: String): Option[WatchFolder] =
    try Some(dao.getById(id))
    catch case _: NotFoundException => None

  // Pauses a Watch folder whose owner cannot own it any more, as the account write that made it so does; the reason
  private def pauseFor(watchFolder: WatchFolder, owner: User): String =
    val reason = if owner.isActive then Const.WatchFolder.OWNER_DEMOTED else Const.WatchFolder.OWNER_DEACTIVATED
    txManager.withTransaction {
      dao.setPaused(watchFolder.persistedId, isPaused = true, Some(reason)): Unit
      changed()
    }
    reason

  // Stores why the Watch folder's directory is unavailable, in whole or in part (the directories of it that a scan could not
  // read and went on past), or that it is available again, when that changed, logged at WARN
  private def available(watchFolder: WatchFolder, problem: Option[String]): Unit =
    if watchFolder.statusReason != problem then
      txManager.withTransaction {
        dao.setStatusReason(watchFolder.persistedId, problem): Unit
        changed()
      }
      val named = s"Watch folder [${watchFolder.persistedId}] \"${watchFolder.name}\" at ${watchFolder.path}"
      problem match
        case Some(reason) => logger.warn(s"$named is unavailable: $reason")
        case None => logger.warn(s"$named is available again")

  /**
   * The files of the directory and of the directories below it, in path order. A symbolic link is neither followed nor read, a
   * file or a directory under a name imports ignore is left out, a directory with everything in it, and so are an empty file and
   * anything that is no regular file. What cannot be read is left out, for a later scan, and the walk goes on: a file, and a
   * directory that cannot be opened or whose listing an I/O error ends midway (a stale network handle), which the walk returns,
   * so one unreadable directory keeps none of the others from being read.
   *
   * The walk never enters one of the app's own directories (`own`), whatever path it meets it under, the watched directory
   * included: it ends there, with that directory, and whoever asked for the walk reads none of the files it found.
   */
  private def walk(root: Path, own: OwnDirectories): Walked =
    val found = List.newBuilder[Found]
    val unreadable = List.newBuilder[Path]
    var met: Option[Path] = None

    // Symbolic links are not followed, which is the walk's default: a link is visited as a file that is one
    Files.walkFileTree(
      root,
      new SimpleFileVisitor[Path]:
        override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
          if own.has(directory, attributes) then
            met = Some(directory)
            FileVisitResult.TERMINATE
          // The directory watched is the one the Admin named, whatever its name
          else if directory != root && IgnoredFiles.ignores(directory.getFileName.toString) then FileVisitResult.SKIP_SUBTREE
          else FileVisitResult.CONTINUE

        override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
          if attributes.isRegularFile && attributes.size > 0 && !IgnoredFiles.ignores(file.getFileName.toString) then
            found += Found(relPathOf(root, file), file, attributes.size, attributes.lastModifiedTime)
          FileVisitResult.CONTINUE

        // A directory that cannot be opened comes here too, rather than to the two visits of a directory
        override def visitFileFailed(file: Path, failure: IOException): FileVisitResult =
          logger.debug(s"Cannot read $file in a Watch folder: $failure")
          if Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) then unreadable += file
          FileVisitResult.CONTINUE

        // With the failure that ended the directory's listing midway, if one did: the default throws it, which would end the
        // walk, and the scan, at one bad directory
        override def postVisitDirectory(directory: Path, failure: IOException): FileVisitResult =
          if failure != null then
            logger.debug(s"Cannot list all of $directory in a Watch folder: $failure")
            unreadable += directory
          FileVisitResult.CONTINUE
    )

    Walked(found.result().sortBy(_.relPath), unreadable.result(), met)

  // A file's path under the directory, as an Import file's is written: its names separated by "/"
  private def relPathOf(root: Path, file: Path): String =
    root.relativize(file).iterator.asScala.map(_.toString).mkString(RelPath.SEPARATOR)

  /**
   * Writes what a walk found to the Watch folder's memory, in the caller's transaction, and returns the files to read, in path
   * order. A file found for the first time is remembered as seen; one whose size or modified time changed is a new file, seen and
   * never tried, whatever became of it before, stamped with this moment, after the walk. A file found as it is remembered is read
   * when it is to be retried, or when it is seen (not read yet) and has rested: the stamp of its first sight with this size and
   * modified time is at least `REST` older than `walkStarted`, the moment before this scan's walk, so the two looks at the file
   * are that far apart whatever the walks took. A seen file that has not rested is left for a later scan, with nothing of it
   * written. A file in the pipeline still is not read again, and a handled, final or parked one is left alone. A file is stamped
   * as found again only once its stamp is a day old, so a scan that finds nothing new writes one row. The walk found the
   * directory available: its last scan is now, and what a scan found wrong with it before gives way to `problem`, what this walk
   * could not read of it, if anything.
   */
  private def remember(watchFolder: WatchFolder, found: List[Found], walkStarted: Instant, problem: Option[String]): List[Found] =
    val id = watchFolder.persistedId
    val now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
    val stale = now.minus(LAST_SEEN_STEP).toInstant
    val restedBy = walkStarted.minus(REST)
    // The files in the pipeline before the rows: a file leaves the pipeline only once its row is written ([[settled]]), so one
    // that settles between the two reads is still taken to be in the pipeline. Read the other way round, such a file is no
    // longer in the pipeline and not read yet by its row, and would be handed to the pipeline a second time.
    val flying = inFlight.values.asScala.filter(_.watchFolderId == id).map(_.relPath).toSet
    val remembered = fileDao.ofWatchFolder(id).map(file => file.relPath -> file).toMap

    val fresh = mutable.ListBuffer[WatchFolderFile]()
    val foundAgain = mutable.ListBuffer[String]()
    val toRead = mutable.ListBuffer[Found]()

    found.foreach {
      file =>
        val modifiedAt = file.modifiedAt.toMillis
        remembered.get(file.relPath) match
          case None =>
            fresh += WatchFolderFile(
              watchFolderId = id,
              relPath = file.relPath,
              sizeBytes = file.sizeBytes,
              modifiedAt = modifiedAt)
          case Some(known) if known.sizeBytes != file.sizeBytes || known.modifiedAt != modifiedAt =>
            fileDao.changed(id, file.relPath, file.sizeBytes, modifiedAt, now): Unit
          case Some(known) =>
            val lastSeen = known.lastSeenAt.map(fileDao.instantOf)
            if lastSeen.forall(_.isBefore(stale)) then foundAgain += file.relPath
            val isDue = known.state == WatchFileState.Retry ||
              (known.state == WatchFileState.Seen && lastSeen.forall(!_.isAfter(restedBy)))
            if isDue && !flying.contains(file.relPath) then toRead += file
    }

    fileDao.addAll(fresh.toList, now)
    fileDao.seenAgain(id, foundAgain.toList, now): Unit
    dao.scanned(id, now): Unit
    available(watchFolder, problem)
    toRead.toList

  // Hands the scan's files to the pipeline one after another, each offer waiting for the queue with no thread held, then marks
  // the scan's Import complete, if it opened one: the Import closes once the last of its files settles
  private def offerAll(run: Run): Future[ScanResult] =
    def next(remaining: List[Found], offered: Int): Future[Int] =
      remaining match
        case Nil => Future.successful(offered)
        case file :: rest =>
          Future(take(run, file)).flatMap {
            case Taken.Stop => Future.successful(offered)
            case Taken.Skipped => next(rest, offered)
            case Taken.Staged(element, importFileId) =>
              run.pipeline.addToQueue(element).transformWith {
                case Success(_) => next(rest, offered + 1)
                // The queue is closed or down, which is no fault of the file: the scan ends here
                case Failure(refusal) => Future(refused(run, file, importFileId, refusal)).map(_ => offered)
              }
          }

    next(run.files, offered = 0).transform {
      result =>
        try run.opened.get.foreach(opened => asOwner(run)(app.service.imports.close(opened.persistedId)))
        catch
          case NonFatal(e) =>
            logger.error(s"Closing the Import of a scan of Watch folder [${run.watchFolder.persistedId}] failed", e)
        result.map(ScanResult.Scanned(_))
    }

  /**
   * Hands one file of a scan to the pipeline, as the Watch folder's owner: a copy of it is staged, described and added to the
   * scan's Import, which the scan's first file opens. The file is never moved: its source is the retry. Before anything is read,
   * the scan ends if the server is stopping or the Watch folder is not scanned any more as the scan read it (paused, removed,
   * edited, its destination lost). A file that is gone, or not as the walk found it, is left for a later scan, and so is one that
   * changes while it is copied. A symbolic link put in the file's place meanwhile is not read. A file that cannot be read is
   * recorded as failed in the scan's Import, and counts as an attempt.
   */
  private def take(run: Run, file: Found): Taken =
    asOwner(run) {
      if isStopping.get || !isAsScanned(run) then Taken.Stop
      else if !isAsFound(file) then Taken.Skipped
      else
        var staged: Option[Path] = None
        try
          staged = Some(Using.resource(Files.newInputStream(file.path, LinkOption.NOFOLLOW_LINKS))(app.service.staging.stage))
          if Files.size(staged.get) != file.sizeBytes || !isAsFound(file) then
            logger.debug(s"${file.path} changed while it was copied: left for a later scan")
            staged.foreach(app.service.staging.discard)
            Taken.Skipped
          else
            val names = file.relPath.split(RelPath.SEPARATOR).toList
            val described = app.service.library.stagedFileToAsset(names.last, staged.get)
            val importFile = app.service.imports.addFile(importOf(run).persistedId, file.relPath, file.sizeBytes)
            inFlight.put(
              importFile.persistedId,
              InFlight(run.watchFolder.persistedId, file.relPath, file.path, file.sizeBytes, file.modifiedAt)): Unit
            val origin = ImportOrigin(importFile.persistedId, names.init, Some(file.path))
            Taken.Staged(
              (described.copy(origin = Some(origin)), PipelineContext(run.repository, Some(run.owner), Permissions.all)),
              importFile.persistedId)
        catch
          case _: NoSuchFileException =>
            staged.foreach(app.service.staging.discard)
            Taken.Skipped
          // The destination went to the Trash since the check above: the Import cannot be opened
          case _: IllegalOperationException =>
            staged.foreach(app.service.staging.discard)
            Taken.Stop
          case NonFatal(failure) =>
            staged.foreach(app.service.staging.discard)
            unreadable(run, file, failure)
            Taken.Skipped
    }

  // The scan's Import, opened by the first file that needs it: titled by the Watch folder's name, with its destination, and
  // expecting the files the scan is about to offer
  private def importOf(run: Run): Import =
    run.opened.get.getOrElse {
      val opened = app.service.imports.open(
        ImportSource.WatchFolder,
        name = Some(run.watchFolder.name),
        destFolderId = run.watchFolder.destFolderId,
        watchFolderId = run.watchFolder.id,
        expectedFiles = Some(run.files.size)
      )
      run.opened.set(Some(opened))
      opened
    }

  // Whether the Watch folder is still to be scanned as the scan read it: there, not paused, with the path, the destination and
  // the owner it had, and its destination not in the Trash
  private def isAsScanned(run: Run): Boolean =
    txManager.asReadOnly {
      find(run.watchFolder.persistedId).exists {
        now =>
          !now.isPaused && now.path == run.watchFolder.path && now.destFolderId == run.watchFolder.destFolderId &&
          now.accountId == run.watchFolder.accountId && !now.destFolderId.map(app.service.folder.getById).exists(_.isRecycled)
      }
    }

  // Whether the file is, now, the regular file the walk found: there, no symbolic link, with that size and modified time
  private def isAsFound(file: Found): Boolean = isUnchanged(file.path, file.sizeBytes, file.modifiedAt)

  private def isUnchanged(path: Path, sizeBytes: Long, modifiedAt: FileTime): Boolean =
    try
      val now = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      now.isRegularFile && now.size == sizeBytes && now.lastModifiedTime == modifiedAt
    catch case _: IOException => false

  // A file that could not be staged or described: failed in the scan's Import, saying so, and one attempt of the file's
  private def unreadable(run: Run, file: Found, failure: Throwable): Unit =
    logger.warn(s"Cannot read ${file.path} of Watch folder [${run.watchFolder.persistedId}]", failure)
    val anImport = importOf(run)
    txManager.withTransaction {
      val importFile = app.service.imports.addFile(anImport.persistedId, file.relPath, file.sizeBytes)
      app.service.imports.settle(importFile.persistedId, ImportOutcome.Failed, reason = Some(Const.WatchFolder.UNREADABLE_FILE))
      fileDao.failed(
        run.watchFolder.persistedId,
        file.relPath,
        file.sizeBytes,
        file.modifiedAt.toMillis,
        Const.WatchFolder.MAX_ATTEMPTS): Unit
    }

  // A file the import queue refused, which the queue has logged: its Import file is failed, saying so, since nothing else would
  // settle it, and what the Watch folder remembers of it stays, so a later scan reads it; no attempt of the file's is counted
  private def refused(run: Run, file: Found, importFileId: String, refusal: Throwable): Unit =
    inFlight.remove(importFileId): Unit
    logger.warn(s"The scan of Watch folder [${run.watchFolder.persistedId}] ends at ${file.relPath}: the import queue refused it")
    asOwner(run) {
      app.service.imports.settle(importFileId, ImportOutcome.Failed, reason = Some(PipelineUtils.dropReason(Some(refusal))))
    }

  // -- The pipeline's end ----------------------------------------------------------------------------------------------------

  /**
   * What became of a Watch folder's file in the import pipeline, told by the pipeline's last working stage once the file's
   * import, or its drop, has committed: `cause` is why it was dropped, none for a file that was imported. The file's memory is
   * written, while the file is remembered as it was staged (one that changed since is a new file):
   *
   *   - imported, or a duplicate of an asset of the Library whose own import is complete: handled, and not read again;
   *   - of an unsupported type, or an image or video that cannot be decoded: final, until the file changes; and so is a duplicate
   *     that is the stored file of the asset it matches, under another path ([[isStoredFileOf]]): a file of the app's own, which
   *     is never handled, so never deleted;
   *   - failed for the Watch folder's sake rather than its own (the destination folder went to the Trash, the owner was
   *     deactivated or deleted), or a duplicate of an asset whose import is not complete or that is gone since: untouched, so a
   *     later scan reads it again;
   *   - any other failure: one more attempt, to be retried, or parked at `Const.WatchFolder.MAX_ATTEMPTS`.
   *
   * Then, once that has committed, a handled file is deleted from the directory if the Watch folder deletes imported files
   * ([[deleteSource]]). The Watch folder is read as it is now: one removed meanwhile remembers and deletes nothing, and one that
   * deletes imported files and is paused deletes nothing and leaves the file's memory as it is, so the file is read again, and
   * deleted then, once it is resumed.
   */
  def settled(origin: ImportOrigin, asset: Asset, cause: Option[Throwable]): Unit =
    // The file is in the pipeline until all of this is done
    try Option(inFlight.get(origin.importFileId)).foreach(settle(_, asset, cause))
    finally inFlight.remove(origin.importFileId): Unit

  private def settle(file: InFlight, asset: Asset, cause: Option[Throwable]): Unit =
    val modifiedAt = file.modifiedAt.toMillis
    val deleting = txManager.withTransaction {
      find(file.watchFolderId).flatMap {
        watchFolder =>
          whatBecame(file, asset, cause) match
            case Settled.Handled if watchFolder.deletesImported && watchFolder.isPaused =>
              logger.info(s"${file.source} is not deleted while its Watch folder is paused: it is read again once it resumes")
              None
            case Settled.Handled =>
              fileDao.setState(file.watchFolderId, file.relPath, file.sizeBytes, modifiedAt, WatchFileState.Handled): Unit
              Option.when(watchFolder.deletesImported)(watchFolder)
            case Settled.Final =>
              fileDao.setState(file.watchFolderId, file.relPath, file.sizeBytes, modifiedAt, WatchFileState.Final): Unit
              None
            case Settled.Failed =>
              fileDao.failed(file.watchFolderId, file.relPath, file.sizeBytes, modifiedAt, Const.WatchFolder.MAX_ATTEMPTS): Unit
              None
            case Settled.Untouched => None
      }
    }
    deleting.foreach(deleteSource(_, file))

  // What a file's memory does with what became of the file
  private def whatBecame(file: InFlight, asset: Asset, cause: Option[Throwable]): Settled =
    cause match
      case None => Settled.Handled
      // Only a copy whose own import is complete stands for the file: one still in the pipeline may yet fail
      case Some(_: DuplicateException) =>
        app.service.asset.getByChecksum(asset.checksum).filter(_.isPipelineProcessed) match
          case Some(existing) if isStoredFileOf(existing, file.source) =>
            logger.warn(
              s"${file.source} is the stored file of asset [${existing.persistedId}] itself, under another path: Watch folder " +
                s"[${file.watchFolderId}] reads the app's own store. It is not deleted, and not read again until it changes")
            Settled.Final
          case Some(_) => Settled.Handled
          case None => Settled.Untouched
      case Some(_: UnsupportedMediaTypeException | _: ImageException | _: VideoException) => Settled.Final
      case Some(_: ImporterUnavailableException) => Settled.Untouched
      case Some(lost: ImportDestinationException) if lost.getMessage == Const.Msg.Err.DESTINATION_IN_TRASH => Settled.Untouched
      case Some(_) => Settled.Failed

  /**
   * Whether a Watch folder's file is the stored file of the asset, the same file under another path (a hard link to it, or the
   * store reached through a second mount), which deleting would take from the Library. A file that cannot be told from it, an I/O
   * error, counts as it: nothing is deleted on a doubt.
   */
  private def isStoredFileOf(asset: Asset, source: Path): Boolean =
    val stored = app.service.fileStore.assetFile(asset.persistedId)
    try Files.exists(stored) && Files.isSameFile(source, stored)
    catch
      case failure: IOException =>
        logger.warn(s"Cannot tell $source from the stored file of asset [${asset.persistedId}]", failure)
        true

  /**
   * Deletes a handled file from its Watch folder's directory, and then the directories it leaves empty, up to the watched
   * directory, which stays. The file must be the one that was imported: under the directory's real path as it is now, below no
   * symbolic link, a regular file, and with the size and the modified time it had when its copy was staged; anything else is left
   * where it is, a changed file for the next scan to read as a new one. A file in one of the app's own directories, or below one,
   * whatever path leads there, is never deleted. A directory that still holds anything, an ignored file included, stays. A delete
   * that fails is logged at WARN, and the file stays remembered as handled.
   */
  private def deleteSource(watchFolder: WatchFolder, file: InFlight): Unit =
    val source = file.source
    try
      val root = Path.of(watchFolder.path).toRealPath()
      val parent = source.getParent
      if parent == null || source == root || !source.startsWith(root) || parent.toRealPath() != parent then
        logger.warn(s"$source is not in the directory of Watch folder [${watchFolder.persistedId}] any more: not deleted")
      else if !isUnchanged(source, file.sizeBytes, file.modifiedAt) then
        logger.info(s"$source changed since it was read, or is gone: not deleted")
      else if ownDirectories().at(parent).isDefined then
        logger.warn(
          s"$source is in a directory of the app's own data, which Watch folder [${watchFolder.persistedId}] reaches under " +
            "another path: not deleted")
      else
        Files.delete(source)
        logger.debug(s"Deleted $source, imported by Watch folder [${watchFolder.persistedId}]")
        removeEmptied(parent, root)
    catch
      case _: NoSuchFileException => logger.debug(s"$source is gone, or its directory is: nothing to delete")
      case NonFatal(failure) => logger.warn(s"Could not delete $source of Watch folder [${watchFolder.persistedId}]", failure)

  // Removes the directory if it is empty, then the one above it, and so on, stopping below `root`, at a directory that holds
  // anything, and at a symbolic link
  @tailrec private def removeEmptied(directory: Path, root: Path): Unit =
    if directory != root && directory.startsWith(root) && !Files.isSymbolicLink(directory) then
      val removed =
        try
          Files.delete(directory)
          logger.debug(s"Removed $directory, left empty in a Watch folder")
          true
        catch
          case _: DirectoryNotEmptyException => false
          case failure: IOException =>
            logger.warn(s"Could not remove the emptied $directory", failure)
            false
      if removed then removeEmptied(directory.getParent, root)
