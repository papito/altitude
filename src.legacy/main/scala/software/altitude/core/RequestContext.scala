package software.altitude.core

import java.sql.Connection

import scala.util.DynamicVariable

import software.altitude.core.models.Repository
import software.altitude.core.models.User

object RequestContext {
  val conn: DynamicVariable[Option[Connection]] = new DynamicVariable(None)
  val account: DynamicVariable[Option[User]] = new DynamicVariable(None)
  val repository: DynamicVariable[Option[Repository]] = new DynamicVariable(None)
  val readQueryCount: DynamicVariable[Int] = new DynamicVariable(0)
  val writeQueryCount: DynamicVariable[Int] = new DynamicVariable(0)

  def getConn: Connection = conn.value.getOrElse(throw new RuntimeException("No connection in context"))
  def getAccount: User = account.value.getOrElse(throw new RuntimeException("No account in context"))
  def getRepository: Repository = repository.value.getOrElse(throw new RuntimeException("No repository in context"))

  // This clears everything EXCEPT for the database-related properties
  def clear(): Unit = {
    account.value = None
    repository.value = None
    readQueryCount.value = 0
    writeQueryCount.value = 0
  }
}
