package altitude.core.models

import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._

enum FieldType:
  case KEYWORD, TEXT, NUMBER, BOOL, DATETIME

object FieldType:
  given format: Format[FieldType] = new Format[FieldType]:
    def reads(json: JsValue): JsResult[FieldType] = json.validate[String].map {
      case "KEYWORD" => FieldType.KEYWORD
      case "TEXT" => FieldType.TEXT
      case "NUMBER" => FieldType.NUMBER
      case "BOOLEAN" => FieldType.BOOL
      case "DATETIME" => FieldType.DATETIME
      case other => throw new IllegalArgumentException(s"Unknown FieldType: $other")
    }
    def writes(ft: FieldType): JsValue = JsString(ft match
      case FieldType.KEYWORD => "KEYWORD"
      case FieldType.TEXT => "TEXT"
      case FieldType.NUMBER => "NUMBER"
      case FieldType.BOOL => "BOOLEAN"
      case FieldType.DATETIME => "DATETIME"
    )

object UserMetadataField:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[UserMetadataField] = Json.format[UserMetadataField]
  given Conversion[JsValue, UserMetadataField] = json => Json.fromJson[UserMetadataField](json).get

case class UserMetadataField(id: Option[String] = None, name: String, fieldType: FieldType) extends BaseModel with NoDates:
  val nameLowercase: String = name.toLowerCase

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
