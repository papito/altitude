package software.altitude.core.models

import play.api.libs.json._

import scala.language.implicitConversions

object FieldType extends Enumeration {
  val KEYWORD: Value = Value("KEYWORD")
  val TEXT: Value = Value("TEXT")
  val NUMBER: Value = Value("NUMBER")
  val BOOL: Value = Value("BOOLEAN")
  val DATETIME: Value = Value("DATETIME")
}

object UserMetadataField {
  implicit def fromJson(json: JsValue): UserMetadataField =
    UserMetadataField(
      id = (json \ Field.ID).asOpt[String],
      name = (json \ Field.MetadataField.NAME).as[String],
      fieldType = FieldType.withName((json \ Field.MetadataField.FIELD_TYPE).as[String])
    ).withCoreAttr(json)
}

case class UserMetadataField(id: Option[String] = None,
                             name: String,
                             fieldType: FieldType.Value) extends BaseModel {
  val nameLowercase: String = name.toLowerCase

  override def toJson: JsObject = Json.obj(
      Field.MetadataField.NAME -> name,
      Field.MetadataField.NAME_LC -> nameLowercase,
      Field.MetadataField.FIELD_TYPE -> fieldType.toString
    ) ++ coreJsonAttrs

}
