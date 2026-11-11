package altitude.core.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object AccountVersions:

  /** Where a token minted at some version of its account stands */
  enum Standing:
    // Minted at the account's current version: its claims hold
    case Current
    // Minted before a change to the account: its claims are rebuilt from the database, or refused where they cannot be replaced
    case Stale
    // Minted before its account's tokens were revoked: refused
    case Revoked

  /** What happened to an account's versions, which a listener is told of */
  enum Change:
    // `bump`: the account changed, and its tokens are rebuilt on their next request
    case Bumped
    // `revoke`: the account's tokens are refused
    case Revoked

  /** An account's versions as read at one moment: a token is judged and rebuilt against one snapshot, never two reads */
  final case class Snapshot(current: Long, revokedBelow: Long):

    /** Where a token minted at `version` stands */
    def standing(version: Long): Standing =
      if version < revokedBelow then Standing.Revoked
      else if version < current then Standing.Stale
      else Standing.Current

  /**
   * The map of the process. A token is minted under a key of the process (`PasetoService`), so no version outlives the tokens it
   * describes, and the map needs no persistence. One map serves every `Altitude` of the process, as one database does: the test
   * server's instance sees the changes the tests' own instance makes.
   */
  val processWide: AccountVersions = AccountVersions()

/**
 * Per account, in memory: its current version, which a token carries as it is minted, and its revoked-below version. A change to
 * an account bumps the current version once it commits, so a token minted before it is stale and rebuilt from the database on its
 * next request; a password change also revokes, so a token minted before it is refused. An account never changed is at version 0,
 * revoked below 0. Listeners are told of each change once the map has it (the import status sockets of the account close).
 */
final class AccountVersions:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val entries = ConcurrentHashMap[String, AccountVersions.Snapshot]()
  private val listeners = CopyOnWriteArrayList[(String, AccountVersions.Change) => Unit]()

  /**
   * The account's versions now. The current one is what a token minted now carries: read before the account is, so a change
   * committed meanwhile leaves the token stale rather than wrong.
   */
  def snapshot(accountId: String): AccountVersions.Snapshot =
    entries.getOrDefault(accountId, AccountVersions.Snapshot(0, 0))

  /** The version a token minted now carries */
  def current(accountId: String): Long = snapshot(accountId).current

  /**
   * The account changed: its tokens are rebuilt from the database on their next request. Called once the change has committed
   * (`TransactionManager.afterCommit`), so a rebuild reads it.
   */
  def bump(accountId: String): Unit =
    entries.compute(
      accountId,
      (_, entry) => Option(entry).fold(AccountVersions.Snapshot(1, 0))(e => e.copy(current = e.current + 1)))
    tell(accountId, AccountVersions.Change.Bumped)

  /** The account's tokens are refused from now on, and one minted after this call holds: a password change */
  def revoke(accountId: String): Unit =
    entries.compute(
      accountId,
      (_, entry) =>
        val next = Option(entry).fold(0L)(_.current) + 1
        AccountVersions.Snapshot(next, next))
    tell(accountId, AccountVersions.Change.Revoked)

  /** Tells `listener` of every change from now on; one that throws is logged at WARN and does not stop the others */
  def onChange(listener: (String, AccountVersions.Change) => Unit): Unit = listeners.add(listener): Unit

  private def tell(accountId: String, change: AccountVersions.Change): Unit =
    listeners.asScala.foreach {
      listener =>
        try listener(accountId, change)
        catch case NonFatal(e) => logger.warn(s"A listener failed on $change of account [$accountId]", e)
    }
