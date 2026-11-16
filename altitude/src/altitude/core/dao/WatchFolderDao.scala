package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.WatchFolder

object WatchFolderDao:
  /** A Watch folder of any Library, with the Library it is in */
  case class Placed(watchFolder: WatchFolder, repositoryId: String, repositoryName: String)

/**
 * Scoped to the context repository, except [[everywhere]] (no two Watch folders of the instance may overlap) and [[pauseOwnedBy]]
 * and [[reassignOwner]], which serve an account write.
 */
trait WatchFolderDao extends BaseDao[WatchFolder]:
  /**
   * Makes the transactions that add a Watch folder or change one's path take turns, so no two overlapping paths pass the check at
   * once (`BaseDao.advisoryLock`)
   */
  def lockPaths(): Unit

  /** By name */
  def all(): List[WatchFolder]

  /** Every Watch folder in every Library */
  def everywhere(): List[WatchFolderDao.Placed]

  /** Those whose destination is one of the folders */
  def targeting(folderIds: Set[String]): List[WatchFolder]

  /** Writes what an Admin edits and its owner; how many rows changed */
  def update(watchFolder: WatchFolder): Int

  /** A resumed one has no reason; how many rows changed */
  def setPaused(id: String, isPaused: Boolean, reason: Option[String]): Int

  def setOwner(id: String, accountId: String): Int

  /** Why its directory is unavailable, none when available; how many rows changed */
  def setStatusReason(id: String, reason: Option[String]): Int

  /** Records a scan at `at`, which found the directory available; how many rows changed */
  def scanned(id: String, at: OffsetDateTime): Int

  /** In every Library; how many it paused */
  def pauseOwnedBy(accountId: String, reason: String): Int

  /** In every Library; how many changed owner */
  def reassignOwner(fromAccountId: String, toAccountId: String): Int
