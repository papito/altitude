package altitude.core

import java.sql.Connection

import scala.util.DynamicVariable

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
  val readQueryCount: DynamicVariable[Int] = new DynamicVariable(0)
  val writeQueryCount: DynamicVariable[Int] = new DynamicVariable(0)

  def getConn: Connection = conn.value.getOrElse(throw RuntimeException("No connection in context"))
  def getAccount: User = account.value.getOrElse(throw RuntimeException("No account in context"))
  def getRepository: Repository = repository.value.getOrElse(throw RuntimeException("No repository in context"))

  // This clears everything EXCEPT for the database-related properties
  def clear(): Unit =
    account.value = None
    repository.value = None
    readQueryCount.value = 0
    writeQueryCount.value = 0
