package altitude.core.models

import java.time.LocalDateTime
import altitude.core.util.JsonCodec
import JsonCodec.given
import JsonCodec.macroRW

object Face:
  // For sorting faces by detection score automatically, highest score first
  given faceOrdering: Ordering[Face] = Ordering.by(-_.detectionScore)
  given JsonCodec.ReadWriter[Face] = JsonCodec.macroRW
  given Conversion[ujson.Value, Face] = json => JsonCodec.read[Face](json)

case class Face(
    id: Option[String] = None,
    x1: Int,
    y1: Int,
    width: Int,
    height: Int,
    assetId: Option[String] = None,
    personId: Option[String] = None,
    personLabel: Option[Int] = None,
    detectionScore: Double,
    checksum: Int,
    features: Array[Float])
  extends BaseModel:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override val createdAt: Option[LocalDateTime] = None
  override val updatedAt: Option[LocalDateTime] = None

  override def toString: String =
    s"FACE $id. Label: $personLabel. Score: $detectionScore, ${width}x$height at ($x1, $y1)"

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match
    case that: Face if !that.canEqual(this) => false
    case that: Face => that.id == this.id
    case _ => false

  override def hashCode: Int = super.hashCode
