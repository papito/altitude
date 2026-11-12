package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.WatchFolderFileRow
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolderFile

object WatchFolderFileDao:
  // How many rows one statement inserts, or names by path: 7 values a row, well within what either engine binds
  private val CHUNK = 500

abstract class WatchFolderFileDao(override val config: Config)
  extends BaseDao[WatchFolderFile]
  with altitude.core.dao.WatchFolderFileDao:

  final override val tableName = "watch_folder_file"

  final override type Row[T[_]] = WatchFolderFileRow[T]
  final override protected def table: Table[Row] = WatchFolderFileRow

  private val F = FieldConst.WatchFolderFile
  private val SEEN = WatchFileState.Seen.dbValue

  private def repositoryId: String = RequestContext.getRepository.persistedId

  // One file of one Watch folder of the context repository, as it is remembered with a size and a modified time
  private val ASIS = s"""
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.REL_PATH} = ?
         AND ${F.SIZE_BYTES} = ?
         AND ${F.MODIFIED_AT} = ?
  """

  override protected def toModel(row: WatchFolderFileRow[Sc]): WatchFolderFile =
    WatchFolderFile(
      id = Option(row.id),
      watchFolderId = row.watchFolderId,
      relPath = row.relPath,
      sizeBytes = row.sizeBytes,
      modifiedAt = row.modifiedAt,
      state = WatchFileState.fromDbValue(row.state),
      attempts = row.attempts,
      lastSeenAt = Some(toLocalDateTime(row.lastSeenAt)),
      createdAt = row.createdAt.map(toLocalDateTime),
      updatedAt = row.updatedAt.map(toLocalDateTime)
    )

  override protected def makeModel(rec: Map[String, AnyRef]): WatchFolderFile =
    WatchFolderFile(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      watchFolderId = rec(F.WATCH_FOLDER_ID).asInstanceOf[String],
      relPath = rec(F.REL_PATH).asInstanceOf[String],
      sizeBytes = getLongField(rec(F.SIZE_BYTES)).get,
      modifiedAt = getLongField(rec(F.MODIFIED_AT)).get,
      state = WatchFileState.fromDbValue(rec(F.STATE).asInstanceOf[String]),
      attempts = getIntField(rec(F.ATTEMPTS)),
      lastSeenAt = getDateTimeField(rec.get(F.LAST_SEEN_AT)),
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )

  override def ofWatchFolder(watchFolderId: String): List[WatchFolderFile] =
    val sql = s"SELECT * FROM $tableName WHERE ${FieldConst.REPO_ID} = ? AND ${F.WATCH_FOLDER_ID} = ?"
    manyBySqlQuery(sql, List(repositoryId, watchFolderId)).map(makeModel)

  override def find(watchFolderId: String, relPath: String): Option[WatchFolderFile] =
    val sql = s"SELECT * FROM $tableName WHERE ${FieldConst.REPO_ID} = ? AND ${F.WATCH_FOLDER_ID} = ? AND ${F.REL_PATH} = ?"
    manyBySqlQuery(sql, List(repositoryId, watchFolderId, relPath)).headOption.map(makeModel)

  // A new file is `seen` and never tried, by the columns' defaults; several rows a statement, since a first scan may find many
  override def addAll(files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit =
    files.grouped(WatchFolderFileDao.CHUNK).foreach {
      chunk =>
        val sql = s"""
          INSERT INTO $tableName (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${F.WATCH_FOLDER_ID}, ${F.REL_PATH}, ${F.SIZE_BYTES},
                                  ${F.MODIFIED_AT}, ${F.LAST_SEEN_AT})
               VALUES ${chunk.map(_ => "(?, ?, ?, ?, ?, ?, ?)").mkString(", ")}
        """

        val seen = nativeUtcTimestamp(seenAt)
        addRecord(
          sql,
          chunk.toList.flatMap {
            file =>
              List(
                file.id.getOrElse(BaseDao.genId),
                repositoryId,
                file.watchFolderId,
                file.relPath,
                file.sizeBytes,
                file.modifiedAt,
                seen)
          }
        )
    }

  override def changed(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, seenAt: OffsetDateTime): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.SIZE_BYTES} = ?, ${F.MODIFIED_AT} = ?, ${F.STATE} = ?, ${F.ATTEMPTS} = 0, ${F.LAST_SEEN_AT} = ?
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.REL_PATH} = ?
    """

    updateByBySql(sql, List(sizeBytes, modifiedAt, SEEN, nativeUtcTimestamp(seenAt), repositoryId, watchFolderId, relPath))

  override def seenAgain(watchFolderId: String, relPaths: Seq[String], seenAt: OffsetDateTime): Int =
    relPaths
      .grouped(WatchFolderFileDao.CHUNK)
      .map {
        chunk =>
          val sql = s"""
            UPDATE $tableName
               SET ${F.LAST_SEEN_AT} = ?
             WHERE ${FieldConst.REPO_ID} = ?
               AND ${F.WATCH_FOLDER_ID} = ?
               AND ${F.REL_PATH} IN (${chunk.map(_ => "?").mkString(", ")})
          """

          updateByBySql(sql, List(nativeUtcTimestamp(seenAt), repositoryId, watchFolderId) ++ chunk)
      }
      .sum

  override def setState(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, state: WatchFileState): Int =
    val sql = s"UPDATE $tableName SET ${F.STATE} = ? $ASIS"
    updateByBySql(sql, List(state.dbValue, repositoryId, watchFolderId, relPath, sizeBytes, modifiedAt))

  // The right-hand sides read the row as it is before this update, so the file that failed `maxAttempts - 1` times is parked
  override def failed(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, maxAttempts: Int): Int =
    val sql = s"""
      UPDATE $tableName
         SET ${F.ATTEMPTS} = ${F.ATTEMPTS} + 1,
             ${F.STATE} = CASE WHEN ${F.ATTEMPTS} + 1 >= ? THEN ? ELSE ? END
      $ASIS
    """

    updateByBySql(
      sql,
      List(
        maxAttempts,
        WatchFileState.Parked.dbValue,
        WatchFileState.Retry.dbValue,
        repositoryId,
        watchFolderId,
        relPath,
        sizeBytes,
        modifiedAt))

  override def reread(watchFolderId: String, states: Set[WatchFileState]): Int =
    if states.isEmpty then return 0

    val sql = s"""
      UPDATE $tableName
         SET ${F.STATE} = ?, ${F.ATTEMPTS} = 0
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.STATE} IN (${states.toList.map(_ => "?").mkString(", ")})
    """

    updateByBySql(sql, List(SEEN, repositoryId, watchFolderId) ++ states.toList.map(_.dbValue))

  override def forgetAll(watchFolderId: String): Int =
    val sql = s"DELETE FROM $tableName WHERE ${FieldConst.REPO_ID} = ? AND ${F.WATCH_FOLDER_ID} = ?"
    updateByBySql(sql, List(repositoryId, watchFolderId))

  override def forgetLastSeenBefore(watchFolderId: String, before: OffsetDateTime): Int =
    val sql = s"""
      DELETE FROM $tableName
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.LAST_SEEN_AT} < ?
    """

    updateByBySql(sql, List(repositoryId, watchFolderId, nativeUtcTimestamp(before)))
