package altitude.core.service

import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.Api
import altitude.core.Const
import altitude.core.DuplicateException
import altitude.core.ForbiddenException
import altitude.core.IllegalOperationException
import altitude.core.ImportDestinationException
import altitude.core.QueueRefusedException
import altitude.core.RequestContext
import altitude.core.UnsupportedMediaTypeException
import altitude.core.ValidationException
import altitude.core.dao.ImportDao
import altitude.core.dao.ImportFileDao
import altitude.core.models.Asset
import altitude.core.models.Folder
import altitude.core.models.Import
import altitude.core.models.ImportEntry
import altitude.core.models.ImportFile
import altitude.core.models.ImportFiles
import altitude.core.models.ImportHistory
import altitude.core.models.ImportOrigin
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.models.NamedPath
import altitude.core.models.Permission
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineUtils
import altitude.core.transactions.TransactionManager
import altitude.core.util.RelPath
import altitude.core.util.Util

/**
 * The Import record: every Import of a Library and its Import files, with what became of each. An Import is opened, given its
 * files as they are offered to the pipeline (each pending until settled), marked complete by whoever delivers it once no more
 * will come, and closed when complete with none pending. Its five counters count its files by outcome, written by the transaction
 * that settles a file.
 */
object ImportService:
  // An Import file's reason is shown as stored, so it reads as the pipeline's do (`PipelineUtils.dropReason`): sentence case, no
  // full stop. A duplicate's reason is a place, which the history prefixes.

  // Where the asset a duplicate matches is, when it is in no folder below the root
  private val TRIAGE = "Triage"
  private val TOP_LEVEL = "Top level"
  // Why a file still pending when the server starts is failed, and one a stop cut off in the pipeline (`PipelineUtils.dropReason`)
  val INTERRUPTED = "Interrupted by a restart"
  // Why a file the page could not send is failed
  private val UPLOAD_FAILED = "Upload failed"
  private val MAX_LOGGED_PATH_LENGTH = 512

  // Names and paths come from a client: control characters are escaped in a log line, so a line break cannot forge a log line
  private def logged(name: Option[String]): String =
    name.fold("")(n => s" \"${Util.printable(n, Api.Constraints.MAX_IMPORT_NAME_LENGTH)}\"")

  private def logged(relPath: String): String = Util.printable(relPath, MAX_LOGGED_PATH_LENGTH)

