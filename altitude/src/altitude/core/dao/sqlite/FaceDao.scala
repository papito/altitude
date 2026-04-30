package altitude.core.dao.sqlite

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.models.Asset
import altitude.core.models.Face
import altitude.core.models.Person
import com.typesafe.config.Config

import java.sql.PreparedStatement

class FaceDao(override val config: Config) extends altitude.core.dao.jdbc.FaceDao(config) with SqliteOverrides:

  private def toVectorAsF32Arg(values: Array[Float]): String =
    // Must match: vector_as_f32('[0.3, 1.0, ...]')
    // We bind just the bracketed list as a String parameter.
    values.mkString("[", ", ", "]")

  override def add(face: Face, asset: Asset, person: Person): Face =
    val id = BaseDao.genId

    val sql =
      s"""
        INSERT INTO face (${FieldConst.ID}, ${FieldConst.REPO_ID}, ${FieldConst.Face.X1}, ${FieldConst.Face.Y1}, ${FieldConst.Face.WIDTH}, ${FieldConst.Face.HEIGHT},
                          ${FieldConst.Face.ASSET_ID}, ${FieldConst.Face.PERSON_ID}, ${FieldConst.Face.DETECTION_SCORE},
                          ${FieldConst.Face.FEATURES}, ${FieldConst.Face.CHECKSUM})
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, vector_as_f32(?), ?)
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
    preparedStatement.setString(10, toVectorAsF32Arg(face.features))
    preparedStatement.setInt(11, face.checksum)

    preparedStatement.execute()

    face.copy(id = Some(id), assetId = asset.id, personId = person.id)

  def searchClosestFaceMatches(features: Array[Float]): List[Face] =
    val sql =
      """
      SELECT
        v.rowid,
        row_number() OVER (ORDER BY v.distance) AS rank_number,
        v.distance,
        face.*
      FROM vector_full_scan('face', 'features', vector_as_f32(?)) AS v
      JOIN face ON face.rowid = v.rowid
      WHERE face.repository_id = ?
        AND detection_score >= ?
        AND v.distance < ?
      ORDER BY v.distance
      LIMIT ?;
   """

    val matchCount = config.getInt("face.recognition.match_count")
    val threshold = config.getDouble("face.recognition.cosine_distance_threshold")
    val minDetectionScore = config.getDouble("face.recognition.min_detection_score")

    val recs: List[Map[String, AnyRef]] =
      manyBySqlQuery(sql, List(toVectorAsF32Arg(features), RequestContext.getRepository.persistedId, minDetectionScore, threshold, matchCount))

    recs.map(makeModel)
