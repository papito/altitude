package altitude.core.service

import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.IllegalOperationException
import altitude.core.RequestContext
import altitude.core.UnsupportedMediaTypeException
import altitude.core.dao.ImportDao
import altitude.core.dao.ImportFileDao
import altitude.core.models.Asset
import altitude.core.models.Import
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.pipeline.PipelineUtils
import altitude.core.transactions.TransactionManager
import altitude.core.util.QueryResult

/**
 * The Import record: every Import of a Library and every one of its Import files, with what became of each. An Import is opened,
 * given its files as they are offered to the import pipeline (each pending until the pipeline settles it), and closed: marked
 * complete by whoever delivers it, once no more files will come, and closed for good when it is complete and none of its files is
 * pending. Its five counters are its files by outcome, written by the same transaction that sets a file's outcome.
 */
object ImportService:
  // Where the asset a duplicate matches is, when it is in no folder below the root
  private val TRIAGE = "Triage"
  private val TOP_LEVEL = "Top level"
  // Why a file still pending when the server starts is failed
  private val INTERRUPTED = "interrupted by a restart"

class ImportService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val importDao: ImportDao = app.DAO.imports
  private val fileDao: ImportFileDao = app.DAO.importFile
  private val txManager: TransactionManager = app.txManager

  /**
   * Opens an Import delivered by the context account, if there is one: in progress, with no file yet. `name` is the uploaded
   * folder's or the Watch folder's name, none for an upload of files; `destFolderId` the folder its files are sorted into, none
   * for Triage. A destination that is no folder of the context repository is a `NotFoundException` that opens nothing.
   */
  def open(
      source: ImportSource,
      name: Option[String] = None,
      destFolderId: Option[String] = None,
      watchFolderId: Option[String] = None): Import =
    txManager.withTransaction {
      destFolderId.foreach(app.service.folder.getById)
      val opened = importDao.add(
        Import(
          source = source,
          name = name,
          watchFolderId = watchFolderId,
          accountId = RequestContext.account.value.map(_.persistedId),
          destFolderId = destFolderId))
      logger.info(
        s"Opened Import [${opened.persistedId}]: ${source.dbValue}${name.fold("")(n => s" \"$n\"")}. Repo: ${RequestContext.getRepository.persistedId}")
      opened
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
        throw IllegalOperationException(s"Import [$importId] is complete and takes no more files")

      val file = fileDao.add(ImportFile(importId = importId, relPath = relPath, sizeBytes = sizeBytes))
      logger.debug(s"Added Import file [${file.persistedId}] \"$relPath\" to Import [$importId]")
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
          settle(fileId, ImportOutcome.Duplicate, reason = existing.map(placeOf), assetId = existing.flatMap(_.id))
        case Some(_: UnsupportedMediaTypeException) =>
          settle(fileId, ImportOutcome.Unsupported, reason = Some(PipelineUtils.dropReason(cause)))
        case _ => settle(fileId, ImportOutcome.Failed, reason = Some(PipelineUtils.dropReason(cause)))
    }

  /** Where an asset is, for the Import file of a duplicate of it: Triage, the path of its folder, or the top level of the Library */
  private def placeOf(asset: Asset): String =
    if asset.isTriaged then ImportService.TRIAGE
    else if app.service.folder.isRootFolder(asset.folderId) then ImportService.TOP_LEVEL
    else app.service.folder.getPath(asset.folderId).map(_.name).mkString("/")

  /**
   * Marks the Import complete: no more files will be added. It closes at once when none of its files is pending, and otherwise
   * when the last of them settles. An Import closed already stays as it is.
   */
  def close(importId: String): Unit =
    txManager.withTransaction {
      BaseService.requireFound(importDao.tableName, importId, importDao.markComplete(importId)): Unit
      logger.info(s"Import [$importId] is complete")
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
   * a server starts and staged files are not resumed, so every Import file still pending is failed, "interrupted by a restart",
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

  /** The Import's files in path order, those of one outcome when `outcome` is given */
  def files(importId: String, outcome: Option[ImportOutcome] = None): List[ImportFile] =
    txManager.asReadOnly {
      // Fails with NotFoundException for an unknown Import, or another repository's
      importDao.getById(importId): Unit
      fileDao.ofImport(importId, outcome)
    }