class ImportService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val importDao: ImportDao = app.DAO.imports
  private val fileDao: ImportFileDao = app.DAO.importFile
  private val txManager: TransactionManager = app.txManager
  private val listeners = CopyOnWriteArrayList[(String, Import) => Unit]()

  /**
   * Opens an Import delivered by the context account, in progress with no files. `name` is the uploaded folder's or the Watch
   * folder's name, stored with its control characters replaced (`Util.withControlCharactersReplaced`: PostgreSQL refuses a NUL,
   * and the history shows the name); `destFolderId` is none for Triage; `expectedFiles` is how many files are coming, when the
   * opener knows.
   */
  def open(
      source: ImportSource,
      name: Option[String] = None,
      destFolderId: Option[String] = None,
      watchFolderId: Option[String] = None,
      expectedFiles: Option[Int] = None): Import =
    // Checked here: SQLite reports the column's CHECK as it reports a duplicate
    if expectedFiles.exists(_ < 0) then throw ValidationException(Const.Msg.Err.EXPECTED_FILES_INVALID)

    txManager.withTransaction {
      destFolderId.foreach {
        folderId =>
          if app.service.folder.getById(folderId).isRecycled then
            throw IllegalOperationException(Const.Msg.Err.DESTINATION_IN_TRASH)
      }
      // The Import's key to its Watch folder is a single column, so the Watch folder's repository is checked here
      watchFolderId.foreach(app.service.watchFolder.getById)
      val opened = importDao.add(
        Import(
          source = source,
          name = name.map(Util.withControlCharactersReplaced),
          watchFolderId = watchFolderId,
          accountId = RequestContext.account.value.map(_.persistedId),
          destFolderId = destFolderId,
          expectedFiles = expectedFiles
        ))
      logger.info(
        s"Opened Import [${opened.persistedId}]: ${source.dbValue}${ImportService.logged(opened.name)}. Repo: ${RequestContext.getRepository.persistedId}")
      changed(opened.persistedId)
      opened
    }

  /**
   * Opens the Import of a browser upload. A destination also takes Organize, since its files arrive sorted; it is checked after
   * the folder is read, so another repository's folder is not found rather than refused.
   */
  def openUpload(name: Option[String] = None, destFolderId: Option[String] = None, expectedFiles: Option[Int] = None): Import =
    txManager.withTransaction {
      destFolderId.foreach {
        folderId =>
          app.service.folder.getById(folderId): Unit
          requireOrganize()
      }
      open(ImportSource.Upload, name, destFolderId, expectedFiles = expectedFiles)
    }

  private def requireOrganize(): Unit =
    if !RequestContext.holds(Permission.Organize) then
      throw ForbiddenException(Const.Msg.Err.FORBIDDEN_PERMISSION.format(Permission.Organize.label))

  /**
   * The folder the file's Import sorts into, none for Triage, as the file's completion reads it. A destination recycled since is
   * an `ImportDestinationException`, failing this and every later file of the Import. It is read under `FolderService.lockTree`,
   * held to the end of the transaction, so a folder delete either committed first and is seen here, or sees what the completion
   * sorted.
   */
  def destinationOf(importFileId: String): Option[Folder] =
    txManager.asReadOnly {
      val anImport = importDao.getById(fileDao.getById(importFileId).importId)
      if anImport.destFolderId.isDefined then app.service.folder.lockTree()
      lostDestination(anImport).foreach(reason => throw ImportDestinationException(reason))
      anImport.destFolderId.map(app.service.folder.getById)
    }

  /**
   * Why the Import's files can no longer be sorted: its destination was recycled. A folder is never deleted, only recycled, so
   * there is no other way to lose one.
   */
  private def lostDestination(anImport: Import): Option[String] =
    anImport.destFolderId
      .filter(app.service.folder.getById(_).isRecycled)
      .map(_ => Const.Msg.Err.DESTINATION_IN_TRASH)

  /**
   * The Import, if it is an upload the context account opened: the upload routes touch no other, so no account sends files into
   * another's upload (whose destination it may not hold Organize for) or into a Watch folder's scan.
   */
  private def ownUpload(importId: String): Import =
    val found = importDao.getById(importId)
    val isOwn = RequestContext.account.value.flatMap(_.id).exists(found.accountId.contains)
    if found.source != ImportSource.Upload || !isOwn then throw ForbiddenException(Const.Msg.Err.FORBIDDEN_UPLOAD)
    found

  /**
   * [[ownUpload]] for adding a file. An upload with a destination sorts its files and creates folders, so it takes a file only
   * while the account still holds Organize. Closing takes [[ownUpload]] alone, so an upload whose account lost Organize can
   * close.
   */
  private def ownUploadTakingFiles(importId: String): Import =
    val found = ownUpload(importId)
    if found.destFolderId.isDefined then requireOrganize()
    found

  /**
   * Checks, before anything of the file is read, that the Import takes a file under the path from the context account. [[upload]]
   * decides for good, since the Import can be marked complete in between.
   */
  def requireTakes(importId: String, relPath: String): Unit =
    RelPath.segments(relPath): Unit
    txManager.asReadOnly {
      if ownUploadTakingFiles(importId).isComplete then throw complete(importId)
    }

  /**
   * Fails a file by its path and size alone when it cannot be imported whatever its content: its path holds a NUL, or the
   * upload's destination is in the Trash, so the rest of such an upload is recorded without staging or queuing anything. None
   * otherwise; [[upload]] then fails the file the same way should the destination be lost by then.
   */
  def failOnArrival(importId: String, relPath: String, sizeBytes: Long): Option[ImportFile] =
    RelPath.segments(relPath): Unit
    txManager.withTransaction {
      failedOnArrival(ownUploadTakingFiles(importId), relPath).map(addFailed(importId, relPath, sizeBytes, _))
    }

  // Why the Import's file under the path fails before its content is read, if it does
  private def failedOnArrival(anImport: Import, relPath: String): Option[String] =
    Option.when(relPath.contains('\u0000'))(Const.Msg.Err.PATH_HOLDS_NUL).orElse(lostDestination(anImport))

  /**
   * Adds a file to the Import and fails it with the reason, in the caller's transaction. A path holding a NUL, which PostgreSQL
   * cannot store, is recorded with its control characters escaped (`Util.printable`), so the history lists the file.
   */
  private def addFailed(importId: String, relPath: String, sizeBytes: Long, reason: String): ImportFile =
    val recorded = if relPath.contains('\u0000') then Util.printable(relPath, Int.MaxValue) else relPath
    val file = addFile(importId, recorded, sizeBytes)
    settle(file.persistedId, ImportOutcome.Failed, reason = Some(reason))
    logger.info(
      s"Import file [${file.persistedId}] \"${ImportService.logged(relPath)}\" of Import [$importId] is failed on arrival: $reason")
    file.copy(outcome = ImportOutcome.Failed, reason = Some(reason))

  private def complete(importId: String) = IllegalOperationException(s"Import [$importId] is complete and takes no more files")

  /**
   * Takes one staged upload of an Import under its relative path: adds it to the Import, pending, describes it and offers it to
   * the import queue with its origin, waiting while the queue is full. The staged file is then the pipeline's.
   *
   * A file refused before it is added is unstaged and the call throws. A file of an Import whose destination is in the Trash is
   * added and failed at once, never queued. Once added, nothing else settles the file until the pipeline has it, so a failure to
   * describe or queue it fails it here, returned as failed rather than thrown: the file was delivered and recorded.
   */
  def upload(
      importId: String,
      relPath: String,
      staged: Path,
      pipeline: ImportPipelineService = app.service.importPipeline): ImportFile =
    val (names, file) =
      try
        val names = RelPath.segments(relPath)
        val size = Files.size(staged)
        if size == 0 then throw ValidationException(Const.Msg.Err.EMPTY_FILE)
        val file = txManager.withTransaction {
          failedOnArrival(ownUploadTakingFiles(importId), relPath) match
            case Some(reason) => addFailed(importId, relPath, size, reason)
            case None => addFile(importId, relPath, size)
        }
        (names, file)
      catch
        case NonFatal(refused) =>
          app.service.staging.discard(staged)
          throw refused

    if file.outcome == ImportOutcome.Failed then
      app.service.staging.discard(staged)
      return file

    try
      val described = app.service.library
        .stagedFileToAsset(names.last, staged)
        .copy(origin = Some(ImportOrigin(file.persistedId, names.init)))
      val context =
        PipelineContext(RequestContext.getRepository, Some(RequestContext.getAccount), RequestContext.permissions.value)
      Await.result(pipeline.addToQueue((described, context)), Duration.Inf)
      file
    catch
      case NonFatal(failure) =>
        val reason = PipelineUtils.dropReason(Some(failure))
        val message = s"Import file [${file.persistedId}] \"${ImportService.logged(relPath)}\" of Import [$importId] failed " +
          s"before the pipeline: $reason"
        failure match
          // The queue has logged its refusal
          case _: QueueRefusedException => logger.warn(message)
          case unexpected => logger.warn(message, unexpected)
        app.service.staging.discard(staged)
        settle(file.persistedId, ImportOutcome.Failed, reason = Some(reason))
        file.copy(outcome = ImportOutcome.Failed, reason = Some(reason))

  /** Records, added and failed, a file the page could not send after its retries, so the Import holds every file of the upload */
  def addFailedFile(importId: String, relPath: String, sizeBytes: Long): ImportFile =
    RelPath.segments(relPath): Unit
    txManager.withTransaction {
      ownUploadTakingFiles(importId): Unit
      addFailed(importId, relPath, sizeBytes, ImportService.UPLOAD_FAILED)
    }

  /** Adds a pending file to an Import still taking files, and counts it */
  def addFile(importId: String, relPath: String, sizeBytes: Long): ImportFile =
    txManager.withTransaction {
      // The counter first: the Import's row is held from here, so the Import cannot be marked complete under the new file
      if importDao.addPending(importId) == 0 then
        importDao.getById(importId): Unit
        throw complete(importId)

      val file = fileDao.add(ImportFile(importId = importId, relPath = relPath, sizeBytes = sizeBytes))
      logger.debug(s"Added Import file [${file.persistedId}] \"${ImportService.logged(relPath)}\" to Import [$importId]")
      changed(importId)
      file
    }

  /**
   * Gives a pending Import file its outcome and moves it between its Import's counters in the same transaction, closing the
   * Import when it is complete and this was its last pending file. `assetId` is the asset an imported file became or a duplicate
   * matches. A file settled already keeps its first outcome and is counted once.
   */
  def settle(fileId: String, outcome: ImportOutcome, reason: Option[String] = None, assetId: Option[String] = None): Unit =
    require(outcome != ImportOutcome.Pending, "A file settles with an outcome other than pending")

    txManager.withTransaction {
      val file = fileDao.getById(fileId)
      // The file's key to its asset is a single column, so the asset's repository is checked here
      assetId.foreach(app.service.asset.getById)

      if fileDao.settle(fileId, outcome, reason, assetId) == 1 then
        BaseService.requireFound(importDao.tableName, file.importId, importDao.fileSettled(file.importId, outcome)): Unit
        logger.debug(
          s"Import file [$fileId] \"${ImportService.logged(file.relPath)}\" of Import [${file.importId}] is ${outcome.dbValue}")
        changed(file.importId)
      else
        logger.warn(
          s"Import file [$fileId] \"${ImportService.logged(file.relPath)}\" was settled already: left as it is, not " +
            outcome.dbValue)
    }

  /**
   * Settles the Import file of a dropped file by its cause. A duplicate points at the completed asset with its content, with that
   * asset's place as the reason. Nothing need be waited for: the pipeline settles a repository's files in order, so the earlier
   * copy it matched has completed or been discarded. When no completed asset holds the content (the copy failed for another
   * reason, or was recycled since), the content is in no Library and the file is failed instead.
   */
  def settleDropped(fileId: String, asset: Asset, cause: Option[Throwable]): Unit =
    txManager.withTransaction {
      cause match
        case Some(_: DuplicateException) =>
          app.service.asset.getByChecksum(asset.checksum).filter(_.isPipelineProcessed) match
            case Some(existing) =>
              settle(fileId, ImportOutcome.Duplicate, reason = Some(placeOf(existing)), assetId = existing.id)
            case None => settle(fileId, ImportOutcome.Failed, reason = Some(Const.Msg.Err.COPY_NOT_IN_LIBRARY))
        case Some(_: UnsupportedMediaTypeException) =>
          settle(fileId, ImportOutcome.Unsupported, reason = Some(PipelineUtils.dropReason(cause)))
        case _ => settle(fileId, ImportOutcome.Failed, reason = Some(PipelineUtils.dropReason(cause)))
    }

  /** Where an asset is, as a duplicate's reason: Triage, its folder's path, or the Library's top level */
  private def placeOf(asset: Asset): String =
    if asset.isTriaged then ImportService.TRIAGE else pathOf(asset.folderId)

  /** A folder's path joined by `separator`, or the Library's top level for the root */
  def pathOf(folderId: String, separator: String = RelPath.SEPARATOR): String =
    if app.service.folder.isRootFolder(folderId) then ImportService.TOP_LEVEL
    else app.service.folder.getPath(folderId).map(_.name).mkString(separator)

  /**
   * Marks the Import complete: no more files will be added. It closes at once when none is pending, otherwise when the last one
   * settles. An Import closed already stays as it is.
   */
  def close(importId: String): Unit =
    txManager.withTransaction {
      BaseService.requireFound(importDao.tableName, importId, importDao.markComplete(importId)): Unit
      logger.info(s"Import [$importId] is complete")
      changed(importId)
    }

  /** [[close]] for the upload routes, by the account that opened the upload */
  def closeUpload(importId: String): Unit =
    txManager.withTransaction {
      ownUpload(importId): Unit
      close(importId)
    }

  /**
   * Startup steps, run before anything is queued. The history is pruned here too, so a server restarted more often than daily,
   * never reaching the daily prune, still keeps its history within the Setting.
   */
  def startup(): Unit =
    failInterrupted()
    pruneOrLog()

  /**
   * Nothing is in the pipeline at startup and staged files are not resumed, so in every repository each pending Import file is
   * failed and each Import in progress closed.
   */
  def failInterrupted(): Unit =
    app.service.library.forEachRepository {
      repository =>
        val (failed, closed) = txManager.withTransaction {
          (fileDao.failPending(ImportService.INTERRUPTED), importDao.closeInterrupted())
        }
        if closed > 0 then
          logger.warn(
            s"Closed $closed Imports a restart interrupted, failing $failed pending Import files. Repo: ${repository.name}")
    }

  /**
   * Deletes, in every repository, the closed Imports opened more than `import.history.days` ago, with their files; the assets
   * stay, and 0 keeps the history forever. Watch folders forget files absent as long, or `Const.WatchFolder.FORGET_AFTER_DAYS`
   * when the history is kept forever; what a Watch folder remembers is its own table, so a prune never makes a file new.
   */
  def prune(): Unit =
    val days = app.settings.current.importHistoryDays
    app.service.watchFolder.forgetAbsent(if days == 0 then Const.WatchFolder.FORGET_AFTER_DAYS else days)

    if days == 0 then logger.debug("The Import history is kept forever: nothing to prune")
    else
      val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days)
      app.service.library.forEachRepository {
        repository =>
          val pruned = txManager.withTransaction(importDao.deleteClosedBefore(cutoff))
          if pruned > 0 then logger.info(s"Pruned $pruned Imports older than $days days. Repo: ${repository.name}")
          else logger.debug(s"No Import older than $days days to prune. Repo: ${repository.name}")
      }

  /** [[prune]] for startup and the daily schedule, which a failed prune must not stop: logged, not thrown */
  def pruneOrLog(): Unit =
    try prune()
    catch case NonFatal(e) => logger.error("Pruning the Import history failed", e)

  def getById(importId: String): Import =
    txManager.asReadOnly {
      importDao.getById(importId)
    }

  /**
   * The context repository's first `shown` Imports (in progress first, then the newest), each with its account's name, its
   * destination's path, and whether a restart failed files of it (asked only of closed Imports with failures)
   */
  def history(shown: Int): ImportHistory =
    txManager.asReadOnly {
      val listed = importDao.list(shown)
      val names =
        app.DAO.user.getByIds(listed.records.flatMap(_.accountId).toSet).map(account => account.persistedId -> account.name).toMap
      val destinations =
        listed.records.flatMap(_.destFolderId).distinct.map(folderId => folderId -> pathOf(folderId, NamedPath.SEPARATOR)).toMap
      val interrupted = fileDao.havingReason(
        listed.records.filter(anImport => !anImport.isInProgress && anImport.failed > 0).map(_.persistedId).toSet,
        ImportOutcome.Failed,
        ImportService.INTERRUPTED)

      val entries = listed.records.map {
        anImport =>
          ImportEntry(
            anImport,
            openedAt = anImport.createdAt.map(importDao.instantOf),
            deliveredBy = anImport.accountId.flatMap(names.get),
            destination = anImport.destFolderId.map(destinations),
            isInterrupted = interrupted.contains(anImport.persistedId)
          )
      }
      ImportHistory(entries, listed.total)
    }

  /** The Import and its first `shown` files in path order, of one outcome if given, in one transaction so the counters match */
  def filesShown(importId: String, outcome: Option[ImportOutcome], shown: Int): ImportFiles =
    txManager.asReadOnly {
      ImportFiles(importDao.getById(importId), outcome, fileDao.ofImport(importId, outcome, shown))
    }

  /** Tells `listener` of every committed change to an Import from now on, with the repository's ID and the Import as committed */
  def onChange(listener: (String, Import) => Unit): Unit = listeners.add(listener): Unit

  /**
   * Tells the listeners after the commit, reading the Import then, so they never see a state inside a transaction. Changes
   * committed at once may be told out of order, each with an Import at least as far along as its own change left it.
   */
  private def changed(importId: String): Unit =
    val repositoryId = RequestContext.getRepository.persistedId
    txManager.afterCommit {
      val committed = getById(importId)
      listeners.forEach(_(repositoryId, committed))
    }
