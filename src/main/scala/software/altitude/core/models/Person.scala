package software.altitude.core.models

import play.api.libs.json._
import play.api.libs.json.JsonNaming.SnakeCase

import scala.collection.mutable
import scala.language.implicitConversions

object Person {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Person] = Json.format[Person]
  implicit def fromJson(json: JsValue): Person = Json.fromJson[Person](json).get
}

case class Person(
    id: Option[String] = None,
    name: Option[String] = None,
    coverFaceId: Option[String] = None,
    mergedWithIds: List[String] = List(),
    mergedIntoId: Option[String] = None,
    mergedIntoLabel: Option[Int] = None,
    label: Int = -1,
    numOfFaces: Int = 0,
    isHidden: Boolean = false)
  extends BaseModel
  with NoDates {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  private val _faces: mutable.TreeSet[Face] = mutable.TreeSet[Face]()

  def addFace(face: Face): Unit = {
    require(face.id.isDefined, "Face must have a persisted ID")
    require(face.personLabel.isDefined, "Face must have a person label")
    require(face.personId.isDefined, "Face must have a person ID")
    _faces.addOne(face)
  }

  def setFaces(faces: Seq[Face]): Unit = {
    _faces.clear()
    _faces.addAll(faces)
  }

  def clearFaces(): Unit = {
    _faces.clear()
  }

  def getFaces: mutable.TreeSet[Face] = {
    // we do not get faces for a person automatically, but "numOfFaces" reflects the actual number in DB
    if (numOfFaces > 0 && _faces.isEmpty) {
      throw new IllegalStateException(s"Faces have not been loaded for person $this")
    }

    _faces
  }

  def hasFaces: Boolean = _faces.nonEmpty

  def wasMergedFrom: Boolean = mergedIntoId.nonEmpty

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match {
    case that: Person if !that.canEqual(this) => false
    case that: Person => that.persistedId == this.persistedId
    case _ => false
  }

  override def hashCode: Int = super.hashCode

  override def toString: String =
    s"PERSON $id. Label: $label. Name: ${name.getOrElse("N/A")}. Faces: $numOfFaces"
}
