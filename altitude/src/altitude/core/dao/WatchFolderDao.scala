package altitude.core.dao

import java.time.OffsetDateTime

import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.WatchFolder

object WatchFolderDao:
  /** A Watch folder of any Library, with the Library it is in */
  case class Placed(watchFolder: WatchFolder, repositoryId: String, repositoryName: String)

/**
 * The Watch folders of the context repository. Three statements reach every Library's, each by something that is no Library's:
 * [[everywhere]], since no two Watch folders of the instance may overlap, and [[pauseOwnedBy]] and [[reassignOwner]], by an
 * account, in the transaction of the account write they serve.
 */
trait WatchFolderDao extends BaseDao[WatchFolder]:
  /**
   * Makes the transactions that add a Watch folder or change one's path take turns until they end, so the second checks its path
   * against the one the first wrote, and no two overlapping ones pass the check at once; see `BaseDao.advisoryLock`
   */
  def lockPaths(): Unit

  /** The context repository's Watch folders, by name */
  def all(): List[WatchFolder]

  /** Every Watch folder of the instance, in every Library */
  def everywhere(): List[WatchFolderDao.Placed]

  /** Those of the context repository whose destination is one of the folders */
  def targeting(folderIds: Set[String]): List[WatchFolder]

  /** Writes what an Admin edits of it (name, path, destination, deletion) and its owner; how many rows changed */
  def update(watchFolder: WatchFolder): Int

  /** Pauses or resumes it, with why it is paused, if anything says; a resumed one has no reason. How many rows changed */
  def setPaused(id: String, isPaused: Boolean, reason: Option[String]): Int

  /** Gives it an owner; how many rows changed */
  def setOwner(id: String, accountId: String): Int

  /** Says why its directory is unavailable, or that it is available (none); how many rows changed */
  def setStatusReason(id: String, reason: Option[String]): Int

  /** Records a walk of its directory at `at`, which found it available; how many rows changed */
  def scanned(id: String, at: OffsetDateTime): Int

  /** Pauses every Watch folder the account owns, in every Library, saying why; how many it paused */
  def pauseOwnedBy(accountId: String, reason: String): Int

  /** Gives every Watch folder of one account, in every Library, to another; how many changed owner */
  def reassignOwner(fromAccountId: String, toAccountId: String): Int
