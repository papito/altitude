package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.ImportRow
import altitude.core.models.Import
import altitude.core.models.ImportOutcome
import altitude.core.models.ImportSource
import altitude.core.util.QueryResult
import altitude.core.util.Util

abstract class ImportDao(override val config: Config) extends BaseDao[Import] with altitude.core.dao.ImportDao:

  final override val tableName = "import"

  final override type Row[T[_]] = ImportRow[T]
  final override protected def table: Table[Row] = ImportRow

  private val TOTAL = "total"
  private val PENDING = ImportOutcome.Pending.dbValue
  private val FAILED = ImportOutcome.Failed.dbValue

  private def repositoryId: String = RequestContext.getRepository.persistedId

  override protected def toModel(row: ImportRow[Sc]): Import =
    Import(
      id = Option(row.id),
      source = ImportSource.fromDbValue(row.source),
      name = row.name,
      watchFolderId = row.watchFolderId,
      accountId = row.accountId,
      destFolderId = row.destFolderId,
      expectedFiles = row.expectedFiles,
      isComplete = row.isComplete,
      closedAt = row.closedAt.map(toLocalDateTime),
      pending = row.pending,
      imported = row.imported,
      duplicate = row.duplicate,
      unsupported = row.unsupported,
      failed = row.failed,
      createdAt = row.createdAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): Import =
    def text(field: String): Option[String] = Option(rec(field)).map(_.toString)
    def counter(outcome: ImportOutcome): Int = getIntField(rec(outcome.dbValue))

    Import(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      source = ImportSource.fromDbValue(rec(FieldConst.Import.SOURCE).asInstanceOf[String]),
      name = text(FieldConst.Import.NAME),
      watchFolderId = text(FieldConst.Import.WATCH_FOLDER_ID),
      accountId = text(FieldConst.Import.ACCOUNT_ID),
      destFolderId = text(FieldConst.Import.DEST_FOLDER_ID),
      expectedFiles = Option(rec(FieldConst.Import.EXPECTED_FILES)).map(getIntField),
      isComplete = getBooleanField(rec(FieldConst.Import.IS_COMPLETE)),
      closedAt = getDateTimeField(rec.get(FieldConst.Import.CLOSED_AT)),
      pending = counter(ImportOutcome.Pending),
      imported = counter(ImportOutcome.Imported),
      duplicate = counter(ImportOutcome.Duplicate),
      unsupported = counter(ImportOutcome.Unsupported),
      failed = counter(ImportOutcome.Failed),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT))
    )

  override def add(model: Import): Import =
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Import.SOURCE}, ${FieldConst.Import.NAME},
                                ${FieldConst.Import.WATCH_FOLDER_ID}, ${FieldConst.Import.ACCOUNT_ID},
                                ${FieldConst.Import.DEST_FOLDER_ID}, ${FieldConst.Import.EXPECTED_FILES},
                                ${FieldConst.CREATED_AT})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """

    val id = model.id.getOrElse(BaseDao.genId)
    // Bound rather than left to the engine default: the history is listed and pruned by it
    val createdAt = Util.utcNow

    addRecord(
      sql,
      List(
        id,
        repositoryId,
        model.source.dbValue,
        model.name.orNull,
        model.watchFolderId.orNull,
        model.accountId.orNull,
        model.destFolderId.orNull,
        model.expectedFiles.map(Int.box).orNull,
        nativeUtcTimestamp(createdAt)
      )
    )
    model.copy(id = Some(id), createdAt = Some(toLocalDateTime(createdAt)))

  /**
   * The statements of [[list]]: the few Imports in progress by `import_03`, the closed ones newest first by `import_01`, and the
   * count. Each reads in index order, so the listing costs the Imports it shows; one statement ordered by whether an Import is
   * closed could use neither index and would sort the whole history.
   */
  def listStatements(shown: Int): List[(String, List[Any])] =
    def newest(closed: String) = s"""
      SELECT *
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Import.CLOSED_AT} $closed
       ORDER BY ${FieldConst.CREATED_AT} DESC, ${FieldConst.ID}
       LIMIT ?
    """

    List(
      (newest("IS NULL"), List(repositoryId, shown)),
      (newest("IS NOT NULL"), List(repositoryId, shown)),
      (s"SELECT COUNT(*) AS $TOTAL FROM $tableName WHERE ${FieldConst.REPO_ID} = ?", List(repositoryId))
    )

  override def list(shown: Int): QueryResult[Import] =
    val List(inProgress, closed, count) = listStatements(shown).map(manyBySqlQuery(_, _)): @unchecked

    QueryResult(
      // The closed ones fill what the ones in progress leave of `shown`
      records = (inProgress ++ closed).take(shown).map(makeModel),
      total = getIntField(count.head(TOTAL)),
      rpp = shown,
      sort = Nil
    )

  override def addPending(importId: String): Int =
    val sql = s"""
      UPDATE $tableName
         SET $PENDING = $PENDING + 1
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Import.IS_COMPLETE} = ?
    """

    updateByBySql(sql, List(importId, repositoryId, nativeBool(false)))

  // The right-hand sides read the row as it is before this update, so the last pending file is the one that finds `pending = 1`
  override def fileSettled(importId: String, outcome: ImportOutcome): Int =
    require(outcome != ImportOutcome.Pending, "A file settles with an outcome other than pending")
    val counter = outcome.dbValue

    val sql = s"""
      UPDATE $tableName
         SET $PENDING = $PENDING - 1,
             $counter = $counter + 1,
             ${FieldConst.Import.CLOSED_AT} =
               CASE WHEN ${FieldConst.Import.IS_COMPLETE} = ? AND $PENDING = 1 THEN ? ELSE ${FieldConst.Import.CLOSED_AT} END
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
    """

    updateByBySql(sql, List(nativeBool(true), nativeUtcTimestamp(Util.utcNow), importId, repositoryId))

  // An Import closed already keeps the moment it closed at
  override def markComplete(importId: String): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.Import.IS_COMPLETE} = ?,
             ${FieldConst.Import.CLOSED_AT} =
               CASE WHEN $PENDING = 0 AND ${FieldConst.Import.CLOSED_AT} IS NULL THEN ? ELSE ${FieldConst.Import.CLOSED_AT} END
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
    """

    updateByBySql(sql, List(nativeBool(true), nativeUtcTimestamp(Util.utcNow), importId, repositoryId))

  override def closeInterrupted(): Int =
    val sql = s"""
      UPDATE $tableName
         SET $FAILED = $FAILED + $PENDING,
             $PENDING = 0,
             ${FieldConst.Import.IS_COMPLETE} = ?,
             ${FieldConst.Import.CLOSED_AT} = ?
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Import.CLOSED_AT} IS NULL
    """

    updateByBySql(sql, List(nativeBool(true), nativeUtcTimestamp(Util.utcNow), repositoryId))

  // Watch folder Imports only, by `import_02`
  override def countsByWatchFolder(): Map[String, (Int, Int)] =
    val imported = ImportOutcome.Imported.dbValue
    val sql = s"""
      SELECT ${FieldConst.Import.WATCH_FOLDER_ID}, SUM($imported) AS $imported, SUM($FAILED) AS $FAILED
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Import.WATCH_FOLDER_ID} IS NOT NULL
       GROUP BY ${FieldConst.Import.WATCH_FOLDER_ID}
    """

    manyBySqlQuery(sql, List(repositoryId)).map {
      rec =>
        rec(FieldConst.Import.WATCH_FOLDER_ID).toString -> (
          getLongField(rec(imported)).get.toInt,
          getLongField(rec(FAILED)).get.toInt)
    }.toMap

  // The Import files go with their Import, by the key's cascade
  override def deleteClosedBefore(cutoff: OffsetDateTime): Int =
    val sql = s"""
      DELETE FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Import.CLOSED_AT} IS NOT NULL
         AND ${FieldConst.CREATED_AT} < ?
    """

    updateByBySql(sql, List(repositoryId, nativeUtcTimestamp(cutoff)))
