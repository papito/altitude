package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID
import org.apache.commons.dbutils.BasicRowProcessor
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.RowProcessor
import org.apache.commons.dbutils.handlers.MapListHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import scalasql.Column
import scalasql.Sc
import scalasql.Table
import scalasql.core.Expr
import scalasql.core.Queryable
import scalasql.dialects.Dialect

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import altitude.core.{ Const => C }
import altitude.core.ConstraintException
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.DynamicAssignments
import altitude.core.dao.sql.DynamicFilter
import altitude.core.models.BaseModel
import altitude.core.transactions.TransactionManager
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SortDirection

object BaseDao:
  final def genId: String = UUID.randomUUID.toString

  private def incrReadQueryCount(): Unit =
    RequestContext.readQueryCount.value = RequestContext.readQueryCount.value + 1

  def incrWriteQueryCount(): Unit =
    RequestContext.writeQueryCount.value = RequestContext.writeQueryCount.value + 1

abstract class BaseDao[Model <: BaseModel]:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  val config: Config
  protected def txManager: TransactionManager = TransactionManager(config)

  val tableName: String

  /** The one remaining hand-written `SELECT` that names its columns is `RepositoryDao.getAll`; everywhere else [[table]] does */
  protected def columnsForSelect: List[String] = List("*")

  /** The ScalaSql row class behind this DAO's table */
  type Row[T[_]]

  protected def table: Table[Row]

  /**
   * A row as the model wants it. This is the typed twin of [[makeModel]]: the two must agree for as long as both a typed and a
   * hand-written SQL path can return the same record.
   */
  protected def toModel(row: Row[Sc]): Model

  // if supported, DB function to store native JSON data
  protected def jsonFunc: String

  protected def nativeBool(value: Boolean): Any

  /** Bind value for a wall-clock timestamp column (no zone): stored and read back verbatim, never converted through an instant */
  protected def nativeLocalDateTime(value: LocalDateTime): Any

  /** Bind value for an instant column: the UTC moment, independent of the JVM or server zone */
  protected def nativeUtcTimestamp(value: OffsetDateTime): Any

  /** Converts result set rows to maps; engines override it to read temporal columns without an instant round trip */
  protected def rowProcessor: RowProcessor = new BasicRowProcessor()

  protected def getBooleanField(value: AnyRef): Boolean

  /** The ScalaSql dialect this engine's typed queries are written against */
  protected val dialect: Dialect

  /**
   * An instant column as the model wants it. The two engines already disagree: PostgreSQL stores a `timestamptz` and shows it in
   * the JVM zone, SQLite stores UTC wall-clock text and hands it back verbatim. Row classes type these columns the same way, so
   * the engine decides here what the `LocalDateTime` on the model means, exactly as `getDateTimeField` does for the raw paths.
   */
  protected def toLocalDateTime(value: OffsetDateTime): LocalDateTime

  // Aggregates such as COUNT(*) come back as Long on Postgres and Integer on SQLite
  protected def getIntField(value: AnyRef): Int = value match
    case i: java.lang.Integer => i
    case l: java.lang.Long => l.toInt
    case _ => throw IllegalArgumentException(s"Invalid type for integer field: $value")

  // A nullable floating-point column; both engines hand a REAL / DOUBLE PRECISION back as a boxed number
  protected def getDoubleField(value: AnyRef): Option[Double] = Option(value).map {
    case n: java.lang.Number => n.doubleValue
    case other => throw IllegalArgumentException(s"Invalid type for double field: $other")
  }

  protected def getNextVal(tableName: String): AnyRef

  private def queryRunner = new QueryRunner()

  protected def getDataSourceType: String = config.getString(C.Conf.DB_ENGINE)

  protected val forUpdate: String

  def add(modelIn: Model): Model = throw NotImplementedError("add method must be implemented")

  def getJsonFromColumn(column: AnyRef): ujson.Obj =
    val jsonStr: String = if column == null then "{}" else column.toString
    ujson.read(jsonStr).asInstanceOf[ujson.Obj]

  def getOneByQuery(q: Query): Model =
    val rows = Db.read(dialect)(_.run(matching(q)))

    if rows.isEmpty then throw NotFoundException(s"Cannot find record in '$tableName' with query: $q")

    if rows.length > 1 then throw ConstraintException("getById should return only a single result")

    toModel(rows.head)

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

  def query(q: Query): QueryResult[Model] = queryRecords(q)

  /**
   * The generic read behind [[query]]. It is separate because some DAOs close the public method off - an asset is only ever
   * queried through one of its view-scoped variants - and still need the implementation.
   */
  protected def queryRecords(q: Query): QueryResult[Model] =
    import dialect.*

    // The window count is added before the ordering and the page, so it counts every match rather than the page
    val counted = matching(q).mapAggregate((row, aggregate) => (row, aggregate.size.over))

    val sorted = q.sort.headOption.fold(counted) {
      sort =>
        val ordered = counted.sortBy(row => column(row._1, sort.param))
        if sort.direction == SortDirection.DESC then ordered.desc else ordered.asc
    }

    val page = if q.rpp > 0 then sorted.drop((q.page - 1) * q.rpp).take(q.rpp) else sorted

    val rows = Db.read(dialect)(_.run(page))
    val total: Int = rows.headOption.map(_._2).getOrElse(0)
    logger.debug(s"Found [$total] records. Retrieved [${rows.length}] records")
    QueryResult(records = rows.map((row, _) => toModel(row)).toList, total = total, rpp = q.rpp, sort = q.sort)

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

    val q = new Query().add(FieldConst.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val rows = Db.read(dialect)(_.run(matching(q)))
    logger.debug(s"Found ${rows.length} records")
    rows.map(toModel).toList

  def updateByQuery(q: Query, data: Map[String, Any]): Int =
    import dialect.*

    val assignments = data.toSeq.map {
      (name, value) => (row: Row[Column]) => DynamicAssignments.one(table, updateColumns(row), name, value, dialect)
    }

    val update = table.update(row => DynamicFilter(table, updateColumns(row), q, dialect)).set(assignments*)
    val numUpdated = Db.write(dialect)(_.run(update))
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

  /** Every row of this table the query selects, as a typed relation */
  private def matching(q: Query) =
    import dialect.*
    table.select.filter(row => DynamicFilter(table, selectColumns(row), q, dialect))

  private def column(row: Row[Expr], name: String): Expr[?] =
    Columns.required(selectColumns(row), table, name)

  private def selectColumns(row: Row[Expr]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.walkExprs(row))

  private def updateColumns(row: Row[Column]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.asInstanceOf[Queryable.Row[Row[Column], Row[Sc]]].walkExprs(row))

  protected given rowQueryable: Queryable.Row[Row[Expr], Row[Sc]] = table.containerQr(using dialect)

  /** Built from a result-set row map by the paths that are still hand-written SQL. See [[toModel]] for the typed twin. */
  protected def makeModel(rec: Map[String, AnyRef]): Model

  protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime]
