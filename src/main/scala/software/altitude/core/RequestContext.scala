package software.altitude.core

import software.altitude.core.models.Repository
import software.altitude.core.models.User

import java.sql.Connection
import java.sql.Savepoint
import scala.collection.mutable
import scala.util.DynamicVariable


object RequestContext {
  val conn: DynamicVariable[Option[Connection]] = new DynamicVariable(None)
  val account: DynamicVariable[Option[User]] = new DynamicVariable(None)
  val repository: DynamicVariable[Option[Repository]] = new DynamicVariable(None)
  val savepoints: DynamicVariable[mutable.Stack[Savepoint]] = new DynamicVariable(new mutable.Stack[Savepoint])

  def getConn: Connection = conn.value.getOrElse(throw new RuntimeException("No connection in context"))
  def getAccount: User = account.value.getOrElse(throw new RuntimeException("No account in context"))
  def getRepository: Repository = repository.value.getOrElse(throw new RuntimeException("No repository in context"))

  // This clears everything EXCEPT for the database-related properties
  def clear(): Unit = {
    account.value = None
    repository.value = None
  }
}
