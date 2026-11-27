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

  /**
   * The Watch folder as it is committed, its row locked until the transaction ends (`FOR NO KEY UPDATE`: its key stays, so an
   * Import or a file of it referencing it waits for nothing); none when it is not in the context repository
   */
  def lock(id: String): Option[WatchFolder]

  /** By name, as the Libraries are listed (`NaturalOrder`), then by ID */
  def all(): List[WatchFolder]

  /** Every Watch folder in every Library */
  def everywhere(): List[WatchFolderDao.Placed]

  /** Those whose destination is one of the folders, in the order of [[all]] */
  def targeting(folderIds: Set[String]): List[WatchFolder]

  /**
   * Writes what an Admin edits and its owner, and clears its status reason, for the next scan to find again; whether it is paused
   * stays as the row has it. How many rows changed.
   */
  def update(watchFolder: WatchFolder): Int

  /** A resumed one has no reason; how many rows changed */
  def setPaused(id: String, isPaused: Boolean, reason: Option[String]): Int

  /**
   * Pauses it for the reason only while the account still owns it and it is not paused: a scan that read an owner who can no
   * longer own it pauses nothing an Admin has since made theirs or paused. How many rows changed.
   */
  def pauseForOwner(id: String, accountId: String, reason: String): Int

  def setOwner(id: String, accountId: String): Int

  /**
   * Why its directory is unavailable, none when available, written only while it is not paused, since a paused one's reason is
   * its pause's, and only where it differs. How many rows changed.
   */
  def setStatusReason(id: String, reason: Option[String]): Int

  /**
   * Records a scan at `at`, which found the directory available at `path`, only while that is still the Watch folder's path; how
   * many rows changed
   */
  def scanned(id: String, at: OffsetDateTime, path: String): Int

  /** In every Library; how many it paused */
  def pauseOwnedBy(accountId: String, reason: String): Int

  /** In every Library; how many changed owner */
  def reassignOwner(fromAccountId: String, toAccountId: String): Int
