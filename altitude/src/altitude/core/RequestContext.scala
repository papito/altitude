package altitude.core

import java.sql.Connection
import java.time.Instant

import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User

/**
 * A value of the current thread alone: unlike a `DynamicVariable`, a thread started while it is set does not inherit it, so a
 * pooled thread reads its initial value until work on it binds one
 */
class ThreadVariable[T](init: T):
  protected val local: ThreadLocal[T] = ThreadLocal.withInitial[T](() => init)

  def value: T = local.get

  /** Runs `f` with the value set, and puts the previous one back when it ends */
  def withValue[A](newValue: T)(f: => A): A =
    val previous = local.get
    local.set(newValue)
    try f
    finally local.set(previous)

/**
 * A value of a request: a [[ThreadVariable]] its decorators also set outright, since the request holds it until it ends, when
 * they clear it ([[RequestContext.clear]]). What lasts only as long as a call (a connection, a transaction's setup) is a plain
 * `ThreadVariable`, which nothing sets but `withValue`.
 */
final class RequestVariable[T](init: T) extends ThreadVariable[T](init):
  def value_=(newValue: T): Unit = local.set(newValue)

/**
 * The request's state, each value its thread's alone ([[ThreadVariable]]): a thread a request starts, or a pooled thread it hands
 * work to, inherits none of it. Work there that forgets to bind reads no Library, and reading one throws ([[getRepository]])
 * rather than answer the Library of whichever request created the thread; it reads no account, no Permission and no grant, so a
 * rule that reads them refuses, and a path that needs an account reads it with [[getAccount]], which throws. Work handed to
 * another thread binds what it reads (`PipelineUtils.withContext`).
 */
object RequestContext:
  // The connection of the thread's transaction, bound by TransactionManager while it lasts
  val conn: ThreadVariable[Option[Connection]] = new ThreadVariable(None)
  val account: RequestVariable[Option[User]] = new RequestVariable(None)
  val repository: RequestVariable[Option[Repository]] = new RequestVariable(None)
  // The account's Permissions in the path's repository, from its login token: every one for an Admin, its grant's for anyone
  // else, and none when the path names no repository
  val permissions: RequestVariable[Permissions] = new RequestVariable(Permissions.none)
  // Library ID to the account's Permissions there, from its login token: empty for an Admin, who holds every one by type
  val grants: RequestVariable[Map[String, Permissions]] = new RequestVariable(Map.empty)
  val sessionExpiresAt: RequestVariable[Option[Instant]] = new RequestVariable(None)
  // The read statements the thread has run, which tests count; reset with the rest of the request context
  val readQueryCount: RequestVariable[Int] = new RequestVariable(0)

  def getConn: Connection = conn.value.getOrElse(throw RuntimeException("No connection in context"))
  def getAccount: User = account.value.getOrElse(throw RuntimeException("No account in context"))
  def getRepository: Repository = repository.value.getOrElse(throw RuntimeException("No repository in context"))

  /** Whether the account holds any one of the Permissions in the request's Library, the same bitmask the decorators read */
  def holds(anyOf: Permission*): Boolean = anyOf.exists(permissions.value.has)

  /** Whether the account holds any one of the Permissions in some Library; an Admin holds every one */
  def holdsAnywhere(anyOf: Permission*): Boolean =
    account.value.exists(_.accountType == AccountType.Admin) || grants.value.values.exists(held => anyOf.exists(held.has))

  /** Whether the account may open the Users page and add an account */
  def createsAccounts: Boolean = holdsAnywhere(Permission.CreateUsers, Permission.CreateGuests)

  /** A member is any account but a Guest: it sees Triage, the Trash and Curation */
  def isMember: Boolean = account.value.exists(_.accountType != AccountType.Guest)

  /**
   * Whether the account may recycle what a view shows, the rule its Recycle controls follow. Triage takes a Curate Permission
   * (the service checks Curate own per asset); the Trash's assets are recycled already.
   */
  def recyclesIn(view: String): Boolean = view match
    case Const.Search.View.TRIAGE => holds(Permission.CurateOwn, Permission.CurateAll)
    case Const.Search.View.TRASHBIN => false
    case _ => holds(Permission.Recycle)

  /**
   * Restoring takes what recycling took: Recycle for an asset from a folder, a Curate Permission for one from Triage. The Trash
   * shows both, so its Restore follows either, and the service refuses per asset.
   */
  def restores: Boolean = holds(Permission.Recycle, Permission.CurateOwn, Permission.CurateAll)

  /** A Guest sees Browse alone; a request with no account (a signed URL) is not a Guest */
  def isGuest: Boolean = account.value.exists(_.accountType == AccountType.Guest)

  // Clears everything but the connection
  def clear(): Unit =
    account.value = None
    repository.value = None
    permissions.value = Permissions.none
    grants.value = Map.empty
    sessionExpiresAt.value = None
    readQueryCount.value = 0
