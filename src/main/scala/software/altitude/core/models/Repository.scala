package software.altitude.core.models
import play.api.libs.json._

import scala.language.implicitConversions


object Repository {
  implicit def fromJson(json: JsValue): Repository = Repository(
      id = (json \ Field.ID).asOpt[String],
      name = (json \ Field.Repository.NAME).as[String],
      ownerAccountId = (json \ Field.Repository.OWNER_ACCOUNT_ID).as[String],
      rootFolderId = (json \ Field.Repository.ROOT_FOLDER_ID).as[String],
      fileStoreType = (json \ Field.Repository.FILE_STORE_TYPE).as[String],
      fileStoreConfig = (json \ Field.Repository.FILES_STORE_CONFIG).as[Map[String, String]]
    ).withCoreAttr(json)

  implicit def toJson(repo: Repository): JsObject = repo.toJson
}

case class Repository(id: Option[String] = None,
                      name: String,
                      ownerAccountId: String,
                      rootFolderId: String,
                      fileStoreType: String,
                      fileStoreConfig: Map[String, String] = Map()) extends BaseModel {

  def toJson: JsObject = {
    Json.obj(
      Field.ID -> id,
      Field.Repository.NAME -> name,
      Field.Repository.OWNER_ACCOUNT_ID -> ownerAccountId,
      Field.Repository.ROOT_FOLDER_ID -> rootFolderId,
      Field.Repository.FILE_STORE_TYPE -> fileStoreType,
      Field.Repository.FILES_STORE_CONFIG -> Json.toJson(fileStoreConfig)
    ) ++ coreJsonAttrs
  }

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
}
