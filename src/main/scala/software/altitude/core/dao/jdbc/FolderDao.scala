package software.altitude.core.dao.jdbc

import com.typesafe.config.Config
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext
import software.altitude.core.models.Folder

abstract class FolderDao(override val config: Config) extends BaseDao with software.altitude.core.dao.FolderDao {
  final override val tableName = "folder"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject = {
    Folder(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      name = rec(FieldConst.Folder.NAME).asInstanceOf[String],
      parentId = rec(FieldConst.Folder.PARENT_ID).asInstanceOf[String],
      isRecycled = getBooleanField(rec(FieldConst.Folder.IS_RECYCLED)),
      numOfAssets = rec(FieldConst.Folder.NUM_OF_ASSETS).asInstanceOf[Int],
      numOfChildren = rec(FieldConst.Folder.NUM_OF_CHILDREN).asInstanceOf[Int]
    )
  }

  override def add(jsonIn: JsObject): JsObject = {
    val folder = jsonIn: Folder

    val id = folder.id match {
      case Some(id) => id
      case None => BaseDao.genId
    }

    val sql = s"""
        INSERT INTO $tableName (
                      ${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Folder.NAME}, ${FieldConst.Folder.NAME_LC}, ${FieldConst.Folder.PARENT_ID}
                    )
             VALUES (?, ? , ?, ?, ?)
    """

    val sqlVals: List[Any] =
      List(id, RequestContext.getRepository.persistedId, folder.name, folder.nameLowercase, folder.parentId)

    addRecord(jsonIn, sql, sqlVals)
    jsonIn ++ Json.obj(FieldConst.ID -> id)
  }
}
