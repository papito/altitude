package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import play.api.libs.json.JsValue.jsValueToJsLookup
import play.api.libs.json._
import software.altitude.core.ConstraintException
import software.altitude.core.NotFoundException
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.querybuilder.SqlQuery
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.BaseModel
import software.altitude.core.models.Field
import software.altitude.core.transactions.TransactionManager
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult

import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

object BaseDao {
  final def genId: String = UUID.randomUUID.toString
  val totalRecsWindowFunction: String = "count(*) OVER() AS total"

  private def incrReadQueryCount(): Unit = {
    RequestContext.readQueryCount.value = RequestContext.readQueryCount.value + 1
  }

  def incrWriteQueryCount(): Unit = {
    RequestContext.writeQueryCount.value = RequestContext.writeQueryCount.value + 1
  }
}

abstract class BaseDao {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)

  val config: Config
  protected def txManager: TransactionManager = TransactionManager(config)

  val tableName: String
  protected def columnsForSelect: List[String] = List("*")

  val sqlQueryBuilder: SqlQueryBuilder[Query] = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  def count(recs: List[Map[String, AnyRef]]): Int

  // if supported, DB function to store native JSON data
  protected def jsonFunc: String

  protected def getBooleanField(value: AnyRef): Boolean

  private def queryRunner = new QueryRunner()

  def add(jsonIn: JsObject): JsObject = throw new NotImplementedError("add method must be implemented")

  def getJsonFromColumn(column: AnyRef): JsObject = {
    val jsonStr: String = if (column == null) "{}" else column.toString
    Json.parse(jsonStr).as[JsObject]
  }

  def getOneByQuery(q: Query): JsObject = {
    val sqlQuery = sqlQueryBuilder.buildSelectSql(q)
    getOneBySql(sqlQuery.sqlAsString, sqlQuery.bindValues)
  }

  def executeAndGetOne(sql: String, values: List[Any] = List()): Map[String, AnyRef] = {
    val res = executeAndGetMany(sql, values)

    if (res.isEmpty) {
      throw NotFoundException(s"Cannot find record with SQL: $sql and values: $values")
    }

    if (res.length > 1) {
      throw ConstraintException("getById should return only a single result")
    }

    res.head
  }

  def getOneBySql(sql: String, values: List[Any] = List()): JsObject = {
    val rec = executeAndGetOne(sql, values)
    makeModel(rec)
  }

  def getById(id: String): JsObject = {
    logger.debug(s"Getting by ID '$id' from '$tableName'")
    val q: Query = new Query().add(Field.ID -> id)
    getOneByQuery(q)
  }

  def deleteById(id: String): Int = {
    val q: Query = new Query().add(Field.ID -> id)
    deleteByQuery(q)
  }

  def updateById(id: String, data: Map[String, Any]): Int = {
    val q: Query = new Query().add(Field.ID -> id)
    updateByQuery(q, data)
  }

  // FIXME: this should be part of query builder, a hack for now
  def deleteByQuery(q: Query): Int = {
    logger.debug(s"Deleting record by query: $q")

    BaseDao.incrWriteQueryCount()

    val fieldPlaceholders: List[String] = q.params.keys.map(_ + " = ?").toList

    val sql = s"""
      DELETE
        FROM $tableName
       WHERE ${fieldPlaceholders.mkString(",")}
      """

    logger.debug(s"Delete SQL: $sql, with values: ${q.params.values.toList}")
    val runner = queryRunner
    val numDeleted = runner.update(
      RequestContext.getConn, sql, q.params.values.toList.map(_.asInstanceOf[Object]): _*)
    logger.debug(s"Deleted records: $numDeleted")
    numDeleted
  }

  def query(q: Query): QueryResult = {
    this.query(q, sqlQueryBuilder)
  }

  protected def query(query: Query, sqlQueryBuilder: SqlQueryBuilder[Query]): QueryResult = {
    val sqlQuery: SqlQuery = sqlQueryBuilder.buildSelectSql(query)
    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)
    val total: Int = count(recs)

    logger.debug(s"Found [$total] records. Retrieved [${recs.length}] records")

//    if (recs.nonEmpty) {
//      logger.debug(recs.map(_.toString()).mkString("\n"))
//    }
    QueryResult(records = recs.map{makeModel}, total = total, rpp = query.rpp, sort = query.sort)
  }

  protected def addRecord(jsonIn: JsObject, sql: String, values: List[Any]): Unit = {
    BaseDao.incrWriteQueryCount()

    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object]): _*)
  }

  private def executeAndGetMany(sql: String, values: List[Any]): List[Map[String, AnyRef]] = {
    BaseDao.incrReadQueryCount()

    logger.debug(s"SELECT SQL: $sql with values: $values")

    val res = queryRunner.query(
      RequestContext.getConn,
      sql, new MapListHandler(),
      values.map(_.asInstanceOf[Object]): _*).asScala.toList

    res.map{_.asScala.toMap[String, AnyRef]}
  }

  protected def manyBySqlQuery(sql: String, values: List[Any] = List()): List[Map[String, AnyRef]] = {
    executeAndGetMany(sql, values)
  }

  def getByIds(ids: Set[String]): List[JsObject] = {
    if (ids.isEmpty) {
      return List()
    }

    BaseDao.incrReadQueryCount()

    val query = new Query().add(Field.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val sqlQuery = sqlQueryBuilder.buildSelectSql(query)

    logger.debug(s"SELECT SQL: ${sqlQuery.sqlAsString} with values: ${ids.toList}")

    val runner: QueryRunner = new QueryRunner()

    val res = runner.query(
      RequestContext.getConn,
      sqlQuery.sqlAsString,
      new MapListHandler(),
      sqlQuery.bindValues:_*).asScala.toList

    logger.debug(s"Found ${res.length} records")
    val recs = res.map{_.asScala.toMap[String, AnyRef]}
    recs.map{makeModel}
  }

  def updateByQuery(q: Query, data: Map[String, Any]): Int = {
    BaseDao.incrWriteQueryCount()

    val sqlQuery = sqlQueryBuilder.buildUpdateSql(q, data)
    logger.debug(s"UPDATE SQL: ${sqlQuery.sqlAsString} with bind values ${sqlQuery.bindValues}")

    val runner = queryRunner

    val numUpdated = runner.update(RequestContext.getConn, sqlQuery.sqlAsString, sqlQuery.bindValues:_*)
    logger.debug("Updated records: " + numUpdated)
    numUpdated
  }

  protected def updateByBySql(sql: String, values: List[Any]): Int = {
    BaseDao.incrWriteQueryCount()

    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object]): _*)
  }

  def getFloatListByJsonKey(jsonStr: String, key: String): List[Float] = {
    val json = Json.parse(jsonStr)
    (json \ key).as[List[Float]]
  }

  def makeCsv[T](values: List[T]): String = {
    values.map(_.toString).mkString(",")
  }

  def loadCsv[T:ClassTag](csv: String): List[T] = {
    if (csv == null || csv.isEmpty) {
      return List()
    }
    csv.split(",").map(_.asInstanceOf[T]).toList
  }

  def increment(id: String, field: String, count: Int = 1): Unit = {
    BaseDao.incrWriteQueryCount()

    val sql = s"""
      UPDATE $tableName
         SET $field = $field + $count
       WHERE id = ?
      """
    logger.debug(s"INCR SQL: $sql, $id")

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
