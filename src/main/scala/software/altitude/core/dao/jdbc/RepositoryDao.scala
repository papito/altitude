package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.models.Field
import software.altitude.core.models.Repository

abstract class RepositoryDao(override val config: Config) extends BaseDao
    with software.altitude.core.dao.RepositoryDao {

  override final val tableName = "repository"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val fileStoreConfigCol = rec(Field.Repository.FILES_STORE_CONFIG)
    val fileStoreConfigJsonStr: String = if (fileStoreConfigCol == null) {
      "{}"
    } else {
      fileStoreConfigCol.asInstanceOf[String]
    }

    val fileStoreConfigJson = Json.parse(fileStoreConfigJsonStr).as[JsObject]

    val model = Repository(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      name = rec(Field.Repository.NAME).asInstanceOf[String],
      ownerAccountId = rec(Field.Repository.OWNER_ACCOUNT_ID).asInstanceOf[String],
      rootFolderId = rec(Field.Repository.ROOT_FOLDER_ID).asInstanceOf[String],
      fileStoreType = rec(Field.Repository.FILE_STORE_TYPE).asInstanceOf[String],
      fileStoreConfig = fileStoreConfigJson.as[Map[String, String]]
    )

    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val repo = jsonIn: Repository

    val sql = s"""
        INSERT INTO $tableName (
             ${Field.ID}, ${Field.Repository.NAME}, ${Field.Repository.OWNER_ACCOUNT_ID}, ${Field.Repository.FILE_STORE_TYPE},
             ${Field.Repository.ROOT_FOLDER_ID},
             ${Field.Repository.FILES_STORE_CONFIG})
            VALUES (?, ?, ?, ?, ?,$jsonFunc)
    """

    val id = BaseDao.genId

    val sqlVals: List[Any] = List(
      id,
      repo.name,
      repo.ownerAccountId,
      repo.fileStoreType,
      repo.rootFolderId,
      Json.toJson(repo.fileStoreConfig).toString())

    addRecord(jsonIn, sql, sqlVals)

    jsonIn ++ Json.obj(Field.ID -> id)
  }

  def getAll: List[Repository] = {
    val sql = s"SELECT ${columnsForSelect.mkString(", ")} FROM $tableName"
    val recs = manyBySqlQuery(sql)
    recs.map(makeModel)
  }
}
