package software.altitude.core.models
import play.api.libs.json._
import software.altitude.core.{Const => C}

import scala.language.implicitConversions


object Repository {
  implicit def fromJson(json: JsValue): Repository = Repository(
      id = (json \ C.Base.ID).asOpt[String],
      name = (json \ C.Repository.NAME).as[String],
      ownerAccountId = (json \ C.Repository.OWNER_ACCOUNT_ID).as[String],
      rootFolderId = (json \ C.Repository.ROOT_FOLDER_ID).as[String],
      fileStoreType = (json \ C.Repository.FILE_STORE_TYPE).as[String],
      fileStoreConfig = (json \ C.Repository.FILES_STORE_CONFIG).as[Map[String, String]]
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
      C.Repository.ID -> id,
      C.Repository.NAME -> name,
      C.Repository.OWNER_ACCOUNT_ID -> ownerAccountId,
      C.Repository.ROOT_FOLDER_ID -> rootFolderId,
      C.Repository.FILE_STORE_TYPE -> fileStoreType,
      C.Repository.FILES_STORE_CONFIG -> Json.toJson(fileStoreConfig)
    ) ++ coreJsonAttrs
  }

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
}
