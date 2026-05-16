package altitude.core.models

import scala.collection.mutable

import altitude.core.Const.FaceRecognition
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Person:
  given JsonCodec.ReadWriter[Person] = JsonCodec.macroRW
  given Conversion[ujson.Value, Person] = json => JsonCodec.read[Person](json)

case class Person(
    id: Option[String] = None,
    name: Option[String] = None,
    isHidden: Boolean = false,
    isNamed: Boolean = false,
    isBadMatch: Boolean = false,
    coverFaceId: Option[String] = None,
    numOfFaces: Int = 0)
  extends BaseModel
  with NoDates:

  def isAboveThreshold: Boolean = numOfFaces >= FaceRecognition.MIN_FACES_THRESHOLD

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  private val _faces: mutable.TreeSet[Face] = mutable.TreeSet[Face]()

  def addFace(face: Face): Unit =
    require(face.id.isDefined, "Face must have a persisted ID")
    require(face.personLabel.isDefined, "Face must have a person label")
    require(face.personId.isDefined, "Face must have a person ID")
    _faces.addOne(face)

  def setFaces(faces: Seq[Face]): Unit =
    _faces.clear()
    _faces.addAll(faces)

  def clearFaces(): Unit = _faces.clear()

  def getFaces: mutable.TreeSet[Face] =
    if numOfFaces > 0 && _faces.isEmpty then throw IllegalStateException(s"Faces have not been loaded for person $this")
    _faces

  def hasFaces: Boolean = _faces.nonEmpty

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Person]

  override def equals(other: Any): Boolean = other match
    case that: Person => that.canEqual(this) && this.id == that.id
    case _ => false

  override def hashCode: Int = super.hashCode

  override def toString: String =
    s"PERSON $id. Name: ${name.getOrElse("N/A")}. Faces: $numOfFaces"
