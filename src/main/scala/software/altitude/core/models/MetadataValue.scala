package software.altitude.core.models

import play.api.libs.json._

import java.util.Base64
import scala.language.implicitConversions

object MetadataValue {
  implicit def fromJson(json: JsValue): MetadataValue = {
    MetadataValue(
      id = (json \ Field.ID).asOpt[String],
      value = (json \ Field.VALUE).as[String]
    )
  }

  def apply(value: String): MetadataValue = MetadataValue(id = None, value = value)
}

case class MetadataValue(id: Option[String] = None,
                         value: String) extends BaseModel {
  private val md = java.security.MessageDigest.getInstance("SHA-1")
  val checksum: String = Base64.getEncoder.encodeToString(
    md.digest(
      value.toLowerCase.getBytes("UTF-8")
    )
  )

  override def canEqual(other: Any): Boolean = other.isInstanceOf[MetadataValue]

  final def nonEmpty: Boolean = value.nonEmpty

  override def equals( that: Any): Boolean = that match {
    case that: MetadataValue if !that.canEqual( this) => false
    case that: MetadataValue => this.checksum == that.checksum
    case _ => false
  }

  override def toJson: JsObject = Json.obj(
    Field.VALUE -> value,
    Field.ID -> {id match {
      case None => JsNull
      case _ => JsString(id.get)
    }}
  )

  override def hashCode: Int = super.hashCode
}
