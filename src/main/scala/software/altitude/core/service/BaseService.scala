package software.altitude.core.service

import java.sql.Connection
import java.sql.SQLException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject

import software.altitude.core.Altitude
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.BaseModel
import software.altitude.core.models.Repository
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.util.Util.getDuplicateExceptionOrSame

abstract class BaseService[Model <: BaseModel] {
  protected val app: Altitude
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  protected val dao: BaseDao
  protected val txManager: TransactionManager = app.txManager

  protected def conn: Connection = {
    // get the connection associated with this request
    RequestContext.conn.value.get
  }

  protected def contextRepo: Repository = {
    // get the connection associated with this request
    RequestContext.getRepository
  }

  def add(objIn: Model): JsObject = {
    txManager.withTransaction[JsObject] {

      try {
        dao.add(objIn)
      } catch {
        // NOTE: duplicate logic in add() and updateById()
        case e: SQLException => throw getDuplicateExceptionOrSame(e)
        case ex: Exception =>
          throw ex
      }
    }
  }

  def updateById(id: String, data: Map[String, Any]): Int = {

    txManager.withTransaction[Int] {
      try {
        dao.updateById(id, data)
      } catch {
        case e: SQLException => throw getDuplicateExceptionOrSame(e)
        case ex: Exception =>
          println(ex.toString)
          throw ex
      }
    }
  }

  def updateByQuery(query: Query, data: Map[String, Any]): Int = {
    if (query.params.isEmpty) {
      throw new RuntimeException("Cannot update [ALL] document with an empty Query")
    }

    txManager.withTransaction[Int] {
      dao.updateByQuery(query, data)
    }
  }

  def getById(id: String): JsObject = {
    txManager.asReadOnly[JsObject] {
      dao.getById(id)
    }
  }

  /** Get a single document using a Query */
  def getOneByQuery(query: Query): JsObject = {
    txManager.asReadOnly[JsObject] {
      dao.getOneByQuery(query)
    }
  }

  /** Get multiple documents using a Query */
  def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.query(query)
    }
  }

  def deleteById(id: String): Int = {
    txManager.withTransaction[Int] {
      dao.deleteById(id)
    }
  }

  def deleteByQuery(query: Query): Int = {
    if (query.params.isEmpty) {
      throw new RuntimeException("Cannot delete [ALL] document with an empty Query")
    }

    txManager.withTransaction[Int] {
      dao.deleteByQuery(query)
    }
  }

  def increment(id: String, field: String, count: Int = 1): Unit = {
    dao.increment(id, field, count)
  }

  def decrement(id: String, field: String, count: Int = 1): Unit = {
    dao.decrement(id, field, count)
  }
}
