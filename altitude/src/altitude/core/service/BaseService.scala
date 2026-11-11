package altitude.core.service

import java.sql.Connection
import java.sql.SQLException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.Altitude
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.BaseModel
import altitude.core.models.Repository
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.Util.newDuplicateExceptionOrRethrow

object BaseService:

  /**
   * A write by ID that changed nothing found nothing: another repository's ID is answered as an unknown one. For a service that
   * calls a DAO's write directly, outside the generic ones.
   */
  def requireFound(tableName: String, id: String, count: Int): Int =
    if count == 0 then throw NotFoundException(s"Cannot find record in '$tableName' with ID [$id]")
    count

/**
 * The generic reads and writes over one DAO. The DAO limits each to the context repository unless its table has none
 * (`BaseDao.repositoryScoped`), so another repository's ID is not found here, exactly as an unknown one.
 */
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
      try dao.add(objIn)
      catch
        case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
        case ex: Exception =>
          throw ex
    }

  /** `NotFoundException` when no row has the ID: none at all, or none of the context repository */
  def updateById(id: String, data: Map[String, Any]): Int =
    txManager.withTransaction {
      val updated =
        try dao.updateById(id, data)
        catch case e: SQLException => throw newDuplicateExceptionOrRethrow(e)
      BaseService.requireFound(dao.tableName, id, updated)
    }

  def updateByQuery(query: Query, data: Map[String, Any]): Int =
    txManager.withTransaction {
      dao.updateByQuery(query, data)
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
    txManager.asReadOnly {
      dao.query(query)
    }

  /** `NotFoundException` when no row has the ID: none at all, or none of the context repository */
  def deleteById(id: String): Int =
    txManager.withTransaction {
      BaseService.requireFound(dao.tableName, id, dao.deleteById(id))
    }

  def deleteByQuery(query: Query): Int =
    txManager.withTransaction {
      dao.deleteByQuery(query)
    }

  /** `NotFoundException` when no row has the ID: none at all, or none of the context repository */
  def increment(id: String, field: String, count: Int = 1): Unit =
    txManager.withTransaction {
      BaseService.requireFound(dao.tableName, id, dao.increment(id, field, count)): Unit
    }
