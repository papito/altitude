package software.altitude.core.models

import play.api.libs.json._
import software.altitude.core.ValidationException
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object Folder {
  implicit def fromJson(json: JsValue): Folder = {
    val childrenJson = (json \ C.Folder.CHILDREN).as[List[JsValue]]

    Folder(
      id = (json \ C.Base.ID).asOpt[String],
      name = (json \ C.Folder.NAME).as[String],
      children = childrenJson.map(Folder.fromJson),
      parentId = (json \ C.Folder.PARENT_ID).as[String],
      numOfAssets = (json \ C.Folder.NUM_OF_ASSETS).as[Int],
      numOfChildren = (json \ C.Folder.NUM_OF_CHILDREN).as[Int],
      isRecycled = (json \ C.Folder.IS_RECYCLED).as[Boolean]
    ).withCoreAttr(json)
  }
}

case class Folder(id: Option[String] = None,
                  parentId: String,
                  name: String,
                  children: List[Folder] = List(),
                  isRecycled: Boolean = false,
                  numOfAssets: Int = 0,
                  numOfChildren: Int = 0) extends BaseModel {

  if (name.isEmpty) {
    throw ValidationException("Folder name cannot be empty")
  }

  val nameLowercase: String = name.toLowerCase

  override def toJson: JsObject = {
    val childrenJson: List[JsValue] = children.map(_.toJson)
    Json.obj(
      C.Folder.NAME -> name,
      C.Folder.NAME_LC -> nameLowercase,
      C.Folder.PARENT_ID -> parentId,
      C.Folder.CHILDREN ->  JsArray(childrenJson),
      C.Folder.NUM_OF_ASSETS -> numOfAssets,
      C.Folder.NUM_OF_CHILDREN -> numOfChildren,
      C.Folder.IS_RECYCLED -> isRecycled
    ) ++ coreJsonAttrs
  }

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Folder]

  override def equals(that: Any): Boolean = that match {
    case that: Folder if !that.canEqual( this) => false
    case that: Folder =>
      val thisStringRepr = this.id.getOrElse("") + this.parentId + this.name.toLowerCase
      val thatStringRepr = that.id.getOrElse("") + that.parentId + that.name.toLowerCase
      thisStringRepr == thatStringRepr
    case _ => false
  }

  override def hashCode: Int = super.hashCode
}
