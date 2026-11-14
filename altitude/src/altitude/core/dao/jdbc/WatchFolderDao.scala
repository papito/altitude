package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.WatchFolderDao.Placed
import altitude.core.dao.sql.tables.WatchFolderRow
import altitude.core.models.WatchFolder

abstract class WatchFolderDao(override val config: Config) extends BaseDao[WatchFolder] with altitude.core.dao.WatchFolderDao:

  final override val tableName = "watch_folder"

  final override type Row[T[_]] = WatchFolderRow[T]
  final override protected def table: Table[Row] = WatchFolderRow

  private val F = FieldConst.WatchFolder
  private val REPOSITORY_NAME = "repository_name"

  private def repositoryId: String = RequestContext.getRepository.persistedId

  /** This moment as an instant column takes it: bound in UTC, to the microsecond */
  private def now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

  override protected def toModel(row: WatchFolderRow[Sc]): WatchFolder =
    WatchFolder(
      id = Option(row.id),
      name = row.name,
      path = row.path,
      destFolderId = row.destFolderId,
      deletesImported = row.deletesImported,
      isPaused = row.isPaused,
      accountId = row.accountId,
      statusReason = row.statusReason,
      lastScanAt = row.lastScanAt.map(toLocalDateTime),
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): WatchFolder =
    def text(field: String): Option[String] = Option(rec(field)).map(_.toString)

    WatchFolder(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(F.NAME).asInstanceOf[String],
      path = rec(F.PATH).asInstanceOf[String],
      destFolderId = text(F.DEST_FOLDER_ID),
      deletesImported = getBooleanField(rec(F.DELETES_IMPORTED)),
      isPaused = getBooleanField(rec(F.IS_PAUSED)),
      accountId = rec(F.ACCOUNT_ID).asInstanceOf[String],
      statusReason = text(F.STATUS_REASON),
      lastScanAt = getDateTimeField(rec.get(F.LAST_SCAN_AT)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def add(model: WatchFolder): WatchFolder =
    val sql = s"""
        INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${F.NAME}, ${F.PATH}, ${F.DEST_FOLDER_ID},
                                ${F.DELETES_IMPORTED}, ${F.IS_PAUSED}, ${F.ACCOUNT_ID})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """

    val id = model.id.getOrElse(BaseDao.genId)
    addRecord(
      sql,
      List(
        id,
        repositoryId,
        model.name,
        model.path,
        model.destFolderId.orNull,
        nativeBool(model.deletesImported),
        nativeBool(model.isPaused),
        model.accountId)
    )
    model.copy(id = Some(id))

  override def lockPaths(): Unit = advisoryLock(tableName)

  override def all(): List[WatchFolder] =
    val sql = s"SELECT * FROM $tableName WHERE ${FieldConst.REPO_ID} = ? ORDER BY ${F.NAME}, ${FieldConst.ID}"
    manyBySqlQuery(sql, List(repositoryId)).map(makeModel)

  // No Library in it: a Watch folder's directory may overlap no other's, whichever Library that one is in
  override def everywhere(): List[Placed] =
    val sql = s"""
      SELECT w.*, r.name AS $REPOSITORY_NAME
        FROM $tableName w
        JOIN repository r ON r.${FieldConst.ID} = w.${FieldConst.REPO_ID}
       ORDER BY w.${F.PATH}
    """

    manyBySqlQuery(sql).map {
      rec => Placed(makeModel(rec), rec(FieldConst.REPO_ID).asInstanceOf[String], rec(REPOSITORY_NAME).asInstanceOf[String])
    }

  override def targeting(folderIds: Set[String]): List[WatchFolder] =
    if folderIds.isEmpty then return Nil

    val sql = s"""
      SELECT *
        FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.DEST_FOLDER_ID} IN (${folderIds.toList.map(_ => "?").mkString(", ")})
       ORDER BY ${F.NAME}, ${FieldConst.ID}
    """

    manyBySqlQuery(sql, repositoryId :: folderIds.toList).map(makeModel)

  override def update(watchFolder: WatchFolder): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.NAME} = ?, ${F.PATH} = ?, ${F.DEST_FOLDER_ID} = ?, ${F.DELETES_IMPORTED} = ?, ${F.ACCOUNT_ID} = ?,
             ${FieldConst.UPDATED_AT} = ?
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
    """

    updateByBySql(
      sql,
      List(
        watchFolder.name,
        watchFolder.path,
        watchFolder.destFolderId.orNull,
        nativeBool(watchFolder.deletesImported),
        watchFolder.accountId,
        nativeUtcTimestamp(now),
        watchFolder.persistedId,
        repositoryId
      )
    )

  override def setPaused(id: String, isPaused: Boolean, reason: Option[String]): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.IS_PAUSED} = ?, ${F.STATUS_REASON} = ?, ${FieldConst.UPDATED_AT} = ?
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
    """

    updateByBySql(sql, List(nativeBool(isPaused), reason.orNull, nativeUtcTimestamp(now), id, repositoryId))

  override def setOwner(id: String, accountId: String): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.ACCOUNT_ID} = ?, ${FieldConst.UPDATED_AT} = ?
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
    """

    updateByBySql(sql, List(accountId, nativeUtcTimestamp(now), id, repositoryId))

  // What a scan finds is no edit of the Watch folder: `updated_at` stays
  override def setStatusReason(id: String, reason: Option[String]): Int =
    val sql = s"UPDATE $tableName SET ${F.STATUS_REASON} = ? WHERE ${FieldConst.ID} = ? AND ${FieldConst.REPO_ID} = ?"
    updateByBySql(sql, List(reason.orNull, id, repositoryId))

  override def scanned(id: String, at: OffsetDateTime): Int =
    val sql = s"UPDATE $tableName SET ${F.LAST_SCAN_AT} = ? WHERE ${FieldConst.ID} = ? AND ${FieldConst.REPO_ID} = ?"
    updateByBySql(sql, List(nativeUtcTimestamp(at), id, repositoryId))

  // No Library in it: an account's Watch folders are reached by the account, in the transaction that deactivates or demotes it
  override def pauseOwnedBy(accountId: String, reason: String): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.IS_PAUSED} = ?, ${F.STATUS_REASON} = ?, ${FieldConst.UPDATED_AT} = ?
       WHERE ${F.ACCOUNT_ID} = ?
    """

    updateByBySql(sql, List(nativeBool(true), reason, nativeUtcTimestamp(now), accountId))

  // No Library in it: an account's Watch folders are reached by the account, in the transaction that deletes it
  override def reassignOwner(fromAccountId: String, toAccountId: String): Int =
    val sql = s"UPDATE $tableName SET ${F.ACCOUNT_ID} = ?, ${FieldConst.UPDATED_AT} = ? WHERE ${F.ACCOUNT_ID} = ?"
    updateByBySql(sql, List(toAccountId, nativeUtcTimestamp(now), fromAccountId))
