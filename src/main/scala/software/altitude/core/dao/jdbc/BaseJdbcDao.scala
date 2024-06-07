package software.altitude.core.dao.jdbc

import net.codingwell.scalaguice.InjectorExtensions._
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.commons.dbutils.handlers.ScalarHandler
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import play.api.libs.json._
import software.altitude.core.ConstraintException
import software.altitude.core.Context
import software.altitude.core.Util
import software.altitude.core.dao.BaseDao
import software.altitude.core.dao.jdbc.querybuilder.SqlQuery
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.BaseModel
import software.altitude.core.transactions.JdbcTransactionManager
import software.altitude.core.transactions.TransactionId
import software.altitude.core.util.Query
import software.altitude.core.util.QueryResult
import software.altitude.core.{Const => C}

import java.sql.Connection
import scala.jdk.CollectionConverters._

abstract class BaseJdbcDao extends BaseDao {
  private final val log = LoggerFactory.getLogger(getClass)

  val tableName: String

  protected final def txManager: JdbcTransactionManager = app.injector.instance[JdbcTransactionManager]

  protected def conn(implicit ctx: Context, txId: TransactionId): Connection = {
    // get the connection associated with this transaction
    txManager.transaction().getConnection
  }

  protected def defaultSqlColsForSelect: List[String]

  protected val sqlQueryBuilder: SqlQueryBuilder[Query] = new SqlQueryBuilder[Query](defaultSqlColsForSelect, tableName)

  // if supported, DB function to store native JSON data
  protected def jsonFunc: String
  // DB current time function
  protected def nowTimeFunc: String

  // conversion function to go from Java time to DB time
  protected def dateTimeToDbFunc(datetime: Option[DateTime]): String
  // opposite of the above
  protected def getDateTimeFromRec(field: String, rec: Map[String, AnyRef]): Option[DateTime]

  // common fields for new records, and their placeholders - mostly to avoid repetition
  protected val coreSqlColsForInsert: List[String] = List(C.Base.ID, C.Base.REPO_ID)
  protected def coreSqlValsForInsert: String = "?, ?"

  // how we get current timestamp
  protected def utcNow: DateTime = Util.utcNow

  // datetime as a JSON value
  protected def dtAsJsString(dt: DateTime): JsString = JsString(Util.isoDateTime(Some(dt)))

  // SQL to select the whole record, in very simple cases
  protected val oneRecSelectSql: String = s"""
      SELECT ${defaultSqlColsForSelect.mkString(", ")}
        FROM $tableName
       WHERE ${C.Base.ID} = ? AND ${C.Base.REPO_ID} = ?"""

