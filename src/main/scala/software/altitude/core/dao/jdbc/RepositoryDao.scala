package software.altitude.core.dao.jdbc

import org.slf4j.LoggerFactory
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.models.Repository
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

abstract class RepositoryDao(val app: AltitudeCoreApp)
  extends BaseJdbcDao
    with software.altitude.core.dao.RepositoryDao {
  private final val log = LoggerFactory.getLogger(getClass)

  override final val tableName = "repository"

  // this is the same as the base one - minus the repository ID, which this model does not have
  override protected val oneRecSelectSql: String = s"""
      SELECT ${defaultSqlColsForSelect.mkString(", ")}
        FROM $tableName
       WHERE ${C.Base.ID} = ?"""

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val fileStoreConfigCol = rec(C.Repository.FILES_STORE_CONFIG)
    val fileStoreConfigJsonStr: String = if (fileStoreConfigCol == null) {
      "{}"
    } else {
      fileStoreConfigCol.asInstanceOf[String]
    }

    val fileStoreConfigJson = Json.parse(fileStoreConfigJsonStr).as[JsObject]

    val model = Repository(
      id = Some(rec(C.Base.ID).asInstanceOf[String]),
      name = rec(C.Repository.NAME).asInstanceOf[String],
      rootFolderId = rec(C.Repository.ROOT_FOLDER_ID).asInstanceOf[String],
      triageFolderId = rec(C.Repository.TRIAGE_FOLDER_ID).asInstanceOf[String],
      fileStoreType = C.FileStoreType.withName(rec(C.Repository.FILE_STORE_TYPE).asInstanceOf[String]),
      fileStoreConfig = fileStoreConfigJson.as[Map[String, String]]
    )

    addCoreAttrs(model, rec)
  }

  override def getById(id: String)(implicit ctx: Context, txId: TransactionId): Option[JsObject] = {
    log.debug(s"Getting by ID '$id' from '$tableName'", C.LogTag.DB)
    val rec: Option[Map[String, AnyRef]] = oneBySqlQuery(oneRecSelectSql, List(id))
    if (rec.isDefined) Some(makeModel(rec.get)) else None
  }

  override def add(jsonIn: JsObject)(implicit ctx: Context, txId: TransactionId): JsObject = {
    val repo = jsonIn: Repository

    val sql = s"""
        INSERT INTO $tableName (
             ${C.Base.ID}, ${C.Repository.NAME}, ${C.Repository.FILE_STORE_TYPE},
             ${C.Repository.ROOT_FOLDER_ID}, ${C.Repository.TRIAGE_FOLDER_ID},
             ${C.Repository.FILES_STORE_CONFIG})
            VALUES (?, ?, ?, ?, ?, $jsonFunc)
    """

    val sqlVals: List[Any] = List(
      repo.name,
      repo.fileStoreType.toString,
      repo.rootFolderId,
      repo.triageFolderId,
      Json.toJson(repo.fileStoreConfig).toString())

    addRecord(jsonIn, sql, sqlVals)
  }

  // we do not use repository ID
  override protected def combineInsertValues(id: String, vals: List[Any])(implicit  ctx: Context): List[Any] =
    id :: vals
}
