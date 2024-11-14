package software.altitude.core.models

import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json._
import software.altitude.core.ValidationException

import scala.language.implicitConversions

object Folder {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Folder] = Json.format[Folder]
  implicit def fromJson(json: JsValue): Folder = Json.fromJson[Folder](json).get
}

case class Folder(id: Option[String] = None,
                  parentId: String,
                  name: String,
                  children: List[Folder] = List(),
                  isRecycled: Boolean = false,
                  numOfAssets: Int = 0,
                  numOfChildren: Int = 0
                 ) extends BaseModel with NoDates {

  if (name.isEmpty) {
    throw ValidationException("Folder name cannot be empty")
  }

  val nameLowercase: String = name.toLowerCase

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Folder]

  override def equals(that: Any): Boolean = that match {
    case that: Folder if !that.canEqual( this) => false
    case that: Folder =>
      val thisStringRepr = this.id.getOrElse("") + this.parentId + this.nameLowercase
      val thatStringRepr = that.id.getOrElse("") + that.parentId + that.nameLowercase
      thisStringRepr == thatStringRepr
    case _ => false
  }

  override def hashCode: Int = super.hashCode

}
