package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.SQLException
import java.time.LocalDate
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
import altitude.core.models.SceneEmbedding
import altitude.core.util.Query
import altitude.core.util.QueryResult
import altitude.core.util.SortDirection

object BaseDao:
  final def genId: String = UUID.randomUUID.toString

  /**
   * What a streaming read ([[BaseDao.collectBySqlQuery]]) hands back: the rows its mapper kept, in the result's order, and how
   * many rows it scanned in all, so a caller reading up to a limit can tell when the limit was reached
   */
  final case class Collected[T](kept: List[T], scanned: Int)

  /**
   * How many rows a streaming read asks the driver for at a time. The PostgreSQL driver buffers a whole result unless the fetch
   * size is positive (and autocommit off, which every transaction sets), and the rows of such a read are large (a Sequence
   * candidate carries two Scene embeddings, about 6 KB); a few hundred of them keep the buffer around a megabyte while a round
   * trip still brings many. SQLite's driver reads row by row whatever the size is.
   */
  private val STREAMING_FETCH_SIZE = 256

  private def incrReadQueryCount(): Unit =
    RequestContext.readQueryCount.value = RequestContext.readQueryCount.value + 1

  def incrWriteQueryCount(): Unit =
    RequestContext.writeQueryCount.value = RequestContext.writeQueryCount.value + 1