  override def add(jsonIn: JsObject)(implicit ctx: Context, txId: TransactionId): JsObject = {
    val sql: String = s"""
      INSERT INTO $tableName (${coreSqlColsForInsert.mkString(", ")})
           VALUES ($coreSqlValsForInsert)"""

    addRecord(jsonIn, sql, List[Any]())
  }

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId): Option[JsObject] = {
    log.debug(s"Getting by ID '$id' from '$tableName'")
    val rec: Option[Map[String, AnyRef]] = oneBySqlQuery(oneRecSelectSql, List(id, ctx.repo.id.get))
    if (rec.isDefined) Some(makeModel(rec.get)) else None
  }

  override def deleteByQuery(q: Query)(implicit ctx: Context, txId: TransactionId): Int = {
    log.debug(s"Deleting record by query: $q")
    val fieldPlaceholders: List[String] = q.params.keys.map(_ + " = ?").toList

    val sql = s"""
      DELETE
        FROM $tableName
       WHERE ${C.Base.REPO_ID} = ? AND ${fieldPlaceholders.mkString(",")}
      """

    log.debug(s"Delete SQL: $sql, with values: ${q.params.values.toList}")
    val runner: QueryRunner = new QueryRunner()
    val numDeleted = runner.update(
      conn, sql, ctx.repo.id.get :: q.params.values.toList.map(_.asInstanceOf[Object]): _*)
    log.debug(s"Deleted records: $numDeleted")
    numDeleted
  }

  override def deleteBySql(sql: String, bindValues: List[Object])(implicit ctx: Context, txId: TransactionId): Int = {
    log.debug("Deleting records by SQL")
    log.debug(s"Delete SQL: $sql, with values: $bindValues")
    val runner: QueryRunner = new QueryRunner()
    val numDeleted = runner.update(conn, sql, bindValues: _*)
    log.debug(s"Deleted records: $numDeleted")
    numDeleted
  }

  override def query(q: Query)(implicit ctx: Context, txId: TransactionId): QueryResult = {
    this.query(q, sqlQueryBuilder)
  }

  /**
   * Internal version for querying with a customized query builder
   */
  protected def query(query: Query, sqlQueryBuilder: SqlQueryBuilder[Query])
           (implicit ctx: Context, txId: TransactionId): QueryResult = {
    val sqlQuery: SqlQuery = sqlQueryBuilder.buildSelectSql(query)

    val recs = manyBySqlQuery(sqlQuery.sqlAsString, sqlQuery.bindValues)
    val count: Int = getQueryResultCount(query, sqlQueryBuilder, sqlQuery.bindValues)

    log.debug(s"Found [$count] records. Retrieved [${recs.length}] records")
    if (recs.nonEmpty) {
      log.debug(recs.map(_.toString()).mkString("\n"))
    }
    QueryResult(records = recs.map{makeModel}, total = count, rpp = query.rpp, sort = query.sort.toList)
  }

  protected def getQueryResultCount(query: Query, sqlQueryBuilder: SqlQueryBuilder[Query], values: List[Any] = List())
                                   (implicit  ctx: Context, txId: TransactionId): Int = {
    val sqlCountQuery: SqlQuery = sqlQueryBuilder.buildCountSql(query)
    getQueryResultCountBySql(sqlCountQuery.sqlAsString, values)
  }

  protected def getQueryResultCountBySql(sql: String, values: List[Any] = List())
                                   (implicit  ctx: Context, txId: TransactionId): Int = {
    val runner: QueryRunner = new QueryRunner()

    // We are defensive with different JDBC drivers operating with either java.lang.Int or java.lang.Long
    runner.query(conn, sql, new ScalarHandler[AnyRef]("count"), values.map(_.asInstanceOf[Object]): _*) match {
      case v: java.lang.Integer => v.intValue
      case v: java.lang.Long => v.asInstanceOf[Long].toInt
      case null => 0
    }
  }

  /**
   * Internally used method to add records. It's convenient for classes overriding
   * the add() method, as it accepts a ready-to-go SQL query, with bind methods.
   * This function takes care of the actual plumbing common to all add() methods.
   */
  protected def addRecord(jsonIn: JsObject, sql: String, values: List[Any])
                         (implicit  ctx: Context, txId: TransactionId): JsObject = {
    log.info(s"JDBC INSERT: $jsonIn")

    val existingObjId: Option[String] = (jsonIn \ C.Base.ID).asOpt[String]

    // create ID unless there is an override
    val id = if (existingObjId.isDefined) existingObjId.get else BaseModel.genId
    BaseDao.verifyId(id)

    // prepend ID and REPO ID, as it is required for most records
    val _values: List[Any] = combineInsertValues(id, values)
    log.debug(s"INSERT SQL: $sql. ARGS: ${_values.toString()}")

    val runner: QueryRunner = new QueryRunner()
    runner.update(conn, sql, _values.map(_.asInstanceOf[Object]): _*)

    val recordJson = jsonIn ++ Json.obj(C.Base.ID -> id)

    log.debug(s"Added: $recordJson")
    recordJson
  }

  /**
   * Create an array of values to insert, joining them in proper order.
   * The base implementation assumes that it's [ID, REPO ID] + [THE REST],
   * but it may not always be the case.
   *
   * @param id is always required
   * @param vals any arbitrary values
   * @return array of values to be bound to columns
   */
  protected def combineInsertValues(id: String, vals: List[Any])(implicit  ctx: Context): List[Any] =
    id :: ctx.repo.id.get :: vals

  protected def manyBySqlQuery(sql: String, values: List[Any] = List())
                              (implicit ctx: Context, txId: TransactionId): List[Map[String, AnyRef]] = {
    log.debug(s"Running SQL query [$sql] with $values")

    val runner: QueryRunner = new QueryRunner()
    val res = runner.query(conn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object]): _*).asScala.toList
    log.debug(s"Found ${res.length} records")
    // FIXME: is spacing here a scala Style violation?
    res.map{_.asScala.toMap[String, AnyRef]}.toList
  }

  /**
   * Internal method to return a UNIQUE object from DB. Does not just get the first one.
   *
   * @throws ConstraintException if a DB constraint is missed and more than one record is found
   */
  protected def oneBySqlQuery(sql: String, values: List[Any] = List())
                             (implicit ctx: Context, txId: TransactionId): Option[Map[String, AnyRef]] = {
    log.debug(s"Running SQL query [$sql] with $values")

    val runner: QueryRunner = new QueryRunner()
    val res = runner.query(conn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object]): _*).asScala.toList
    log.debug(s"Found ${res.length} records")

    if (res.isEmpty) {
      return None
    }

    if (res.length > 1) {
      throw ConstraintException("getById should return only a single result")
    }

    val rec = res.head

    log.debug(s"RECORD: $rec")
    Some(rec.asScala.toMap)
  }

  override def getByIds(ids: Set[String])
                       (implicit ctx: Context, txId: TransactionId): List[JsObject] = {
    val placeholders = ids.toSeq.map(_ => "?")
    val sql = s"""
      SELECT ${defaultSqlColsForSelect.mkString(", ")}
        FROM $tableName
       WHERE ${C.Base.REPO_ID} = ? AND id IN (${placeholders.mkString(",")})
      """

    log.debug(s"SQL: $sql with values: ${ids.toList}")

    val runner: QueryRunner = new QueryRunner()
    val res = runner.query(conn, sql, new MapListHandler(), ctx.repo.id.get :: ids.toList: _*).asScala.toList
    log.debug(s"Found ${res.length} records")
    val recs = res.map{_.asScala.toMap[String, AnyRef]}
    recs.map{makeModel}
  }

  override def updateByQuery(q: Query, json: JsObject, fields: List[String])
                            (implicit ctx: Context, txId: TransactionId): Int = {
    log.debug(s"Updating record by query $q with data $json for fields: $fields")

    val queryFieldPlaceholders: List[String] = q.params.keys.map(_ + " = ?").toList
    val updateFieldPlaceholders: List[String] = json.fields.filter {
      // extract only the json elements we want to update
      v: (String, JsValue) => fields.contains(v._1)}.map {
      v: (String, JsValue) => s"${v._1} = ?"
    }.toList

    val sql = s"""
      UPDATE $tableName
         SET ${C.Base.UPDATED_AT} = $nowTimeFunc, ${updateFieldPlaceholders.mkString(", ")}
       WHERE ${C.Base.REPO_ID} = ? AND ${queryFieldPlaceholders.mkString(",")}
      """

    val dataUpdateValues: List[Any] = json.fields.filter {
      // extract only the json elements we want to update
      v: (String, JsValue) => fields.contains(v._1)}.map {
      // convert the values to string
      v: (String, JsValue) => {
        val jsVal: JsValue = v._2

        jsVal match {
          case JsTrue => 1
          case JsFalse => 0
          case _ => jsVal.as[String]
        }
      }
    }.toList

    val valuesForAllPlaceholders = dataUpdateValues ::: List(ctx.repo.id.get) ::: q.params.values.toList
    val runner: QueryRunner = new QueryRunner()

    val numUpdated = runner.update(conn, sql, valuesForAllPlaceholders.map(_.asInstanceOf[Object]): _*)
    numUpdated
  }

  override def increment(id: String, field: String, count: Int = 1)
                        (implicit ctx: Context, txId: TransactionId): Unit = {
    val sql = s"""
      UPDATE $tableName
         SET $field = $field + $count
       WHERE ${C.Base.REPO_ID} = ? AND id = ?
      """
    log.debug(s"INCR SQL: $sql, for $id")

    val runner: QueryRunner = new QueryRunner()
    runner.update(conn, sql, ctx.repo.id.get, id)
  }

  override def decrement(id: String, field: String, count: Int = 1)
                        (implicit ctx: Context, txId: TransactionId): Unit = {
    increment(id, field, -count)
  }

  /**
   * Make a string of SQL placeholders for a list - such as "? , ? ,?, ?"
   */
  protected def makeSqlPlaceholders(s: Seq[AnyRef]): String = s.map(_ => "?").mkString(",")

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
