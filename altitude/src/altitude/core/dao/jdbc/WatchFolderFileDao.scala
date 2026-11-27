package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.OffsetDateTime
import scalasql.Sc
import scalasql.Table

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.tables.WatchFolderFileRow
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolder
import altitude.core.models.WatchFolderFile

object WatchFolderFileDao:
  // How many rows one statement inserts: 7 values a row, well within what either engine binds
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

  // A file only as it is remembered, with this size and modified time
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
      createdAt = row.createdAt.map(toLocalDateTime)
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
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT))
    )

  // Streamed, each row straight into the map, so a memory of many files is never also held as a list of rows
  override def memoryOf(watchFolderId: String): Map[String, WatchFolderFile] =
    val sql = s"SELECT * FROM $tableName WHERE ${FieldConst.REPO_ID} = ? AND ${F.WATCH_FOLDER_ID} = ?"
    val memory = Map.newBuilder[String, WatchFolderFile]
    foreachBySqlQuery(sql, List(repositoryId, watchFolderId)) {
      rec =>
        val file = makeModel(rec)
        memory += file.relPath -> file
    }: Unit
    memory.result()

  // A new file is `seen` and never tried, by the columns' defaults; several rows a statement, since a first scan may find many
  override def addAll(files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit =
    // A scan skips such a file (`WatchFolderService.walk`); PostgreSQL's index would refuse the whole statement
    require(!files.exists(file => WatchFolder.isPathTooLong(file.relPath)), "A file path too long for the memory")
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

  override def changed(watchFolderId: String, files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit =
    val sql = s"""
      UPDATE $tableName
         SET ${F.SIZE_BYTES} = ?, ${F.MODIFIED_AT} = ?, ${F.STATE} = ?, ${F.ATTEMPTS} = 0, ${F.LAST_SEEN_AT} = ?
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.REL_PATH} = ?
    """

    val seen = nativeUtcTimestamp(seenAt)
    batchBySql(
      sql,
      files.map(file => List(file.sizeBytes, file.modifiedAt, SEEN, seen, repositoryId, watchFolderId, file.relPath))): Unit

  // The paths bound as one set parameter, as a set of IDs is (`inIdSet`), however many a scan finds unchanged
  override def seenAgain(watchFolderId: String, relPaths: Seq[String], seenAt: OffsetDateTime): Int =
    if relPaths.isEmpty then return 0

    val sql = s"""
      UPDATE $tableName
         SET ${F.LAST_SEEN_AT} = ?
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${F.WATCH_FOLDER_ID} = ?
         AND ${F.REL_PATH} $inIdSet
    """

    updateByBySql(sql, List(nativeUtcTimestamp(seenAt), repositoryId, watchFolderId, idSet(relPaths.toSet)))

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
