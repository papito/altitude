package software.altitude.core.dao.jdbc

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.AltitudeAppContext
import software.altitude.core.models.Repository
import software.altitude.core.{Const => C}

abstract class RepositoryDao(val appContext: AltitudeAppContext)
  extends BaseDao
    with software.altitude.core.dao.RepositoryDao {

  override final val tableName = "repository"

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
      ownerAccountId = rec(C.Repository.OWNER_ACCOUNT_ID).asInstanceOf[String],
      rootFolderId = rec(C.Repository.ROOT_FOLDER_ID).asInstanceOf[String],
      triageFolderId = rec(C.Repository.TRIAGE_FOLDER_ID).asInstanceOf[String],
      fileStoreType = C.FileStoreType.withName(rec(C.Repository.FILE_STORE_TYPE).asInstanceOf[String]),
      fileStoreConfig = fileStoreConfigJson.as[Map[String, String]]
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val repo = jsonIn: Repository

    val sql = s"""
        INSERT INTO $tableName (
             ${C.Repository.ID}, ${C.Repository.NAME}, ${C.Repository.OWNER_ACCOUNT_ID}, ${C.Repository.FILE_STORE_TYPE},
             ${C.Repository.ROOT_FOLDER_ID}, ${C.Repository.TRIAGE_FOLDER_ID},
             ${C.Repository.FILES_STORE_CONFIG})
            VALUES (?, ?, ?, ?, ?, ?,$jsonFunc)
    """

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      repo.name,
      repo.ownerAccountId,
      repo.fileStoreType.toString,
      repo.rootFolderId,
      repo.triageFolderId,
      Json.toJson(repo.fileStoreConfig).toString())

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(C.Base.ID -> id)
  }
}
