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
import altitude.core.util.QueryResult
import altitude.core.util.RelPath

/**
 * The Import record: every Import of a Library and every one of its Import files, with what became of each. An Import is opened,
 * given its files as they are offered to the import pipeline (each pending until the pipeline settles it), and closed: marked
 * complete by whoever delivers it, once no more files will come, and closed for good when it is complete and none of its files is
 * pending. Its five counters are its files by outcome, written by the same transaction that sets a file's outcome.
 */
object ImportService:
  // An Import file's reason reads as the pipeline's do (`PipelineUtils.dropReason`): a phrase in sentence case with no full
  // stop, which the Import history shows as it is stored. A duplicate's reason is a place, which the history leads with
  // "Already in the Library: ".

  // Where the asset a duplicate matches is, when it is in no folder below the root
  private val TRIAGE = "Triage"
  private val TOP_LEVEL = "Top level"
  // Why a file still pending when the server starts is failed
  private val INTERRUPTED = "Interrupted by a restart"
  // Why a file the page could not send is failed
  private val UPLOAD_FAILED = "Upload failed"

class ImportService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val importDao: ImportDao = app.DAO.imports
  private val fileDao: ImportFileDao = app.DAO.importFile
  private val txManager: TransactionManager = app.txManager
  private val listeners = CopyOnWriteArrayList[(String, Import) => Unit]()

  /**
   * Opens an Import delivered by the context account, if there is one: in progress, with no file yet. `name` is the uploaded
   * folder's or the Watch folder's name, none for an upload of files; `destFolderId` the folder its files are sorted into, none
   * for Triage. A destination that is no folder of the context repository is a `NotFoundException`, and one in the Trash an
   * `IllegalOperationException`; neither opens anything.
   */
  def open(
      source: ImportSource,
      name: Option[String] = None,
      destFolderId: Option[String] = None,
      watchFolderId: Option[String] = None): Import =
    txManager.withTransaction {
      destFolderId.foreach {
        folderId =>
          if app.service.folder.getById(folderId).isRecycled then
            throw IllegalOperationException(Const.Msg.Err.DESTINATION_IN_TRASH)
      }
      val opened = importDao.add(
        Import(
          source = source,
          name = name,
          watchFolderId = watchFolderId,
          accountId = RequestContext.account.value.map(_.persistedId),
          destFolderId = destFolderId))
      logger.info(
        s"Opened Import [${opened.persistedId}]: ${source.dbValue}${name.fold("")(n => s" \"$n\"")}. Repo: ${RequestContext.getRepository.persistedId}")
      changed(opened.persistedId)
      opened
    }

  /**
   * Opens the Import of an upload from a browser ([[open]]). A destination takes Organize as well as the Import Permission the
   * upload routes take, since its files arrive sorted: an account without it is refused (`ForbiddenException`) once the folder is
   * read, so a folder that is not the repository's is not found rather than refused.
   */
  def openUpload(name: Option[String] = None, destFolderId: Option[String] = None): Import =
    txManager.withTransaction {
      destFolderId.foreach {
        folderId =>
          app.service.folder.getById(folderId): Unit
          if !RequestContext.holds(Permission.Organize) then
            throw ForbiddenException(Const.Msg.Err.FORBIDDEN_PERMISSION.format(Permission.Organize.label))
      }
      open(ImportSource.Upload, name, destFolderId)
    }

  /**
   * The folder the Import of the file sorts its files into, none for Triage, as the completion of the file's import reads it. A
   * destination recycled since the Import was opened is an `ImportDestinationException`: the file fails, and so does every file
   * of the Import after it, while the folder stays in the Trash.
   */
  def destinationOf(importFileId: String): Option[Folder] =
    txManager.asReadOnly {
      val anImport = importDao.getById(fileDao.getById(importFileId).importId)
      lostDestination(anImport).foreach(reason => throw ImportDestinationException(reason))
      anImport.destFolderId.map(app.service.folder.getById)
    }

  /**
   * Why the files of the Import cannot be sorted any more, when its destination was recycled since it was opened; none for an
   * Import whose destination is live, and for one to Triage. A folder is never deleted, only recycled, so there is no other way
   * to lose one.
   */
  private def lostDestination(anImport: Import): Option[String] =
    anImport.destFolderId
      .filter(app.service.folder.getById(_).isRecycled)
      .map(_ => Const.Msg.Err.DESTINATION_IN_TRASH)

  /**
   * The Import, when it is an upload the context account opened: the only Import the upload routes add a file to or close, so no
   * account sends files into another's upload, whose destination it may not hold Organize for, nor into a Watch folder's scan.
   * Any other Import of the repository is a `ForbiddenException`; one that is not the repository's is not found.
   */
  private def ownUpload(importId: String): Import =
    val found = importDao.getById(importId)
    val isOwn = RequestContext.account.value.flatMap(_.id).exists(found.accountId.contains)
    if found.source != ImportSource.Upload || !isOwn then throw ForbiddenException(Const.Msg.Err.FORBIDDEN_UPLOAD)
    found

  /**
   * Whether the Import takes a file under the path from the context account, before anything of the file is read: a path the
   * uploads refuse (`RelPath.segments`) is a `ValidationException`, an Import that is not the context repository's a
   * `NotFoundException`, one that is not the account's own upload a `ForbiddenException` ([[ownUpload]]), and one marked complete
   * an `IllegalOperationException`. [[upload]] decides for good: an Import can be marked complete between the two.
   */
  def requireTakes(importId: String, relPath: String): Unit =
    RelPath.segments(relPath): Unit
    txManager.asReadOnly {
      if ownUpload(importId).isComplete then throw complete(importId)
    }

  /**
   * Fails a file sent to an upload's Import whose destination is in the Trash, by its path and size alone, before its content is
   * read: the file is added to the Import and failed with the reason its completion would fail with
   * (`Const.Msg.Err.DESTINATION_IN_TRASH`), in one transaction, and returned, so every remaining file of such an upload is
   * recorded and none is staged or run through the pipeline for nothing. None, recording nothing, for an Import whose destination
   * is live or that has none: the file is then staged and uploaded ([[upload]], which fails it the same way should the
   * destination be lost by then). The path and the Import are held to what [[upload]] holds them to.
   */
  def failForLostDestination(importId: String, relPath: String, sizeBytes: Long): Option[ImportFile] =
    RelPath.segments(relPath): Unit
    txManager.withTransaction {
      lostDestination(ownUpload(importId)).map(addFailed(importId, relPath, sizeBytes, _))
    }

  /** Adds a file to the Import and fails it with the reason, in the caller's transaction; the file as failed */
  private def addFailed(importId: String, relPath: String, sizeBytes: Long, reason: String): ImportFile =
    val file = addFile(importId, relPath, sizeBytes)
    settle(file.persistedId, ImportOutcome.Failed, reason = Some(reason))
    logger.info(s"Import file [${file.persistedId}] \"$relPath\" of Import [$importId] is failed on arrival: $reason")
    file.copy(outcome = ImportOutcome.Failed, reason = Some(reason))

  private def complete(importId: String) = IllegalOperationException(s"Import [$importId] is complete and takes no more files")

  /**
   * Takes one uploaded file of an Import, staged, under its relative path: the file is added to the Import, pending, described
   * (`LibraryService.stagedFileToAsset`, under the name that ends the path) and offered to the import queue with its origin, the
   * Import file and the folder names of the path, waiting while the queue is full. The staged file is the pipeline's from then
   * on, and the Import file is returned as it stands.
   *
   * A file that is not added is not staged any longer either, and fails the call: a path the uploads refuse or an empty file
   * (`ValidationException`), an Import that is not the context account's own upload ([[ownUpload]]), and one that is not the
   * repository's or that takes no more files, as [[addFile]] refuses them. A file of an Import whose destination is in the Trash
   * is added and failed at once, saying so, its staged file deleted, and is never offered to the queue ([[lostDestination]]; the
   * upload route asks [[failForLostDestination]] first, so such a file is usually not even staged). Once the file is added,
   * nothing else settles it until the pipeline has it, so a failure from there on is settled here: a file that cannot be
   * described, or that the queue refuses (`QueueRefusedException`), is failed with that as its reason
   * (`PipelineUtils.dropReason`), its staged file deleted, and returned as failed rather than thrown, the file having been
   * delivered and recorded.
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
          lostDestination(ownUpload(importId)) match
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
        val message = s"Import file [${file.persistedId}] \"$relPath\" of Import [$importId] failed before the pipeline: $reason"
        failure match
          // The queue has logged its refusal
          case _: QueueRefusedException => logger.warn(message)
          case unexpected => logger.warn(message, unexpected)
        app.service.staging.discard(staged)
        settle(file.persistedId, ImportOutcome.Failed, reason = Some(reason))
        file.copy(outcome = ImportOutcome.Failed, reason = Some(reason))

  /**
   * Records a file of an upload that the page could not send, after its retries: added to the Import and failed, "Upload failed",
   * in one transaction, under the path and the size the page gives, so the Import's record holds every file of the upload. The
   * path and the Import are held to what [[upload]] holds them to.
   */
  def addFailedFile(importId: String, relPath: String, sizeBytes: Long): ImportFile =
    RelPath.segments(relPath): Unit
    txManager.withTransaction {
      ownUpload(importId): Unit
      addFailed(importId, relPath, sizeBytes, ImportService.UPLOAD_FAILED)
    }

  /**
   * Adds a file to an Import that is still taking files, pending, and counts it. An Import marked complete takes no more
   * (`IllegalOperationException`), and one that is not the context repository's is not found; neither stores the file.
   */
  def addFile(importId: String, relPath: String, sizeBytes: Long): ImportFile =
    txManager.withTransaction {
      // The counter first: the Import's row is held from here, so the Import cannot be marked complete under the new file
      if importDao.addPending(importId) == 0 then
        importDao.getById(importId): Unit
        throw complete(importId)

      val file = fileDao.add(ImportFile(importId = importId, relPath = relPath, sizeBytes = sizeBytes))
      logger.debug(s"Added Import file [${file.persistedId}] \"$relPath\" to Import [$importId]")
      changed(importId)
      file
    }

  /**
   * Gives a pending Import file its outcome and, in the same transaction, moves it from pending to that outcome in its Import's
   * counters, closing the Import when it is marked complete and this was its last pending file. `assetId` is the asset an
   * imported file became, or the asset a duplicate matches, and must be an asset of the context repository; `reason` says why,
   * where there is something to say. A file settled already keeps its first outcome and is counted once. A file, or an asset,
   * that is not the context repository's is a `NotFoundException` that writes nothing.
   */
  def settle(fileId: String, outcome: ImportOutcome, reason: Option[String] = None, assetId: Option[String] = None): Unit =
    require(outcome != ImportOutcome.Pending, "A file settles with an outcome other than pending")

    txManager.withTransaction {
      val file = fileDao.getById(fileId)
      // The file's key to its asset is a single column, so the asset's repository is checked here
      assetId.foreach(app.service.asset.getById)

      if fileDao.settle(fileId, outcome, reason, assetId) == 1 then
        BaseService.requireFound(importDao.tableName, file.importId, importDao.fileSettled(file.importId, outcome)): Unit
        logger.debug(s"Import file [$fileId] \"${file.relPath}\" of Import [${file.importId}] is ${outcome.dbValue}")
        changed(file.importId)
      else logger.warn(s"Import file [$fileId] \"${file.relPath}\" was settled already: left as it is, not ${outcome.dbValue}")
    }

  /**
   * Settles the Import file of a file the import pipeline dropped, by the cause it was dropped for. A duplicate points at the
   * asset of the context repository that has its content, with where that asset is as the reason; when no such asset is there
   * (any more), it has neither. An unsupported media type is unsupported. Anything else is failed, with the cause as the reason
   * (`PipelineUtils.dropReason`).
   */
  def settleDropped(fileId: String, asset: Asset, cause: Option[Throwable]): Unit =
    txManager.withTransaction {
      cause match
        case Some(_: DuplicateException) =>
          val existing = app.service.asset.getByChecksum(asset.checksum)
          settle(fileId, ImportOutcome.Duplicate, reason = existing.map(placeOf(_, fileId)), assetId = existing.flatMap(_.id))
        case Some(_: UnsupportedMediaTypeException) =>
          settle(fileId, ImportOutcome.Unsupported, reason = Some(PipelineUtils.dropReason(cause)))
        case _ => settle(fileId, ImportOutcome.Failed, reason = Some(PipelineUtils.dropReason(cause)))
    }

  /**
   * Where an asset is, for the Import file (`fileId`) of a duplicate of it: Triage, the path of its folder, or the top level of
   * the Library. An asset whose import is not complete yet is still as it was staged, triaged and in no folder, whatever its
   * Import does with it; the pipeline completes a repository's files in the order they came, so such a copy is an earlier file of
   * the duplicate's own Import, and it is named where that Import is headed: its destination, or Triage.
   */
  private def placeOf(asset: Asset, fileId: String): String =
    if !asset.isPipelineProcessed then
      importDao.getById(fileDao.getById(fileId).importId).destFolderId.fold(ImportService.TRIAGE)(pathOf(_))
    else if asset.isTriaged then ImportService.TRIAGE
    else pathOf(asset.folderId)

  /**
   * A folder as an Import file names it, and as the Import history names a destination under its own `separator`: its path, or
   * the top level of the Library for the root
   */
  private def pathOf(folderId: String, separator: String = RelPath.SEPARATOR): String =
    if app.service.folder.isRootFolder(folderId) then ImportService.TOP_LEVEL
    else app.service.folder.getPath(folderId).map(_.name).mkString(separator)

  /**
   * Marks the Import complete: no more files will be added. It closes at once when none of its files is pending, and otherwise
   * when the last of them settles. An Import closed already stays as it is.
   */
  def close(importId: String): Unit =
    txManager.withTransaction {
      BaseService.requireFound(importDao.tableName, importId, importDao.markComplete(importId)): Unit
      logger.info(s"Import [$importId] is complete")
      changed(importId)
    }

  /** [[close]] for the upload routes: the Import is closed by the account that opened it, as an upload ([[ownUpload]]) */
  def closeUpload(importId: String): Unit =
    txManager.withTransaction {
      ownUpload(importId): Unit
      close(importId)
    }

  /**
   * The Import record's startup steps, which `App` runs before anything is offered to the import queue: the Imports a stop
   * interrupted are failed and closed ([[failInterrupted]]), then the Import history is pruned ([[pruneOrLog]]), so a server
   * restarted more often than daily, which never reaches the daily prune, still keeps its history within the Setting.
   */
  def startup(): Unit =
    failInterrupted()
    pruneOrLog()

  /**
   * Startup's step for the Imports a server that stopped left behind, in every repository. Nothing is in the import pipeline when
   * a server starts and staged files are not resumed, so every Import file still pending is failed, "Interrupted by a restart",
   * and every Import still in progress is closed, counting those files as failed.
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
   * The prune of the Import history, in every repository: the closed Imports opened more than `import.history.days` days ago (a
   * Live Setting, read as the prune runs) are deleted with their Import files; the assets they imported stay. At 0 the history is
   * kept forever. An Import in progress is never pruned, however old.
   */
  def prune(): Unit =
    val days = app.settings.current.importHistoryDays
    if days == 0 then logger.debug("The Import history is kept forever: nothing to prune")
    else
      val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days)
      app.service.library.forEachRepository {
        repository =>
          val pruned = txManager.withTransaction(importDao.deleteClosedBefore(cutoff))
          if pruned > 0 then logger.info(s"Pruned $pruned Imports older than $days days. Repo: ${repository.name}")
          else logger.debug(s"No Import older than $days days to prune. Repo: ${repository.name}")
      }

  /**
   * [[prune]] for the startup and the daily schedule, neither of which a failed prune may stop: a failure is logged at ERROR and
   * not thrown
   */
  def pruneOrLog(): Unit =
    try prune()
    catch case NonFatal(e) => logger.error("Pruning the Import history failed", e)

  def getById(importId: String): Import =
    txManager.asReadOnly {
      importDao.getById(importId)
    }

  /** A page of the context repository's Imports with their total: the ones in progress first, then the newest first */
  def list(rpp: Int, page: Int = 1): QueryResult[Import] =
    txManager.asReadOnly {
      importDao.list(rpp, page)
    }

  /**
   * The top of the context repository's Import history, its first `shown` Imports as [[list]] orders them, each with what the
   * history says of it beside its record: the name of the account that delivered it, the path of its destination folder, and
   * whether a restart failed files of it, which only an Import closed with failed files is asked.
   */
  def history(shown: Int): ImportHistory =
    txManager.asReadOnly {
      val listed = importDao.list(shown, page = 1)
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

  /** The Import's files in path order, those of one outcome when `outcome` is given */
  def files(importId: String, outcome: Option[ImportOutcome] = None): List[ImportFile] =
    txManager.asReadOnly {
      // Fails with NotFoundException for an unknown Import, or another repository's
      importDao.getById(importId): Unit
      fileDao.ofImport(importId, outcome)
    }

  /**
   * The Import with its first `shown` files in path order, those of one outcome when `outcome` is given, read in one transaction,
   * so the Import's counters are the numbers of the files the outcomes hold
   */
  def filesShown(importId: String, outcome: Option[ImportOutcome], shown: Int): ImportFiles =
    txManager.asReadOnly {
      ImportFiles(importDao.getById(importId), outcome, fileDao.ofImport(importId, outcome, Some(shown)))
    }

  /**
   * Tells `listener` of every change to an Import from now on, with the repository's ID and the Import as the change committed
   * it: one opened, given a file, a file of it settled, or marked complete. A change that rolls back is not told.
   */
  def onChange(listener: (String, Import) => Unit): Unit = listeners.add(listener): Unit

  /**
   * Tells the listeners that the Import changed, once the transaction that changes it has committed
   * (`TransactionManager.afterCommit`): the Import is read then, so what is told is what was committed, never a state inside a
   * transaction that changes it twice. Changes committed at once may be told out of order, each with an Import at least as far
   * along as its own change left it.
   */
  private def changed(importId: String): Unit =
    val repositoryId = RequestContext.getRepository.persistedId
    txManager.afterCommit {
      val committed = getById(importId)
      listeners.forEach(_(repositoryId, committed))
    }
