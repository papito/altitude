package altitude.core.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object AccountVersions:

  enum Standing:
    // Minted at the account's current version: its claims hold
    case Current
    // Minted before a change to the account: its claims are rebuilt from the database, or refused where they cannot be
    // replaced
    case Stale
    // Minted before its account's tokens were revoked: refused
    case Revoked

  /** What a listener is told of */
  enum Change:
    case Bumped
    case Revoked

  /** A token is judged and rebuilt against one snapshot, never two reads */
  final case class Snapshot(current: Long, revokedBelow: Long):

    def standing(version: Long): Standing =
      if version < revokedBelow then Standing.Revoked
      else if version < current then Standing.Stale
      else Standing.Current

  /**
   * Tokens are minted under a per-process key (`PasetoService`), so no version outlives its tokens and the map needs no
   * persistence. One map serves every `Altitude` of the process, so the test server's instance sees the tests' changes.
   */
  val processWide: AccountVersions = AccountVersions()

/**
 * Per account, in memory: the current version a token carries when minted, and a revoked-below version. A committed change bumps
 * the current version, so older tokens are rebuilt from the database on their next request; a password change also revokes, so
 * older tokens are refused. An account never changed is at version 0, revoked below 0.
 */
final class AccountVersions:
  private val logger: Logger = LoggerFactory.getLogger(getClass)

  private val entries = ConcurrentHashMap[String, AccountVersions.Snapshot]()
  private val listeners = CopyOnWriteArrayList[(String, AccountVersions.Change) => Unit]()

  /** Read before the account is, so a change committed meanwhile leaves a token minted now stale rather than wrong */
  def snapshot(accountId: String): AccountVersions.Snapshot =
    entries.getOrDefault(accountId, AccountVersions.Snapshot(0, 0))

  def current(accountId: String): Long = snapshot(accountId).current

  /** Called once the change has committed (`TransactionManager.afterCommit`), so a rebuild reads it */
  def bump(accountId: String): Unit =
    entries.compute(
      accountId,
      (_, entry) => Option(entry).fold(AccountVersions.Snapshot(1, 0))(e => e.copy(current = e.current + 1)))
    tell(accountId, AccountVersions.Change.Bumped)

  /** On a password change: the account's tokens are refused, and one minted after this call holds */
  def revoke(accountId: String): Unit =
    entries.compute(
      accountId,
      (_, entry) =>
        val next = Option(entry).fold(0L)(_.current) + 1
        AccountVersions.Snapshot(next, next))
    tell(accountId, AccountVersions.Change.Revoked)

  /** A listener that throws is logged and does not stop the others */
  def onChange(listener: (String, AccountVersions.Change) => Unit): Unit = listeners.add(listener): Unit

  private def tell(accountId: String, change: AccountVersions.Change): Unit =
    listeners.asScala.foreach {
      listener =>
        try listener(accountId, change)
        catch case NonFatal(e) => logger.warn(s"A listener failed on $change of account [$accountId]", e)
    }
