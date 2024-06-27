package software.altitude.core.service

import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.BaseModel
import software.altitude.core.models.Repository
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

import java.sql.Connection

abstract class BaseService[Model <: BaseModel] {
  protected val app: Altitude
  private final val log = LoggerFactory.getLogger(getClass)
  protected val dao: BaseDao
  protected val txManager: TransactionManager = app.txManager

  protected def conn: Connection = {
    // get the connection associated with this request
    RequestContext.conn.value.get
  }

  protected def contextRepo: Repository = {
    // get the connection associated with this request
    RequestContext.repository.value.get
  }

  /**
   * Add a single document
   * @param objIn the document to be added
   * @param queryForDup query to find duplicate documents
   *
   * @return the document, complete with its ID in the database
   */
  def add(objIn: Model, queryForDup: Option[Query] = None): JsObject = {

    val existing = if (queryForDup.isDefined) query(queryForDup.get) else QueryResult.EMPTY

    if (existing.nonEmpty) {
      log.debug(s"Duplicate found for [$objIn] and query: ${queryForDup.get.params}")
      val existingId = (existing.records.head \ C.Base.ID).get.toString
      throw DuplicateException(existingId)
    }

    txManager.withTransaction[JsObject] {
      dao.add(objIn)
    }
  }

  /**
   * Update a document by ID with select field values (does not overwrite the document)
   *
   * @param id id of the document to be updated
   * @param data JSON data for the update document, which is NOT used to overwrite the existing one
   * @param fields fields to be updated with new values, taken from <code>data</code>
   * @param queryForDup query to find a document that will violate a constraint after updating
   *                    this document with this particular data set. For example, prevent
   *                    updating a record with a certain email if that email already exists somewhere
   *                    else. The DAO layer will relegate this to the storage engine to deal with,
   *                    if this is missed.
   *
   * @return number of documents updated - 0 or 1
   */
  def updateById(id: String, data: Model, fields: List[String], queryForDup: Option[Query] = None)
                : Int = {

    val existing = if (queryForDup.isDefined) query(queryForDup.get) else QueryResult.EMPTY

    if (existing.nonEmpty) {
      log.debug(s"Duplicate found for [$data] and query: ${queryForDup.get.params}")
      val existingId = (existing.records.head \ C.Base.ID).get.toString
      throw DuplicateException(existingId)
    }

    txManager.withTransaction[Int] {
      dao.updateById(id, data, fields)
    }
  }

  /**
   * Update multiple documents by query with select field values (does not overwrite the document).
   * Faster but less safe - constraint violations are handled by the store directly.
   *
   * @param query the query
   * @param data JSON data for the update documents, which is NOT used to overwrite the existing one
   * @param fields fields to be updated with new values, taken from <code>data</code>
   * @throws RuntimeException if attempting to update all documents with an empty query
   *
   * @return number of documents updated
   */
  def updateByQuery(query: Query, data: JsObject, fields: List[String])
                   : Int = {
    if (query.params.isEmpty) {
      throw new RuntimeException("Cannot update [ALL] document with an empty Query")
    }

    txManager.withTransaction[Int] {
      dao.updateByQuery(query, data, fields)
    }
  }

  /**
   * Gert a single record by ID
   *
   * @param id record id as string
   *
   * @return the document
   */
  def getById(id: String): JsObject = {
    txManager.asReadOnly[JsObject] {
      dao.getById(id)
    }
  }

  /**
   * Get multiple documents using a Query
   */
  def query(query: Query): QueryResult = {
    txManager.asReadOnly[QueryResult] {
      dao.query(query)
    }
  }

  /**
   * Delete a document by its ID
   *
   * @return number of documents deleted - 0 or 1
   */
  def deleteById(id: String): Int = {
    txManager.withTransaction[Int] {
      dao.deleteById(id)
    }
  }

  /**
   * Delete one or more document by query.
   *
   * @throws RuntimeException if attempting to delete all documents with an empty query
   * @return number of documents deleted
   */
  def deleteByQuery(query: Query): Int = {
    if (query.params.isEmpty) {
      throw new RuntimeException("Cannot delete [ALL] document with an empty Query")
    }

    txManager.withTransaction[Int] {
      dao.deleteByQuery(query)
    }
  }
}
