package altitude.core.models

import altitude.core.ValidationException
import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

object Folder:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[Folder] = Json.format[Folder]
  given Conversion[JsValue, Folder] = json => Json.fromJson[Folder](json).get

case class Folder(
    id: Option[String] = None,
    parentId: String,
    name: String,
    children: List[Folder] = List(),
    isRecycled: Boolean = false,
    numOfChildren: Int = 0)
  extends BaseModel
  with NoDates:

  if name.isEmpty then throw ValidationException("Folder name cannot be empty")

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Folder]

  override def equals(that: Any): Boolean = that match
    case that: Folder if !that.canEqual(this) => false
    case that: Folder =>
      val thisStringRepr = this.id.getOrElse("") + this.parentId + this.nameLowercase
      val thatStringRepr = that.id.getOrElse("") + that.parentId + that.nameLowercase
      thisStringRepr == thatStringRepr
    case _ => false

  override def hashCode: Int = super.hashCode
