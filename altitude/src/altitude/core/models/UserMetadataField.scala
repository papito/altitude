package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.NameKey

enum FieldType:
  case KEYWORD, TEXT, NUMBER, BOOL, DATETIME

object FieldType:
  given JsonCodec.ReadWriter[FieldType] = JsonCodec
    .readwriter[String]
    .bimap(
      {
        case FieldType.KEYWORD => "KEYWORD"
        case FieldType.TEXT => "TEXT"
        case FieldType.NUMBER => "NUMBER"
        case FieldType.BOOL => "BOOLEAN"
        case FieldType.DATETIME => "DATETIME"
      },
      {
        case "KEYWORD" => FieldType.KEYWORD
        case "TEXT" => FieldType.TEXT
        case "NUMBER" => FieldType.NUMBER
        case "BOOLEAN" => FieldType.BOOL
        case "DATETIME" => FieldType.DATETIME
        case other => throw IllegalArgumentException(s"Unknown FieldType: $other")
      }
    )

object UserMetadataField:
  given JsonCodec.ReadWriter[UserMetadataField] = JsonCodec.macroRW
  given Conversion[ujson.Value, UserMetadataField] = json => JsonCodec.read[UserMetadataField](json)

case class UserMetadataField(id: Option[String] = None, name: String, fieldType: FieldType) extends BaseModel with NoDates:
  /** The key `metadata_field_02` keeps unique per repository */
  val nameKey: String = NameKey.of(name)

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
