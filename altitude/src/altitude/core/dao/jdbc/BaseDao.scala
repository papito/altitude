package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.SQLException
import java.time.Instant
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

  /** The rows a streaming read kept, and how many it scanned, so a caller reading up to a limit can tell it reached the limit */
  final case class Collected[T](kept: List[T], scanned: Int)

  /**
   * The PostgreSQL driver buffers a whole result unless the fetch size is positive (with autocommit off). Streamed rows are large
   * (a Sequence candidate carries two Scene embeddings, about 6 KB), so a few hundred keep the buffer near a megabyte. SQLite's
   * driver reads row by row regardless.
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

  /**
   * Whether rows belong to one repository each (`repository_id`). If so, every generic read and write here is limited to the
   * context repository and throws when there is none, so another repository's row is as unreachable as a missing one. A
   * hand-written statement on a scoped table binds `repository_id = ?` itself.
   */
  protected val repositoryScoped: Boolean = true

  private def scoped(q: Query): Query =
    if repositoryScoped then q.add(FieldConst.REPO_ID -> RequestContext.getRepository.persistedId) else q

  /** A write by an empty query would change every row (of the repository, for a scoped DAO) */
  private def requireParams(q: Query, operation: String): Unit =
    if q.params.isEmpty then throw IllegalArgumentException(s"Cannot $operation all of '$tableName' with an empty query")

  /** The columns of `RepositoryDao`'s hand-written `SELECT`s; typed reads go through [[table]] */
  protected def columnsForSelect: List[String] = List("*")

  type Row[T[_]]

  protected def table: Table[Row]

  /** The typed twin of [[makeModel]]: the two must agree while both a typed and a hand-written path return the same record */
  protected def toModel(row: Row[Sc]): Model

  // The placeholder of a bound JSON text: cast to jsonb on PostgreSQL, plain on SQLite
  protected def jsonFunc: String

  /**
   * A top-level key of a JSON object column, spelled as its index is (`asset_importer`) so a comparison uses the index:
   * `(column->>'key')` on PostgreSQL, `json_extract(column, '$.key')` on SQLite
   */
  protected def jsonText(column: String, key: String): String

  protected def nativeBool(value: Boolean): Any

  /** The placeholder of a bound Scene embedding: `?::vector` on PostgreSQL (pgvector), plain `?` on SQLite (a BLOB) */
  protected def vectorPlaceholder: String

  /**
   * The bind value for [[vectorPlaceholder]]: the pgvector literal on PostgreSQL, little-endian float32 bytes on SQLite; `None`
   * binds NULL
   */
  protected def vectorBind(embedding: Option[SceneEmbedding]): Any

  /** A vector column as read: a `PGobject` holding the literal on PostgreSQL, the BLOB's bytes on SQLite */
  protected def getVectorField(value: AnyRef): Option[SceneEmbedding]

  /** Bind value for a wall-clock timestamp column (no zone): stored and read back verbatim, never converted through an instant */
  protected def nativeLocalDateTime(value: LocalDateTime): Any

  /** Bind value for an instant column: the UTC moment, independent of the JVM or server zone */
  protected def nativeUtcTimestamp(value: OffsetDateTime): Any

  /** Bind value for a calendar day compared with [[dayOf]] a wall-clock timestamp column */
  protected def nativeLocalDate(value: LocalDate): Any

  /**
   * The calendar day of a wall-clock timestamp column, spelled as the capture-day indexes are (`SearchDialect.dayOf`) so a
   * comparison uses the index
   */
  protected def dayOf(column: String): String

  /** Engines override it to read temporal columns without an instant round trip */
  protected def rowProcessor: RowProcessor = new BasicRowProcessor()

  protected def getBooleanField(value: AnyRef): Boolean

  protected val dialect: Dialect

  /**
   * An instant column as the model's `LocalDateTime`. The engines differ: PostgreSQL stores a `timestamptz` and shows it in the
   * JVM zone, SQLite stores UTC wall-clock text verbatim. The engine decides here, as `getDateTimeField` does for the raw paths.
   */
  protected def toLocalDateTime(value: OffsetDateTime): LocalDateTime

  /**
   * The instant a model's `LocalDateTime` stands for, read the way this engine wrote it: in the JVM zone on PostgreSQL, as UTC on
   * SQLite. Needed before a moment leaves the server, which shows it in the viewer's zone.
   */
  def instantOf(value: LocalDateTime): Instant

  // Aggregates such as COUNT(*) come back as Long on Postgres and Integer on SQLite
  protected def getIntField(value: AnyRef): Int = value match
    case i: java.lang.Integer => i
    case l: java.lang.Long => l.toInt
    case _ => throw IllegalArgumentException(s"Invalid type for integer field: $value")

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
   * Keeps a read row from being deleted or changed in its key until the transaction ends, without blocking writes that leave the
   * key alone: `FOR KEY SHARE` (a foreign key check's lock) on PostgreSQL; nothing on SQLite, whose one write connection
   * serializes the writers
   */
  protected val forKeyShare: String

  /**
   * Locks a row to write it, short of its key: `FOR NO KEY UPDATE` on PostgreSQL, which does not block a foreign key's check, so
   * a writer of a referencing row never waits on it; nothing on SQLite
   */
  protected val forNoKeyUpdate: String

  /**
   * Serializes the transactions that take the same key until they end, where row locks cannot: a row one of them will insert is
   * not there to lock yet. `pg_advisory_xact_lock` on PostgreSQL; nothing on SQLite.
   */
  protected def advisoryLock(key: String): Unit

  /**
   * `column $inIdSet` tests a column against a set of IDs bound as one [[idSet]] parameter, so any selection size neither grows
   * the statement nor hits the engine's parameter limit. The typed twin is `Columns.isInSet`.
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
    requireParams(q, "delete")

    val filter = scoped(q)
    val numDeleted = Db.write(dialect)(_.run(table.delete(row => DynamicFilter(table, writeColumns(row), filter, dialect))))
    logger.trace(s"Deleted records: $numDeleted")
    numDeleted

  def query(q: Query): QueryResult[Model] = queryRecords(q)

  /** The generic read behind [[query]], kept separate for DAOs that close [[query]] off (assets are read view-scoped) */
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
   * A read whose rows are judged as they are fetched and mostly dropped: `f` sees each row as [[manyBySqlQuery]] would return it,
   * and only what it keeps stays in memory, however large the result. A failure is rethrown with the statement and values in the
   * message, as `QueryRunner` reports it for the other reads.
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
    requireParams(q, "update")

    val assignments = data.toSeq.map {
      (name, value) => (row: Row[Column]) => DynamicAssignments.one(table, writeColumns(row), name, value, dialect)
    }

    val filter = scoped(q)
    val update = table.update(row => DynamicFilter(table, writeColumns(row), filter, dialect)).set(assignments*)
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

  /** Returns the rows changed: 0 for a missing row or, for a scoped DAO, another repository's */
  def increment(id: String, field: String, count: Int = 1): Int =
    val repositoryClause = if repositoryScoped then s" AND ${FieldConst.REPO_ID} = ?" else ""
    val sql = s"""
      UPDATE $tableName
         SET $field = $field + $count
       WHERE id = ?$repositoryClause
      """
    val values = if repositoryScoped then List(id, RequestContext.getRepository.persistedId) else List(id)
    updateByBySql(sql, values)

  private def matching(q: Query) =
    import dialect.*
    val filter = scoped(q)
    table.select.filter(row => DynamicFilter(table, selectColumns(row), filter, dialect))

  private def column(row: Row[Expr], name: String): Expr[?] =
    Columns.required(selectColumns(row), table, name)

  private def selectColumns(row: Row[Expr]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.walkExprs(row))

  private def writeColumns(row: Row[Column]): Map[String, Expr[?]] =
    Columns.byName(table, rowQueryable.asInstanceOf[Queryable.Row[Row[Column], Row[Sc]]].walkExprs(row))

  protected given rowQueryable: Queryable.Row[Row[Expr], Row[Sc]] = table.containerQr(using dialect)

  /** A result-set row map as the model, for the hand-written SQL paths; [[toModel]] is the typed twin */
  protected def makeModel(rec: Map[String, AnyRef]): Model

  protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime]
