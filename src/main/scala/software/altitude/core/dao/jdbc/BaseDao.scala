package software.altitude.core.dao.jdbc

import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.AltitudeAppContext
import software.altitude.core.ConstraintException
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQuery
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.BaseModel
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

import java.util.UUID
import scala.jdk.CollectionConverters._

object BaseDao {
  final def genId: String = UUID.randomUUID.toString
  val totalRecsWindowFunction: String = "count(*) OVER() AS total"
}

abstract class BaseDao {
  val appContext: AltitudeAppContext

  private final val log = LoggerFactory.getLogger(getClass)

  val tableName: String

  protected final def txManager: TransactionManager = appContext.txManager

  protected def columnsForSelect: List[String] = List("*")

  val sqlQueryBuilder: SqlQueryBuilder[Query] = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  def count(recs: List[Map[String, AnyRef]]): Int

  // if supported, DB function to store native JSON data
  protected def jsonFunc: String

  protected def getBooleanField(value: AnyRef): Boolean

  private def queryRunner = new QueryRunner()

  def add(jsonIn: JsObject): JsObject = throw new NotImplementedError("add method must be implemented")

  def getJsonFromColumn(column: AnyRef): JsObject = {
    val jsonStr: String = if (column == null) "{}" else column.asInstanceOf[String]
    Json.parse(jsonStr).as[JsObject]
  }

  def getOneByQuery(q: Query): JsObject = {
    val sqlQuery = sqlQueryBuilder.buildSelectSql(q)
    getOneBySql(sqlQuery.sqlAsString, sqlQuery.bindValues)
  }

  def getOneRawRecordBySql(sql: String, values: List[Any] = List()): Map[String, AnyRef] = {
    val res = getResults(sql, values)

    if (res.isEmpty) {
      throw NotFoundException(s"Cannot find record with SQL: $sql and values: $values")
    }

    if (res.length > 1) {
      throw ConstraintException("getById should return only a single result")
    }

    res.head
  }

  def getOneBySql(sql: String, values: List[Any] = List()): JsObject = {
    val rec = getOneRawRecordBySql(sql, values)
    makeModel(rec)
  }

  def getById(id: String): JsObject = {
    log.debug(s"Getting by ID '$id' from '$tableName'")
    val q: Query = new Query().add(C.Base.ID -> id)
    getOneByQuery(q)
  }

  def deleteById(id: String): Int = {
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
  def updateById(id: String, data: JsObject, fields: List[String]): Int = {
    val q: Query = new Query().add(C.Base.ID -> id)
    updateByQuery(q, data, fields)
  }

  def deleteByQuery(q: Query): Int = {
    log.debug(s"Deleting record by query: $q")
    val fieldPlaceholders: List[String] = q.params.keys.map(_ + " = ?").toList

    val sql = s"""
      DELETE
        FROM $tableName
       WHERE ${fieldPlaceholders.mkString(",")}
      """

    log.debug(s"Delete SQL: $sql, with values: ${q.params.values.toList}")
    val runner = queryRunner
    val numDeleted = runner.update(
      RequestContext.getConn, sql, q.params.values.toList.map(_.asInstanceOf[Object]): _*)
    log.debug(s"Deleted records: $numDeleted")
    numDeleted
  }

  def query(q: Query): QueryResult = {
    this.query(q, sqlQueryBuilder)
  }

  protected def query(query: Query, sqlQueryBuilder: SqlQueryBuilder[Query]): QueryResult = {
    val sqlQuery: SqlQuery = sqlQueryBuilder.buildSelectSql(query)
    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)
    val total: Int = count(recs)

    log.debug(s"Found [$total] records. Retrieved [${recs.length}] records")

    if (recs.nonEmpty) {
      log.debug(recs.map(_.toString()).mkString("\n"))
    }
    QueryResult(records = recs.map{makeModel}, total = total, rpp = query.rpp, sort = query.sort.toList)
  }

  protected def addRecord(jsonIn: JsObject, sql: String, values: List[Any]): Unit = {
   val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object]): _*)
  }

  private def getResults(sql: String, values: List[Any]): List[Map[String, AnyRef]] = {
    val res = queryRunner.query(
      RequestContext.getConn,
      sql, new MapListHandler(),
      values.map(_.asInstanceOf[Object]): _*).asScala.toList

    res.map{_.asScala.toMap[String, AnyRef]}
  }

  protected def manyBySqlQuery(sql: String, values: List[Any] = List()): List[Map[String, AnyRef]] = {
    getResults(sql, values)
  }

  def getByIds(ids: Set[String]): List[JsObject] = {
    if (ids.isEmpty) {
      return List()
    }

    val query = new Query().add(C.Base.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val sqlQuery = sqlQueryBuilder.buildSelectSql(query)

    log.debug(s"SQL: ${sqlQuery.sqlAsString} with values: ${ids.toList}")

    val runner: QueryRunner = new QueryRunner()

    val res = runner.query(
      RequestContext.getConn,
      sqlQuery.sqlAsString,
      new MapListHandler(),
      sqlQuery.bindValues:_*).asScala.toList

    log.debug(s"Found ${res.length} records")
    val recs = res.map{_.asScala.toMap[String, AnyRef]}
    recs.map{makeModel}
  }

  def updateByQuery(q: Query, json: JsObject, fields: List[String]): Int = {
    log.debug(s"Updating record by query $q with data $json for fields: $fields")

    val queryFieldPlaceholders: List[String] = q.params.keys.map(_ + " = ?").toList
    val updateFieldPlaceholders: List[String] = json.fields.filter {
      // extract only the json elements we want to update
      v: (String, JsValue) => fields.contains(v._1)}.map {
      v: (String, JsValue) => s"${v._1} = ?"
    }.toList

    val sql = s"""
      UPDATE $tableName
         SET ${updateFieldPlaceholders.mkString(", ")}
       WHERE ${queryFieldPlaceholders.mkString(",")}
      """

    val dataUpdateValues: List[Any] = json.fields.filter {
      // extract only the json elements we want to update
      v: (String, JsValue) => fields.contains(v._1)}.map {
      // convert the values to string
      v: (String, JsValue) => {
        val jsVal: JsValue = v._2

        jsVal match {
          case JsTrue => true
          case JsFalse => false
          case _ => jsVal.as[String]
        }
      }
    }.toList

    val valuesForAllPlaceholders = dataUpdateValues ::: q.params.values.toList

    val runner = queryRunner
    val numUpdated = runner.update(RequestContext.getConn, sql, valuesForAllPlaceholders.map(_.asInstanceOf[Object]): _*)
    numUpdated
  }

  def increment(id: String, field: String, count: Int = 1): Unit = {
    val sql = s"""
      UPDATE $tableName
         SET $field = $field + $count
       WHERE id = ?
      """
    log.debug(s"INCR SQL: $sql, for $id")

    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, id)
  }

  def decrement(id: String, field: String, count: Int = 1): Unit = {
    increment(id, field, -count)
  }

  /**
   * Implementations should define this method, which returns an optional
   * JSON object which is guaranteed to serialize into a valid model backing this class.
   * JSON can be constructed directly, but best to create a model instance first
   * and return it, triggering implicit conversion.
   */
  protected def makeModel(rec: Map[String, AnyRef]): JsObject

  /**
   *  Given a model and an SQL record, calculate and set properties common to most models
   */
  protected def addCoreAttrs(model: BaseModel, rec: Map[String, AnyRef]): model.type
}
