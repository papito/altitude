package altitude.core.models

import altitude.core.ValidationException
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Folder:
  given JsonCodec.ReadWriter[Folder] = JsonCodec.macroRW
  given Conversion[ujson.Value, Folder] = json => JsonCodec.read[Folder](json)

case class Folder(
    id: Option[String] = None,
    parentId: String,
    name: String,
    children: List[Folder] = List(),
    isRecycled: Boolean = false,
    numOfChildren: Int = 0,
    numOfAssets: Int = 0)
  extends BaseModel
  with NoDates:

  if name.isEmpty then throw ValidationException("Folder name cannot be empty")

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Folder]

  override def equals(that: Any): Boolean = that match
    case that: Folder if !that.canEqual(this) => false
    case that: Folder =>
      val thisStringRepr = this.id.getOrElse("") + this.parentId + this.nameLowercase
      val thatStringRepr = that.id.getOrElse("") + that.parentId + that.nameLowercase
      thisStringRepr == thatStringRepr
    case _ => false

  override def hashCode: Int = super.hashCode
