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
    /** This many files were handed to the import pipeline */
    case Scanned(offered: Int)

    /**
     * A scan was already running, the server is stopping, or the Watch folder changed since the scan read it, and the next scan
     * reads it again
     */
    case NotStarted

    /** Not scanned, for the reason: paused, destination lost, directory unavailable, removed, or its path changed */
    case Blocked(reason: String)

  /** Why a path cannot be a Watch folder's, or be scanned: `error` for the dialog's path field, `reason` for the card */
  case class PathProblem(error: String, reason: String)

  // A file as a walk found it; `relPath` is under the watched directory
  private case class Found(relPath: String, path: Path, sizeBytes: Long, modifiedAt: FileTime)

  /**
   * A walk's result: files in path order; what it went past, each in path order, so the note it makes stays the same from scan to
   * scan: the unreadable directories, the paths of the files too long to remember (`Const.WatchFolder.MAX_PATH_BYTES`) and those
   * of the files whose names read as another's, by their bytes percent-encoded ([[onePerPath]]); and the app's own directory it
   * stopped at, if any.
   */
  private case class Walked(
      files: List[Found],
      unreadable: List[Path],
      tooLong: List[String],
      sameName: List[String],
      own: Option[Path]):
    // The card's note on what the walk went past, if anything
    def problem: Option[String] =
      val W = Const.WatchFolder
      val directories = unreadable match
        case Nil => None
        case List(directory) => Some(W.DIRECTORY_UNREADABLE.format(directory))
        case first :: _ => Some(W.DIRECTORIES_UNREADABLE.format(unreadable.size, first))
      val long = tooLong match
        case Nil => None
        case List(relPath) => Some(W.PATH_TOO_LONG.format(W.MAX_PATH_BYTES, shortened(relPath)))
        case first :: _ => Some(W.PATHS_TOO_LONG.format(tooLong.size, W.MAX_PATH_BYTES, shortened(first)))
      val named = sameName match
        case Nil => None
        case List(relPath) => Some(W.NAME_READS_AS_ANOTHER.format(shortened(relPath)))
        case first :: _ => Some(W.NAMES_READ_AS_OTHERS.format(sameName.size, shortened(first)))
      Option(List(directories, long, named).flatten.mkString("; ")).filter(_.nonEmpty)

  // A path cut to what a card shows, by whole characters
  private def shortened(relPath: String): String =
    val shown = Const.WatchFolder.SHOWN_PATH_LENGTH
    if relPath.codePointCount(0, relPath.length) <= shown then relPath
    else relPath.substring(0, relPath.offsetByCodePoints(0, shown)) + "…"

  /**
   * The directories the app stores into (`paths`, real paths), which no Watch folder may read or delete from. A directory matches
   * by path or by file key (`Files.isSameFile` where there is none), which also catches a second path no symbolic link explains,
   * such as a volume mounted twice.
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

    /** Whether the real path is, lies inside or holds one of them, by path and by the identity of every directory above either */
    def overlaps(real: Path): Boolean =
      paths.exists(path => real.startsWith(path) || path.startsWith(real)) || at(real).isDefined || {
        val candidate = OwnDirectories(List(real))
        paths.exists(candidate.at(_).isDefined)
      }

  // A file of a Watch folder that is in the import pipeline, as it was when its copy was staged
  private case class InFlight(watchFolderId: String, relPath: String, source: Path, sizeBytes: Long, modifiedAt: FileTime)

  // A scan past its walk: the Watch folder as read, the directory's real path, the files to offer in path order, and the
  // scan's Import once opened
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
    // Staged and added to the scan's Import, to offer to the queue
    case Staged(element: TDataAssetWithContext, importFileId: String)
    // Left for a later scan, or recorded as failed: the scan goes on
    case Skipped
    // The Watch folder changed or the server is stopping: the scan ends
    case Stop

  // The state a file's memory takes once the file settles in the pipeline
  private enum Settled:
    case Handled, Final, Failed, Untouched

  // A remembered file's last-seen stamp is refreshed only once it is this old, so a scan that finds nothing new writes no file
  // rows; forgetting absent files allows for it
  private val LAST_SEEN_STEP = java.time.Duration.ofDays(1)

  // How long a file must stay unchanged (size and modified time) between two scans before it is read. Far shorter than the
  // scan interval, so two scheduled scans always span it, and manual scans in quick succession cannot shorten it
  private val REST = java.time.Duration.ofSeconds(Const.WatchFolder.REST_SECONDS)

  private val STOPPING = "the server is stopping"

  /** Why a scan of a Watch folder removed before or during it scans nothing */
  val REMOVED = "the Watch folder was removed"

  /** Why a scan of a Watch folder whose path an edit changed during it remembers nothing */
  val PATH_CHANGED = "the Watch folder's path was changed"

