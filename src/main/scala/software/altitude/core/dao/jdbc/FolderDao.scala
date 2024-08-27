package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.RequestContext
import software.altitude.core.models.Field
import software.altitude.core.models.Folder

abstract class FolderDao(override val config: Config) extends BaseDao with software.altitude.core.dao.FolderDao {
  override final val tableName = "folder"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    val model = Folder(
      id = Option(rec(Field.ID).asInstanceOf[String]),
      name = rec(Field.Folder.NAME).asInstanceOf[String],
      parentId = rec(Field.Folder.PARENT_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(Field.Folder.IS_RECYCLED)),
      numOfAssets = rec(Field.Folder.NUM_OF_ASSETS).asInstanceOf[Int],
      numOfChildren = rec(Field.Folder.NUM_OF_CHILDREN).asInstanceOf[Int]
    )
    addCoreAttrs(model, rec)
  }

  override def add(jsonIn: JsObject): JsObject = {
    val folder = jsonIn: Folder

    val id = folder.id match {
      case Some(id) => id
      case None => BaseDao.genId
    }

    val sql = s"""
        INSERT INTO $tableName (
                      ${Field.ID}, ${Field.REPO_ID}, ${Field.Folder.NAME}, ${Field.Folder.NAME_LC}, ${Field.Folder.PARENT_ID}
                    )
             VALUES (?, ? , ?, ?, ?)
    """

    val sqlVals: List[Any] = List(
      id,
      RequestContext.getRepository.persistedId,
      folder.name,
      folder.nameLowercase,
      folder.parentId)

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(Field.ID -> id)
  }
}
