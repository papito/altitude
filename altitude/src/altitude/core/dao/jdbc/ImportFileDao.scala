package altitude.core.dao.jdbc

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.ImportFileRow
import altitude.core.models.ImportFile
import altitude.core.models.ImportOutcome

abstract class ImportFileDao(override val config: Config) extends BaseDao[ImportFile] with altitude.core.dao.ImportFileDao:

  final override val tableName = "import_file"

  final override type Row[T[_]] = ImportFileRow[T]
  final override protected def table: Table[Row] = ImportFileRow

  private def repositoryId: String = RequestContext.getRepository.persistedId

  override protected def toModel(row: ImportFileRow[Sc]): ImportFile =
    ImportFile(
      id = Option(row.id),
      importId = row.importId,
      relPath = row.relPath,
      outcome = ImportOutcome.fromDbValue(row.outcome),
      reason = row.reason,
      assetId = row.assetId,
      sizeBytes = row.sizeBytes,
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): ImportFile =
    def text(field: String): Option[String] = Option(rec(field)).map(_.toString)

    ImportFile(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      importId = rec(FieldConst.ImportFile.IMPORT_ID).asInstanceOf[String],
      relPath = rec(FieldConst.ImportFile.REL_PATH).asInstanceOf[String],
      outcome = ImportOutcome.fromDbValue(rec(FieldConst.ImportFile.OUTCOME).asInstanceOf[String]),
      reason = text(FieldConst.ImportFile.REASON),
      assetId = text(FieldConst.ImportFile.ASSET_ID),
      sizeBytes = getLongField(rec(FieldConst.ImportFile.SIZE_BYTES)).get,
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  // A new file is pending, by the column's default
  override def add(file: ImportFile): ImportFile =
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.ImportFile.IMPORT_ID},
                                ${FieldConst.ImportFile.REL_PATH}, ${FieldConst.ImportFile.SIZE_BYTES})
             VALUES (?, ?, ?, ?, ?)
    """

    val id = file.id.getOrElse(BaseDao.genId)
    addRecord(sql, List(id, repositoryId, file.importId, file.relPath, file.sizeBytes))
    file.copy(id = Some(id))

  override def ofImport(importId: String, outcome: Option[ImportOutcome], limit: Option[Int]): List[ImportFile] =
    val sql = s"""
      SELECT *
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.ImportFile.IMPORT_ID} = ?
         ${outcome.fold("")(_ => s"AND ${FieldConst.ImportFile.OUTCOME} = ?")}
       ORDER BY ${FieldConst.ImportFile.REL_PATH}, ${FieldConst.ID}
       ${limit.fold("")(_ => "LIMIT ?")}
    """

    manyBySqlQuery(sql, List(repositoryId, importId) ++ outcome.map(_.dbValue) ++ limit).map(makeModel)

  // Reads the files of that outcome alone, by the index on a file's Import and outcome
  override def havingReason(importIds: Set[String], outcome: ImportOutcome, reason: String): Set[String] =
    if importIds.isEmpty then return Set.empty

    val sql = s"""
      SELECT DISTINCT ${FieldConst.ImportFile.IMPORT_ID}
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.ImportFile.IMPORT_ID} IN (${importIds.toList.map(_ => "?").mkString(", ")})
         AND ${FieldConst.ImportFile.OUTCOME} = ?
         AND ${FieldConst.ImportFile.REASON} = ?
    """

    manyBySqlQuery(sql, (repositoryId :: importIds.toList) ++ List(outcome.dbValue, reason))
      .map(_(FieldConst.ImportFile.IMPORT_ID).asInstanceOf[String])
      .toSet

  // Only a pending file takes an outcome, so of two writers settling one file the second changes nothing
  override def settle(fileId: String, outcome: ImportOutcome, reason: Option[String], assetId: Option[String]): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.ImportFile.OUTCOME} = ?,
             ${FieldConst.ImportFile.REASON} = ?,
             ${FieldConst.ImportFile.ASSET_ID} = ?
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.ImportFile.OUTCOME} = ?
    """

    updateByBySql(sql, List(outcome.dbValue, reason.orNull, assetId.orNull, fileId, repositoryId, ImportOutcome.Pending.dbValue))

  // The pending files are reached through the Imports in progress, which are few, by the index on a file's Import and outcome
  override def failPending(reason: String): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${FieldConst.ImportFile.OUTCOME} = ?,
             ${FieldConst.ImportFile.REASON} = ?
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.ImportFile.OUTCOME} = ?
         AND ${FieldConst.ImportFile.IMPORT_ID} IN (
           SELECT ${FieldConst.ID}
             FROM import
            WHERE ${FieldConst.REPO_ID} = ?
              AND ${FieldConst.Import.CLOSED_AT} IS NULL)
    """

    updateByBySql(sql, List(ImportOutcome.Failed.dbValue, reason, repositoryId, ImportOutcome.Pending.dbValue, repositoryId))
