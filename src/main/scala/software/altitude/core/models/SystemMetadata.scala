package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object SystemMetadata {

  implicit def fromJson(json: JsValue): SystemMetadata = SystemMetadata(
    version = (json \ C.SystemMetadata.VERSION).as[Int],
    isInitialized = (json \ C.SystemMetadata.IS_INITIALIZED).as[Boolean]
  )
}

case class SystemMetadata(version: Int, isInitialized: Boolean) {

  implicit def toJson: JsObject = Json.obj(
    C.SystemMetadata.VERSION -> version,
    C.SystemMetadata.IS_INITIALIZED -> isInitialized,
  )

  override def toString: String = s"<system> version=$version, initialized=$isInitialized"
}
