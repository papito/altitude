package altitude.core.dao.jdbc

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.Face
import com.typesafe.config.Config
import play.api.libs.json.JsObject

import scala.language.implicitConversions

abstract class FaceDao(override val config: Config) extends BaseDao with altitude.core.dao.FaceDao:

  def searchClosestFaceMatches(features: Array[Float]): List[Face]

  final override val tableName = "face"

  override protected def makeModel(rec: Map[String, AnyRef]): JsObject =
    Face(
      id = Option(rec(FieldConst.ID).asInstanceOf[String]),
      x1 = rec(FieldConst.Face.X1).asInstanceOf[Int],
      y1 = rec(FieldConst.Face.Y1).asInstanceOf[Int],
      width = rec(FieldConst.Face.WIDTH).asInstanceOf[Int],
      height = rec(FieldConst.Face.HEIGHT).asInstanceOf[Int],
      assetId = Option(rec(FieldConst.Face.ASSET_ID).asInstanceOf[String]),
      personId = Option(rec(FieldConst.Face.PERSON_ID).asInstanceOf[String]),
      detectionScore = rec(FieldConst.Face.DETECTION_SCORE).asInstanceOf[Double],
      features = Array[Float](),
      checksum = rec(FieldConst.Face.CHECKSUM).asInstanceOf[Int]
    ).toJson

  def getAssetFaces(assetId: String): List[Face] =
    val sql = """
        SELECT face.*
          FROM face, person
         WHERE face.repository_id = ?
           AND face.asset_id = ?
           AND face.person_id = person.id
           AND person.is_hidden = FALSE
           AND person.is_bad_match = FALSE
      """

    val recs: List[Map[String, AnyRef]] = manyBySqlQuery(sql, List(RequestContext.getRepository.persistedId, assetId))

    recs.map(makeModel)
