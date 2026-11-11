package altitude.core

import java.sql.Connection
import java.time.Instant

import scala.util.DynamicVariable

import altitude.core.models.AccountType
import altitude.core.models.Permission
import altitude.core.models.Permissions
import altitude.core.models.Repository
import altitude.core.models.User

/** A value of the current thread alone: unlike a `DynamicVariable`, a thread started while it is set does not inherit it */
final class ThreadVariable[T](init: T):
  private val local = ThreadLocal.withInitial[T](() => init)

  def value: T = local.get

  /** Runs `f` with the value set, and puts the previous one back when it ends */
  def withValue[A](newValue: T)(f: => A): A =
    val previous = local.get
    local.set(newValue)
    try f
    finally local.set(previous)

object RequestContext:
  // The connection of the transaction the thread is in, bound by TransactionManager for as long as the transaction lasts
  val conn: ThreadVariable[Option[Connection]] = new ThreadVariable(None)
  val account: DynamicVariable[Option[User]] = new DynamicVariable(None)
  val repository: DynamicVariable[Option[Repository]] = new DynamicVariable(None)
  // The account's Permissions in the repository of the request's path, from its login token (decorators.requireLogin): every one
  // for an Admin, its grant's for anyone else, and none for anyone else when the path names no repository
  val permissions: DynamicVariable[Permissions] = new DynamicVariable(Permissions.none)
  // The account's grants in every Library, Library ID to Permissions, from its login token (decorators.requireLogin): none for an
  // Admin, who holds every Permission by type
  val grants: DynamicVariable[Map[String, Permissions]] = new DynamicVariable(Map.empty)
  // When the request's login token expires (decorators.requireLogin), which the account info dialog shows
  val sessionExpiresAt: DynamicVariable[Option[Instant]] = new DynamicVariable(None)
  val readQueryCount: DynamicVariable[Int] = new DynamicVariable(0)
  val writeQueryCount: DynamicVariable[Int] = new DynamicVariable(0)

  def getConn: Connection = conn.value.getOrElse(throw RuntimeException("No connection in context"))
  def getAccount: User = account.value.getOrElse(throw RuntimeException("No account in context"))
  def getRepository: Repository = repository.value.getOrElse(throw RuntimeException("No repository in context"))

  /**
   * Whether the context's account holds any one of the Permissions in the request's Library (`permissions`): what a template
   * renders an action control by, from the same bitmask the route's decorators read
   */
  def holds(anyOf: Permission*): Boolean = anyOf.exists(permissions.value.has)

  /**
   * Whether the context's account holds any one of the Permissions in some Library, from its login token's grants (`grants`): an
   * Admin holds every one. What reaches beyond the request's Library reads it, such as the Users page, which opens for a holder
   * of Create Users or Create Guests anywhere.
   */
  def holdsAnywhere(anyOf: Permission*): Boolean =
    account.value.exists(_.accountType == AccountType.Admin) || grants.value.values.exists(held => anyOf.exists(held.has))

  /**
   * Whether the context's account may open the Users page and add an account: an Admin, or a holder of Create Users or Create
   * Guests in some Library
   */
  def createsAccounts: Boolean = holdsAnywhere(Permission.CreateUsers, Permission.CreateGuests)

  /** Whether the context's account is a member, an account that is no Guest: it sees Triage, the Trash and Curation */
  def isMember: Boolean = account.value.exists(_.accountType != AccountType.Guest)

  /**
   * Whether the context's account may recycle what a view shows (`Const.Search.View`), the rule its Recycle controls follow:
   * Browse's assets are sorted, which takes Recycle; Triage's are triaged, which takes a Curate Permission (Curate own reaching
   * only the account's own, which the service checks per asset); the Trash's are recycled already
   */
  def recyclesIn(view: String): Boolean = view match
    case Const.Search.View.TRIAGE => holds(Permission.CurateOwn, Permission.CurateAll)
    case Const.Search.View.TRASHBIN => false
    case _ => holds(Permission.Recycle)

  /**
   * Whether the context's account may restore from the Trash, which takes what recycling took: Recycle for an asset recycled from
   * a folder, a Curate Permission for one recycled from Triage. The Trash shows both, so its Restore follows either, and the
   * service refuses per asset.
   */
  def restores: Boolean = holds(Permission.Recycle, Permission.CurateOwn, Permission.CurateAll)

  /** Whether the context's account is a Guest, who sees Browse alone; a request with no account (a signed URL) is none */
  def isGuest: Boolean = account.value.exists(_.accountType == AccountType.Guest)

  // This clears everything EXCEPT for the database-related properties
  def clear(): Unit =
    account.value = None
    repository.value = None
    permissions.value = Permissions.none
    grants.value = Map.empty
    sessionExpiresAt.value = None
    readQueryCount.value = 0
    writeQueryCount.value = 0
