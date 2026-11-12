package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.WatchFileState
import altitude.core.models.WatchFolderFile

/** The Watch folders' memory of the files of their directories, in the context repository */
trait WatchFolderFileDao extends BaseDao[WatchFolderFile]:
  /** Everything the Watch folder remembers */
  def ofWatchFolder(watchFolderId: String): List[WatchFolderFile]

  /** Remembers files a scan saw for the first time, each seen at `seenAt` */
  def addAll(files: Seq[WatchFolderFile], seenAt: OffsetDateTime): Unit

  /** A file a scan found changed is a new file: seen at `seenAt` with its new size and modified time, and never tried */
  def changed(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, seenAt: OffsetDateTime): Int

  /** Stamps the files at the paths as found by a scan at `seenAt`; how many rows changed */
  def seenAgain(watchFolderId: String, relPaths: Seq[String], seenAt: OffsetDateTime): Int

  /**
   * Gives the file at the path its state, while it is remembered with that size and modified time: a file that changed since is a
   * new file, which keeps the state it has. How many rows changed.
   */
  def setState(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, state: WatchFileState): Int

  /**
   * Counts a failed import of the file at the path, while it is remembered with that size and modified time: to be read again, or
   * parked once it has failed `maxAttempts` times. How many rows changed.
   */
  def failed(watchFolderId: String, relPath: String, sizeBytes: Long, modifiedAt: Long, maxAttempts: Int): Int

  /** Makes every file in one of the states a file to read again, never tried; how many rows changed */
  def reread(watchFolderId: String, states: Set[WatchFileState]): Int

  /** Forgets everything the Watch folder remembers; how many rows it deleted */
  def forgetAll(watchFolderId: String): Int

  /** Forgets the files of the Watch folder last found before `before`; how many rows it deleted */
  def forgetLastSeenBefore(watchFolderId: String, before: OffsetDateTime): Int
