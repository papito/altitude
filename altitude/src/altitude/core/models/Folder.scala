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

  // Folders are equal by ID, parent and case-insensitive name, and hash by the same fields
  private def equalityKey = (id, parentId, nameLowercase)

  override def equals(that: Any): Boolean = that match
    case that: Folder => that.canEqual(this) && equalityKey == that.equalityKey
    case _ => false

  override def hashCode: Int = equalityKey.hashCode
