package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object User {
  implicit def fromJson(json: JsValue): User = User(
    id = (json \ C.Base.ID).asOpt[String]
  ).withCoreAttr(json)
}

case class User(id: Option[String] = None) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    C.Base.ID -> id
  ) ++ coreJsonAttrs

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}"
}
