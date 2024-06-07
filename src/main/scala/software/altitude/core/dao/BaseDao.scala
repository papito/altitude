package software.altitude.core.dao

import play.api.libs.json.JsObject
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.models.BaseModel
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

import java.util.regex.Pattern

object BaseDao {
  // this is the valid ID pattern
  private val VALID_ID_PATTERN = Pattern.compile("[a-z0-9]+")

  /**
   * Verify that a DB id to be used is valid
   */
  def verifyId(id: String): Unit = {
    if (id == null) {
      throw new IllegalArgumentException("ID is not defined")
    }

    if (id.length != BaseModel.ID_LEN) {
      throw new IllegalArgumentException(s"ID length should be ${BaseModel.ID_LEN}. Was: [${id.length}]")
    }

    if (!VALID_ID_PATTERN.matcher(id).find()) {
      throw new IllegalArgumentException(s"ID [$id] is not alphanumeric")
    }
  }
}

trait BaseDao {
  val app: AltitudeCoreApp

  /**
   * Add a single record
   * @param json JsObject OR a model
   * @return JsObject of the added record, with ID of the record in the databases
   */
  def add(json: JsObject)(implicit ctx: Context, txId: TransactionId): JsObject

  /**
   * Delete one or more document by query.
   *
   * @return number of documents deleted
   */
  def deleteByQuery(query: Query)(implicit ctx: Context, txId: TransactionId): Int

  /**
   * Delete bt SQL.
   *
   * @return number of documents deleted
   */
  def deleteBySql(sql: String, bindValues: List[Object])(implicit ctx: Context, txId: TransactionId): Int

  /**
   * Gert a single record by ID
   *
   * @param id record id as string
   * @return optional JsObject, which implicitly can be turned into an instance of a concrete domain
   *         model. This method does NOT throw a NotFound error, as it is not assumed it is always
   *         and error.
   */
  def getById(id: String)(implicit ctx: Context, txId: TransactionId): Option[JsObject]

  /**
   * Get multiple records by ID lookup

   * @param id array of document IDs
   */
  def getByIds(id: Set[String])(implicit ctx: Context, txId: TransactionId): List[JsObject]


  /**
   * Get all documents, which will literally crash your machine if you do it by accident on
   * a massive document set
   */
  def getAll(implicit ctx: Context, txId: TransactionId): List[JsObject] = query(new Query()).records

  /**
   * Get multiple documents using a Query
   */
  def query(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult

  /**
   * Delete a document by its ID
   *
   * @return number of documents deleted - 0 or 1
   */
  def deleteById(id: String)(implicit ctx: Context, txId: TransactionId): Int = {
    val q: Query = new Query().add(C.Base.ID -> id)
    deleteByQuery(q)
  }

  /**
   * Update a document by ID with select field values (does not overwrite the document)
   *
   * @param id id of the document to be updated
   * @param data JSON data for the update document, which is NOT used to overwrite the existing one
   * @param fields fields to be updated with new values, taken from <code>data</code>
   *
   * @return number of documents updated - 0 or 1
   */
  def updateById(id: String, data: JsObject, fields: List[String])
                (implicit ctx: Context, txId: TransactionId): Int = {
    val q: Query = new Query().add(C.Base.ID -> id)
    updateByQuery(q, data, fields)
  }

  /**
   * Update multiple documents by query with select field values (does not overwrite the document)
   *
   * @param query the query to find the documents to update
   * @param data JSON data for the update document, which is NOT used to overwrite the existing one
   * @param fields fields to be updated with new values, taken from <code>data</code>
   *
   * @return number of documents updated
   */
  def updateByQuery(query: Query, data: JsObject, fields: List[String])(implicit ctx: Context, txId: TransactionId): Int

  /**
   * Increment an integer field in a table by X
   *
   * @param id record id
   * @param field field to increment
   * @param count the X
   */
  def increment(id: String, field: String, count: Int = 1)(implicit ctx: Context, txId: TransactionId): Unit

  /**
   * Decrement an integer field in a table by X
   *
   * @param id record id
   * @param field field to decrement
   * @param count the X
   */
  def decrement(id: String, field: String, count: Int = 1)(implicit ctx: Context, txId: TransactionId): Unit
}
