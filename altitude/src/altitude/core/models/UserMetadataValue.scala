package altitude.core.models
import altitude.core.util.MurmurHash
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

object UserMetadataValue:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[UserMetadataValue] = Json.format[UserMetadataValue]
  given Conversion[JsValue, UserMetadataField] = json => Json.fromJson[UserMetadataField](json).get

case class UserMetadataValue(id: Option[String] = None, value: String) extends BaseModel with NoDates:
  val checksum: Int = MurmurHash.hash32(value.toLowerCase.getBytes("UTF-8"))

  override def canEqual(other: Any): Boolean = other.isInstanceOf[UserMetadataValue]

  final def nonEmpty: Boolean = value.nonEmpty

  override def equals(that: Any): Boolean = that match
    case that: UserMetadataValue if !that.canEqual(this) => false
    case that: UserMetadataValue => this.checksum == that.checksum
    case _ => false

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def hashCode: Int = super.hashCode
