package altitude.core.models

import java.time.LocalDateTime

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

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
    features: Array[Float],
    // The L2 norm of the raw ArcFace embedding: how recognizable the face is, higher is better
    quality: Double,
    // An enrolled Face can start a Person and is a candidate in the vector search; a match-only Face can only join a Person
    isEnrolled: Boolean,
    // The Frame time the crop and box were taken from, for a Face in a Video; None for a Face in an image
    frameTimeMs: Option[Long] = None)
  extends BaseModel:

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  override val createdAt: Option[LocalDateTime] = None
  override val updatedAt: Option[LocalDateTime] = None

  override def toString: String =
    f"FACE $id. Label: $personLabel. Score: $detectionScore, quality $quality%.1f ${if isEnrolled then "enrolled" else "match-only"}, " +
      s"${width}x$height at ($x1, $y1)${frameTimeMs.map(t => s" at $t ms").getOrElse("")}"

  override def canEqual(other: Any): Boolean = other.isInstanceOf[Face]

  override def equals(that: Any): Boolean = that match
    case that: Face if !that.canEqual(this) => false
    case that: Face => that.id == this.id
    case _ => false

  override def hashCode: Int = id.hashCode