/**
 * Watch folders: server directories the app scans, each for one Library, importing the files that arrive. Files are only ever
 * copied out, and deleted only when the Watch folder deletes imported files.
 *
 * A scan walks the directory without following symbolic links and remembers each file by path, size and modified time
 * (`watch_folder_file`). A file unchanged for `REST` between two scans is staged and offered to the import queue as a file of the
 * scan's Import. The pipeline's last stage reports each outcome to [[settled]], which records it and deletes the source.
 */
class WatchFolderService(val app: Altitude):
  import WatchFolderService.*

  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val dao: WatchFolderDao = app.DAO.watchFolder
  private val fileDao: WatchFolderFileDao = app.DAO.watchFolderFile
  private val txManager: TransactionManager = app.txManager
  private val F = FieldConst.WatchFolder

  // Scan work runs on the import dispatcher, as the pipeline's stages do; nothing blocks there waiting for the queue
  private given ExecutionContext = app.importDispatcher

  // Watch folders being scanned: one scan per Watch folder at a time
  private val scanning = ConcurrentHashMap.newKeySet[String]()

  // Files in the import pipeline, by Import file ID: a scan does not offer one again, and [[settled]] finds it here. In memory,
  // as the pipeline is, so nothing is in flight at startup.
  private val inFlight = ConcurrentHashMap[String, InFlight]()

  // Why each Watch folder was last not scanned, so the log says it once per change
  private val notScanned = ConcurrentHashMap[String, String]()

  private val listeners = CopyOnWriteArrayList[String => Unit]()

  // The next scheduled round
  private val nextScan = AtomicReference[Option[Cancellable]](None)
  private val isStopping = AtomicBoolean(false)

  /**
   * Adds a Watch folder to the context repository, owned by the context account. The path must be an absolute, readable directory
   * (writable when it deletes imported files) that overlaps neither the app's own directories nor another Watch folder of any
   * Library, by real path; the destination must not be in the Trash. A `ValidationException` names every refused field.
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
   * Edits a Watch folder under the rules of [[add]]; the context account owns it from here on. A changed path forgets the old
   * directory's files. Switching deletion on makes its handled files read again, so each is checked against the Library (and
   * re-imported if its asset is gone) before it is deleted, never on the memory's word alone. The status reason is cleared for
   * the next scan to find again; the edit writes no pause, so one paused or resumed meanwhile stays as that left it.
   */
  def update(id: String, spec: WatchFolderSpec): WatchFolder =
    txManager.withTransaction {
      dao.lockPaths()
      lockEditor()
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
   * Removes a Watch folder and its memory; its directory's files and its Imports stay. A file still in the pipeline is imported
   * but not deleted.
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
   * Resumes a paused Watch folder. One whose owner is no longer an active Admin (which paused it) passes to the context account,
   * since its assets need an owner. It decides on the Watch folder as its row lock finds it, after the editor's share lock
   * (**Lock order**): an edit committed meanwhile made its editor, an active Admin, the owner, which stands, and one removed
   * meanwhile is a `NotFoundException`.
   */
  def resume(id: String): Unit =
    txManager.withTransaction {
      lockEditor()
      val stored = dao.lock(id).getOrElse(throw NotFoundException(s"Cannot find record in '${dao.tableName}' with ID [$id]"))
      if !owns(app.DAO.user.getById(stored.accountId)) then
        val owner = RequestContext.getAccount
        dao.setOwner(id, owner.persistedId): Unit
        logger.info(s"Watch folder [$id] is owned by account [${owner.persistedId}] from here on, who resumed it")
      dao.setPaused(id, isPaused = false, reason = None): Unit
      logger.info(s"Resumed Watch folder [$id]")
      changed()
    }

  /**
   * Share-locks the context account (`UserService.lockActor`), which an edit or a resume may make the owner, before the Watch
   * folder's row: an account write locks the accounts first and then pauses or passes on Watch folders, so the two take turns.
   * After `lockPaths`, as an add's insert share-locks its owner after it. An editor deactivated, deleted or demoted by then is
   * refused, since a Watch folder's owner must be an active Admin.
   */
  private def lockEditor(): Unit =
    app.service.user.lockActor(RequestContext.getAccount.persistedId, mustBeAdmin = true): Unit

  /** Makes the Watch folder's failed files (final, to retry and parked) read again, as never tried; returns how many */
  def retryFailed(id: String): Int =
    txManager.withTransaction {
      dao.getById(id): Unit
      val retried = fileDao.reread(id, Set(WatchFileState.Final, WatchFileState.Retry, WatchFileState.Parked))
      logger.info(s"Watch folder [$id]: $retried failed files are read again")
      retried
    }

  def getById(id: String): WatchFolder =
    txManager.asReadOnly {
      dao.getById(id)
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
   * Status and reason. A destination in the Trash pauses it without storing anything, so it resumes once the folder is restored
   * or replaced; a stored status reason means its last scan found the directory, or part of it, unavailable.
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
   * How many files a scan would find at the path now, to name before imported files are deleted; None for a path that cannot be a
   * Watch folder's (`self` is the one being edited)
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
   * Forgets, in every repository, remembered files absent for `days`. What a Watch folder's last scan found is never forgotten,
   * so one that is not being scanned keeps its memory.
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

  /** Calls `listener` with the repository ID on every change to a Library's Watch folders, scans starting and ending included */
  def onChange(listener: String => Unit): Unit = listeners.add(listener): Unit

  // Tells the listeners once the transaction that changes a Watch folder has committed
  private def changed(): Unit =
    val repositoryId = RequestContext.getRepository.persistedId
    txManager.afterCommit(tell(repositoryId))

  private def tell(repositoryId: String): Unit = listeners.forEach(_(repositoryId))

  // -- The path rules --------------------------------------------------------------------------------------------------------

  // The spec as stored, or a `ValidationException` naming every refused field
  private def validated(spec: WatchFolderSpec, self: Option[WatchFolder]): WatchFolderSpec =
    val refused = ValidationException()

    val name = spec.name.trim
    if name.isEmpty then refused.errors += F.NAME -> Const.Msg.Err.VALUE_REQUIRED
    else
      // Each scan's Import takes the name, so it holds no control character either, as no name does
      try Validators.validName(name, F.NAME, Api.Constraints.MAX_WATCH_FOLDER_NAME_LENGTH): Unit
      catch case invalid: ValidationException => refused.errors ++= invalid.errors

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

  // The path as typed: absolute, with no "." or ".." left in it, and within the bytes its unique index takes
  private def parsed(text: String): Either[String, Path] =
    if text.trim.isEmpty then Left(Const.Msg.Err.VALUE_REQUIRED)
    else
      try
        val path = Path.of(text.trim)
        if !path.isAbsolute then Left(Const.Msg.Err.WATCH_PATH_RELATIVE)
        else if WatchFolder.isPathTooLong(path.normalize().toString) then
          Left(Const.Msg.Err.WATCH_PATH_TOO_LONG.format(Const.WatchFolder.MAX_PATH_BYTES))
        else Right(path.normalize())
      catch case _: InvalidPathException => Left(Const.Msg.Err.WATCH_PATH_INVALID)

  // A path with its symbolic links resolved, or as it is written where it does not exist
  private def realOrWritten(path: Path): Path =
    try path.toRealPath()
    catch case _: IOException => path.toAbsolutePath.normalize()

  /**
   * The directories the app stores into, down to each Library's directory and the ones in it. Each is resolved on its own, since
   * any may be a symbolic link out of the data directory (a store on another volume). Missing ones are left out, except the data
   * directory, which is then taken as written.
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

  /** What keeps the path from being a Watch folder's, if anything; overlap with `others` (any Library's) is by real path */
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
   * Starts the scans: a startup round now, catching up on what arrived while the server was down, then one every
   * `watch_folder.scan_interval` minutes (a Live Setting, read as each round is scheduled). `schedule` defers a round; a caller
   * may drive the rounds itself.
   */
  def start(schedule: (FiniteDuration, () => Unit) => Cancellable = onActorSystem): Unit =
    warnOfFileNameEncoding()
    logger.info(
      s"Scanning the Watch folders, now and then every ${Util.minutesText(app.settings.current.watchFolderScanInterval)} " +
        "(watch_folder.scan_interval)")
    tick(schedule)

  /**
   * Warns when the JVM reads file names in an encoding other than UTF-8 (`sun.jnu.encoding`, from the locale it starts under: a
   * start under the C or POSIX locale reads ASCII). Every name with a byte it cannot read then reads with U+FFFD in its place, so
   * names that differ only there read as one and a scan skips all but one of them ([[onePerPath]]). Windows reads file names as
   * UTF-16, whatever the property says.
   */
  private def warnOfFileNameEncoding(): Unit =
    val encoding = System.getProperty("sun.jnu.encoding", "")
    val isUtf8 = encoding.equalsIgnoreCase("UTF-8") || encoding.equalsIgnoreCase("UTF8")
    if !isUtf8 && !System.getProperty("os.name", "").startsWith("Windows") then
      logger.warn(
        s"The JVM reads file names as $encoding, not UTF-8: a Watch folder's files whose names differ only in characters " +
          s"$encoding cannot read are taken for one file, and all but one skipped. Start the server under a UTF-8 locale " +
          "(LANG=C.UTF-8, or another UTF-8 one).")

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

  /** Starts a [[scan]] at once; one paused, or with its destination in the Trash, is an `IllegalOperationException` */
  def scanNow(id: String): Future[ScanResult] =
    txManager.asReadOnly {
      val watchFolder = dao.getById(id)
      if watchFolder.isPaused then throw IllegalOperationException(Const.Msg.Err.WATCH_FOLDER_PAUSED)
      if watchFolder.destFolderId.map(app.service.folder.getById).exists(_.isRecycled) then
        throw IllegalOperationException(Const.Msg.Err.DESTINATION_IN_TRASH)
    }
    scan(id)

  /**
   * Scans a Watch folder of the context repository, one scan of it at a time. The future completes once the last file is handed
   * to the import queue; the scan's Import closes when the last of them settles.
   *
   * A Watch folder that is paused, whose owner is no active Admin (which pauses it), whose destination is in the Trash, or whose
   * directory is unavailable is not scanned, and its memory is untouched; unavailability is stored as its status reason. Offers
   * wait for the queue without holding an import dispatcher thread, which the pipeline's stages need to make room.
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
          // None when the owner it read can no longer own it, yet it is no longer theirs or theirs to pause either
          val heldBack: Option[Option[String]] =
            if watchFolder.isPaused then Some(Some(watchFolder.statusReason.getOrElse(WatchFolderStatus.Paused.label)))
            else if !owns(owner) then pauseFor(watchFolder, owner).map(Some(_))
            else if destination.exists(_.isRecycled) then Some(Some(Const.WatchFolder.DESTINATION_LOST))
            else Some(None)

          heldBack match
            case None =>
              logger.info(
                s"Watch folder [$id] \"${watchFolder.name}\" changed since its scan read it, which is not started: the next " +
                  "one reads it again")
              Left(ScanResult.NotStarted)
            case Some(Some(reason)) =>
              if notScanned.put(id, reason) != reason then
                logger.info(s"Watch folder [$id] \"${watchFolder.name}\" is not scanned: $reason")
              Left(ScanResult.Blocked(reason))
            case Some(None) =>
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
                    case Walked(_, _, _, _, Some(met)) => unavailable(Const.WatchFolder.PATH_HOLDS_DATA.format(met))
                    case walked @ Walked(found, _, _, _, None) =>
                      txManager.withTransaction(remember(watchFolder, found, walkStarted, walked.problem)) match
                        case Left(reason) =>
                          logger.info(s"Watch folder [$id] \"${watchFolder.name}\" is not scanned, as during its walk $reason")
                          Left(ScanResult.Blocked(reason))
                        case Right(files) =>
                          logger.debug(
                            s"Watch folder [$id] \"${watchFolder.name}\": ${found.size} files found, ${files.size} to read")
                          Right(Run(repository, watchFolder, owner, root, files, pipeline))
    }

  // The Watch folder, unless it was removed
  private def find(id: String): Option[WatchFolder] =
    try Some(dao.getById(id))
    catch case _: NotFoundException => None

  /**
   * Pauses a Watch folder whose owner can no longer own it, as the account change would have, and returns the reason. The owner
   * is share-locked and read again first, before the Watch folder's row as the Lock order takes an account before its Watch
   * folders, so an account write of it either committed and is read here or waits for the pause. None, pausing nothing, when the
   * owner may own it again (reactivated, promoted), or when the Watch folder is no longer theirs or unpaused, as the scan read
   * it: an Admin who edited or resumed it since owns it now, or one paused it.
   */
  private def pauseFor(watchFolder: WatchFolder, owner: User): Option[String] =
    txManager.withTransaction {
      val now = app.DAO.user.shareLock(owner.persistedId)
      if now.exists(owns) then None
      else
        val reason = if now.exists(_.isActive) then Const.WatchFolder.OWNER_DEMOTED else Const.WatchFolder.OWNER_DEACTIVATED
        Option.when(dao.pauseForOwner(watchFolder.persistedId, owner.persistedId, reason) > 0) {
          changed()
          reason
        }
    }

  /**
   * Stores and logs a change in the directory's availability: `problem` is why it is unavailable, in whole or in part
   * (directories and files a scan went past), or None. Only a problem other than the one the scan read is written, so a scan that
   * finds it as before writes nothing; then the row decides, not that earlier read, and a paused Watch folder keeps its pause's
   * reason. The log escapes the problem, which may name a file as a name on disk spells it (`Util.printable`).
   */
  private def available(watchFolder: WatchFolder, problem: Option[String]): Unit =
    val isChanged = watchFolder.statusReason != problem && txManager.withTransaction {
      val updated = dao.setStatusReason(watchFolder.persistedId, problem) > 0
      if updated then changed()
      updated
    }
    if isChanged then
      val named = s"Watch folder [${watchFolder.persistedId}] \"${watchFolder.name}\" at ${watchFolder.path}"
      problem match
        case Some(reason) => logger.warn(s"$named is unavailable: ${Util.printable(reason, reason.length * 6)}")
        case None => logger.warn(s"$named is available again")

  /**
   * The non-empty regular files under the directory, in path order, without symbolic links or names imports ignore. What cannot
   * be read is left for a later scan and the walk goes on; a directory that cannot be opened, or whose listing fails midway (a
   * stale network handle), is returned as unreadable, and a file whose path under the directory is too long to remember as too
   * long. Of files whose paths read as one, one is kept and the others returned ([[onePerPath]]).
   *
   * The walk stops at one of the app's own directories, under whatever path it meets it, and the caller then reads nothing.
   */
  private def walk(root: Path, own: OwnDirectories): Walked =
    val found = List.newBuilder[Found]
    val unreadable = List.newBuilder[Path]
    val tooLong = List.newBuilder[String]
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
            val relPath = relPathOf(root, file)
            if WatchFolder.isPathTooLong(relPath) then tooLong += relPath
            else found += Found(relPath, file, attributes.size, attributes.lastModifiedTime)
          FileVisitResult.CONTINUE

        // A directory that cannot be opened comes here too, rather than to the two visits of a directory
        override def visitFileFailed(file: Path, failure: IOException): FileVisitResult =
          logger.debug(s"Cannot read $file in a Watch folder: $failure")
          if Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) then unreadable += file
          FileVisitResult.CONTINUE

        // `failure` ended the listing midway; the default rethrows it, which would end the walk, and the scan, at one bad
        // directory
        override def postVisitDirectory(directory: Path, failure: IOException): FileVisitResult =
          if failure != null then
            logger.debug(s"Cannot list all of $directory in a Watch folder: $failure")
            unreadable += directory
          FileVisitResult.CONTINUE
    )

    val (files, sameName) = onePerPath(root, found.result())
    Walked(files, unreadable.result().sorted, tooLong.result().sorted, sameName, met)

  /**
   * The files in path order, one per path, and the paths of those left out. Two names whose bytes the JVM's file name encoding
   * cannot read (invalid UTF-8) both read with U+FFFD in their place, as one path, and the memory keeps one file per path: the
   * first by its name's bytes is kept, the same one every scan, and each other is left alone, its path under the directory given
   * by its bytes, percent-encoded as a file URI spells them, so the card tells it from the one kept.
   */
  private def onePerPath(root: Path, found: List[Found]): (List[Found], List[String]) =
    val sorted = found.sortBy(file => (file.relPath, file.path))
    val kept = sorted.distinctBy(_.relPath)
    val left = sorted.diff(kept).map(file => root.toUri.relativize(file.path.toUri).getRawPath)
    (kept, left.sorted)

  // A file's path under the directory, as an Import file's is written: its names separated by "/"
  private def relPathOf(root: Path, file: Path): String =
    root.relativize(file).iterator.asScala.map(_.toString).mkString(RelPath.SEPARATOR)

  /**
   * Records what a walk found, in the caller's transaction, and returns the files to read, in path order. A changed size or
   * modified time makes a new file, whatever became of it before. A remembered file is read when it is to be retried, or when it
   * is seen and has rested: last seen at least `REST` before `walkStarted`, so the two looks are that far apart however long the
   * walks took. A file still in the pipeline is not read again. The status reason becomes `problem` unless it is paused by then;
   * the memory is read streamed into a map, and the changed files are written in one batch.
   *
   * The Watch folder's row is written first, as an edit and a removal of it write it first, so the two take turns instead of each
   * holding rows the other waits on, and only while its path is the one walked. Otherwise nothing is remembered, and the reason
   * is returned: the Watch folder was removed meanwhile, or an edit moved it to another directory and forgot the old one's files,
   * which the walk found.
   */
  private def remember(
      watchFolder: WatchFolder,
      found: List[Found],
      walkStarted: Instant,
      problem: Option[String]): Either[String, List[Found]] =
    val id = watchFolder.persistedId
    val now = Util.utcNow
    if dao.scanned(id, now, watchFolder.path) == 0 then return Left(if find(id).isEmpty then REMOVED else PATH_CHANGED)

    val stale = now.minus(LAST_SEEN_STEP).toInstant
    val restedBy = walkStarted.minus(REST)
    // In-flight files are read before the rows: a file leaves the pipeline only after its row is written ([[settled]]), so one
    // settling in between still counts as in flight. The other order could offer it twice.
    val flying = inFlight.values.asScala.filter(_.watchFolderId == id).map(_.relPath).toSet
    val remembered = fileDao.memoryOf(id)

    val fresh = mutable.ListBuffer[WatchFolderFile]()
    val changedFiles = mutable.ListBuffer[WatchFolderFile]()
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
            changedFiles += known.copy(sizeBytes = file.sizeBytes, modifiedAt = modifiedAt)
          case Some(known) =>
            val lastSeen = known.lastSeenAt.map(fileDao.instantOf)
            if lastSeen.forall(_.isBefore(stale)) then foundAgain += file.relPath
            val isDue = known.state == WatchFileState.Retry ||
              (known.state == WatchFileState.Seen && lastSeen.forall(!_.isAfter(restedBy)))
            if isDue && !flying.contains(file.relPath) then toRead += file
    }

    fileDao.addAll(fresh.toList, now)
    fileDao.changed(id, changedFiles.toList, now)
    fileDao.seenAgain(id, foundAgain.toList, now): Unit
    available(watchFolder, problem)
    Right(toRead.toList)

  // Offers the files one after another, each waiting for the queue without holding a thread, then marks the scan's Import
  // complete, if it opened one; it closes once its last file settles
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
   * Stages one file as the Watch folder's owner and adds it to the scan's Import; the file is never moved, so its source is the
   * retry. The scan ends if the server is stopping or the Watch folder changed since the scan read it. A file that is gone or
   * changed since the walk, or while being copied, is left for a later scan; one that cannot be read fails in the Import and
   * counts as an attempt.
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

  // The scan's Import, opened by the first file that needs it, expecting all the files the scan is about to offer
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

  // Whether the Watch folder is still to be scanned as the scan read it
  private def isAsScanned(run: Run): Boolean =
    txManager.asReadOnly {
      find(run.watchFolder.persistedId).exists {
        now =>
          !now.isPaused && now.path == run.watchFolder.path && now.destFolderId == run.watchFolder.destFolderId &&
          now.accountId == run.watchFolder.accountId && !now.destFolderId.map(app.service.folder.getById).exists(_.isRecycled)
      }
    }

  // Whether the file is still the regular file the walk found, not a symbolic link
  private def isAsFound(file: Found): Boolean = isUnchanged(file.path, file.sizeBytes, file.modifiedAt)

  private def isUnchanged(path: Path, sizeBytes: Long, modifiedAt: FileTime): Boolean =
    try
      val now = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      now.isRegularFile && now.size == sizeBytes && now.lastModifiedTime == modifiedAt
    catch case _: IOException => false

  // A file that could not be staged or described: failed in the scan's Import and counted as an attempt
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

  // A file the queue refused: its Import file is failed, since nothing else would settle it, and its memory is untouched, so a
  // later scan reads it with no attempt counted
  private def refused(run: Run, file: Found, importFileId: String, refusal: Throwable): Unit =
    inFlight.remove(importFileId): Unit
    logger.warn(s"The scan of Watch folder [${run.watchFolder.persistedId}] ends at ${file.relPath}: the import queue refused it")
    asOwner(run) {
      app.service.imports.settle(importFileId, ImportOutcome.Failed, reason = Some(PipelineUtils.dropReason(Some(refusal))))
    }

  // -- The pipeline's end ----------------------------------------------------------------------------------------------------

  /**
   * Records what became of a Watch folder's file, called by the pipeline's last working stage once its import or drop has
   * committed (`cause` is why it was dropped). Imported files and duplicates of a completed asset are handled; files that can
   * never import are final until they change; failures that are the Watch folder's fault rather than the file's leave the memory
   * untouched; any other failure is an attempt, parked at `Const.WatchFolder.MAX_ATTEMPTS`.
   *
   * After commit, a handled file is deleted if the Watch folder deletes imported files. One removed meanwhile records nothing; a
   * paused one that deletes leaves the memory as is, so the file is read again and deleted once it resumes.
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
   * Whether the file is the asset's stored file under another path (a hard link, a second mount), which deleting would take from
   * the Library. An I/O error counts as yes: nothing is deleted on a doubt.
   */
  private def isStoredFileOf(asset: Asset, source: Path): Boolean =
    val stored = app.service.fileStore.assetFile(asset.persistedId)
    try Files.exists(stored) && Files.isSameFile(source, stored)
    catch
      case failure: IOException =>
        logger.warn(s"Cannot tell $source from the stored file of asset [${asset.persistedId}]", failure)
        true

  /**
   * Deletes a handled file, then the directories it leaves empty, up to the watched directory. Only the file that was imported is
   * deleted: under the directory's real path, below no symbolic link, unchanged since staging, and outside the app's own
   * directories by any path. A failed delete is logged and the file stays remembered as handled.
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

  // Removes empty directories upward, keeping `root` and stopping at a non-empty directory or a symbolic link
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
