package software.altitude.core.models

import play.api.libs.json._
import software.altitude.core.{Const => C}

import scala.collection.mutable
import scala.language.implicitConversions

object Person {
  implicit def fromJson(json: JsValue): Person = {
    val person = Person(
      id = (json \ C.Base.ID).asOpt[String],
      name = (json \ C.Person.NAME).asOpt[String],
      coverFaceId = (json \ C.Person.COVER_FACE_ID).asOpt[String],
      label = (json \ C.Person.LABEL).as[Int],
      mergedWithIds = (json \ C.Person.MERGED_WITH_IDS).as[List[String]],
      mergedIntoId = (json \ C.Person.MERGED_INTO_ID).asOpt[String],
      mergedIntoLabel = (json \ C.Person.MERGED_INTO_LABEL).asOpt[Int],
      numOfFaces = (json \ C.Person.NUM_OF_FACES).as[Int],
      isHidden = (json \ C.Person.IS_HIDDEN).as[Boolean]
    ).withCoreAttr(json)

    val faces: List[Face] = (json \ C.Person.FACES).asOpt[List[JsValue]].getOrElse(List()).map(Face.fromJson)
    person.setFaces(faces)
    person
  }
}

case class Person(id: Option[String] = None,
                  name: Option[String] = None,
                  coverFaceId: Option[String] = None,
                  mergedWithIds: List[String] = List(),
                  mergedIntoId: Option[String] = None,
                  mergedIntoLabel: Option[Int] = None,
                  label: Int = -1,
                  numOfFaces: Int = 0,
                  isHidden: Boolean = false) extends BaseModel {

  override def toJson: JsObject = {
    Json.obj(
      C.Person.LABEL -> label,
      C.Person.NAME -> name,
      C.Person.COVER_FACE_ID -> coverFaceId,
      C.Person.NUM_OF_FACES -> numOfFaces,
      C.Person.MERGED_WITH_IDS -> mergedWithIds,
      C.Person.MERGED_INTO_ID -> mergedIntoId,
      C.Person.MERGED_INTO_LABEL -> mergedIntoLabel,
      C.Person.IS_HIDDEN -> isHidden,
      C.Person.FACES -> _faces.toList.map(_.toJson)
    ) ++ coreJsonAttrs
  }

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
      throw new IllegalStateException("Faces have not been loaded for this person.")
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
