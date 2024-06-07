package software.altitude.core.dao.jdbc

import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.models.User
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

abstract class UserDao(val app: AltitudeCoreApp) extends BaseJdbcDao with software.altitude.core.dao.UserDao {
  private final val log = LoggerFactory.getLogger(getClass)

  override final val tableName = "repository_user"

  override protected val oneRecSelectSql: String = s"""
      SELECT ${defaultSqlColsForSelect.mkString(", ")}
        FROM $tableName
       WHERE ${C.Base.ID} = ?"""


  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = User(
      id = Some(rec(C.Base.ID).asInstanceOf[String])
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject)(implicit ctx: Context, txId: TransactionId): JsObject = {
    val sql = s"""
        INSERT INTO $tableName (${C.Base.ID})
             VALUES (?)
    """

    addRecord(jsonIn, sql, List())
  }

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId): Option[JsObject] = {
    log.debug(s"Getting by ID '$id' from '$tableName'", C.LogTag.DB)
    val rec: Option[Map[String, AnyRef]] = oneBySqlQuery(oneRecSelectSql, List(id))
    if (rec.isDefined) Some(makeModel(rec.get)) else None
  }

  override protected def combineInsertValues(id: String, vals: List[Any])(implicit  ctx: Context): List[Any] =
    id :: vals
}
