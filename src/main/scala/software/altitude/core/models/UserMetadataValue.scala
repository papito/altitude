package software.altitude.core.models

import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._

import java.util.Base64
import scala.language.implicitConversions

object UserMetadataValue {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[UserMetadataValue] = Json.format[UserMetadataValue]
  implicit def fromJson(json: JsValue): UserMetadataField = Json.fromJson[UserMetadataField](json).get
}

case class UserMetadataValue(id: Option[String] = None,
                             value: String) extends BaseModel with NoDates {
  private val md = java.security.MessageDigest.getInstance("SHA-1")
  val checksum: String = Base64.getEncoder.encodeToString(
    md.digest(
      value.toLowerCase.getBytes("UTF-8")
    )
  )

  override def canEqual(other: Any): Boolean = other.isInstanceOf[UserMetadataValue]

  final def nonEmpty: Boolean = value.nonEmpty

  override def equals( that: Any): Boolean = that match {
    case that: UserMetadataValue if !that.canEqual( this) => false
    case that: UserMetadataValue => this.checksum == that.checksum
    case _ => false
  }

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def hashCode: Int = super.hashCode
}
