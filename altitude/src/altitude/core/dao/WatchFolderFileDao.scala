package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolderFile

/** The Watch folders' memory of the files of their directories, in the context repository */
trait WatchFolderFileDao extends BaseDao[WatchFolderFile]:
  /** Its whole memory, by path */
  def memoryOf(watchFolderId: String): Map[String, WatchFolderFile]

  /** Files a scan saw for the first time */
  def addAll(files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit

  /** Files a scan found changed are new files, each with its new size and modified time, never tried: one batch */
  def changed(watchFolderId: String, files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit

  /** Returns how many rows changed */
  def seenAgain(watchFolderId: String, relPaths: Seq[String], seenAt: OffsetDateTime): Int

  /** Only while the file is remembered with that size and modified time: one changed since keeps its state. How many rows changed. */
  def setState(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, state: WatchFileState): Int

  /**
   * Counts a failed import while the file is remembered with that size and modified time: read again, or parked after
   * `maxAttempts` failures. How many rows changed.
   */
  def failed(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, maxAttempts: Int): Int

  /** Makes every file in one of the states one to read again, never tried; how many rows changed */
  def reread(watchFolderId: String, states: Set[WatchFileState]): Int

  /** Returns how many rows it deleted */
  def forgetAll(watchFolderId: String): Int

  /** Returns how many rows it deleted */
  def forgetLastSeenBefore(watchFolderId: String, before: OffsetDateTime): Int
