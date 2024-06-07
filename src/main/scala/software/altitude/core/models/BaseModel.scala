package software.altitude.core.models

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json._
import software.altitude.core.Util
import software.altitude.core.{Const => C}

import java.util.UUID
import scala.language.implicitConversions

object BaseModel {
  final val ID_LEN = 36

  // make a new model ID
  final def genId: String = UUID.randomUUID.toString

  // implicit converter to go from a model to JSON
  implicit def toJson(obj: BaseModel): JsObject = obj.toJson
}

abstract class BaseModel {
  val id: Option[String]

  def toJson: JsObject

  // created at - mutable, but can only be set once
  protected var _createdAt: Option[DateTime] = None

  def createdAt: Option[DateTime] = _createdAt

  def createdAt_= (arg: DateTime): Unit = {
    if (_createdAt.isDefined) {
      throw new RuntimeException("Cannot set 'created at' twice")
    }
    _createdAt = Some(arg)
  }

  // updated at - mutable, but can only be set once
  protected var _updatedAt: Option[DateTime] = None

  def updatedAt: Option[DateTime] = _updatedAt

  def updatedAt_= (arg: DateTime): Unit = {
    if (_updatedAt.isDefined) {
      throw new RuntimeException("Cannot set 'updated at' twice")
    }
    _updatedAt = Some(arg)
  }

  // is clean (for validation) - mutable, but can only be set once
  protected var _isClean = false

  def isClean: Boolean = _isClean

  def isClean_= (arg: Boolean): Boolean = {
    if (!_isClean && arg) {
      _isClean = true
    }

    isClean
  }

  // is validated - mutable, but can only be set once
  protected var _isValidated = false

  def isValidated: Boolean = _isValidated

  def isValidated_= (arg: Boolean): Boolean = {
    if (!_isValidated && arg) {
      _isValidated = true
    }

    isClean
  }

  def modify(fields: (String, JsValueWrapper)*): JsObject = {
    // get a set of property names that we are updating
    val updatedPropNames: Set[String] = fields.map(pair => pair._1).toSet

    // Extract the list of property names that are not part of this object -
    // we won't get any indication of user error
    val wrongPropNames = updatedPropNames.foldLeft(Set[String]()) { (res, propName) =>
      if (!this.toJson.keys.contains(propName)) res + propName else res
    }

    if (wrongPropNames.nonEmpty) {
      throw new IllegalArgumentException(
        s"Cannot update the model with these properties ${wrongPropNames}")
    }

    this.toJson ++ Json.obj(fields: _*)
  }

  /**
    * Returns core JSON attributes that most models should have
    */
  protected def coreJsonAttrs: JsObject = JsObject(Map(
    C.Base.ID -> {id match {
      case None => JsNull
      case _ => JsString(id.get)
    }},

    C.Base.CREATED_AT -> {createdAt match {
      case None => JsNull
      case _ => JsString(Util.isoDateTime(createdAt))
    }},

    C.Base.UPDATED_AT -> {updatedAt match {
      case None => JsNull
      case _ => JsString(Util.isoDateTime(updatedAt))
    }}
  ).toSeq)

  /**
    * Return this type of object, but with core attributes
    * present, parsed from the passed in JSON object (if the values are present)
    */
  protected def withCoreAttr(json: JsValue): this.type = {
    val isoCreatedAt = (json \ C.Base.CREATED_AT).asOpt[String]
    if (isoCreatedAt.isDefined) {
      createdAt = ISODateTimeFormat.dateTime().parseDateTime(isoCreatedAt.get)
    }

    val isoUpdatedAt = (json \ C.Base.UPDATED_AT).asOpt[String]
    if (isoUpdatedAt.isDefined) {
      updatedAt = ISODateTimeFormat.dateTime().parseDateTime(isoUpdatedAt.get)
    }

    this
  }

  override def toString: String = toJson.toString()
}
