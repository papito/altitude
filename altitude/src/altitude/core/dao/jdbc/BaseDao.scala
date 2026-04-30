package altitude.core.dao.jdbc

import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.jdbc.querybuilder.SqlQuery
import altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import altitude.core.transactions.TransactionManager
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.Const as C
import com.typesafe.config.Config
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.jdk.CollectionConverters.*
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

  protected def getBooleanField(value: AnyRef): Boolean

  protected def getNextVal(tableName: String): AnyRef

  private def queryRunner = new QueryRunner()

  protected def getDataSourceType: String = config.getString(C.Conf.DB_ENGINE)

  protected val forUpdate: String

  def add(jsonIn: ujson.Obj): ujson.Obj = throw new NotImplementedError("add method must be implemented")

  def getJsonFromColumn(column: AnyRef): ujson.Obj = {
    val jsonStr: String = if (column == null) "{}" else column.toString
    ujson.read(jsonStr).asInstanceOf[ujson.Obj]
  }

  def getOneByQuery(q: Query): ujson.Obj = {
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

  def getOneBySql(sql: String, values: List[Any] = List()): ujson.Obj = {
    val rec = executeAndGetOne(sql, values)
    makeModel(rec)
  }

  def getById(id: String): ujson.Obj = {
    logger.debug(s"Getting by ID '$id' from '$tableName'")
    val q: Query = new Query().add(FieldConst.ID -> id)
    getOneByQuery(q)
  }

  def deleteById(id: String): Int = {
    val q: Query = new Query().add(FieldConst.ID -> id)
    deleteByQuery(q)
  }

  def updateById(id: String, data: Map[String, Any]): Int = {
    val q: Query = new Query().add(FieldConst.ID -> id)
    updateByQuery(q, data)
  }

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
    val numDeleted = runner.update(RequestContext.getConn, sql, q.params.values.toList.map(_.asInstanceOf[Object])*)
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
    QueryResult(records = recs.map(makeModel), total = total, rpp = query.rpp, sort = query.sort)
  }

  protected def addRecord(jsonIn: ujson.Obj, sql: String, values: List[Any]): Unit = {
    BaseDao.incrWriteQueryCount()
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
  }

  private def executeAndGetMany(sql: String, values: List[Any]): List[Map[String, AnyRef]] = {
    BaseDao.incrReadQueryCount()
    logger.debug(s"SELECT SQL: $sql with values: $values")
    val res =
      queryRunner.query(RequestContext.getConn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object])*).asScala.toList
    res.map(_.asScala.toMap[String, AnyRef])
  }

  def manyBySqlQuery(sql: String, values: List[Any] = List()): List[Map[String, AnyRef]] = {
    executeAndGetMany(sql, values)
  }

  def getByIds(ids: Set[String]): List[ujson.Obj] = {
    if (ids.isEmpty) {
      return List()
    }
    BaseDao.incrReadQueryCount()
    val query = new Query().add(FieldConst.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val sqlQuery = sqlQueryBuilder.buildSelectSql(query)
    logger.debug(s"SELECT SQL: ${sqlQuery.sqlAsString} with values: ${ids.toList}")
    val runner: QueryRunner = new QueryRunner()
    val res =
      runner.query(RequestContext.getConn, sqlQuery.sqlAsString, new MapListHandler(), sqlQuery.bindValues*).asScala.toList
    logger.debug(s"Found ${res.length} records")
    val recs = res.map(_.asScala.toMap[String, AnyRef])
    recs.map(makeModel)
  }

  def updateByQuery(q: Query, data: Map[String, Any]): Int = {
    BaseDao.incrWriteQueryCount()
    val sqlQuery = sqlQueryBuilder.buildUpdateSql(q, data)
    logger.debug(s"UPDATE SQL: ${sqlQuery.sqlAsString} with bind values ${sqlQuery.bindValues}")
    val runner = queryRunner
    val numUpdated = runner.update(RequestContext.getConn, sqlQuery.sqlAsString, sqlQuery.bindValues*)
    logger.debug("Updated records: " + numUpdated)
    numUpdated
  }

  def updateByBySql(sql: String, values: List[Any]): Int = {
    BaseDao.incrWriteQueryCount()
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
  }

  def getFloatListByJsonKey(jsonStr: String, key: String): List[Float] = {
    val json = ujson.read(jsonStr)
    json(key).arr.map(_.num.toFloat).toList
  }

  def makeCsv[T](values: List[T]): String = {
    values.map(_.toString).mkString(",")
  }

  def loadCsv[T: ClassTag](csv: String): List[T] = {
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

  protected def makeModel(rec: Map[String, AnyRef]): ujson.Obj

  protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime]
}
