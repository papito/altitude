package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.apache.commons.dbutils.BasicRowProcessor
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.RowProcessor
import org.apache.commons.dbutils.handlers.MapListHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import altitude.core.{ Const => C }
import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.jdbc.querybuilder.SqlQuery
import altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import altitude.core.models.BaseModel
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SortValue

object BaseDao:
  final def genId: String = UUID.randomUUID.toString
  val totalRecsWindowFunction: String = "count(*) OVER() AS total"

  private def incrReadQueryCount(): Unit =
    RequestContext.readQueryCount.value = RequestContext.readQueryCount.value + 1

  def incrWriteQueryCount(): Unit =
    RequestContext.writeQueryCount.value = RequestContext.writeQueryCount.value + 1

abstract class BaseDao[Model <: BaseModel]:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  val config: Config
  protected def txManager: TransactionManager = TransactionManager(config)

  val tableName: String
  protected def columnsForSelect: List[String] = List("*")

  val sqlQueryBuilder: SqlQueryBuilder[Query] = new SqlQueryBuilder[Query](columnsForSelect, tableName)

  def count(recs: List[Map[String, AnyRef]]): Int

  // if supported, DB function to store native JSON data
  protected def jsonFunc: String

  protected val exifDateTimeFormatterPattern: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

  protected def nativeBool(value: Boolean): Any

  /** Bind value for a wall-clock timestamp column (no zone): stored and read back verbatim, never converted through an instant */
  protected def nativeLocalDateTime(value: LocalDateTime): Any

  /** Bind value for an instant column: the UTC moment, independent of the JVM or server zone */
  protected def nativeUtcTimestamp(value: OffsetDateTime): Any

  /** Converts result set rows to maps; engines override it to read temporal columns without an instant round trip */
  protected def rowProcessor: RowProcessor = new BasicRowProcessor()

  protected def getBooleanField(value: AnyRef): Boolean

  // Aggregates such as COUNT(*) come back as Long on Postgres and Integer on SQLite
  protected def getIntField(value: AnyRef): Int = value match
    case i: java.lang.Integer => i
    case l: java.lang.Long => l.toInt
    case _ => throw IllegalArgumentException(s"Invalid type for integer field: $value")

  protected def getNextVal(tableName: String): AnyRef

  private def queryRunner = new QueryRunner()

  protected def getDataSourceType: String = config.getString(C.Conf.DB_ENGINE)

  protected val forUpdate: String

  def add(modelIn: Model): Model = throw NotImplementedError("add method must be implemented")

  def getJsonFromColumn(column: AnyRef): ujson.Obj =
    val jsonStr: String = if column == null then "{}" else column.toString
    ujson.read(jsonStr).asInstanceOf[ujson.Obj]

  def getOneByQuery(q: Query): Model =
    val sqlQuery = sqlQueryBuilder.buildSelectSql(q)
    getOneBySql(sqlQuery.sqlAsString, sqlQuery.bindValues)

  def executeAndGetOne(sql: String, values: List[Any] = List()): Map[String, AnyRef] =
    val res = executeAndGetMany(sql, values)

    if res.isEmpty then throw NotFoundException(s"Cannot find record with SQL: $sql and values: $values")

    if res.length > 1 then throw ConstraintException("getById should return only a single result")

    res.head

  def getOneBySql(sql: String, values: List[Any] = List()): Model =
    val rec = executeAndGetOne(sql, values)
    makeModel(rec)

  def getById(id: String): Model =
    logger.debug(s"Getting by ID '$id' from '$tableName'")
    val q: Query = new Query().add(FieldConst.ID -> id)
    getOneByQuery(q)

  def deleteById(id: String): Int =
    val q: Query = new Query().add(FieldConst.ID -> id)
    deleteByQuery(q)

  def updateById(id: String, data: Map[String, Any]): Int =
    val q: Query = new Query().add(FieldConst.ID -> id)
    updateByQuery(q, data)

  def deleteByQuery(q: Query): Int =
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
    val numDeleted = runner.update(RequestContext.getConn, sql, q.params.values.toList.map(_.asInstanceOf[Object])*)
    logger.debug(s"Deleted records: $numDeleted")
    numDeleted

  def query(q: Query): QueryResult[Model] =
    this.query(q, sqlQueryBuilder)

  protected def query(query: Query, sqlQueryBuilder: SqlQueryBuilder[Query]): QueryResult[Model] =
    val sqlQuery: SqlQuery = sqlQueryBuilder.buildSelectSql(query)
    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)
    val total: Int = count(recs)
    logger.debug(s"Found [$total] records. Retrieved [${recs.length}] records")
    QueryResult(records = recs.map(makeModel), total = total, rpp = query.rpp, sort = query.sort)

  protected def addRecord(sql: String, values: List[Any]): Unit =
    BaseDao.incrWriteQueryCount()
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)

  private def executeAndGetMany(sql: String, values: List[Any]): List[Map[String, AnyRef]] =
    BaseDao.incrReadQueryCount()
    logger.debug(s"SELECT SQL: $sql with values: $values")
    val res =
      queryRunner
        .query(RequestContext.getConn, sql, new MapListHandler(rowProcessor), values.map(_.asInstanceOf[Object])*)
        .asScala
        .toList
    res.map(_.asScala.toMap[String, AnyRef])

  def manyBySqlQuery(sql: String, values: List[Any] = List()): List[Map[String, AnyRef]] =
    executeAndGetMany(sql, values)

  def getByIds(ids: Set[String]): List[Model] =
    if ids.isEmpty then return List()
    BaseDao.incrReadQueryCount()
    val query = new Query().add(FieldConst.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val sqlQuery = sqlQueryBuilder.buildSelectSql(query)
    logger.debug(s"SELECT SQL: ${sqlQuery.sqlAsString} with values: ${ids.toList}")
    val runner: QueryRunner = new QueryRunner()
    val res =
      runner
        .query(RequestContext.getConn, sqlQuery.sqlAsString, new MapListHandler(rowProcessor), sqlQuery.bindValues*)
        .asScala
        .toList
    logger.debug(s"Found ${res.length} records")
    val recs = res.map(_.asScala.toMap[String, AnyRef])
    recs.map(makeModel)

  def updateByQuery(q: Query, data: Map[String, Any]): Int =
    BaseDao.incrWriteQueryCount()
    val sqlQuery = sqlQueryBuilder.buildUpdateSql(q, data)
    logger.debug(s"UPDATE SQL: ${sqlQuery.sqlAsString} with bind values ${sqlQuery.bindValues}")
    val runner = queryRunner
    val numUpdated = runner.update(RequestContext.getConn, sqlQuery.sqlAsString, sqlQuery.bindValues*)
    logger.debug("Updated records: " + numUpdated)
    numUpdated

  def updateByBySql(sql: String, values: List[Any]): Int =
    BaseDao.incrWriteQueryCount()
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)

  def getFloatListByJsonKey(jsonStr: String, key: String): List[Float] =
    val json = ujson.read(jsonStr)
    json(key).arr.map(_.num.toFloat).toList

  def makeCsv[T](values: List[T]): String =
    values.map(_.toString).mkString(",")

  def loadCsv[T: ClassTag](csv: String): List[T] =
    if csv == null || csv.isEmpty then return List()
    csv.split(",").map(_.asInstanceOf[T]).toList

  def increment(id: String, field: String, count: Int = 1): Unit =
    BaseDao.incrWriteQueryCount()
    val sql = s"""
      UPDATE $tableName
         SET $field = $field + $count
       WHERE id = ?
      """
    logger.debug(s"INCR SQL: $sql, $id")
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, id)

  def decrement(id: String, field: String, count: Int = 1): Unit =
    increment(id, field, -count)

  protected def makeModel(rec: Map[String, AnyRef]): Model

  protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime]

  /** A calendar-day column, as the engine's day expression returns it */
  protected def getDateField(value: AnyRef): LocalDate

  /** A sort-key column, typed so it can be bound back for comparison exactly as stored */
  protected def getSortValueField(value: AnyRef): SortValue
