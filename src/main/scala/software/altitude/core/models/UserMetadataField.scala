package software.altitude.core.models

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.language.implicitConversions

object FieldType extends Enumeration {
  type FieldType = Value
  val KEYWORD: Value = Value("KEYWORD")
  val TEXT: Value = Value("TEXT")
  val NUMBER: Value = Value("NUMBER")
  val BOOL: Value = Value("BOOLEAN")
  val DATETIME: Value = Value("DATETIME")

  implicit val format: Format[FieldType] = Json.formatEnum(this)
}

object UserMetadataField {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[UserMetadataField] = Json.format[UserMetadataField]
  implicit def fromJson(json: JsValue): UserMetadataField = Json.fromJson[UserMetadataField](json).get
}

case class UserMetadataField(id: Option[String] = None, name: String, fieldType: FieldType.Value) extends BaseModel with NoDates {
  val nameLowercase: String = name.toLowerCase

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
}
