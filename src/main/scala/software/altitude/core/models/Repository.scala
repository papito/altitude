package software.altitude.core.models
import java.time.LocalDateTime
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.language.implicitConversions

object Repository {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Repository] = Json.format[Repository]
  implicit def fromJson(json: JsValue): Repository = Json.fromJson[Repository](json).get
}

case class Repository(
    id: Option[String] = None,
    name: String,
    ownerAccountId: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
}
