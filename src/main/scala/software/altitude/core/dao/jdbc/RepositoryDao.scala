package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json

import software.altitude.core.FieldConst
import software.altitude.core.models.Repository

abstract class RepositoryDao(override val config: Config) extends BaseDao with software.altitude.core.dao.RepositoryDao {

  final override val tableName = "repository"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val fileStoreConfigCol = rec(FieldConst.Repository.FILES_STORE_CONFIG)
    val fileStoreConfigJsonStr: String = if (fileStoreConfigCol == null) {
      "{}"
    } else {
      fileStoreConfigCol.asInstanceOf[String]
    }

    val fileStoreConfigJson = Json.parse(fileStoreConfigJsonStr).as[JsObject]

    Repository(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Repository.NAME).asInstanceOf[String],
      ownerAccountId = rec(FieldConst.Repository.OWNER_ACCOUNT_ID).asInstanceOf[String],
      rootFolderId = rec(FieldConst.Repository.ROOT_FOLDER_ID).asInstanceOf[String],
      fileStoreType = rec(FieldConst.Repository.FILE_STORE_TYPE).asInstanceOf[String],
      fileStoreConfig = fileStoreConfigJson.as[Map[String, String]],
      createdAt = getDateTimeField(rec.get(FieldConst.CREATED_AT)),
      updatedAt = getDateTimeField(rec.get(FieldConst.UPDATED_AT))
    )
  }

  override def add(jsonIn: JsObject): JsObject = {
    val repo = jsonIn: Repository

    val sql = s"""
        INSERT INTO $tableName (
             ${FieldConst.ID}, ${FieldConst.Repository.NAME}, ${FieldConst.Repository.OWNER_ACCOUNT_ID}, ${FieldConst.Repository.FILE_STORE_TYPE},
             ${FieldConst.Repository.ROOT_FOLDER_ID},
             ${FieldConst.Repository.FILES_STORE_CONFIG})
            VALUES (?, ?, ?, ?, ?,$jsonFunc)
    """

    val id = BaseDao.genId

    val sqlVals: List[Any] =
      List(
        id,
        repo.name,
        repo.ownerAccountId,
        repo.fileStoreType,
        repo.rootFolderId,
        Json.toJson(repo.fileStoreConfig).toString())

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(FieldConst.ID -> id)
  }

  def getAll: List[Repository] = {
    val sql = s"SELECT ${columnsForSelect.mkString(", ")} FROM $tableName"
    val recs = manyBySqlQuery(sql)
    recs.map(makeModel)
  }
}
