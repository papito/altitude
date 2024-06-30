package software.altitude.core.models
import play.api.libs.json._
import software.altitude.core.Util
import software.altitude.core.{Const => C}

import java.time.LocalDateTime
import scala.language.implicitConversions

object BaseModel {
  // implicit converter to go from model to JSON
  implicit def toJson(obj: BaseModel): JsObject = obj.toJson
}

abstract class BaseModel {
  val id: Option[String]

  // Should be always used to get the ID of an object, unless we are positive that the object has not been persisted yet
  def persistedId: String = {
    id match {
      case None => throw new RuntimeException("Cannot get persisted ID for a model that has not been saved yet")
      case _ => id.get
    }
  }

  // implicit converter to go from JSON to model
  implicit def toJson: JsObject

  // created at - mutable, but can only be set once
  private var _createdAt: Option[LocalDateTime] = None

  def createdAt: Option[LocalDateTime] = _createdAt

  def createdAt_= (arg: LocalDateTime): Unit = {
    if (_createdAt.isDefined) {
      throw new RuntimeException("Cannot set 'created at' twice")
    }
    _createdAt = Some(arg)
  }

  // updated at - mutable, but can only be set once
  private var _updatedAt: Option[LocalDateTime] = None

  def updatedAt: Option[LocalDateTime] = _updatedAt

  def updatedAt_= (arg: LocalDateTime): Unit = {
    if (_updatedAt.isDefined) {
      throw new RuntimeException("Cannot set 'updated at' twice")
    }
    _updatedAt = Some(arg)
  }

  /**
   * Returns core JSON attributes that all models should have
    */
  protected def coreJsonAttrs: JsObject = JsObject(Map(
    C.Base.ID -> {id match {
      case None => JsNull
      case _ => JsString(id.get)
    }},

    C.Base.CREATED_AT -> {createdAt match {
      case None => JsNull
      case _ => JsString(Util.localDateTimeToString(createdAt))
    }},

    C.Base.UPDATED_AT -> {updatedAt match {
      case None => JsNull
      case _ => JsString(Util.localDateTimeToString(updatedAt))
    }}
  ).toSeq)

  /**
    * Return this type of object, but with core attributes
    * present, parsed from the passed in JSON object (if the values are present)
    */
  protected def withCoreAttr(json: JsValue): this.type = {
    val isoCreatedAt = (json \ C.Base.CREATED_AT).asOpt[String]
    if (isoCreatedAt.isDefined) {
      createdAt = Util.stringToLocalDateTime(isoCreatedAt.get).get
    }

    val isoUpdatedAt = (json \ C.Base.UPDATED_AT).asOpt[String]
    if (isoUpdatedAt.isDefined) {
      updatedAt = Util.stringToLocalDateTime(isoUpdatedAt.get).get
    }

    this
  }

  override def toString: String = toJson.toString()
}
