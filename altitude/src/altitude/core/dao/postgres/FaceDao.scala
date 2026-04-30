package altitude.core.dao.postgres

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given
import com.typesafe.config.Config

import java.sql.PreparedStatement
import scala.language.implicitConversions

class FaceDao(override val config: Config) extends altitude.core.dao.jdbc.FaceDao(config) with PostgresOverrides:

  /**
   * Format a Scala Float array as a pgvector-compatible string literal, e.g. "[0.1, 0.2, ...]". pgvector accepts this format when
   * cast with `?::vector`.
   */
  private def toVectorString(values: Array[Float]): String =
    values.mkString("[", ",", "]")

  override def add(jsonIn: ujson.Obj, asset: Asset, person: Person): ujson.Obj =
    val face: Face = jsonIn

    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO face (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                          ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.DETECTION_SCORE},
                          ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
    """

    val conn = RequestContext.getConn

    val preparedStatement: PreparedStatement = conn.prepareStatement(sql)
    preparedStatement.setString(1, id)
    preparedStatement.setString(2, RequestContext.getRepository.persistedId)
    preparedStatement.setInt(3, face.x1)
    preparedStatement.setInt(4, face.y1)
    preparedStatement.setInt(5, face.width)
    preparedStatement.setInt(6, face.height)
    preparedStatement.setString(7, asset.persistedId)
    preparedStatement.setString(8, person.persistedId)
    preparedStatement.setDouble(9, face.detectionScore)
    preparedStatement.setString(10, toVectorString(face.features))
    preparedStatement.setInt(11, face.checksum)

    preparedStatement.execute()

    jsonIn(FieldConst.ID) = id
    jsonIn(FieldConst.Face.ASSET_ID) = asset.id.get
    jsonIn(FieldConst.Face.PERSON_ID) = person.id.get
    jsonIn

  def searchClosestFaceMatches(features: Array[Float]): List[Face] =
    val featuresStr = toVectorString(features)

    val sql =
      """
        SELECT *,
               features <=> ?::vector AS distance
        FROM face
        WHERE repository_id = ?
          AND detection_score >= ?
          AND features <=> ?::vector < ?
        ORDER BY features <=> ?::vector
        LIMIT ?;
        """

    val matchCount = config.getInt("face.recognition.match_count")
    val threshold = config.getDouble("face.recognition.cosine_distance_threshold")
    val minDetectionScore = config.getDouble("face.recognition.min_detection_score")

    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(featuresStr, RequestContext.getRepository.persistedId, minDetectionScore, featuresStr, threshold, featuresStr, matchCount))

    recs.map(makeModel)
