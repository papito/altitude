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
  // The connection of the thread's transaction, bound by TransactionManager while it lasts
  val conn: ThreadVariable[Option[Connection]] = new ThreadVariable(None)
  val account: DynamicVariable[Option[User]] = new DynamicVariable(None)
  val repository: DynamicVariable[Option[Repository]] = new DynamicVariable(None)
  // The account's Permissions in the path's repository, from its login token: every one for an Admin, its grant's for anyone
  // else, and none when the path names no repository
  val permissions: DynamicVariable[Permissions] = new DynamicVariable(Permissions.none)
  // Library ID to the account's Permissions there, from its login token: empty for an Admin, who holds every one by type
  val grants: DynamicVariable[Map[String, Permissions]] = new DynamicVariable(Map.empty)
  val sessionExpiresAt: DynamicVariable[Option[Instant]] = new DynamicVariable(None)
  val readQueryCount: DynamicVariable[Int] = new DynamicVariable(0)
  val writeQueryCount: DynamicVariable[Int] = new DynamicVariable(0)

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
    writeQueryCount.value = 0