abstract class BaseDao[Model <: BaseModel]:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  val config: Config

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

  /**
   * The placeholder of a bound vector (a Scene embedding) in a hand-written statement: `?::vector` on PostgreSQL, whose column is
   * pgvector's type, and a plain `?` on SQLite, whose column is a BLOB
   */
  protected def vectorPlaceholder: String

  /**
   * The bind value of a Scene embedding for [[vectorPlaceholder]]: the pgvector literal (`[v1,v2,...]`) on PostgreSQL, the raw
   * little-endian float32 values as bytes on SQLite; `None` binds SQL NULL on both
   */
  protected def vectorBind(embedding: Option[SceneEmbedding]): Any

  /**
   * A vector column as the result row hands it back: a `PGobject` whose value is the `[v1,v2,...]` text on PostgreSQL, the BLOB's
   * bytes on SQLite; `null` is `None`
   */
  protected def getVectorField(value: AnyRef): Option[SceneEmbedding]

  /** Bind value for a wall-clock timestamp column (no zone): stored and read back verbatim, never converted through an instant */
  protected def nativeLocalDateTime(value: LocalDateTime): Any

  /** Bind value for an instant column: the UTC moment, independent of the JVM or server zone */
  protected def nativeUtcTimestamp(value: OffsetDateTime): Any

  /** Bind value for a calendar day compared with [[dayOf]] a wall-clock timestamp column */
  protected def nativeLocalDate(value: LocalDate): Any

  /**
   * The calendar day of a wall-clock timestamp column, spelled as the capture-day indexes spell it, so a statement that compares
   * it reads the index: the engine's `SearchDialect.dayOf`, the one spelling the typed searches render too
   */
  protected def dayOf(column: String): String

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

  // A nullable integer column, which SQLite hands back as Integer or Long by the value's size
  protected def getLongField(value: AnyRef): Option[Long] = Option(value).map {
    case n: java.lang.Number => n.longValue
    case other => throw IllegalArgumentException(s"Invalid type for long field: $other")
  }

  protected def getNextVal(tableName: String): AnyRef

  private def queryRunner = new QueryRunner()

  protected def getDataSourceType: String = config.getString(C.Conf.DB_ENGINE)

  protected val forUpdate: String

  /**
   * The test of a hand-written statement for a column being one of a set of IDs, `column $inIdSet`, whose one placeholder takes
   * the whole set as [[idSet]] binds it, so a selection of any size neither grows the statement nor reaches the engine's
   * parameter limit. The typed twin is `Columns.isInSet`.
   */
  protected val inIdSet: String

  protected def idSet(ids: Set[String]): Any

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
    logger.trace(s"Getting by ID '$id' from '$tableName'")
    val q: Query = new Query().add(FieldConst.ID -> id)
    getOneByQuery(q)

  def deleteById(id: String): Int =
    val q: Query = new Query().add(FieldConst.ID -> id)
    deleteByQuery(q)

  def updateById(id: String, data: Map[String, Any]): Int =
    val q: Query = new Query().add(FieldConst.ID -> id)
    updateByQuery(q, data)

  def deleteByQuery(q: Query): Int =
    import dialect.*

    val numDeleted = Db.write(dialect)(_.run(table.delete(row => DynamicFilter(table, writeColumns(row), q, dialect))))
    logger.trace(s"Deleted records: $numDeleted")
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
    logger.trace(s"Found [$total] records. Retrieved [${rows.length}] records")
    QueryResult(records = rows.map((row, _) => toModel(row)).toList, total = total, rpp = q.rpp, sort = q.sort)

  protected def addRecord(sql: String, values: List[Any]): Unit =
    BaseDao.incrWriteQueryCount()
    logger.trace(s"INSERT SQL: $sql with values: $values")
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)

  private def executeAndGetMany(sql: String, values: List[Any]): List[Map[String, AnyRef]] =
    BaseDao.incrReadQueryCount()
    logger.trace(s"SELECT SQL: $sql with values: $values")
    val res =
      queryRunner
        .query(RequestContext.getConn, sql, new MapListHandler(rowProcessor), values.map(_.asInstanceOf[Object])*)
        .asScala
        .toList
    logger.trace(s"Found ${res.length} records")
    res.map(_.asScala.toMap[String, AnyRef])

  def manyBySqlQuery(sql: String, values: List[Any] = List()): List[Map[String, AnyRef]] =
    executeAndGetMany(sql, values)

  /**
   * A read whose rows are judged one by one and mostly dropped: `f` sees each row as it is fetched, as [[makeModel]] would see it
   * (the same row processor as [[manyBySqlQuery]], so the keys are spelled the same), and only what it returns is kept, so at
   * most the kept rows are alive at once however large the result. [[BaseDao.STREAMING_FETCH_SIZE]] is what keeps the driver from
   * buffering the whole result on PostgreSQL. A failure is rethrown with the statement and its values in the message, as
   * `QueryRunner` reports one for the other hand-written reads.
   */
  def collectBySqlQuery[T](sql: String, values: List[Any] = List())(f: Map[String, AnyRef] => Option[T]): BaseDao.Collected[T] =
    BaseDao.incrReadQueryCount()
    logger.trace(s"STREAMING SELECT SQL: $sql with values: $values")
    try
      val statement = RequestContext.getConn.prepareStatement(sql)
      try
        statement.setFetchSize(BaseDao.STREAMING_FETCH_SIZE)
        queryRunner.fillStatement(statement, values.map(_.asInstanceOf[Object])*)
        val rs = statement.executeQuery()
        try
          val kept = List.newBuilder[T]
          var scanned = 0
          while rs.next() do
            scanned += 1
            f(rowProcessor.toMap(rs).asScala.toMap[String, AnyRef]).foreach(kept += _)
          val collected = BaseDao.Collected(kept.result(), scanned)
          logger.trace(s"Scanned ${collected.scanned} records, kept ${collected.kept.length}")
          collected
        finally rs.close()
      finally statement.close()
    catch
      case e: SQLException =>
        val message = s"${e.getMessage} Query: $sql Parameters: ${values.mkString(", ")}"
        throw new SQLException(message, e.getSQLState, e.getErrorCode, e)

  def getByIds(ids: Set[String]): List[Model] =
    if ids.isEmpty then return List()

    val q = new Query().add(FieldConst.ID -> Query.IN(ids.asInstanceOf[Set[Any]]))
    val rows = Db.read(dialect)(_.run(matching(q)))
    logger.trace(s"Found ${rows.length} records")
    rows.map(toModel).toList

  def updateByQuery(q: Query, data: Map[String, Any]): Int =
    import dialect.*

    val assignments = data.toSeq.map {
      (name, value) => (row: Row[Column]) => DynamicAssignments.one(table, writeColumns(row), name, value, dialect)
    }

    val update = table.update(row => DynamicFilter(table, writeColumns(row), q, dialect)).set(assignments*)
    val numUpdated = Db.write(dialect)(_.run(update))
    logger.trace("Updated records: " + numUpdated)
    numUpdated

  def updateByBySql(sql: String, values: List[Any]): Int =
    BaseDao.incrWriteQueryCount()
    logger.trace(s"WRITE SQL: $sql with values: $values")
    val runner = queryRunner
    val numAffected = runner.update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
    logger.trace(s"Affected records: $numAffected")
    numAffected

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
    logger.trace(s"INCR SQL: $sql, $id")
    val runner = queryRunner
    runner.update(RequestContext.getConn, sql, id)

  /** Every row of this table the query selects, as a typed relation */
  private def matching(q: Query) =
    import dialect.*
    table.select.filter(row => DynamicFilter(table, selectColumns(row), q, dialect))

  private def column(row: Row[Expr], name: String): Expr[?] =
    Columns.required(selectColumns(row), table, name)

  private def selectColumns(row: Row[Expr]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.walkExprs(row))

  private def writeColumns(row: Row[Column]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.asInstanceOf[Queryable.Row[Row[Column], Row[Sc]]].walkExprs(row))

  protected given rowQueryable: Queryable.Row[Row[Expr], Row[Sc]] = table.containerQr(using dialect)

  /** Built from a result-set row map by the paths that are still hand-written SQL. See [[toModel]] for the typed twin. */
  protected def makeModel(rec: Map[String, AnyRef]): Model

  protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime]
