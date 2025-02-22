package software.altitude.core.models
import java.time.LocalDateTime
import play.api.libs.json._

import scala.language.implicitConversions

object BaseModel {
  // implicit converter to go from model to JSON
  implicit def toJson(obj: BaseModel): JsObject = obj.toJson
}

abstract class BaseModel {
  val id: Option[String]
  val createdAt: Option[LocalDateTime]
  val updatedAt: Option[LocalDateTime]

  // Should be always used to get the ID of an object, unless we are positive that
  // the object has not been persisted yet
  def persistedId: String = {
    id match {
      case None => throw new RuntimeException("Cannot get persisted ID for a model that has not been saved yet")
      case _ => id.get
    }
  }

  def toJson: JsObject

  override def toString: String = s"<${getClass.getSimpleName}> ${id.getOrElse("NO ID")}"
}
