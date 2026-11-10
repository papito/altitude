package altitude.core.models

import scala.collection.mutable

import altitude.core.Const.FaceRecognition
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object Person:
  given JsonCodec.ReadWriter[Person] = JsonCodec.macroRW
  given Conversion[ujson.Value, Person] = json => JsonCodec.read[Person](json)

/**
 * `numOfFaces` is, despite its name, the number of assets outside the trash on which the person has a Face, triaged ones
 * included: what a search for the person finds with triaged items shown, and what the Matched line is drawn on. A person seen
 * twice in one image has two Faces there and counts it once. The number the People tab shows is `ListedPerson.visibleCount`.
 */
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

  /**
   * The glossary's Matched person: named, or a Face on at least `MIN_FACES_THRESHOLD` assets outside the trash (`numOfFaces`);
   * `PersonDao.getInBuckets` restates the rule in SQL
   */
  def isMatched: Boolean = isNamed || numOfFaces >= FaceRecognition.MIN_FACES_THRESHOLD

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

  override def hashCode: Int = id.hashCode

  override def toString: String =
    s"PERSON $id. Name: ${name.getOrElse("N/A")}. Faces: $numOfFaces"
