package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.MurmurHash

object UserMetadataValue:
  given JsonCodec.ReadWriter[UserMetadataValue] = JsonCodec.macroRW
  given Conversion[ujson.Value, UserMetadataField] = json => JsonCodec.read[UserMetadataField](json)

case class UserMetadataValue(id: Option[String] = None, value: String) extends BaseModel with NoDates:
  val checksum: Int = MurmurHash.hash32(value.toLowerCase.getBytes("UTF-8"))

  override def canEqual(other: Any): Boolean = other.isInstanceOf[UserMetadataValue]

  final def nonEmpty: Boolean = value.nonEmpty

  override def equals(that: Any): Boolean = that match
    case that: UserMetadataValue if !that.canEqual(this) => false
    case that: UserMetadataValue => this.checksum == that.checksum
    case _ => false

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def hashCode: Int = super.hashCode
