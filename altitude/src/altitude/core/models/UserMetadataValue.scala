package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import altitude.core.util.MurmurHash
import altitude.core.util.NameKey

object UserMetadataValue:
  given JsonCodec.ReadWriter[UserMetadataValue] = JsonCodec.macroRW
  given Conversion[ujson.Value, UserMetadataField] = json => JsonCodec.read[UserMetadataField](json)

case class UserMetadataValue(id: Option[String] = None, value: String) extends BaseModel with NoDates:

  /** The value's equality: a hash of its name key, so values that differ only in case or Unicode form are one value */
  val checksum: Int = MurmurHash.hash32(NameKey.of(value).getBytes("UTF-8"))

  override def canEqual(other: Any): Boolean = other.isInstanceOf[UserMetadataValue]

  final def nonEmpty: Boolean = value.nonEmpty

  override def equals(that: Any): Boolean = that match
    case that: UserMetadataValue if !that.canEqual(this) => false
    case that: UserMetadataValue => this.checksum == that.checksum
    case _ => false

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def hashCode: Int = checksum
