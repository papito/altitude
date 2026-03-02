package altitude.core.models

import java.time.LocalDateTime
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

object Repository:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[Repository] = Json.format[Repository]
  given Conversion[JsValue, Repository] = json => Json.fromJson[Repository](json).get

case class Repository(
    id: Option[String] = None,
    name: String,
    ownerAccountId: String,
    rootFolderId: String,
    fileStoreType: String,
    fileStoreConfig: Map[String, String] = Map(),
    createdAt: Option[LocalDateTime] = None,
    updatedAt: Option[LocalDateTime] = None)
  extends BaseModel:

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def toString: String = s"<repo> ${id.getOrElse("NO ID")}: $name"
