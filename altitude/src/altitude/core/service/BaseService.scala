package altitude.core.service

import altitude.core.Altitude
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.BaseModel
import altitude.core.models.Repository
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util.newDuplicateExceptionOrRethrow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.SQLException


abstract class BaseService[Model <: BaseModel]:
  protected val app: Altitude
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: BaseDao[Model]
  protected val txManager: TransactionManager = app.txManager

  protected def conn: Connection =
    // get the connection associated with this request
    RequestContext.conn.value.get

  protected def contextRepo: Repository =
    // get the repository associated with this request
    RequestContext.getRepository

  def add(objIn: Model): Model =
    txManager.withTransaction {
      try
        dao.add(objIn)
      catch
        case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
        case ex: Exception =>
          throw ex
    }

  def updateById(id: String, data: Map[String, Any]): Int =
    txManager.withTransaction {
      try
        dao.updateById(id, data)
      catch
        case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
        case ex: Exception =>
          throw ex
    }

  def updateByQuery(query: Query, data: Map[String, Any]): Int =
    if query.params.isEmpty then
      throw new RuntimeException("Cannot update [ALL] document with an empty Query")

    // should not update ALL repositories by default
    val repoScopedQuery = query.withRepository()

    txManager.withTransaction {
      dao.updateByQuery(repoScopedQuery, data)
    }

  def getById(id: String): Model =
    txManager.asReadOnly {
      dao.getById(id)
    }

  /** Get a single document using a Query */
  def getOneByQuery(query: Query): Model =
    txManager.asReadOnly {
      dao.getOneByQuery(query)
    }

  /** Get multiple documents using a Query */
  def query(query: Query): QueryResult[Model] =
    val repoScopedQuery = query.withRepository()

    txManager.asReadOnly {
      dao.query(repoScopedQuery)
    }

  def deleteById(id: String): Int =
    txManager.withTransaction {
      dao.deleteById(id)
    }

  def deleteByQuery(query: Query): Int =
    if query.params.isEmpty then
      throw new RuntimeException("Cannot delete [ALL] document with an empty Query")

    // should not delete from ALL repositories by default
    val repoScopedQuery = query.withRepository()

    txManager.withTransaction {
      dao.deleteByQuery(repoScopedQuery)
    }

  def increment(id: String, field: String, count: Int = 1): Unit =
    dao.increment(id, field, count)

  def decrement(id: String, field: String, count: Int = 1): Unit =
    dao.decrement(id, field, count)
